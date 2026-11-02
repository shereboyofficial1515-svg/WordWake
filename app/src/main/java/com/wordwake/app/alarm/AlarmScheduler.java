package com.wordwake.app.alarm;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import com.wordwake.app.data.DiagLog;
import com.wordwake.app.data.ScheduleRepository;
import com.wordwake.app.engine.ScheduleEngine;
import com.wordwake.app.model.Schedule;
import com.wordwake.app.ui.MainActivity;

import java.time.ZoneId;
import java.util.List;

/**
 * Registers alarms with AlarmManager. Exactly one "fire" PendingIntent exists per schedule
 * (keyed by a schedule-specific data URI), so re-scheduling replaces rather than duplicates.
 *
 * Exact alarms use {@link AlarmManager#setAlarmClock}, which is delivered in Doze and allows the
 * receiver to start the alarm foreground service. Without exact-alarm access we fall back to
 * {@link AlarmManager#setAndAllowWhileIdle}, which Android may delay.
 *
 * All methods do disk I/O: call them on {@code AppExecutors.io}.
 */
public final class AlarmScheduler {

    private static final String TAG = "WordWakeScheduler";

    private AlarmScheduler() {}

    public static boolean canScheduleExact(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        AlarmManager am = context.getSystemService(AlarmManager.class);
        return am != null && am.canScheduleExactAlarms();
    }

    private static Intent fireIntent(Context context, long id) {
        return new Intent(context, AlarmReceiver.class)
                .setAction(AlarmReceiver.ACTION_FIRE)
                .setData(Uri.parse("wordwake://schedule/" + id))
                .putExtra(AlarmReceiver.EXTRA_SCHEDULE_ID, id);
    }

    private static Intent endIntent(Context context, long id) {
        return new Intent(context, AlarmReceiver.class)
                .setAction(AlarmReceiver.ACTION_END)
                .setData(Uri.parse("wordwake://end/" + id))
                .putExtra(AlarmReceiver.EXTRA_SCHEDULE_ID, id);
    }

    /** Recomputes and registers the next alarm for one schedule, and persists the result. */
    public static void reschedule(Context context, Schedule s) {
        reschedule(context, s, ActiveAlarm.isRinging(s.id));
    }

    public static void reschedule(Context context, Schedule s, boolean ringingInProcess) {
        ScheduleRepository repo = new ScheduleRepository(context);
        long now = System.currentTimeMillis();
        ScheduleEngine.Event ev = ScheduleEngine.nextEvent(s, now, ZoneId.systemDefault(), ringingInProcess);

        // Tell the user when an alert passed without ringing (phone off, alarm held back by the system).
        if (s.enabled && !s.isCompleted() && ScheduleEngine.wasMissed(s, ev, now)) {
            s.lastOutcome = Schedule.OUTCOME_MISSED;
            DiagLog.log(context, DiagLog.MISSED, s.id, s.displayTitle(), "expectedAt=" + s.nextTriggerAt);
            Notifications.postMissed(context, s, s.nextTriggerAt);
        }

        if (ev == null) {
            cancelFire(context, s.id);
            if (s.enabled && !s.isCompleted() && s.isFiniteRepeat() && !ringingInProcess && s.ringActiveUntil <= now) {
                // Nothing left to ring: switch it off. A one-time alert that was never registered
                // long enough to fire (e.g. restored after its time) still counts as missed.
                if (s.repeatMode == Schedule.REPEAT_ONCE && s.lastOutcome == Schedule.OUTCOME_NONE
                        && s.lastRingAt == 0 && s.dismissedOcc == 0) {
                    s.lastOutcome = Schedule.OUTCOME_MISSED;
                    long start = ScheduleEngine.windowFor(s.onceDate(), s.startMinute, s.endMinute,
                            s.zone(ZoneId.systemDefault())).start;
                    DiagLog.log(context, DiagLog.MISSED, s.id, s.displayTitle(), "expectedAt=" + start);
                    Notifications.postMissed(context, s, start);
                } else if (s.lastOutcome == Schedule.OUTCOME_NONE) {
                    s.lastOutcome = Schedule.OUTCOME_COMPLETED;
                }
                s.enabled = false;
            }
            s.nextTriggerAt = 0;
            repo.saveState(s);
            return;
        }

        AlarmManager am = context.getSystemService(AlarmManager.class);
        PendingIntent pi = PendingIntent.getBroadcast(context, requestCode(s.id), fireIntent(context, s.id),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        boolean exact = canScheduleExact(context);
        try {
            if (exact) {
                PendingIntent show = PendingIntent.getActivity(context, 0,
                        new Intent(context, MainActivity.class),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                am.setAlarmClock(new AlarmManager.AlarmClockInfo(ev.at, show), pi);
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ev.at, pi);
            }
        } catch (SecurityException e) {
            // Exact access revoked between the check and the call.
            exact = false;
            DiagLog.log(context, DiagLog.PERMISSION_FAILURE, s.id, "exact alarm access revoked");
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ev.at, pi);
        } catch (RuntimeException e) {
            // For example the system's per-app limit on registered alarms.
            DiagLog.log(context, DiagLog.SCHEDULE_FAILURE, s.id, e.getClass().getSimpleName());
            return;
        }
        if (s.nextTriggerAt != ev.at || s.nextExact != exact) {
            DiagLog.log(context, DiagLog.REGISTERED, s.id, "at=" + ev.at + " exact=" + exact + " kind=" + ev.kind);
            if (!exact) DiagLog.log(context, DiagLog.PERMISSION_FAILURE, s.id, "exact alarms unavailable; using inexact fallback");
        }
        s.nextTriggerAt = ev.at;
        s.nextExact = exact;
        repo.saveState(s);
        Log.d(TAG, "Scheduled #" + s.id + " kind=" + ev.kind + " in " + (ev.at - now) / 1000 + "s exact=" + exact);
    }

