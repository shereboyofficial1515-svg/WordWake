package com.wordwake.app;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.app.ApplicationExitInfo;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import com.wordwake.app.alarm.AlarmScheduler;
import com.wordwake.app.alarm.Notifications;
import com.wordwake.app.data.AppExecutors;
import com.wordwake.app.data.AppSettings;
import com.wordwake.app.data.DiagLog;

import java.util.List;

public class WordWakeApp extends Application {

    private static volatile int startedActivities;

    public static boolean isInForeground() {
        return startedActivities > 0;
    }

    /** Records how the previous process ended, which is what reveals a force stop or OEM kill. */
    private void recordLastExit() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        ActivityManager am = getSystemService(ActivityManager.class);
        if (am == null) return;
        try {
            List<ApplicationExitInfo> exits = am.getHistoricalProcessExitReasons(getPackageName(), 0, 1);
            if (exits.isEmpty()) return;
            ApplicationExitInfo e = exits.get(0);
            AppSettings settings = new AppSettings(this);
            if (settings.lastExitLogged() == e.getTimestamp()) return;
            settings.setLastExitLogged(e.getTimestamp());
            DiagLog.log(this, DiagLog.PROCESS_EXIT, 0, "reason=" + e.getReason() + " subreason=" + e.getStatus() + " at=" + e.getTimestamp());
        } catch (RuntimeException e) {
            Log.w("WordWakeApp", "Could not read exit reasons");
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Notifications.createChannels(this);
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityStarted(Activity a) { startedActivities++; }
            @Override public void onActivityStopped(Activity a) { startedActivities = Math.max(0, startedActivities - 1); }
            @Override public void onActivityCreated(Activity a, Bundle b) {}
            @Override public void onActivityResumed(Activity a) {}
            @Override public void onActivityPaused(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            @Override public void onActivityDestroyed(Activity a) {}
        });
        // Recovery after process death or a missed broadcast: make AlarmManager match the database.
        AppExecutors.io(() -> {
            recordLastExit();
            AlarmScheduler.reconcileAll(this, "process start");
        });
    }
}
