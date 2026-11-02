package com.wordwake.app.data;

import android.content.Context;
import android.database.DatabaseErrorHandler;
import android.database.DefaultDatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.UserManager;
import android.util.Log;

/**
 * Local SQLite storage. Nothing here ever leaves the device.
 *
 * Migrations are applied step by step in {@link #onUpgrade}. If the file is corrupted the
 * platform error handler replaces it with an empty database so the app keeps working, and
 * {@link #wasReset} lets the UI tell the user.
 */
public final class WordWakeDatabase extends SQLiteOpenHelper {

    private static final String TAG = "WordWakeDb";
    public static final String NAME = "wordwake.db";
    /** v1: schedules table. v2: last_outcome column. v3: structured reminders and diagnostics. */
    public static final int VERSION = 3;

    public static final String T_SCHEDULES = "schedules";
    public static final String T_CHECKLIST = "checklist_items";
    public static final String T_ATTACHMENTS = "attachments";
    public static final String T_QUOTES = "quotes";
    public static final String T_CATEGORIES = "categories";
    public static final String T_EVENTS = "events";

    private static volatile WordWakeDatabase instance;
    private static volatile boolean wasReset;

    public static WordWakeDatabase get(Context context) {
        if (instance == null) {
            synchronized (WordWakeDatabase.class) {
                if (instance == null) {
                    Context app = context.getApplicationContext();
                    // Device-protected storage is readable before the first unlock after a reboot,
                    // so alarms can ring on a phone that restarted overnight.
                    Context dp = app.createDeviceProtectedStorageContext();
                    // The old location is only readable once the phone has been unlocked.
                    UserManager um = app.getSystemService(UserManager.class);
                    if (!dp.getDatabasePath(NAME).exists() && (um == null || um.isUserUnlocked())) {
                        dp.moveDatabaseFrom(app, NAME);
                    }
                    instance = new WordWakeDatabase(dp, NAME);
                }
            }
        }
        return instance;
    }

    /** For tests: a separate database file. */
    public static WordWakeDatabase createForTest(Context context, String name) {
        return new WordWakeDatabase(context.getApplicationContext(), name);
    }

    private WordWakeDatabase(Context context, String name) {
        super(context, name, null, VERSION, new ResettingErrorHandler());
        setWriteAheadLoggingEnabled(true);
    }

    public static boolean consumeWasReset() {
        boolean r = wasReset;
        wasReset = false;
        return r;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        createV1(db);
        migrateV1toV2(db);
        migrateV2toV3(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        int v = oldVersion;
        if (v < 2) {
            migrateV1toV2(db);
            v = 2;
        }
        if (v < 3) {
            migrateV2toV3(db);
            v = 3;
        }
        Log.i(TAG, "Database migrated from " + oldVersion + " to " + v);
    }

    @Override
    public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // A downgrade can only happen when installing an older build over a newer one. Columns
        // added later are simply ignored by older code, so keep the data.
        Log.w(TAG, "Database downgrade " + oldVersion + " -> " + newVersion + " (data kept)");
    }

