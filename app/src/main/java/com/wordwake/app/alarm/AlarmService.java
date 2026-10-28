package com.wordwake.app.alarm;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.wordwake.app.R;
import com.wordwake.app.WordWakeApp;
import com.wordwake.app.data.AppExecutors;
import com.wordwake.app.data.ScheduleRepository;
import com.wordwake.app.engine.ScheduleEngine;
import com.wordwake.app.model.Schedule;
import com.wordwake.app.sound.AlarmPlayer;
import com.wordwake.app.ui.AlarmActivity;

/**
 * Foreground service that owns the audible part of an alarm: sound, vibration, wake lock and the
 * session deadline timers. Only one session plays at a time; a new ring replaces the old one.
 */
public class AlarmService extends Service {

    private static final String TAG = "WordWakeService";
    static final String ACTION_RING = "com.wordwake.app.action.RING";
    private static final String X_ID = "id";
    private static final String X_OCC_START = "occStart";
    private static final String X_OCC_END = "occEnd";
    private static final String X_RING_AT = "ringAt";
    private static final String X_SESSION_END = "sessionEnd";
    private static final String X_SOUND_END = "soundEnd";
    private static final String X_KIND = "kind";
    private static final String X_TEST = "test";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private AlarmPlayer player;
    private Vibrator vibrator;
    private PowerManager.WakeLock wakeLock;
    private ActiveAlarm.Session session;

    static boolean start(Context context, long id, long occStart, long occEnd, long ringAt,
                         long sessionEnd, long soundEnd, int kind, boolean test) {
        Intent i = new Intent(context, AlarmService.class)
                .setAction(ACTION_RING)
                .putExtra(X_ID, id)
                .putExtra(X_OCC_START, occStart)
                .putExtra(X_OCC_END, occEnd)
                .putExtra(X_RING_AT, ringAt)
                .putExtra(X_SESSION_END, sessionEnd)
                .putExtra(X_SOUND_END, soundEnd)
                .putExtra(X_KIND, kind)
                .putExtra(X_TEST, test);
        if (!test) ActiveAlarm.markStarting(id);
        try {
            ContextCompat.startForegroundService(context, i);
            return true;
        } catch (IllegalStateException | SecurityException e) {
            ActiveAlarm.clearStarting();
            // ForegroundServiceStartNotAllowedException (Android 12+) extends IllegalStateException.
            Log.w(TAG, "Cannot start alarm service: " + e.getClass().getSimpleName());
            return false;
        }
    }

