package com.wordwake.app.ui;

import android.content.Context;
import android.text.format.DateFormat;

import com.wordwake.app.R;
import com.wordwake.app.engine.ScheduleEngine;
import com.wordwake.app.model.Schedule;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.time.format.TextStyle;
import java.util.Locale;

/** Display formatting that follows the phone's 12/24-hour setting and locale. */
public final class Formats {

    private Formats() {}

    private static DateTimeFormatter timeFormatter(Context c) {
        return DateTimeFormatter.ofPattern(DateFormat.is24HourFormat(c) ? "HH:mm" : "h:mm a", Locale.getDefault());
    }

    public static String time(Context c, long epochMs) {
        return timeFormatter(c).format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()));
    }

    public static String minuteOfDay(Context c, int minute) {
        return timeFormatter(c).format(ScheduleEngine.toLocalTime(minute));
    }

    public static String date(LocalDate d) {
        return DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault()).format(d);
    }

    public static String dateTime(Context c, long epochMs) {
        java.time.ZonedDateTime z = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault());
        LocalDate today = LocalDate.now();
        String day;
        if (z.toLocalDate().equals(today)) day = c.getString(R.string.today);
        else if (z.toLocalDate().equals(today.plusDays(1))) day = c.getString(R.string.tomorrow);
        else day = z.format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault()));
        return day + " · " + time(c, epochMs);
    }

    public static String duration(Context c, int minutes) {
        int h = minutes / 60, m = minutes % 60;
        if (h == 0) return c.getString(R.string.duration_min, m);
        if (m == 0) return c.getResources().getQuantityString(R.plurals.duration_hours, h, h);
        return c.getString(R.string.duration_h_min, h, m);
    }

    public static String remaining(long ms) {
        long s = Math.max(0, ms / 1000);
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        if (h > 0) return String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, sec);
        return String.format(Locale.getDefault(), "%d:%02d", m, sec);
    }

    public static String repeat(Context c, Schedule s) {
        switch (s.repeatMode) {
            case Schedule.REPEAT_ONCE:
                return c.getString(R.string.repeat_once_on, date(s.onceDate()));
            case Schedule.REPEAT_DAILY:
                return c.getString(R.string.repeat_daily);
            default:
                if ((s.daysMask & Schedule.ALL_DAYS) == Schedule.WEEKDAYS) return c.getString(R.string.repeat_weekdays);
                if ((s.daysMask & Schedule.ALL_DAYS) == 0x60) return c.getString(R.string.repeat_weekends);
                StringBuilder sb = new StringBuilder();
                for (DayOfWeek d : DayOfWeek.values()) {
                    if ((s.daysMask & Schedule.dayBit(d)) != 0) {
                        if (sb.length() > 0) sb.append(", ");
                        sb.append(d.getDisplayName(TextStyle.SHORT, Locale.getDefault()));
                    }
                }
                return sb.toString();
        }
    }

    /** "6:00 AM → 6:30 AM (30 min)" with a next-day marker when the window crosses midnight. */
    public static String window(Context c, Schedule s) {
        int mins = ScheduleEngine.windowMinutes(s.startMinute, s.endMinute);
        String end = minuteOfDay(c, s.endMinute);
        if (ScheduleEngine.endsNextDay(s.startMinute, s.endMinute)) end += " " + c.getString(R.string.next_day_marker);
        return c.getString(R.string.window_format, minuteOfDay(c, s.startMinute), end, duration(c, mins));
    }

    public static String alertStyle(Context c, Schedule s) {
        if (s.reminderMinutes > 0) return c.getString(R.string.style_reminders_every, s.reminderMinutes);
        return c.getString(R.string.style_continuous);
    }
}
