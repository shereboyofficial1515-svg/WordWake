package com.wordwake.app.alarm;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.wordwake.app.R;
import com.wordwake.app.model.Schedule;
import com.wordwake.app.sound.SoundCatalog;
import com.wordwake.app.ui.AlarmActivity;
import com.wordwake.app.ui.MainActivity;
import com.wordwake.app.ui.Formats;

/** Notification channels and builders. */
public final class Notifications {

    /** Ringing alarm. Silent channel: the foreground service plays the chosen sound itself. */
    public static final String CH_ALARM = "alarm_ringing";
    /** Used only if the foreground service cannot start: the channel itself plays an alarm sound. */
    public static final String CH_ALARM_FALLBACK = "alarm_fallback_sound";
    /** Quiet copy of the message after the sound stops, and snooze status. */
    public static final String CH_MESSAGE = "alarm_message";
    /** Missed alerts and warnings. */
    public static final String CH_STATUS = "status";

    public static final int ID_RINGING = 1000;
    private static final int BASE_MESSAGE = 200_000;
    private static final int BASE_FALLBACK = 400_000;
    private static final int BASE_STATUS = 600_000;

    private Notifications() {}

    public static void createChannels(Context context) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return;

        NotificationChannel alarm = new NotificationChannel(CH_ALARM,
                context.getString(R.string.channel_alarm), NotificationManager.IMPORTANCE_HIGH);
        alarm.setDescription(context.getString(R.string.channel_alarm_desc));
        alarm.setSound(null, null);
        alarm.enableVibration(false);
        alarm.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(alarm);

        NotificationChannel fallback = new NotificationChannel(CH_ALARM_FALLBACK,
                context.getString(R.string.channel_fallback), NotificationManager.IMPORTANCE_HIGH);
        fallback.setDescription(context.getString(R.string.channel_fallback_desc));
        fallback.setSound(SoundCatalog.systemDefaultAlarm(context), new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build());
        fallback.enableVibration(true);
        fallback.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(fallback);

        NotificationChannel message = new NotificationChannel(CH_MESSAGE,
                context.getString(R.string.channel_message), NotificationManager.IMPORTANCE_LOW);
        message.setDescription(context.getString(R.string.channel_message_desc));
        nm.createNotificationChannel(message);

