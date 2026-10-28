package com.wordwake.app.alarm;

import android.content.Context;
import android.util.Log;

import com.wordwake.app.data.ScheduleRepository;
import com.wordwake.app.engine.ScheduleEngine;
import com.wordwake.app.model.Schedule;

import java.time.ZoneId;

/**
 * Every alarm state transition goes through here: fire, stop (silence), snooze, dismiss and
 * end-of-window. All methods do disk I/O and must run on {@code AppExecutors.io} (a single
 * thread, so transitions never interleave).
 */
public final class AlarmController {

    private static final String TAG = "WordWakeController";

    private AlarmController() {}

    /** An AlarmManager "fire" broadcast arrived. Validates it against stored state first. */
    static void onFire(Context context, long id) {
        ScheduleRepository repo = new ScheduleRepository(context);
        Schedule s = repo.get(id);
        if (s == null || !s.enabled) {
            // Deleted or disabled after the alarm was set: never ring, clean up.
            AlarmScheduler.cancelAll(context, id);
            return;
        }
        long now = System.currentTimeMillis();
        ScheduleEngine.Event ev = ScheduleEngine.nextEvent(s, now, ZoneId.systemDefault(), ActiveAlarm.isRinging(id));
        if (!ScheduleEngine.isDue(ev, now)) {
            // Stale or duplicate delivery (e.g. time changed, already rang this slot): reschedule only.
            Log.d(TAG, "Fire for #" + id + " not due; rescheduling");
            AlarmScheduler.reschedule(context, s);
            return;
        }
        ring(context, s, ev, now);
    }

    static void ring(Context context, Schedule s, ScheduleEngine.Event ev, long now) {
        long sessionEnd = (ev.kind == ScheduleEngine.KIND_RESUME && s.ringActiveUntil > now)
                ? Math.min(s.ringActiveUntil, ev.occEnd)
                : ScheduleEngine.sessionEnd(s, now, ev.occStart, ev.occEnd);
        long soundEnd = ScheduleEngine.soundEnd(s, now, sessionEnd);

        s.occStart = ev.occStart;
        s.lastRingAt = now;
        s.snoozeUntil = 0;
        s.ringActiveUntil = sessionEnd;
        s.lastOutcome = Schedule.OUTCOME_NONE;
        new ScheduleRepository(context).saveState(s);

        Notifications.cancelFor(context, s.id);
        AlarmScheduler.scheduleEnd(context, s.id, sessionEnd);
        Log.i(TAG, "Ringing #" + s.id + " kind=" + ev.kind + " for " + (sessionEnd - now) / 1000 + "s");
        boolean started = AlarmService.start(context, s.id, ev.occStart, ev.occEnd, now, sessionEnd, soundEnd, ev.kind, false);
        if (!started) {
            // Background start blocked: the notification channel plays the phone's alarm sound instead.
            s.ringActiveUntil = 0;
            new ScheduleRepository(context).saveState(s);
            Notifications.postFallback(context, s, ev.occStart, ev.occEnd);
        }
        AlarmScheduler.reschedule(context, s, started);
    }

    /** "Test alert" from the app: rings for one minute without touching the schedule state. */
    public static void test(Context context, long id) {
        Schedule s = new ScheduleRepository(context).get(id);
        if (s == null) return;
        long now = System.currentTimeMillis();
        long end = now + 60_000L;
        AlarmService.start(context, s.id, now, end, now, end, ScheduleEngine.soundEnd(s, now, end),
                ScheduleEngine.KIND_START, true);
    }

    private static boolean stopIfTest(Context context, long id) {
        ActiveAlarm.Session cur = ActiveAlarm.get();
        if (cur != null && cur.test && cur.scheduleId == id) {
            AlarmService.stop(context);
            return true;
        }
        return false;
    }

    /** Stop button: silence the sound; the message stays readable until dismissed or the end time. */
    public static void silence(Context context, long id) {
        if (stopIfTest(context, id)) return;
        ScheduleRepository repo = new ScheduleRepository(context);
        Schedule s = repo.get(id);
        if (ActiveAlarm.isRinging(id)) AlarmService.stop(context);
        AlarmScheduler.cancelEnd(context, id);
        if (s == null) return;
        s.ringActiveUntil = 0;
        repo.saveState(s);
        long occEnd = ScheduleEngine.windowOf(s, s.occStart, ZoneId.systemDefault()).end;
        if (System.currentTimeMillis() < occEnd) {
            Notifications.postMessage(context, s, s.occStart, occEnd, false);
        }
        AlarmScheduler.reschedule(context, s, false);
    }

