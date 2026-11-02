package com.wordwake.app.alarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.wordwake.app.data.AppSettings;
import com.wordwake.app.data.ContentRepository;
import com.wordwake.app.data.DiagLog;
import com.wordwake.app.data.Presets;
import com.wordwake.app.data.ScheduleRepository;
import com.wordwake.app.data.WordWakeDatabase;
import com.wordwake.app.engine.ScheduleEngine;
import com.wordwake.app.model.ChecklistItem;
import com.wordwake.app.model.Schedule;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;

/**
 * Exercises real AlarmManager delivery and state transitions on a device. These run in the app's
 * process, so they prove the pipeline works, not that a manufacturer's power manager lets it run
 * while the app is in the background. That is covered by RUN ALARM SYSTEM TEST and the manual runs.
 */
@RunWith(AndroidJUnit4.class)
public class AlarmDeliveryTest {

    private Context context;
    private ScheduleRepository repo;
    private long id;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        repo = new ScheduleRepository(context);
    }

    @After
    public void tearDown() {
        if (id != 0) {
            AlarmController.onDisabledOrDeleted(context, id);
            repo.delete(id);
            // These tests share the app's real database: leave no entries in the user's history.
            WordWakeDatabase.get(context).getWritableDatabase().delete(WordWakeDatabase.T_EVENTS,
                    "schedule_id=?", new String[]{String.valueOf(id)});
        }
        AlarmService.stop(context);
    }

    /** A one-time reminder whose window is open right now. */
    private Schedule ringingNow(int alertMode) {
        Schedule s = Presets.newSchedule(new AppSettings(context));
        s.title = "Test";
        s.body = "Instrumented test";
        s.repeatMode = Schedule.REPEAT_ONCE;
        s.alertMode = alertMode;
        s.vibrate = false;
        s.volumePercent = 10;
        LocalTime now = LocalTime.now();
        int minute = now.getHour() * 60 + now.getMinute();
        s.startDate = LocalDate.now().toEpochDay();
        s.startMinute = minute;
        s.endMinute = (minute + 30) % 1440;
        s.snoozeMinutes = 5;
        id = repo.save(s);
        return s;
    }

    private static void waitUntil(long timeoutMs, java.util.function.BooleanSupplier condition) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end && !condition.getAsBoolean()) Thread.sleep(200);
    }

    @Test
    public void systemTestAlarmIsDeliveredByAlarmManagerOnTime() throws InterruptedException {
        AppSettings settings = new AppSettings(context);
        long expected = SystemTest.start(context);
        assertTrue("alarm registered", expected > 0);
        waitUntil((SystemTest.LEAD_SECONDS + 20) * 1000L, () -> settings.testFiredAt() != 0);
        assertTrue("alarm was delivered", settings.testFiredAt() != 0);
        long delay = settings.testFiredAt() - expected;
        assertTrue("delivered at most 5s late, was " + delay + "ms", delay >= 0 && delay <= SystemTest.ON_TIME_MS);
        waitUntil(10_000, () -> settings.testAudio() != SystemTest.AUDIO_UNKNOWN);
        assertEquals("audio player was running", SystemTest.AUDIO_ENGINE_RUNNING, settings.testAudio());
        assertTrue("service started", settings.testServiceAt() != 0);
    }

    /** Regression: Snooze after Stop on a one-time alert did nothing, because it had been switched off. */
    @Test
    public void snoozeAfterStopOnOneTimeAlertStillRingsAgain() {
        ringingNow(Schedule.ALERT_NOTIFICATION);
        AlarmController.onFire(context, id);
        Schedule s = repo.get(id);
        assertTrue("rang", s.lastRingAt > 0);

        AlarmController.silence(context, id);
        assertFalse("one-time alert finishes after stop", repo.get(id).enabled);

        AlarmController.snooze(context, id);
        s = repo.get(id);
        assertTrue("snooze re-enables it", s.enabled);
        assertTrue(s.snoozeUntil > System.currentTimeMillis());
        assertEquals("next alert is the snooze", s.snoozeUntil, s.nextTriggerAt);
        assertTrue(AlarmScheduler.isRegistered(context, id));
    }

    @Test
    public void notificationModeDoesNotStartAlarmService() {
        ringingNow(Schedule.ALERT_NOTIFICATION);
        AlarmController.onFire(context, id);
        Schedule s = repo.get(id);
        assertTrue(s.lastRingAt > 0);
        assertEquals(0, s.ringActiveUntil);
        assertFalse(ActiveAlarm.isRinging(id));
    }

    @Test
    public void duplicateFireDoesNotRingTwice() {
        ringingNow(Schedule.ALERT_NOTIFICATION);
        AlarmController.onFire(context, id);
        long first = repo.get(id).lastRingAt;
        AlarmController.onFire(context, id);
        assertEquals(first, repo.get(id).lastRingAt);
    }

    @Test
    public void completingDeadlineTaskStopsAlertsAndLogsIt() {
        Schedule s = Presets.newSchedule(new AppSettings(context));
        s.title = "Pay tax";
        s.type = Schedule.TYPE_TAX;
        s.repeatMode = Schedule.REPEAT_DEADLINE;
        s.startDate = LocalDate.now().toEpochDay();
        s.deadlineAt = System.currentTimeMillis() + 20L * 86_400_000L;
        s.leadDays = "7,3,0";
        id = repo.save(s);
        AlarmScheduler.reschedule(context, s);
        assertTrue(AlarmScheduler.isRegistered(context, id));

        AlarmController.complete(context, id);
        Schedule done = repo.get(id);
        assertTrue(done.isCompleted());
        assertFalse(done.enabled);
        assertFalse("future alerts are cancelled", AlarmScheduler.isRegistered(context, id));

        AlarmController.reopen(context, id);
        assertFalse(repo.get(id).isCompleted());
        assertTrue("reopening schedules it again", AlarmScheduler.isRegistered(context, id));

        List<DiagLog.Entry> history = DiagLog.history(context, id, 10);
        assertEquals(DiagLog.REOPENED, history.get(0).type);
        assertEquals(DiagLog.COMPLETED, history.get(1).type);
    }

    @Test
    public void completingRecurringReminderFinishesTodayAndResetsChecklist() {
        Schedule s = ringingNow(Schedule.ALERT_NOTIFICATION);
        s.repeatMode = Schedule.REPEAT_DAILY;
        repo.save(s);
        ContentRepository content = new ContentRepository(context);
        ChecklistItem step = new ChecklistItem("Stretch");
        step.done = true;
        content.saveChecklist(id, Collections.singletonList(step));

        AlarmController.onFire(context, id);
        AlarmController.complete(context, id);

        Schedule after = repo.get(id);
        assertFalse("a daily reminder keeps going", after.isCompleted());
        assertTrue(after.enabled);
        assertFalse("checklist starts fresh next time", content.checklist(id).get(0).done);
        ScheduleEngine.Event next = ScheduleEngine.nextEvent(after, System.currentTimeMillis(), ZoneId.systemDefault());
        assertTrue("next alert is tomorrow, not now", next.at > System.currentTimeMillis() + 60_000L);
    }

    @Test
    public void missedAlertIsLoggedAndReported() {
        Schedule s = Presets.newSchedule(new AppSettings(context));
        s.title = "Was off";
        s.repeatMode = Schedule.REPEAT_DAILY;
        id = repo.save(s);
        // Pretend the alert was due two days ago and nothing delivered it.
        s.nextTriggerAt = System.currentTimeMillis() - 2 * 86_400_000L;
        repo.saveState(s);

        AlarmScheduler.reschedule(context, repo.get(id));

        Schedule after = repo.get(id);
        assertEquals(Schedule.OUTCOME_MISSED, after.lastOutcome);
        DiagLog.Entry e = DiagLog.history(context, id, 1).get(0);
        assertEquals(DiagLog.MISSED, e.type);
        assertFalse("log detail holds no message text", e.detail.contains("Was off"));
        assertTrue(after.nextTriggerAt > System.currentTimeMillis());
    }
}
