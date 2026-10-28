package com.wordwake.app.sound;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.media.RingtoneManager;
import android.net.Uri;

import java.util.ArrayList;
import java.util.List;

/** Lists available alarm sounds and checks whether a selected sound can still be opened. */
public final class SoundCatalog {

    public static final String PACKAGE = "com.wordwake.app";
    public static final String DEFAULT_BUILT_IN_URI = rawUri("sunrise_chime");
    public static final String DEFAULT_BUILT_IN_NAME = "Sunrise Chime";

    public static final int SOURCE_BUILT_IN = 0;
    public static final int SOURCE_SYSTEM_ALARM = 1;
    public static final int SOURCE_SYSTEM_RINGTONE = 2;
    public static final int SOURCE_FILE = 3;

    public static final class SoundOption {
        public final String uri;
        public final String name;
        public final int source;

        public SoundOption(String uri, String name, int source) {
            this.uri = uri;
            this.name = name;
            this.source = source;
        }
    }

    private SoundCatalog() {}

    public static String rawUri(String name) {
        return ContentResolver.SCHEME_ANDROID_RESOURCE + "://" + PACKAGE + "/raw/" + name;
    }

    public static List<SoundOption> builtIns() {
        List<SoundOption> list = new ArrayList<>();
        list.add(new SoundOption(rawUri("sunrise_chime"), "Sunrise Chime", SOURCE_BUILT_IN));
        list.add(new SoundOption(rawUri("classic_beep"), "Classic Beep", SOURCE_BUILT_IN));
        list.add(new SoundOption(rawUri("soft_bells"), "Soft Bells", SOURCE_BUILT_IN));
        list.add(new SoundOption(rawUri("rising_pulse"), "Rising Pulse (loud)", SOURCE_BUILT_IN));
        return list;
    }

    /** System sounds of the given RingtoneManager type (TYPE_ALARM or TYPE_RINGTONE). */
    public static List<SoundOption> systemSounds(Context context, int type) {
        List<SoundOption> list = new ArrayList<>();
        int source = type == RingtoneManager.TYPE_ALARM ? SOURCE_SYSTEM_ALARM : SOURCE_SYSTEM_RINGTONE;
        try {
            RingtoneManager rm = new RingtoneManager(context);
            rm.setType(type);
            Cursor c = rm.getCursor();
            while (c.moveToNext()) {
                String title = c.getString(RingtoneManager.TITLE_COLUMN_INDEX);
                Uri uri = rm.getRingtoneUri(c.getPosition());
                if (uri != null) list.add(new SoundOption(uri.toString(), title, source));
            }
        } catch (RuntimeException e) {
            // Some devices restrict ringtone queries; the built-in sounds remain available.
        }
        return list;
    }

    /** Returns true when the sound can currently be opened for reading. */
    public static boolean isAvailable(Context context, String uri) {
        if (uri == null || uri.isEmpty()) return false;
        try (AssetFileDescriptor fd = context.getContentResolver().openAssetFileDescriptor(Uri.parse(uri), "r")) {
            return fd != null;
        } catch (Exception e) {
            return false;
        }
    }

    public static Uri systemDefaultAlarm(Context context) {
        Uri u = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM);
        if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        return u;
    }
}
