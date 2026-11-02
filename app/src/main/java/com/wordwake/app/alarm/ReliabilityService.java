package com.wordwake.app.alarm;

import android.app.Notification;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.wordwake.app.data.AppExecutors;
import com.wordwake.app.data.AppSettings;
import com.wordwake.app.data.ScheduleRepository;
import com.wordwake.app.model.Schedule;

/**
 * Optional "reliable delivery" mode for phones whose power managers stop background apps.
 * Tested on a TECNO KM6 (HiOS, Android 15): it did NOT stop "Hiber" freezing the app and holding
 * its alarms, so it is off by default. It may still help on other phones.
 *
 * It is an idle foreground service: a visible, silent notification, no timers, no polling and no
 * wake lock, so it uses almost no battery. AlarmManager stays the only scheduling mechanism; this
 * service only keeps the process from being frozen. It runs only while an alert is scheduled.
 */
public class ReliabilityService extends Service {

    private static final String TAG = "WordWakeReliable";
    private static volatile boolean running;

    private final BroadcastReceiver unlockReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // The phone was just unlocked: catch up on anything the system held back.
            final Context app = context.getApplicationContext();
            AppExecutors.io(() -> AlarmScheduler.reconcileAll(app, "unlock"));
        }
    };

    public static boolean isEnabled(Context context) {
        return new AppSettings(context).reliableDelivery(false);
    }

    public static boolean isRunning() {
        return running;
    }

    /** Starts or stops the service to match the setting and whether anything is scheduled. */
    public static void sync(Context context) {
        boolean wanted = false;
        if (isEnabled(context)) {
            for (Schedule s : new ScheduleRepository(context).getAll()) {
                if (s.enabled && s.nextTriggerAt > 0) {
                    wanted = true;
                    break;
                }
            }
        }
        Intent i = new Intent(context, ReliabilityService.class);
        if (wanted && !running) {
            try {
                ContextCompat.startForegroundService(context, i);
            } catch (IllegalStateException | SecurityException e) {
                // Not allowed from the background right now; the next app start or boot retries.
                Log.w(TAG, "Could not start: " + e.getClass().getSimpleName());
            }
        } else if (!wanted && running) {
            context.stopService(i);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ContextCompat.registerReceiver(this, unlockReceiver, new IntentFilter(Intent.ACTION_USER_PRESENT),
                ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = Notifications.reliability(this);
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(Notifications.ID_RELIABILITY, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(Notifications.ID_RELIABILITY, n);
            }
            running = true;
        } catch (RuntimeException e) {
            Log.w(TAG, "startForeground failed: " + e.getClass().getSimpleName());
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        unregisterReceiver(unlockReceiver);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