    /** True when AlarmManager currently holds this schedule's alarm (for diagnostics and tests). */
    public static boolean isRegistered(Context context, long id) {
        return PendingIntent.getBroadcast(context, requestCode(id), fireIntent(context, id),
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE) != null;
    }

    public static void cancelFire(Context context, long id) {
        cancel(context, PendingIntent.getBroadcast(context, requestCode(id), fireIntent(context, id),
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE));
    }

    /** Backup alarm that ends a ring session at its deadline even if in-process timers are lost. */
    static void scheduleEnd(Context context, long id, long at) {
        AlarmManager am = context.getSystemService(AlarmManager.class);
        PendingIntent pi = PendingIntent.getBroadcast(context, requestCode(id), endIntent(context, id),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            if (canScheduleExact(context)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    static void cancelEnd(Context context, long id) {
        cancel(context, PendingIntent.getBroadcast(context, requestCode(id), endIntent(context, id),
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE));
    }

    /** Fully removes every alarm belonging to a schedule (disable/delete). */
    public static void cancelAll(Context context, long id) {
        cancelFire(context, id);
        cancelEnd(context, id);
    }

    private static void cancel(Context context, PendingIntent pi) {
        if (pi == null) return;
        AlarmManager am = context.getSystemService(AlarmManager.class);
        am.cancel(pi);
        pi.cancel();
    }

    private static int requestCode(long id) {
        return (int) (id & 0x7FFFFFFF);
    }

    /**
     * Reconciles stored schedules with AlarmManager. Called at app start, after reboot, app update,
     * time/time-zone change and exact-alarm permission changes.
     */
    public static void reconcileAll(Context context, String reason) {
        List<Schedule> all = new ScheduleRepository(context).getAll();
        Log.i(TAG, "Reconcile (" + reason + "): " + all.size() + " schedules");
        for (Schedule s : all) {
            try {
                if (s.enabled) {
                    reschedule(context, s);
                } else {
                    cancelAll(context, s.id);
                    if (s.nextTriggerAt != 0) {
                        s.nextTriggerAt = 0;
                        new ScheduleRepository(context).saveState(s);
                    }
                }
            } catch (RuntimeException e) {
                Log.e(TAG, "Reconcile failed for #" + s.id, e);
                DiagLog.log(context, DiagLog.SCHEDULE_FAILURE, s.id, e.getClass().getSimpleName());
            }
        }
        ReliabilityService.sync(context);
    }
}
