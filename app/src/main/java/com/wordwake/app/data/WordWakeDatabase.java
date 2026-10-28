package com.wordwake.app.data;

import android.content.Context;
import android.database.DatabaseErrorHandler;
import android.database.DefaultDatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
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
    /** v1: schedules table. v2: last_outcome column. */
    public static final int VERSION = 2;

    public static final String T_SCHEDULES = "schedules";

    private static volatile WordWakeDatabase instance;
    private static volatile boolean wasReset;

    public static WordWakeDatabase get(Context context) {
        if (instance == null) {
            synchronized (WordWakeDatabase.class) {
                if (instance == null) instance = new WordWakeDatabase(context.getApplicationContext(), NAME);
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
    }

    public static boolean consumeWasReset() {
        boolean r = wasReset;
        wasReset = false;
        return r;
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        db.enableWriteAheadLogging();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        createV1(db);
        migrateV1toV2(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        int v = oldVersion;
        if (v < 2) {
            migrateV1toV2(db);
            v = 2;
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
