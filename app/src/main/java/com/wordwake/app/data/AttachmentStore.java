package com.wordwake.app.data;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.UserManager;
import android.provider.OpenableColumns;
import android.util.Log;

import androidx.core.content.FileProvider;

import com.wordwake.app.model.Attachment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

/**
 * Copies picked pictures and documents into the app's private storage, so a reminder keeps its
 * attachment even if the original file is moved or deleted. Nothing is ever uploaded.
 */
public final class AttachmentStore {

    public static final long MAX_BYTES = 50L * 1024 * 1024;
    private static final String TAG = "WordWakeFiles";
    private static final String DIR = "attachments";

    private AttachmentStore() {}

    public static File dir(Context context) {
        File d = new File(context.getFilesDir(), DIR);
        if (!d.exists() && !d.mkdirs()) Log.w(TAG, "Could not create attachments directory");
        return d;
    }

    public static File file(Context context, Attachment a) {
        return new File(dir(context), a.fileName);
    }

    /** Files are in credential-encrypted storage, so they cannot be read before the first unlock. */
    public static boolean available(Context context) {
        UserManager um = context.getSystemService(UserManager.class);
        return um == null || um.isUserUnlocked();
    }

    /** Copies a picked file in. Returns null if it cannot be read or is too large. */
    public static Attachment importUri(Context context, Uri uri, long scheduleId) {
        String mime = context.getContentResolver().getType(uri);
        String name = displayName(context, uri);
        File target = new File(dir(context), UUID.randomUUID().toString());
        long total = 0;
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(target)) {
            if (in == null) return null;
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_BYTES) throw new IOException("too large");
                out.write(buf, 0, n);
            }
        } catch (IOException | SecurityException e) {
            Log.w(TAG, "Could not import attachment: " + e.getClass().getSimpleName());
            if (!target.delete()) Log.w(TAG, "Could not remove partial file");
            return null;
        }
        Attachment a = new Attachment();
        a.scheduleId = scheduleId;
        a.fileName = target.getName();
        a.displayName = name == null ? "attachment" : name;
        a.mime = mime == null ? "application/octet-stream" : mime;
        return a;
    }

    /** Copies an attachment for a duplicated reminder. */
    public static Attachment duplicate(Context context, Attachment a, long newScheduleId) {
        File target = new File(dir(context), UUID.randomUUID().toString());
        try (InputStream in = new java.io.FileInputStream(file(context, a));
             OutputStream out = new FileOutputStream(target)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (IOException e) {
            if (!target.delete()) Log.w(TAG, "Could not remove partial file");
            return null;
        }
        Attachment c = new Attachment();
        c.scheduleId = newScheduleId;
        c.fileName = target.getName();
        c.displayName = a.displayName;
        c.mime = a.mime;
        return c;
    }

    public static void deleteFile(Context context, Attachment a) {
        if (!file(context, a).delete()) Log.w(TAG, "Attachment file was already gone");
    }

    public static Uri shareUri(Context context, File f) {
        return FileProvider.getUriForFile(context, context.getPackageName() + ".files", f);
    }

    /** An intent that opens the attachment in whichever app the user prefers. */
    public static Intent viewIntent(Context context, Attachment a) {
        return new Intent(Intent.ACTION_VIEW)
                .setDataAndType(shareUri(context, file(context, a)), a.mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** Decodes a downsampled bitmap, or null when the file is missing or not an image. */
    public static Bitmap loadBitmap(Context context, Attachment a, int maxSizePx) {
        if (!available(context) || !a.isImage()) return null;
        String path = file(context, a).getPath();
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            if (o.outWidth <= 0) return null;
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / sample > maxSizePx) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            return BitmapFactory.decodeFile(path, o);
        } catch (OutOfMemoryError e) {
            Log.w(TAG, "Image too large to display");
            return null;
        }
    }

    public static Bitmap firstImage(Context context, long scheduleId, int maxSizePx) {
        if (!available(context)) return null;
        for (Attachment a : new ContentRepository(context).attachments(scheduleId)) {
            Bitmap b = loadBitmap(context, a, maxSizePx);
            if (b != null) return b;
        }
        return null;
    }

    private static String displayName(Context context, Uri uri) {
        try (Cursor c = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not read file name");
        }
        return null;
    }
}
