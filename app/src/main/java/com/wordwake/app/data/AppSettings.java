package com.wordwake.app.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.wordwake.app.sound.SoundCatalog;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Lightweight preferences stored locally in SharedPreferences. */
public final class AppSettings {

    public static final int TEXT_NORMAL = 0;
    public static final int TEXT_LARGE = 1;
    public static final int TEXT_HUGE = 2;

    private static final String FILE = "wordwake_settings";
    private static volatile boolean moved;
    private final SharedPreferences p;

    public AppSettings(Context context) {
        Context app = context.getApplicationContext();
        // Device-protected, like the database, so alarm settings are readable before first unlock.
        Context dp = app.createDeviceProtectedStorageContext();
        // The old location is only readable once the phone has been unlocked.
        android.os.UserManager um = app.getSystemService(android.os.UserManager.class);
        if (!moved && (um == null || um.isUserUnlocked())) {
            moved = dp.moveSharedPreferencesFrom(app, FILE);
        }
        p = dp.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public boolean onboardingDone() { return p.getBoolean("onboarding_done", false); }
    public void setOnboardingDone(boolean v) { p.edit().putBoolean("onboarding_done", v).apply(); }

    public boolean presetOffered() { return p.getBoolean("preset_offered", false); }
    public void setPresetOffered(boolean v) { p.edit().putBoolean("preset_offered", v).apply(); }

    public String defaultSoundUri() { return p.getString("default_sound_uri", SoundCatalog.DEFAULT_BUILT_IN_URI); }
    public String defaultSoundName() { return p.getString("default_sound_name", SoundCatalog.DEFAULT_BUILT_IN_NAME); }
    public void setDefaultSound(String uri, String name) {
        p.edit().putString("default_sound_uri", uri).putString("default_sound_name", name).apply();
    }

    public int defaultSnoozeMinutes() { return p.getInt("default_snooze", 10); }
    public void setDefaultSnoozeMinutes(int v) { p.edit().putInt("default_snooze", v).apply(); }

    public int defaultWindowMinutes() { return p.getInt("default_window", 30); }
    public void setDefaultWindowMinutes(int v) { p.edit().putInt("default_window", v).apply(); }

    public boolean defaultVibrate() { return p.getBoolean("default_vibrate", true); }
    public void setDefaultVibrate(boolean v) { p.edit().putBoolean("default_vibrate", v).apply(); }

    public boolean highContrast() { return p.getBoolean("high_contrast", false); }
    public void setHighContrast(boolean v) { p.edit().putBoolean("high_contrast", v).apply(); }

    public int alarmTextSize() { return p.getInt("alarm_text_size", TEXT_LARGE); }
    public void setAlarmTextSize(int v) { p.edit().putInt("alarm_text_size", v).apply(); }

    public boolean keepScreenOn() { return p.getBoolean("keep_screen_on", true); }
    public void setKeepScreenOn(boolean v) { p.edit().putBoolean("keep_screen_on", v).apply(); }

    /** Reliable delivery: on by default only where the phone's power manager withholds alarms. */
    public boolean reliableDelivery(boolean defaultValue) { return p.getBoolean("reliable_delivery", defaultValue); }
    public void setReliableDelivery(boolean v) { p.edit().putBoolean("reliable_delivery", v).apply(); }

    /** Sound chosen for a category; returns {uri, name} or null. */
    public String[] categorySound(String key) {
        String uri = p.getString("catsound_uri_" + key, null);
        return uri == null ? null : new String[]{uri, p.getString("catsound_name_" + key, "")};
    }
    public void setCategorySound(String key, String uri, String name) {
        p.edit().putString("catsound_uri_" + key, uri).putString("catsound_name_" + key, name).apply();
    }

    public long lastExitLogged() { return p.getLong("last_exit", 0); }
    public void setLastExitLogged(long v) { p.edit().putLong("last_exit", v).apply(); }

    public boolean quotesSeeded() { return p.getBoolean("quotes_seeded", false); }
    public void setQuotesSeeded() { p.edit().putBoolean("quotes_seeded", true).apply(); }

    // Results of the last RUN ALARM SYSTEM TEST, kept so a failed delivery is still reported later.
    public long testExpectedAt() { return p.getLong("test_expected", 0); }
    public long testFiredAt() { return p.getLong("test_fired", 0); }
    public long testServiceAt() { return p.getLong("test_service", 0); }
    public boolean testNotificationPosted() { return p.getBoolean("test_notif", false); }
    public int testAudio() { return p.getInt("test_audio", 0); }
    public boolean testScreenWasOff() { return p.getBoolean("test_screen_off", false); }
    public void startTest(long expectedAt) {
        p.edit().putLong("test_expected", expectedAt).putLong("test_fired", 0).putLong("test_service", 0)
                .putBoolean("test_notif", false).putInt("test_audio", 0).putBoolean("test_screen_off", false).apply();
    }
    public void recordTestFired(long at, boolean screenOff) {
        p.edit().putLong("test_fired", at).putBoolean("test_screen_off", screenOff).apply();
    }
    public void recordTestService(long at, boolean notificationPosted) {
        p.edit().putLong("test_service", at).putBoolean("test_notif", notificationPosted).apply();
    }
    public void recordTestAudio(int result) { p.edit().putInt("test_audio", result).apply(); }

    /** Whether we already asked for POST_NOTIFICATIONS once (so we never nag). */
    public boolean notificationPermissionAsked() { return p.getBoolean("notif_asked", false); }
    public void setNotificationPermissionAsked() { p.edit().putBoolean("notif_asked", true).apply(); }

    /** User-picked audio files, stored as "uri\nname" entries, most recent first. */
    public List<String[]> pickedFiles() {
        List<String[]> out = new ArrayList<>();
        String raw = p.getString("picked_files", "");
        if (raw.isEmpty()) return out;
        for (String entry : raw.split("\u001F")) {
            int nl = entry.indexOf('\n');
            if (nl > 0) out.add(new String[]{entry.substring(0, nl), entry.substring(nl + 1)});
        }
        return out;
    }

    public void addPickedFile(String uri, String name) {
        Set<String> entries = new LinkedHashSet<>();
        entries.add(uri + "\n" + name);
        for (String[] e : pickedFiles()) {
            if (!e[0].equals(uri)) entries.add(e[0] + "\n" + e[1]);
            if (entries.size() >= 20) break;
        }
        p.edit().putString("picked_files", String.join("\u001F", entries)).apply();
    }

    public void removePickedFile(String uri) {
        List<String> keep = new ArrayList<>();
        for (String[] e : pickedFiles()) if (!e[0].equals(uri)) keep.add(e[0] + "\n" + e[1]);
        p.edit().putString("picked_files", String.join("\u001F", keep)).apply();
    }
}
