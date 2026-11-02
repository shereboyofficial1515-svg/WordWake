package com.wordwake.app.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.wordwake.app.model.Attachment;
import com.wordwake.app.model.ChecklistItem;
import com.wordwake.app.model.Schedule;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/** Database recovery and migration: existing users' reminders must survive the upgrade. */
@RunWith(AndroidJUnit4.class)
public class DatabaseUpgradeTest {

    private static final String DB = "wordwake-upgrade-test.db";
    private Context context;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.deleteDatabase(DB);
    }

    @After
    public void tearDown() {
        context.deleteDatabase(DB);
    }

    @Test
    public void version2DataSurvivesUpgradeToCurrentVersion() {
        File file = context.getDatabasePath(DB);
        if (file.getParentFile() != null) assertTrue(file.getParentFile().exists() || file.getParentFile().mkdirs());
        try (SQLiteDatabase old = SQLiteDatabase.openOrCreateDatabase(file, null)) {
            old.execSQL("CREATE TABLE schedules (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL DEFAULT '',"
                    + "body TEXT NOT NULL DEFAULT '', repeat_mode INTEGER NOT NULL DEFAULT 1, start_date INTEGER NOT NULL DEFAULT 0,"
                    + "start_minute INTEGER NOT NULL DEFAULT 360, end_minute INTEGER NOT NULL DEFAULT 390, days_mask INTEGER NOT NULL DEFAULT 127,"
                    + "zone_id TEXT NOT NULL DEFAULT '', reminder_minutes INTEGER NOT NULL DEFAULT 0, sound_uri TEXT NOT NULL DEFAULT '',"
                    + "sound_name TEXT NOT NULL DEFAULT '', volume INTEGER NOT NULL DEFAULT 100, loop_sound INTEGER NOT NULL DEFAULT 1,"
                    + "sound_duration INTEGER NOT NULL DEFAULT 0, vibrate INTEGER NOT NULL DEFAULT 1, snooze_minutes INTEGER NOT NULL DEFAULT 10,"
                    + "auto_dismiss INTEGER NOT NULL DEFAULT 1, enabled INTEGER NOT NULL DEFAULT 1, created_at INTEGER NOT NULL DEFAULT 0,"
                    + "updated_at INTEGER NOT NULL DEFAULT 0, occ_start INTEGER NOT NULL DEFAULT 0, last_ring_at INTEGER NOT NULL DEFAULT 0,"
                    + "dismissed_occ INTEGER NOT NULL DEFAULT 0, snooze_until INTEGER NOT NULL DEFAULT 0, ring_active_until INTEGER NOT NULL DEFAULT 0,"
                    + "next_trigger_at INTEGER NOT NULL DEFAULT 0, next_exact INTEGER NOT NULL DEFAULT 0, last_outcome INTEGER NOT NULL DEFAULT 0)");
            old.execSQL("INSERT INTO schedules (title, body, start_minute, end_minute) VALUES ('Morning', 'Line one\nLine two', 370, 400)");
            old.setVersion(2);
        }

        WordWakeDatabase db = WordWakeDatabase.createForTest(context, DB);
        try {
            ScheduleRepository repo = new ScheduleRepository(db);
            List<Schedule> all = repo.getAll();
            assertEquals(1, all.size());
            Schedule s = all.get(0);
            assertEquals("Morning", s.title);
            assertEquals("Line one\nLine two", s.body);
            assertEquals(370, s.startMinute);
            assertEquals(Schedule.TYPE_ALARM, s.type);
            assertEquals(Schedule.ALERT_ALARM, s.alertMode);
            assertEquals(Schedule.STATUS_NOT_STARTED, s.status);
            ContentRepository content = new ContentRepository(db);
            assertTrue(content.checklist(s.id).isEmpty());
        } finally {
            db.close();
        }
    }

    @Test
    public void corruptedFileIsReplacedAndAppKeepsWorking() throws IOException {
        File file = context.getDatabasePath(DB);
        if (file.getParentFile() != null) assertTrue(file.getParentFile().exists() || file.getParentFile().mkdirs());
        try (FileOutputStream out = new FileOutputStream(file)) {
            byte[] garbage = new byte[8192];
            Arrays.fill(garbage, (byte) 0x5A);
            out.write(garbage);
        }
        WordWakeDatabase db = WordWakeDatabase.createForTest(context, DB);
        try {
            ScheduleRepository repo = new ScheduleRepository(db);
            assertEquals(0, repo.count());
            Schedule s = new Schedule();
            s.body = "after recovery";
            long id = repo.save(s);
            assertEquals("after recovery", repo.get(id).body);
            assertTrue(WordWakeDatabase.consumeWasReset());
        } finally {
            db.close();
        }
    }

    @Test
    public void checklistKeepsOrderAndAttachmentsAreRemovedWithReminder() {
        WordWakeDatabase db = WordWakeDatabase.createForTest(context, DB);
        try {
            ScheduleRepository repo = new ScheduleRepository(db);
            ContentRepository content = new ContentRepository(db);
            Schedule s = new Schedule();
            s.body = "Pay tax";
            long id = repo.save(s);
            content.saveChecklist(id, Arrays.asList(new ChecklistItem("Second"), new ChecklistItem("  "), new ChecklistItem("Third")));
            List<ChecklistItem> items = content.checklist(id);
            assertEquals(2, items.size());
            assertEquals("Second", items.get(0).text);
            assertEquals("Third", items.get(1).text);
            content.setChecklistDone(items.get(0).id, true);
            assertTrue(content.checklist(id).get(0).done);
            content.resetChecklist(id);
            assertFalse(content.checklist(id).get(0).done);

            Attachment a = new Attachment();
            a.scheduleId = id;
            a.fileName = "x";
            a.displayName = "receipt.pdf";
            a.mime = "application/pdf";
            content.addAttachment(a);
            assertEquals(1, content.attachments(id).size());
            assertNotNull(content.attachments(id).get(0).fileName);

            repo.delete(id);
            assertTrue(content.checklist(id).isEmpty());
            assertTrue(content.attachments(id).isEmpty());
        } finally {
            db.close();
        }
    }
}