    private static void createV1(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + T_SCHEDULES + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "title TEXT NOT NULL DEFAULT '',"
                + "body TEXT NOT NULL DEFAULT '',"
                + "repeat_mode INTEGER NOT NULL DEFAULT 1,"
                + "start_date INTEGER NOT NULL DEFAULT 0,"
                + "start_minute INTEGER NOT NULL DEFAULT 360,"
                + "end_minute INTEGER NOT NULL DEFAULT 390,"
                + "days_mask INTEGER NOT NULL DEFAULT 127,"
                + "zone_id TEXT NOT NULL DEFAULT '',"
                + "reminder_minutes INTEGER NOT NULL DEFAULT 0,"
                + "sound_uri TEXT NOT NULL DEFAULT '',"
                + "sound_name TEXT NOT NULL DEFAULT '',"
                + "volume INTEGER NOT NULL DEFAULT 100,"
                + "loop_sound INTEGER NOT NULL DEFAULT 1,"
                + "sound_duration INTEGER NOT NULL DEFAULT 0,"
                + "vibrate INTEGER NOT NULL DEFAULT 1,"
                + "snooze_minutes INTEGER NOT NULL DEFAULT 10,"
                + "auto_dismiss INTEGER NOT NULL DEFAULT 1,"
                + "enabled INTEGER NOT NULL DEFAULT 1,"
                + "created_at INTEGER NOT NULL DEFAULT 0,"
                + "updated_at INTEGER NOT NULL DEFAULT 0,"
                + "occ_start INTEGER NOT NULL DEFAULT 0,"
                + "last_ring_at INTEGER NOT NULL DEFAULT 0,"
                + "dismissed_occ INTEGER NOT NULL DEFAULT 0,"
                + "snooze_until INTEGER NOT NULL DEFAULT 0,"
                + "ring_active_until INTEGER NOT NULL DEFAULT 0,"
                + "next_trigger_at INTEGER NOT NULL DEFAULT 0,"
                + "next_exact INTEGER NOT NULL DEFAULT 0)");
    }

    private static void migrateV1toV2(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE " + T_SCHEDULES + " ADD COLUMN last_outcome INTEGER NOT NULL DEFAULT 0");
    }

    private static void migrateV2toV3(SQLiteDatabase db) {
        String[] columns = {
                "type INTEGER NOT NULL DEFAULT 0",
                "alert_mode INTEGER NOT NULL DEFAULT 0",
                "category_key TEXT NOT NULL DEFAULT 'motivation'",
                "custom_dates TEXT NOT NULL DEFAULT ''",
                "quote TEXT NOT NULL DEFAULT ''",
                "quote_random INTEGER NOT NULL DEFAULT 0",
                "deadline_at INTEGER NOT NULL DEFAULT 0",
                "lead_days TEXT NOT NULL DEFAULT ''",
                "follow_up_daily INTEGER NOT NULL DEFAULT 0",
                "overdue_daily INTEGER NOT NULL DEFAULT 0",
                "status INTEGER NOT NULL DEFAULT 0",
                "completed_at INTEGER NOT NULL DEFAULT 0",
                "finance_json TEXT NOT NULL DEFAULT ''"};
        for (String c : columns) db.execSQL("ALTER TABLE " + T_SCHEDULES + " ADD COLUMN " + c);
        db.execSQL("CREATE TABLE " + T_CHECKLIST + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "schedule_id INTEGER NOT NULL,"
                + "position INTEGER NOT NULL DEFAULT 0,"
                + "text TEXT NOT NULL DEFAULT '',"
                + "done INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX idx_checklist_schedule ON " + T_CHECKLIST + "(schedule_id)");
        db.execSQL("CREATE TABLE " + T_ATTACHMENTS + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "schedule_id INTEGER NOT NULL,"
                + "file_name TEXT NOT NULL,"
                + "display_name TEXT NOT NULL DEFAULT '',"
                + "mime TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE INDEX idx_attachments_schedule ON " + T_ATTACHMENTS + "(schedule_id)");
        db.execSQL("CREATE TABLE " + T_QUOTES + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "text TEXT NOT NULL,"
                + "favorite INTEGER NOT NULL DEFAULT 0,"
                + "built_in INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE " + T_CATEGORIES + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "name TEXT NOT NULL,"
                + "color INTEGER NOT NULL DEFAULT 0)");
        // Diagnostics and history. "title" is shown in reminder history but is never exported.
        db.execSQL("CREATE TABLE " + T_EVENTS + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "at INTEGER NOT NULL,"
                + "type TEXT NOT NULL,"
                + "schedule_id INTEGER NOT NULL DEFAULT 0,"
                + "title TEXT NOT NULL DEFAULT '',"
                + "detail TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE INDEX idx_events_at ON " + T_EVENTS + "(at)");
        db.execSQL("CREATE INDEX idx_events_type ON " + T_EVENTS + "(type)");
    }

    private static final class ResettingErrorHandler implements DatabaseErrorHandler {
        private final DefaultDatabaseErrorHandler delegate = new DefaultDatabaseErrorHandler();

        @Override
        public void onCorruption(SQLiteDatabase db) {
            Log.e(TAG, "Database corruption detected; resetting");
            wasReset = true;
            delegate.onCorruption(db);
        }
    }
}