    /** Snooze: silence now, ring again after the snooze time if that is still inside the window. */
    public static void snooze(Context context, long id) {
        if (stopIfTest(context, id)) return;
        ScheduleRepository repo = new ScheduleRepository(context);
        Schedule s = repo.get(id);
        if (s == null) {
            if (ActiveAlarm.isRinging(id)) AlarmService.stop(context);
            return;
        }
        long now = System.currentTimeMillis();
        long occEnd = ScheduleEngine.windowOf(s, s.occStart, ZoneId.systemDefault()).end;
        if (!ScheduleEngine.canSnooze(s, now, occEnd)) {
            silence(context, id);
            return;
        }
        if (ActiveAlarm.isRinging(id)) AlarmService.stop(context);
        AlarmScheduler.cancelEnd(context, id);
        s.snoozeUntil = now + s.snoozeMinutes * 60_000L;
        s.ringActiveUntil = 0;
        repo.saveState(s);
        Notifications.cancelFor(context, id);
        Notifications.postSnoozed(context, s, s.snoozeUntil);
        AlarmScheduler.reschedule(context, s, false);
    }

    /** Dismiss: end this occurrence completely (no more reminders or snoozes until the next one). */
    public static void dismiss(Context context, long id) {
        if (stopIfTest(context, id)) return;
        ScheduleRepository repo = new ScheduleRepository(context);
        Schedule s = repo.get(id);
        if (ActiveAlarm.isRinging(id)) AlarmService.stop(context);
        AlarmScheduler.cancelEnd(context, id);
        Notifications.cancelFor(context, id);
        if (s == null) return;
        if (s.occStart != 0) s.dismissedOcc = s.occStart;
        s.snoozeUntil = 0;
        s.ringActiveUntil = 0;
        s.lastOutcome = Schedule.OUTCOME_DISMISSED;
        repo.saveState(s);
        AlarmScheduler.reschedule(context, s, false);
    }

    /** The ring session reached its deadline (service timer or backup END alarm). */
    static void endSession(Context context, long id) {
        ScheduleRepository repo = new ScheduleRepository(context);
        Schedule s = repo.get(id);
        long now = System.currentTimeMillis();
        if (s == null) {
            if (ActiveAlarm.isRinging(id)) AlarmService.stop(context);
            return;
        }
        if (s.ringActiveUntil > now + ScheduleEngine.DUE_TOLERANCE_MS) {
            // A newer, longer session replaced the one this deadline belonged to.
            return;
        }
        if (ActiveAlarm.isRinging(id)) AlarmService.stop(context);
        AlarmScheduler.cancelEnd(context, id);
        boolean wasActive = s.ringActiveUntil != 0;
        s.ringActiveUntil = 0;
        long occEnd = ScheduleEngine.windowOf(s, s.occStart, ZoneId.systemDefault()).end;
        boolean windowOver = now >= occEnd - ScheduleEngine.DUE_TOLERANCE_MS;
        if (windowOver) {
            if (s.lastOutcome == Schedule.OUTCOME_NONE) s.lastOutcome = Schedule.OUTCOME_ENDED;
            if (s.autoDismiss) {
                Notifications.cancelFor(context, id);
            } else if (wasActive) {
                Notifications.postMessage(context, s, s.occStart, occEnd, true);
            }
        } else if (wasActive) {
            // Reminder mode: this reminder is over, the next one is scheduled. Keep the text readable.
            Notifications.postMessage(context, s, s.occStart, occEnd, false);
        }
        repo.saveState(s);
        AlarmScheduler.reschedule(context, s, false);
    }

    /** Schedule disabled or about to be deleted: stop any ring and remove every alarm/notification. */
    public static void onDisabledOrDeleted(Context context, long id) {
        ActiveAlarm.Session cur = ActiveAlarm.get();
        if (cur != null && cur.scheduleId == id) AlarmService.stop(context);
        AlarmScheduler.cancelAll(context, id);
        Notifications.cancelFor(context, id);
        androidx.core.app.NotificationManagerCompat.from(context).cancel(Notifications.statusId(id));
    }

    /** Another schedule's alarm replaced this one; keep its message available quietly. */
    static void onSuperseded(Context context, long id) {
        ScheduleRepository repo = new ScheduleRepository(context);
        Schedule s = repo.get(id);
        if (s == null) return;
        s.ringActiveUntil = 0;
        repo.saveState(s);
        AlarmScheduler.cancelEnd(context, id);
        long occEnd = ScheduleEngine.windowOf(s, s.occStart, ZoneId.systemDefault()).end;
        if (System.currentTimeMillis() < occEnd) Notifications.postMessage(context, s, s.occStart, occEnd, false);
    }
}
