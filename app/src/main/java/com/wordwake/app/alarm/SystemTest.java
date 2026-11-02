package com.wordwake.app.alarm;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.PowerManager;

import com.wordwake.app.R;
import com.wordwake.app.data.AppSettings;
import com.wordwake.app.data.DiagLog;
import com.wordwake.app.engine.ScheduleEngine;
import com.wordwake.app.model.Schedule;
import com.wordwake.app.sound.SoundCatalog;
import com.wordwake.app.ui.MainActivity;

/**
 * RUN ALARM SYSTEM TEST: registers a short real alarm with AlarmManager (the same API, receiver,
 * foreground service and audio player real alerts use) and records what actually happened.
 * Nothing is simulated, and an alarm that never arrives is reported as not delivered.
 */
public final class SystemTest {

    public static final long SCHEDULE_ID = -1;
    public static final int LEAD_SECONDS = 30;
    private static final long RING_MS = 15_000L;

    public static final int AUDIO_UNKNOWN = 0;
    /** The player reported it was playing and its position advanced. */
    public static final int AUDIO_ENGINE_RUNNING = 1;
    public static final int AUDIO_NOT_RUNNING = 2;

    public static final long ON_TIME_MS = 5_000L;

    private SystemTest() {}

    public static Schedule schedule(Context context) {
        Schedule s = new Schedule();
        s.id = SCHEDULE_ID;
        s.title = context.getString(R.string.diag_test_title);
        s.body = context.getString(R.string.diag_test_body);
        s.soundUri = SoundCatalog.DEFAULT_BUILT_IN_URI;
        s.soundName = SoundCatalog.DEFAULT_BUILT_IN_NAME;
        s.snoozeMinutes = 0;
        s.vibrate = false;
        s.autoDismiss = true;
        return s;
    }

    private static PendingIntent pendingIntent(Context context) {
        Intent i = new Intent(context, AlarmReceiver.class)
                .setAction(AlarmReceiver.ACTION_TEST)
                .setData(Uri.parse("wordwake://test"));
        return PendingIntent.getBroadcast(context, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Returns the expected time, or 0 if the alarm could not be registered. */
    public static long start(Context context) {
        long at = System.currentTimeMillis() + LEAD_SECONDS * 1000L;
        new AppSettings(context).startTest(at);
        AlarmManager am = context.getSystemService(AlarmManager.class);
        PendingIntent show = PendingIntent.getActivity(context, 0, new Intent(context, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            if (AlarmScheduler.canScheduleExact(context)) {
                am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, show), pendingIntent(context));
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent(context));
            }
        } catch (RuntimeException e) {
            DiagLog.log(context, DiagLog.SCHEDULE_FAILURE, SCHEDULE_ID, e.getClass().getSimpleName());
            return 0;
        }
        DiagLog.log(context, DiagLog.TEST_STARTED, SCHEDULE_ID, "exact=" + AlarmScheduler.canScheduleExact(context));
        return at;
    }

    static void onFired(Context context) {
        long now = System.currentTimeMillis();
        AppSettings settings = new AppSettings(context);
        PowerManager pm = context.getSystemService(PowerManager.class);
        boolean screenOff = pm != null && !pm.isInteractive();
        settings.recordTestFired(now, screenOff);
        DiagLog.log(context, DiagLog.TEST_FIRED, SCHEDULE_ID,
                "delayMs=" + (now - settings.testExpectedAt()) + " screenOff=" + screenOff);
        long end = now + RING_MS;
        boolean started = AlarmService.start(context, SCHEDULE_ID, now, end, now, end, end, ScheduleEngine.KIND_START, true);
        if (!started) {
            DiagLog.log(context, DiagLog.SCHEDULE_FAILURE, SCHEDULE_ID, "foreground service start blocked");
        }
    }
}