    static void stop(Context context) {
        context.stopService(new Intent(context, AlarmService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        player = new AlarmPlayer(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vm = getSystemService(VibratorManager.class);
            vibrator = vm != null ? vm.getDefaultVibrator() : null;
        } else {
            vibrator = getSystemService(Vibrator.class);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !ACTION_RING.equals(intent.getAction())) {
            startForegroundSafely(placeholder());
            stopSelf();
            return START_NOT_STICKY;
        }
        long id = intent.getLongExtra(X_ID, 0);
        boolean test = intent.getBooleanExtra(X_TEST, false);
        long now = System.currentTimeMillis();
        long sessionEnd = intent.getLongExtra(X_SESSION_END, 0);
        Schedule s = new ScheduleRepository(this).get(id);

        boolean stale = s == null || sessionEnd <= now || (!test && s.ringActiveUntil <= now);
        if (stale) {
            ActiveAlarm.clearStarting();
            // Redelivered after process death but the user already handled it, or it expired.
            startForegroundSafely(placeholder());
            stopSelf();
            return START_NOT_STICKY;
        }

        ActiveAlarm.Session previous = session;
        ActiveAlarm.Session next = new ActiveAlarm.Session(id,
                intent.getLongExtra(X_OCC_START, now), intent.getLongExtra(X_OCC_END, sessionEnd),
                intent.getLongExtra(X_RING_AT, now), sessionEnd,
                intent.getLongExtra(X_SOUND_END, sessionEnd), intent.getIntExtra(X_KIND, 0), test);
        boolean canSnooze = !test && ScheduleEngine.canSnooze(s, now, next.occEnd);

        if (!startForegroundSafely(Notifications.ringing(this, s, next, canSnooze))) {
            ActiveAlarm.clearStarting();
            Notifications.postFallback(this, s, next.occStart, next.occEnd);
            stopSelf();
            return START_NOT_STICKY;
        }

        // Prevent overlapping sessions: stop whatever was playing before.
        handler.removeCallbacksAndMessages(null);
        player.stop();
        stopVibration();
        if (previous != null && previous.scheduleId != id && !previous.test) {
            final long prevId = previous.scheduleId;
            AppExecutors.io(() -> AlarmController.onSuperseded(getApplicationContext(), prevId));
        }

        session = next;
        acquireWakeLock(sessionEnd - now + 30_000L);
        int result = player.start(s.soundUri, s.loopSound, s.volumePercent,
                () -> handler.post(this::onSoundFinished));
        next.fallbackSound = result == AlarmPlayer.RESULT_FALLBACK;
        next.soundFailed = result == AlarmPlayer.RESULT_FAILED;
        if (s.vibrate) startVibration();
        ActiveAlarm.set(next);

        long soundEnd = next.soundEnd;
        if (soundEnd < sessionEnd) handler.postDelayed(this::onSoundFinished, Math.max(0, soundEnd - now));
        handler.postDelayed(this::onSessionFinished, Math.max(0, sessionEnd - now));

        if (WordWakeApp.isInForeground()) {
            // The app is on screen, so launching the alarm screen directly is allowed.
            Intent screen = new Intent(this, AlarmActivity.class)
                    .putExtra(AlarmActivity.EXTRA_SCHEDULE_ID, id)
                    .putExtra(AlarmActivity.EXTRA_OCC_START, next.occStart)
                    .putExtra(AlarmActivity.EXTRA_OCC_END, next.occEnd)
                    .putExtra(AlarmActivity.EXTRA_TEST, test)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(screen);
            } catch (RuntimeException e) {
                Log.w(TAG, "Could not open alarm screen directly");
            }
        }
        return START_REDELIVER_INTENT;
    }

    private void onSoundFinished() {
        ActiveAlarm.Session s = session;
        if (s == null) return;
        if (s.test) {
            stopSelf();
            return;
        }
        final long id = s.scheduleId;
        AppExecutors.io(() -> AlarmController.silence(getApplicationContext(), id));
    }

    private void onSessionFinished() {
        ActiveAlarm.Session s = session;
        if (s == null) return;
        if (s.test) {
            stopSelf();
            return;
        }
        final long id = s.scheduleId;
        AppExecutors.io(() -> AlarmController.endSession(getApplicationContext(), id));
    }

    private boolean startForegroundSafely(Notification n) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(Notifications.ID_RINGING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(Notifications.ID_RINGING, n);
            }
            return true;
        } catch (RuntimeException e) {
            Log.e(TAG, "startForeground failed: " + e.getClass().getSimpleName());
            return false;
        }
    }

    private Notification placeholder() {
        return new NotificationCompat.Builder(this, Notifications.CH_MESSAGE)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.app_name))
                .setSilent(true)
                .build();
    }

    private void startVibration() {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        VibrationEffect effect = VibrationEffect.createWaveform(new long[]{0, 900, 700}, 1);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM));
        } else {
            vibrator.vibrate(effect, new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
        }
    }

    private void stopVibration() {
        if (vibrator != null) vibrator.cancel();
    }

    private void acquireWakeLock(long timeoutMs) {
        releaseWakeLock();
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm == null) return;
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WordWake:ring");
        wakeLock.setReferenceCounted(false);
        // Bounded: released automatically even if every other cleanup path failed.
        wakeLock.acquire(Math.max(10_000L, Math.min(timeoutMs, ScheduleEngine.MAX_WINDOW_MINUTES * 60_000L + 60_000L)));
    }

    private void releaseWakeLock() {
        if (wakeLock != null) {
            if (wakeLock.isHeld()) wakeLock.release();
            wakeLock = null;
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (player != null) player.stop();
        stopVibration();
        releaseWakeLock();
        ActiveAlarm.clear(session);
        session = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
