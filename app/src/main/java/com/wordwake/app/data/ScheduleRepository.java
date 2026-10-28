package com.wordwake.app.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import com.wordwake.app.model.Schedule;

import java.util.ArrayList;
import java.util.List;

/** Synchronous data access. Call from a background thread (see {@link AppExecutors}). */
public final class ScheduleRepository {

    private static final String TAG = "WordWakeRepo";
    private final WordWakeDatabase db;

    public ScheduleRepository(Context context) {
        this(WordWakeDatabase.get(context));
    }

    public ScheduleRepository(WordWakeDatabase db) {
        this.db = db;
    }

    public List<Schedule> getAll() {
        List<Schedule> list = new ArrayList<>();
        try (Cursor c = db.getReadableDatabase().query(WordWakeDatabase.T_SCHEDULES, null, null, null,
                null, null, "start_minute ASC, id ASC")) {
            while (c.moveToNext()) {
                Schedule s = read(c);
                if (s != null) list.add(s);
            }
        }
        return list;
    }

    public Schedule get(long id) {
        try (Cursor c = db.getReadableDatabase().query(WordWakeDatabase.T_SCHEDULES, null, "id=?",
                new String[]{String.valueOf(id)}, null, null, null)) {
            return c.moveToFirst() ? read(c) : null;
        }
    }

    public int count() {
        try (Cursor c = db.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM " + WordWakeDatabase.T_SCHEDULES, null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    /** Inserts when id == 0, otherwise updates. Returns the id. */
    public long save(Schedule s) {
        SQLiteDatabase w = db.getWritableDatabase();
        long now = System.currentTimeMillis();
        if (s.createdAt == 0) s.createdAt = now;
        s.updatedAt = now;
        ContentValues v = toValues(s);
        if (s.id == 0) {
            s.id = w.insertOrThrow(WordWakeDatabase.T_SCHEDULES, null, v);
        } else {
            int rows = w.update(WordWakeDatabase.T_SCHEDULES, v, "id=?", new String[]{String.valueOf(s.id)});
            if (rows == 0) {
                // The row was deleted meanwhile; never resurrect a deleted schedule.
                Log.w(TAG, "save: schedule " + s.id + " no longer exists");
                return 0;
            }
        }
        return s.id;
    }

    /** Updates only runtime state columns (does not touch updated_at or user fields). */
    public boolean saveState(Schedule s) {
        ContentValues v = new ContentValues();
        putState(v, s);
        v.put("enabled", s.enabled ? 1 : 0);
        return db.getWritableDatabase().update(WordWakeDatabase.T_SCHEDULES, v, "id=?",
                new String[]{String.valueOf(s.id)}) > 0;
    }

    public void delete(long id) {
        db.getWritableDatabase().delete(WordWakeDatabase.T_SCHEDULES, "id=?", new String[]{String.valueOf(id)});
    }

    private static ContentValues toValues(Schedule s) {
        ContentValues v = new ContentValues();
        v.put("title", s.title == null ? "" : s.title);
        v.put("body", s.body == null ? "" : s.body);
        v.put("repeat_mode", s.repeatMode);
        v.put("start_date", s.startDate);
        v.put("start_minute", s.startMinute);
        v.put("end_minute", s.endMinute);
        v.put("days_mask", s.daysMask);
        v.put("zone_id", s.zoneId == null ? "" : s.zoneId);
        v.put("reminder_minutes", s.reminderMinutes);
        v.put("sound_uri", s.soundUri == null ? "" : s.soundUri);
        v.put("sound_name", s.soundName == null ? "" : s.soundName);
        v.put("volume", s.volumePercent);
        v.put("loop_sound", s.loopSound ? 1 : 0);
        v.put("sound_duration", s.soundDurationMinutes);
        v.put("vibrate", s.vibrate ? 1 : 0);
        v.put("snooze_minutes", s.snoozeMinutes);
        v.put("auto_dismiss", s.autoDismiss ? 1 : 0);
        v.put("enabled", s.enabled ? 1 : 0);
        v.put("created_at", s.createdAt);
        v.put("updated_at", s.updatedAt);
        putState(v, s);
        return v;
    }

    private static void putState(ContentValues v, Schedule s) {
        v.put("occ_start", s.occStart);
        v.put("last_ring_at", s.lastRingAt);
        v.put("dismissed_occ", s.dismissedOcc);
        v.put("snooze_until", s.snoozeUntil);
        v.put("ring_active_until", s.ringActiveUntil);
        v.put("next_trigger_at", s.nextTriggerAt);
        v.put("next_exact", s.nextExact ? 1 : 0);
        v.put("last_outcome", s.lastOutcome);
    }

    private static Schedule read(Cursor c) {
        try {
            Schedule s = new Schedule();
            s.id = c.getLong(c.getColumnIndexOrThrow("id"));
            s.title = str(c, "title");
            s.body = str(c, "body");
            s.repeatMode = clamp(i(c, "repeat_mode"), 0, 2);
            s.startDate = l(c, "start_date");
            s.startMinute = clamp(i(c, "start_minute"), 0, 1439);
            s.endMinute = clamp(i(c, "end_minute"), 0, 1439);
            s.daysMask = i(c, "days_mask") & Schedule.ALL_DAYS;
            s.zoneId = str(c, "zone_id");
            s.reminderMinutes = Math.max(0, i(c, "reminder_minutes"));
            s.soundUri = str(c, "sound_uri");
            s.soundName = str(c, "sound_name");
            s.volumePercent = clamp(i(c, "volume"), 0, 100);
            s.loopSound = i(c, "loop_sound") != 0;
            s.soundDurationMinutes = Math.max(0, i(c, "sound_duration"));
            s.vibrate = i(c, "vibrate") != 0;
            s.snoozeMinutes = Math.max(0, i(c, "snooze_minutes"));
            s.autoDismiss = i(c, "auto_dismiss") != 0;
            s.enabled = i(c, "enabled") != 0;
            s.createdAt = l(c, "created_at");
            s.updatedAt = l(c, "updated_at");
            s.occStart = l(c, "occ_start");
            s.lastRingAt = l(c, "last_ring_at");
            s.dismissedOcc = l(c, "dismissed_occ");
            s.snoozeUntil = l(c, "snooze_until");
            s.ringActiveUntil = l(c, "ring_active_until");
            s.nextTriggerAt = l(c, "next_trigger_at");
            s.nextExact = i(c, "next_exact") != 0;
            s.lastOutcome = i(c, "last_outcome");
            return s;
        } catch (RuntimeException e) {
            // Skip a malformed row rather than crashing the whole app or the alarm receiver.
            Log.e(TAG, "Skipping unreadable schedule row", e);
            return null;
        }
    }

    private static String str(Cursor c, String col) {
        String v = c.getString(c.getColumnIndexOrThrow(col));
        return v == null ? "" : v;
    }

    private static int i(Cursor c, String col) {
        return c.getInt(c.getColumnIndexOrThrow(col));
    }

    private static long l(Cursor c, String col) {
        return c.getLong(c.getColumnIndexOrThrow(col));
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
