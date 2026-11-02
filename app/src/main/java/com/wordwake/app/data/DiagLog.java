package com.wordwake.app.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Structured alarm log, stored locally. Details hold timestamps, IDs and error names only, never
 * message text. The reminder title is kept for the in-app history and is left out of exports.
 */
public final class DiagLog {

    private static final String TAG = "WordWakeDiag";
    private static final int MAX_ROWS = 3000;
    private static int writesSincePrune;

    public static final String CREATED = "ALARM_CREATED";
    public static final String UPDATED = "ALARM_UPDATED";
    public static final String REGISTERED = "ALARM_REGISTERED";
    public static final String CANCELLED = "ALARM_CANCELLED";
    public static final String TRIGGERED = "ALARM_TRIGGERED";
    public static final String NOTIFICATION_POSTED = "NOTIFICATION_POSTED";
    public static final String AUDIO_STARTED = "AUDIO_STARTED";
    public static final String AUDIO_STOPPED = "AUDIO_STOPPED";
    public static final String AUTO_DISMISSED = "ALARM_AUTO_DISMISSED";
    public static final String REBOOT_RECOVERY = "REBOOT_RECOVERY";
    public static final String SCHEDULE_FAILURE = "SCHEDULING_FAILURE";
    public static final String PERMISSION_FAILURE = "PERMISSION_FAILURE";
    public static final String MISSED = "ALARM_MISSED";
    public static final String STOPPED = "ALARM_STOPPED";
    public static final String SNOOZED = "ALARM_SNOOZED";
    public static final String DISMISSED = "ALARM_DISMISSED";
    public static final String COMPLETED = "TASK_COMPLETED";
    public static final String REOPENED = "TASK_REOPENED";
    public static final String PROCESS_EXIT = "PROCESS_EXIT";
    public static final String TEST_STARTED = "SYSTEM_TEST_STARTED";
    public static final String TEST_FIRED = "SYSTEM_TEST_FIRED";

    /** Event types shown as a reminder's history. */
    public static final List<String> HISTORY_TYPES = Arrays.asList(
            CREATED, TRIGGERED, MISSED, STOPPED, SNOOZED, DISMISSED, AUTO_DISMISSED, COMPLETED, REOPENED);

    /** Event types that mean an alert failed to reach the user. */
    public static final List<String> FAILURE_TYPES = Arrays.asList(MISSED, SCHEDULE_FAILURE, PERMISSION_FAILURE);

    public static final class Entry {
        public long at;
        public String type;
        public long scheduleId;
        public String title;
        public String detail;
    }

    private DiagLog() {}

    public static void log(Context context, String type, long scheduleId, String title, String detail) {
        Log.i(TAG, type + " #" + scheduleId + " " + (detail == null ? "" : detail));
        try {
            ContentValues v = new ContentValues();
            v.put("at", System.currentTimeMillis());
            v.put("type", type);
            v.put("schedule_id", scheduleId);
            v.put("title", title == null ? "" : title);
            v.put("detail", detail == null ? "" : detail);
            SQLiteDatabase db = WordWakeDatabase.get(context).getWritableDatabase();
            db.insert(WordWakeDatabase.T_EVENTS, null, v);
            if (++writesSincePrune >= 200) {
                writesSincePrune = 0;
                db.execSQL("DELETE FROM " + WordWakeDatabase.T_EVENTS + " WHERE id <= (SELECT id FROM "
                        + WordWakeDatabase.T_EVENTS + " ORDER BY id DESC LIMIT 1 OFFSET " + MAX_ROWS + ")");
            }
        } catch (RuntimeException e) {
            // Logging must never be the reason an alarm fails.
            Log.e(TAG, "Could not store event " + type, e);
        }
    }

    public static void log(Context context, String type, long scheduleId, String detail) {
        log(context, type, scheduleId, "", detail);
    }

    public static Entry last(Context context, String... types) {
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < types.length; i++) in.append(i == 0 ? "?" : ",?");
        List<Entry> rows = query(context, "type IN (" + in + ")", types, 1);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public static List<Entry> recent(Context context, int limit) {
        return query(context, null, null, limit);
    }

    public static List<Entry> history(Context context, long scheduleId, int limit) {
        List<String> args = new ArrayList<>(HISTORY_TYPES);
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < HISTORY_TYPES.size(); i++) in.append(i == 0 ? "?" : ",?");
        String where = "type IN (" + in + ")";
        if (scheduleId != 0) {
            where += " AND schedule_id=?";
            args.add(String.valueOf(scheduleId));
        }
        return query(context, where, args.toArray(new String[0]), limit);
    }

    private static List<Entry> query(Context context, String where, String[] args, int limit) {
        List<Entry> out = new ArrayList<>();
        try (Cursor c = WordWakeDatabase.get(context).getReadableDatabase().query(WordWakeDatabase.T_EVENTS,
                new String[]{"at", "type", "schedule_id", "title", "detail"}, where, args, null, null,
                "id DESC", String.valueOf(limit))) {
            while (c.moveToNext()) {
                Entry e = new Entry();
                e.at = c.getLong(0);
                e.type = c.getString(1);
                e.scheduleId = c.getLong(2);
                e.title = c.getString(3);
                e.detail = c.getString(4);
                out.add(e);
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not read events", e);
        }
        return out;
    }
}