        NotificationChannel status = new NotificationChannel(CH_STATUS,
                context.getString(R.string.channel_status), NotificationManager.IMPORTANCE_DEFAULT);
        status.setDescription(context.getString(R.string.channel_status_desc));
        nm.createNotificationChannel(status);
    }

    public static int messageId(long scheduleId) {
        return BASE_MESSAGE + (int) (scheduleId % 100_000);
    }

    public static int fallbackId(long scheduleId) {
        return BASE_FALLBACK + (int) (scheduleId % 100_000);
    }

    public static int statusId(long scheduleId) {
        return BASE_STATUS + (int) (scheduleId % 100_000);
    }

    static PendingIntent alarmScreen(Context context, long scheduleId, long occStart, long occEnd, boolean test) {
        Intent i = new Intent(context, AlarmActivity.class)
                .putExtra(AlarmActivity.EXTRA_SCHEDULE_ID, scheduleId)
                .putExtra(AlarmActivity.EXTRA_OCC_START, occStart)
                .putExtra(AlarmActivity.EXTRA_OCC_END, occEnd)
                .putExtra(AlarmActivity.EXTRA_TEST, test)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        return PendingIntent.getActivity(context, (int) scheduleId, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static PendingIntent action(Context context, String action, long scheduleId) {
        Intent i = new Intent(context, AlarmActionReceiver.class)
                .setAction(action)
                .putExtra(AlarmActionReceiver.EXTRA_SCHEDULE_ID, scheduleId);
        return PendingIntent.getBroadcast(context, (int) scheduleId, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static PendingIntent openApp(Context context) {
        Intent i = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(context, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** The foreground-service notification shown while the alarm rings. */
    static Notification ringing(Context context, Schedule s, ActiveAlarm.Session session, boolean canSnooze) {
        PendingIntent screen = alarmScreen(context, s.id, session.occStart, session.occEnd, session.test);
        String when = context.getString(R.string.notif_window, Formats.time(context, session.occStart),
                Formats.time(context, session.occEnd));
        NotificationCompat.Builder b = new NotificationCompat.Builder(context, CH_ALARM)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(s.displayTitle())
                .setContentText(firstLine(s.body))
                .setSubText(when)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(s.body))
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .setShowWhen(true)
                .setWhen(session.ringAt)
                .setContentIntent(screen)
                .setFullScreenIntent(screen, true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .addAction(0, context.getString(R.string.action_stop), action(context, AlarmActionReceiver.ACTION_STOP, s.id));
        if (canSnooze) {
            b.addAction(0, context.getString(R.string.action_snooze_n, s.snoozeMinutes),
                    action(context, AlarmActionReceiver.ACTION_SNOOZE, s.id));
        }
        b.addAction(0, context.getString(R.string.action_dismiss), action(context, AlarmActionReceiver.ACTION_DISMISS, s.id));
        return b.build();
    }

    /**
     * Used when the foreground service could not start (background start restriction). The
     * channel plays the phone's alarm sound once; the full-screen intent still opens the message.
     */
    static void postFallback(Context context, Schedule s, long occStart, long occEnd) {
        PendingIntent screen = alarmScreen(context, s.id, occStart, occEnd, false);
        NotificationCompat.Builder b = new NotificationCompat.Builder(context, CH_ALARM_FALLBACK)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(s.displayTitle())
                .setContentText(firstLine(s.body))
                .setStyle(new NotificationCompat.BigTextStyle().bigText(s.body))
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(screen)
                .setFullScreenIntent(screen, true)
                .setAutoCancel(true)
                .addAction(0, context.getString(R.string.action_dismiss), action(context, AlarmActionReceiver.ACTION_DISMISS, s.id));
        long remaining = occEnd - System.currentTimeMillis();
        if (s.autoDismiss && remaining > 0) b.setTimeoutAfter(remaining);
        notify(context, fallbackId(s.id), b.build());
    }

    /** A quiet notification that keeps the message readable after the sound stops. */
    static void postMessage(Context context, Schedule s, long occStart, long occEnd, boolean windowOver) {
        PendingIntent screen = alarmScreen(context, s.id, occStart, occEnd, false);
        NotificationCompat.Builder b = new NotificationCompat.Builder(context, CH_MESSAGE)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(s.displayTitle())
                .setContentText(firstLine(s.body))
                .setStyle(new NotificationCompat.BigTextStyle().bigText(s.body))
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(screen)
                .setShowWhen(true)
                .setWhen(occStart)
                .setAutoCancel(false);
        if (!windowOver) {
            b.addAction(0, context.getString(R.string.action_dismiss), action(context, AlarmActionReceiver.ACTION_DISMISS, s.id));
            long remaining = occEnd - System.currentTimeMillis();
            if (s.autoDismiss && remaining > 0) b.setTimeoutAfter(remaining);
        } else {
            b.setAutoCancel(true);
        }
        notify(context, messageId(s.id), b.build());
    }

    static void postSnoozed(Context context, Schedule s, long until) {
        NotificationCompat.Builder b = new NotificationCompat.Builder(context, CH_MESSAGE)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.notif_snoozed_title, s.displayTitle()))
                .setContentText(context.getString(R.string.notif_snoozed_text, Formats.time(context, until)))
                .setContentIntent(openApp(context))
                .setOngoing(false)
                .setTimeoutAfter(Math.max(1000, until - System.currentTimeMillis()))
                .addAction(0, context.getString(R.string.action_dismiss), action(context, AlarmActionReceiver.ACTION_DISMISS, s.id));
        notify(context, messageId(s.id), b.build());
    }

    static void postMissed(Context context, Schedule s, long occStart) {
        NotificationCompat.Builder b = new NotificationCompat.Builder(context, CH_STATUS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.notif_missed_title, s.displayTitle()))
                .setContentText(context.getString(R.string.notif_missed_text, Formats.dateTime(context, occStart)))
                .setStyle(new NotificationCompat.BigTextStyle().bigText(
                        context.getString(R.string.notif_missed_text, Formats.dateTime(context, occStart)) + "\n\n" + s.body))
                .setContentIntent(openApp(context))
                .setAutoCancel(true);
        notify(context, statusId(s.id), b.build());
    }

    static void cancelFor(Context context, long scheduleId) {
        NotificationManagerCompat nm = NotificationManagerCompat.from(context);
        nm.cancel(messageId(scheduleId));
        nm.cancel(fallbackId(scheduleId));
    }

    private static void notify(Context context, int id, Notification n) {
        try {
            NotificationManagerCompat.from(context).notify(id, n);
        } catch (SecurityException e) {
            // POST_NOTIFICATIONS denied; nothing else we can do here. The status page explains it.
        }
    }

    private static String firstLine(String body) {
        if (body == null) return "";
        String b = body.trim();
        int nl = b.indexOf('\n');
        return nl > 0 ? b.substring(0, nl) : b;
    }
}
