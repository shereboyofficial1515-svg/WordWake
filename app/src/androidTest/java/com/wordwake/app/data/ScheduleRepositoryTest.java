package com.wordwake.app.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.wordwake.app.model.Schedule;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class ScheduleRepositoryTest {

    private static final String DB = "wordwake-test.db";
    private Context context;
    private WordWakeDatabase db;
    private ScheduleRepository repo;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.deleteDatabase(DB);
        db = WordWakeDatabase.createForTest(context, DB);
        repo = new ScheduleRepository(db);
    }

    @After
    public void tearDown() {
        db.close();
        context.deleteDatabase(DB);
    }

    @Test
    public void longMultilineMessageRoundTripsExactly() {
        Schedule s = Presets.motivation(new AppSettings(context));
        StringBuilder body = new StringBuilder(Presets.MOTIVATION_BODY);
        body.append("\n\n🌅 Emoji, “quotes”, tabs\tand\r\nCRLF.\n");
        for (int i = 0; i < 200; i++) body.append("Line ").append(i).append(": I never quit.\n");
        s.body = body.toString();
        long id = repo.save(s);
        Schedule loaded = repo.get(id);
        assertEquals(s.body, loaded.body);
        assertEquals(Presets.MOTIVATION_TITLE, loaded.title);
        assertEquals(s.startMinute, loaded.startMinute);
        assertEquals(s.endMinute, loaded.endMinute);
    }

    @Test
    public void deletedScheduleIsNeverResurrectedByLateWrites() {
        Schedule s = Presets.newSchedule(new AppSettings(context));
        s.body = "Wake up";
        long id = repo.save(s);
        repo.delete(id);
        assertNull(repo.get(id));
        // A stale copy being saved (e.g. an editor that was open) must not recreate the row.
        s.lastRingAt = 123;
        assertEquals(0, repo.save(s));
        repo.saveState(s);
        assertNull(repo.get(id));
        assertEquals(0, repo.count());
    }

    @Test
    public void runtimeStateIsPersisted() {
        Schedule s = Presets.newSchedule(new AppSettings(context));
        s.body = "x";
        long id = repo.save(s);
        s.occStart = 1000;
        s.lastRingAt = 2000;
        s.dismissedOcc = 1000;
        s.snoozeUntil = 3000;
        s.ringActiveUntil = 4000;
        s.lastOutcome = Schedule.OUTCOME_DISMISSED;
        assertTrue(repo.saveState(s));
        Schedule l = repo.get(id);
        assertEquals(1000, l.occStart);
        assertEquals(2000, l.lastRingAt);
        assertEquals(1000, l.dismissedOcc);
        assertEquals(3000, l.snoozeUntil);
        assertEquals(4000, l.ringActiveUntil);
        assertEquals(Schedule.OUTCOME_DISMISSED, l.lastOutcome);
    }

    @Test
    public void editingKeepsIdAndUpdatesFields() {
        Schedule s = Presets.newSchedule(new AppSettings(context));
        s.body = "first";
        long id = repo.save(s);
        s.body = "second";
        s.startMinute = 7 * 60;
        assertEquals(id, repo.save(s));
        assertEquals("second", repo.get(id).body);
        assertEquals(7 * 60, repo.get(id).startMinute);
        assertEquals(1, repo.count());
        Schedule copy = s.copy();
        copy.id = 0;
        assertNotEquals(id, repo.save(copy));
        assertEquals(2, repo.count());
    }
}
