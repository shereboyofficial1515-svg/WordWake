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
    private final SharedPreferences p;

    public AppSettings(Context context) {
        p = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
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
