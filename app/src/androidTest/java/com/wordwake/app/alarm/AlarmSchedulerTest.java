package com.wordwake.app.alarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.wordwake.app.data.AppSettings;
import com.wordwake.app.data.Presets;
import com.wordwake.app.data.ScheduleRepository;
import com.wordwake.app.model.Schedule;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.LocalTime;

/**
 * Exercises real AlarmManager registration on a device. Uses the app's own database, so it
 * removes everything it creates.
 */
@RunWith(AndroidJUnit4.class)
public class AlarmSchedulerTest {

    private Context context;
    private ScheduleRepository repo;
    private long id;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        repo = new ScheduleRepository(context);
        Schedule s = Presets.newSchedule(new AppSettings(context));
        s.body = "Instrumented test";
        // Two hours from now so it never rings during the test.
        int start = (LocalTime.now().toSecondOfDay() / 60 + 120) % 1440;
        s.startMinute = start;
        s.endMinute = (start + 30) % 1440;
        id = repo.save(s);
    }

    @After
    public void tearDown() {
        AlarmScheduler.cancelAll(context, id);
        repo.delete(id);
    }

    private boolean firePendingIntentExists() {
        Intent i = new Intent(context, AlarmReceiver.class)
                .setAction(AlarmReceiver.ACTION_FIRE)
                .setData(Uri.parse("wordwake://schedule/" + id));
        return PendingIntent.getBroadcast(context, (int) id, i,
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE) != null;
    }

    @Test
    public void rescheduleRegistersAlarmAndCancelRemovesIt() {
        Schedule s = repo.get(id);
        AlarmScheduler.reschedule(context, s);
        assertTrue(firePendingIntentExists());
        Schedule saved = repo.get(id);
        long minutesAway = (saved.nextTriggerAt - System.currentTimeMillis()) / 60_000L;
        assertTrue("next trigger ~2h away, was " + minutesAway, minutesAway >= 118 && minutesAway <= 121);
        assertEquals(AlarmScheduler.canScheduleExact(context), saved.nextExact);

        AlarmScheduler.cancelAll(context, id);
        assertFalse(firePendingIntentExists());
    }

    @Test
    public void reschedulingTwiceKeepsASingleAlarm() {
        Schedule s = repo.get(id);
        AlarmScheduler.reschedule(context, s);
        AlarmScheduler.reschedule(context, repo.get(id));
        AlarmScheduler.reconcileAll(context, "test");
        assertTrue(firePendingIntentExists());
        AlarmScheduler.cancelFire(context, id);
        // One cancel removes it: there was only ever one PendingIntent for this schedule.
        assertFalse(firePendingIntentExists());
    }

    @Test
    public void disablingRemovesAlarmAndReconcileDoesNotRestoreIt() {
        Schedule s = repo.get(id);
        AlarmScheduler.reschedule(context, s);
        s = repo.get(id);
        s.enabled = false;
        repo.save(s);
        AlarmController.onDisabledOrDeleted(context, id);
        AlarmScheduler.reconcileAll(context, "test");
        assertFalse(firePendingIntentExists());
        assertEquals(0, repo.get(id).nextTriggerAt);
    }

    @Test
    public void fireForDeletedScheduleDoesNothing() {
        AlarmScheduler.reschedule(context, repo.get(id));
        repo.delete(id);
        AlarmController.onFire(context, id);
        assertFalse(firePendingIntentExists());
        assertNull(ActiveAlarm.get());
    }

    @Test
    public void earlyOrStaleFireOnlyReschedules() {
        AlarmScheduler.reschedule(context, repo.get(id));
        AlarmController.onFire(context, id); // not due for ~2 hours
        assertNull(ActiveAlarm.get());
        assertTrue(firePendingIntentExists());
        assertNotNull(repo.get(id));
        assertEquals(0, repo.get(id).lastRingAt);
    }
}
