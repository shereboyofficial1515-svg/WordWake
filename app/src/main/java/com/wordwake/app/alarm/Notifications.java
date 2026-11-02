package com.wordwake.app.alarm;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.AudioAttributes;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.wordwake.app.R;
import com.wordwake.app.data.AttachmentStore;
import com.wordwake.app.data.ContentRepository;
import com.wordwake.app.engine.ScheduleEngine;
import com.wordwake.app.model.Schedule;
import com.wordwake.app.sound.SoundCatalog;
import com.wordwake.app.ui.AlarmActivity;
import com.wordwake.app.ui.MainActivity;
import com.wordwake.app.ui.ReminderDetailActivity;
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
    /** Ordinary reminders that use the notification alert style. */
    public static final String CH_REMINDER = "reminders";
    /** The silent notification shown while reliable delivery is on. */
    public static final String CH_RELIABILITY = "reliable_delivery";

    public static final int ID_RINGING = 1000;
    public static final int ID_RELIABILITY = 1001;
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

        NotificationChannel reminder = new NotificationChannel(CH_REMINDER,
                context.getString(R.string.channel_reminder), NotificationManager.IMPORTANCE_HIGH);
        reminder.setDescription(context.getString(R.string.channel_reminder_desc));
        reminder.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(reminder);

        NotificationChannel reliable = new NotificationChannel(CH_RELIABILITY,
                context.getString(R.string.channel_reliability), NotificationManager.IMPORTANCE_MIN);
        reliable.setDescription(context.getString(R.string.channel_reliability_desc));
        reliable.setShowBadge(false);
        nm.createNotificationChannel(reliable);
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

    static Notification reliability(Context context) {
        return new NotificationCompat.Builder(context, CH_RELIABILITY)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.notif_reliability_title))
                .setContentText(context.getString(R.string.notif_reliability_text))
                .setContentIntent(openApp(context))
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .setSilent(true)
                .setShowWhen(false)
                .build();
    }

    /** The details screen; asks Android for the unlock when the phone is locked. */
    static PendingIntent details(Context context, long scheduleId) {
        Intent i = new Intent(context, ReminderDetailActivity.class)
                .putExtra(ReminderDetailActivity.EXTRA_ID, scheduleId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(context, (int) (scheduleId & 0x7FFFFFFF), i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Applies the attached picture where Android can show one, otherwise the full text. */
    private static void applyStyle(Context context, NotificationCompat.Builder b, Schedule s) {
        Bitmap image = s.id > 0 ? AttachmentStore.firstImage(context, s.id, 1024) : null;
        String text = expandedText(context, s);
        if (image != null) {
            b.setLargeIcon(image);
            b.setStyle(new NotificationCompat.BigPictureStyle().bigPicture(image).setSummaryText(text));
        } else {
            b.setStyle(new NotificationCompat.BigTextStyle().bigText(text));
        }
    }

    private static String expandedText(Context context, Schedule s) {
        String text = s.body == null ? "" : s.body;
        String quote = s.quoteRandom && s.id > 0 ? new ContentRepository(context).randomQuote() : s.quote;
        if (quote != null && !quote.trim().isEmpty()) {
            String q = context.getString(R.string.alarm_quote_format, quote.trim());
            text = text.isEmpty() ? q : text + "\n\n" + q;
        }
        if (s.deadlineAt > 0) {
            String line = context.getString(R.string.notif_deadline, Formats.dateTime(context, s.deadlineAt),
                    Formats.deadlineRemaining(context, s.deadlineAt));
            text = text.isEmpty() ? line : text + "\n\n" + line;
        }
        return text;
    }

    /** A less intrusive alert: a normal high-priority notification with the channel's sound. */
    static boolean postReminder(Context context, Schedule s, long occStart, long occEnd) {
        NotificationCompat.Builder b = new NotificationCompat.Builder(context, CH_REMINDER)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(s.displayTitle())
                .setContentText(firstLine(s.body.isEmpty() ? s.quote : s.body))
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(details(context, s.id))
                .setAutoCancel(true)
                .setShowWhen(true)
                .setWhen(occStart);
        applyStyle(context, b, s);
        long now = System.currentTimeMillis();
        if (ScheduleEngine.canSnooze(s, now, occEnd)) {
            b.addAction(0, context.getString(R.string.action_snooze_n, s.snoozeMinutes),
                    action(context, AlarmActionReceiver.ACTION_SNOOZE, s.id));
        }
        if (s.isTracked()) {
            b.addAction(0, context.getString(R.string.action_complete), action(context, AlarmActionReceiver.ACTION_COMPLETE, s.id));
        }
        b.addAction(0, context.getString(R.string.action_dismiss), action(context, AlarmActionReceiver.ACTION_DISMISS, s.id));
        if (s.autoDismiss && occEnd > now) b.setTimeoutAfter(occEnd - now);
        return notify(context, messageId(s.id), b.build());
    }

    /** The foreground-service notification shown while the alarm rings. */
    static Notification ringing(Context context, Schedule s, ActiveAlarm.Session session, boolean canSnooze) {
        PendingIntent screen = alarmScreen(context, s.id, session.occStart, session.occEnd, session.test);
        String when = context.getString(R.string.notif_window, Formats.time(context, session.occStart),
                Formats.time(context, session.occEnd));
        NotificationCompat.Builder b = new NotificationCompat.Builder(context, CH_ALARM)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(s.displayTitle())
                .setContentText(firstLine(s.body.isEmpty() ? s.quote : s.body))
                .setSubText(when)
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
        applyStyle(context, b, s);
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
        if (s.body == null) s.body = "";
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

    private static boolean notify(Context context, int id, Notification n) {
        try {
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false;
            NotificationManagerCompat.from(context).notify(id, n);
            return true;
        } catch (SecurityException e) {
            // POST_NOTIFICATIONS denied; nothing else we can do here. The status page explains it.
            return false;
        }
    }

    private static String firstLine(String body) {
        if (body == null) return "";
        String b = body.trim();
        int nl = b.indexOf('\n');
        return nl > 0 ? b.substring(0, nl) : b;
    }
}
