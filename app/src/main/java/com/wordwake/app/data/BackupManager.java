package com.wordwake.app.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.util.Log;

import com.wordwake.app.alarm.AlarmController;
import com.wordwake.app.alarm.AlarmScheduler;
import com.wordwake.app.model.Schedule;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Optional backup to a file the user chooses (for example on a memory card): reminders, checklists,
 * quotes, categories and attachments in one .zip. Nothing is sent anywhere by the app, and the file
 * is not encrypted, which the Settings screen tells the user.
 */
public final class BackupManager {

    private static final String TAG = "WordWakeBackup";
    private static final String JSON_ENTRY = "wordwake.json";
    private static final String FILE_PREFIX = "attachments/";
    private static final String[] TABLES = {WordWakeDatabase.T_SCHEDULES, WordWakeDatabase.T_CHECKLIST,
            WordWakeDatabase.T_ATTACHMENTS, WordWakeDatabase.T_QUOTES, WordWakeDatabase.T_CATEGORIES};

    private BackupManager() {}

    public static boolean export(Context context, Uri uri) {
        try (OutputStream raw = context.getContentResolver().openOutputStream(uri);
             ZipOutputStream zip = new ZipOutputStream(raw)) {
            SQLiteDatabase db = WordWakeDatabase.get(context).getReadableDatabase();
            JSONObject root = new JSONObject();
            root.put("version", WordWakeDatabase.VERSION);
            for (String table : TABLES) root.put(table, dump(db, table));
            zip.putNextEntry(new ZipEntry(JSON_ENTRY));
            zip.write(root.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            byte[] buf = new byte[64 * 1024];
            File[] files = AttachmentStore.dir(context).listFiles();
            if (files != null) {
                for (File f : files) {
                    zip.putNextEntry(new ZipEntry(FILE_PREFIX + f.getName()));
                    try (InputStream in = new java.io.FileInputStream(f)) {
                        int n;
                        while ((n = in.read(buf)) > 0) zip.write(buf, 0, n);
                    }
                    zip.closeEntry();
                }
            }
            return true;
        } catch (IOException | JSONException | SecurityException | NullPointerException e) {
            Log.e(TAG, "Backup failed: " + e.getClass().getSimpleName());
            return false;
        }
    }

    private static JSONArray dump(SQLiteDatabase db, String table) throws JSONException {
        JSONArray rows = new JSONArray();
        try (Cursor c = db.query(table, null, null, null, null, null, null)) {
            while (c.moveToNext()) {
                JSONObject row = new JSONObject();
                for (int i = 0; i < c.getColumnCount(); i++) {
                    if (c.getType(i) == Cursor.FIELD_TYPE_INTEGER) row.put(c.getColumnName(i), c.getLong(i));
                    else if (c.getType(i) != Cursor.FIELD_TYPE_NULL) row.put(c.getColumnName(i), c.getString(i));
                }
                rows.put(row);
            }
        }
        return rows;
    }

    /**
     * Replaces all reminders with the backup's. Nothing is changed unless the whole file is readable.
     * Returns false on any problem, leaving the current data as it was.
     */
    public static boolean restore(Context context, Uri uri) {
        File staging = new File(context.getCacheDir(), "restore");
        deleteDir(staging);
        if (!staging.mkdirs()) return false;
        JSONObject root = null;
        try (InputStream raw = context.getContentResolver().openInputStream(uri);
             ZipInputStream zip = new ZipInputStream(raw)) {
            ZipEntry e;
            byte[] buf = new byte[64 * 1024];
            while ((e = zip.getNextEntry()) != null) {
                String name = e.getName();
                if (JSON_ENTRY.equals(name)) {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    int n;
                    while ((n = zip.read(buf)) > 0) bytes.write(buf, 0, n);
                    root = new JSONObject(bytes.toString("UTF-8"));
                } else if (name.startsWith(FILE_PREFIX)) {
                    // Use only the base name, so a crafted archive cannot write outside the folder.
                    String base = new File(name).getName();
                    if (base.isEmpty()) continue;
                    try (OutputStream out = new FileOutputStream(new File(staging, base))) {
                        int n;
                        while ((n = zip.read(buf)) > 0) out.write(buf, 0, n);
                    }
                }
            }
            if (root == null || root.optInt("version") > WordWakeDatabase.VERSION) return false;
            applyRestore(context, root, staging);
            return true;
        } catch (IOException | JSONException | RuntimeException e) {
            Log.e(TAG, "Restore failed: " + e.getClass().getSimpleName());
            return false;
        } finally {
            deleteDir(staging);
        }
    }

    private static void applyRestore(Context context, JSONObject root, File staging) throws JSONException {
        ScheduleRepository repo = new ScheduleRepository(context);
        for (Schedule s : repo.getAll()) AlarmController.onDisabledOrDeleted(context, s.id);

        SQLiteDatabase db = WordWakeDatabase.get(context).getWritableDatabase();
        db.beginTransaction();
        try {
            for (String table : TABLES) {
                db.delete(table, null, null);
                JSONArray rows = root.optJSONArray(table);
                if (rows == null) continue;
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.getJSONObject(i);
                    ContentValues v = new ContentValues();
                    java.util.Iterator<String> keys = row.keys();
                    while (keys.hasNext()) {
                        String k = keys.next();
                        Object val = row.get(k);
                        if (val instanceof Number) v.put(k, ((Number) val).longValue());
                        else v.put(k, String.valueOf(val));
                    }
                    if (WordWakeDatabase.T_SCHEDULES.equals(table)) {
                        // Alarm state belongs to the old registration; start clean, and don't report
                        // times from before the backup as missed.
                        v.put("occ_start", 0);
                        v.put("last_ring_at", 0);
                        v.put("dismissed_occ", 0);
                        v.put("snooze_until", 0);
                        v.put("ring_active_until", 0);
                        v.put("next_trigger_at", 0);
                    }
                    db.insertOrThrow(table, null, v);
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        File dir = AttachmentStore.dir(context);
        File[] old = dir.listFiles();
        if (old != null) {
            for (File f : old) {
                if (!f.delete()) Log.w(TAG, "Could not remove old attachment");
            }
        }
        File[] incoming = staging.listFiles();
        if (incoming != null) {
            for (File f : incoming) {
                if (!f.renameTo(new File(dir, f.getName()))) Log.w(TAG, "Could not restore attachment");
            }
        }
        AlarmScheduler.reconcileAll(context, "restore");
    }

    private static void deleteDir(File d) {
        File[] files = d.listFiles();
        if (files != null) {
            for (File f : files) {
                if (!f.delete()) Log.w(TAG, "Could not remove temporary file");
            }
        }
        if (d.exists() && !d.delete()) Log.w(TAG, "Could not remove temporary folder");
    }
}
