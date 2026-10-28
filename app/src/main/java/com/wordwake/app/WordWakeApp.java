package com.wordwake.app;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import com.wordwake.app.alarm.AlarmScheduler;
import com.wordwake.app.alarm.Notifications;
import com.wordwake.app.data.AppExecutors;

public class WordWakeApp extends Application {

    private static volatile int startedActivities;

    public static boolean isInForeground() {
        return startedActivities > 0;
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
        AppExecutors.io(() -> AlarmScheduler.reconcileAll(this, "process start"));
    }
}
