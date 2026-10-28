package com.wordwake.app.ui;

import android.Manifest;
import android.app.ActivityManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.wordwake.app.alarm.AlarmScheduler;
import com.wordwake.app.alarm.Notifications;
import com.wordwake.app.sound.AlarmPlayer;

/** Reads the current state of every permission/setting that affects alarm delivery. */
public final class PermissionStatus {

    private PermissionStatus() {}

    public static boolean notificationsAllowed(Context c) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        if (!NotificationManagerCompat.from(c).areNotificationsEnabled()) return false;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel ch = nm == null ? null : nm.getNotificationChannel(Notifications.CH_ALARM);
        return ch == null || ch.getImportance() >= NotificationManager.IMPORTANCE_HIGH;
    }

    /** True only if the alarm channel was turned down by the user (permission itself granted). */
    public static boolean alarmChannelLowered(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel ch = nm == null ? null : nm.getNotificationChannel(Notifications.CH_ALARM);
        return ch != null && ch.getImportance() < NotificationManager.IMPORTANCE_HIGH;
    }

    public static boolean exactAlarmsAllowed(Context c) {
        return AlarmScheduler.canScheduleExact(c);
    }

    /** Full-screen intents are restricted from Android 14; earlier versions grant them at install. */
    public static boolean fullScreenAllowed(Context c) {
        if (Build.VERSION.SDK_INT < 34) return true;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        return nm != null && nm.canUseFullScreenIntent();
    }

    public static boolean ignoringBatteryOptimizations(Context c) {
        PowerManager pm = c.getSystemService(PowerManager.class);
        return pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
    }

    /**
     * Manufacturers whose own power managers are known to freeze apps and hold back their alarms
     * (observed on a TECNO KM6: HiOS "Hiber" proxied an exact alarm-clock alarm indefinitely).
     */
    public static boolean aggressiveOem() {
        String m = (Build.MANUFACTURER + " " + Build.BRAND).toLowerCase(java.util.Locale.ROOT);
        for (String oem : new String[]{"tecno", "infinix", "itel", "transsion", "xiaomi", "redmi", "poco",
                "huawei", "honor", "oppo", "realme", "vivo", "oneplus", "meizu", "asus"}) {
            if (m.contains(oem)) return true;
        }
        return false;
    }

    /** True when battery optimisation is likely to block alarms on this phone. */
    public static boolean batteryBlocksAlarms(Context c) {
        return aggressiveOem() && !ignoringBatteryOptimizations(c);
    }

    public static boolean powerSaveOn(Context c) {
        PowerManager pm = c.getSystemService(PowerManager.class);
        return pm != null && pm.isPowerSaveMode();
    }

    /** "Restricted" background usage (Android 9+) blocks alarms entirely on many devices. */
    public static boolean backgroundRestricted(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false;
        ActivityManager am = c.getSystemService(ActivityManager.class);
        return am != null && am.isBackgroundRestricted();
    }

    public static boolean alarmVolumeLow(Context c) {
        return AlarmPlayer.alarmStreamLevel(c) < 0.3f;
    }

    /** Number of issues that can stop an alarm from being seen or heard. */
    public static int criticalIssues(Context c) {
        int n = 0;
        if (!notificationsAllowed(c)) n++;
        if (!exactAlarmsAllowed(c)) n++;
        if (!fullScreenAllowed(c)) n++;
        if (backgroundRestricted(c)) n++;
        if (alarmVolumeLow(c)) n++;
        if (batteryBlocksAlarms(c)) n++;
        return n;
    }

    // ---- intents to the relevant system settings pages ----

    public static Intent appNotificationSettings(Context c) {
        return new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, c.getPackageName());
    }

    public static Intent exactAlarmSettings(Context c) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + c.getPackageName()));
        }
        return appDetails(c);
    }

    public static Intent fullScreenSettings(Context c) {
        if (Build.VERSION.SDK_INT >= 34) {
            return new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + c.getPackageName()));
        }
        return appNotificationSettings(c);
    }

    public static Intent batterySettings() {
        return new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
    }

    /** System dialog asking the user to let this one app run unrestricted (one tap to accept). */
    public static Intent requestBatteryExemption(Context c) {
        return new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + c.getPackageName()));
    }

    public static Intent soundSettings() {
        return new Intent(Settings.ACTION_SOUND_SETTINGS);
    }

    public static Intent appDetails(Context c) {
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + c.getPackageName()));
    }

    /** Starts a settings intent, falling back to the app details page if the device lacks it. */
    public static void open(Context c, Intent intent) {
        try {
            c.startActivity(intent);
        } catch (RuntimeException e) {
            try {
                c.startActivity(appDetails(c));
            } catch (RuntimeException ignored) {
            }
        }
    }
}
