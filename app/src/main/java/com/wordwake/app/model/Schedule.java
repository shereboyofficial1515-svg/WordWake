package com.wordwake.app.model;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A scheduled reminder plus its runtime alarm state.
 *
 * Times are stored as minute-of-day in the schedule's time zone. The alert window starts at
 * {@link #startMinute} and ends at {@link #endMinute}; when the end minute is less than or equal
 * to the start minute the window ends on the following day.
 */
public final class Schedule {

    public static final int REPEAT_ONCE = 0;
    public static final int REPEAT_DAILY = 1;
    public static final int REPEAT_DAYS = 2;
    public static final int REPEAT_WEEKLY = 3;
    public static final int REPEAT_MONTHLY = 4;
    public static final int REPEAT_YEARLY = 5;
    public static final int REPEAT_CUSTOM_DATES = 6;
    /** Alerts are derived from {@link #deadlineAt}: lead days and optional daily follow-ups. */
    public static final int REPEAT_DEADLINE = 7;

    public static final int TYPE_ALARM = 0;
    public static final int TYPE_REMINDER = 1;
    public static final int TYPE_TASK = 2;
    public static final int TYPE_APPOINTMENT = 3;
    public static final int TYPE_DEADLINE = 4;
    public static final int TYPE_TAX = 5;
    public static final int TYPE_BILL = 6;
    public static final int TYPE_QUOTE = 7;

    /** Sound, vibration and full-screen display. */
    public static final int ALERT_ALARM = 0;
    /** An ordinary high-priority notification. */
    public static final int ALERT_NOTIFICATION = 1;

    public static final int STATUS_NOT_STARTED = 0;
    public static final int STATUS_IN_PROGRESS = 1;
    public static final int STATUS_WAITING = 2;
    public static final int STATUS_READY = 3;
    public static final int STATUS_COMPLETED = 4;

    /** Bit for Monday; Tuesday is MONDAY << 1, ... Sunday is MONDAY << 6. */
    public static final int DAY_MONDAY = 1;
    public static final int ALL_DAYS = 0x7F;
    public static final int WEEKDAYS = 0x1F;

    public static final int OUTCOME_NONE = 0;
    public static final int OUTCOME_DISMISSED = 1;
    public static final int OUTCOME_MISSED = 2;
    public static final int OUTCOME_COMPLETED = 3;
    public static final int OUTCOME_ENDED = 4;

    public static final String DEFAULT_LEAD_DAYS = "30,14,7,3,0";

    // ---- user-editable fields ----
    public long id;
    public String title = "";
    public String body = "";
    public int type = TYPE_ALARM;
    public int alertMode = ALERT_ALARM;
    public String categoryKey = Category.KEY_PERSONAL;
    public int repeatMode = REPEAT_DAILY;
    /** Epoch day: the date of a one-time alert, or the anchor/first date of other modes. */
    public long startDate;
    public int startMinute = 6 * 60;
    public int endMinute = 6 * 60 + 30;
    public int daysMask = ALL_DAYS;
    /** Comma-separated epoch days for {@link #REPEAT_CUSTOM_DATES}. */
    public String customDates = "";
    /** Empty means "follow the device time zone". */
    public String zoneId = "";
    /** 0 = ring once continuously; > 0 = repeat a reminder every N minutes inside the window. */
    public int reminderMinutes;
    public String soundUri = "";
    public String soundName = "";
    /** 0..100, relative to the phone's alarm volume. Never changes system volume. */
    public int volumePercent = 100;
    public boolean loopSound = true;
    /** 0 = play for the whole ring session; otherwise stop the sound after N minutes. */
    public int soundDurationMinutes;
    public boolean vibrate = true;
    /** 0 = snooze disabled. */
    public int snoozeMinutes = 10;
    /** Close the message screen/notification automatically at the end time. */
    public boolean autoDismiss = true;
    public boolean enabled = true;
    public String quote = "";
    /** Show a random saved quote instead of {@link #quote}. */
    public boolean quoteRandom;
    /** Epoch ms of the deadline; 0 = none. Independent of the alert window. */
    public long deadlineAt;
    /** Days before the deadline to alert, comma separated; 0 means on the deadline day. */
    public String leadDays = "";
    /** Alert every day from {@link #startDate} until the deadline or completion. */
    public boolean followUpDaily;
    /** Keep alerting every day after the deadline until the task is completed. */
    public boolean overdueDaily;
    public int status = STATUS_NOT_STARTED;
    public long completedAt;
    /** Tax/bill details as JSON; see {@link Finance}. */
    public String financeJson = "";
    public long createdAt;
    public long updatedAt;

    // ---- runtime state (persisted so alarms survive process death and reboot) ----
    /** Start (epoch ms) of the occurrence the state below refers to. */
    public long occStart;
    /** Last time a ring started for {@link #occStart}. 0 = never. */
    public long lastRingAt;
    /** Occurrence start the user dismissed; that occurrence never rings again. */
    public long dismissedOcc;
    public long snoozeUntil;
    /** While > now a ring with sound should be active; used to resume after process death/reboot. */
    public long ringActiveUntil;
    /** Next scheduled trigger time, informational (dashboard). 0 = none. */
    public long nextTriggerAt;
    public boolean nextExact;
    public int lastOutcome = OUTCOME_NONE;

    public ZoneId zone(ZoneId deviceZone) {
        if (zoneId == null || zoneId.isEmpty()) return deviceZone;
        try {
            return ZoneId.of(zoneId);
        } catch (Exception e) {
            return deviceZone;
        }
    }

    public boolean runsOn(DayOfWeek day) {
        switch (repeatMode) {
            case REPEAT_DAILY:
                return true;
            case REPEAT_DAYS:
                return (daysMask & dayBit(day)) != 0;
            default:
                return false;
        }
    }

    public LocalDate onceDate() {
        return LocalDate.ofEpochDay(startDate);
    }

    public static int dayBit(DayOfWeek day) {
        return DAY_MONDAY << (day.getValue() - 1);
    }

    public boolean isCompleted() {
        return status == STATUS_COMPLETED;
    }

    /** True for modes with a limited set of dates, which finish when the dates run out. */
    public boolean isFiniteRepeat() {
        return repeatMode == REPEAT_ONCE || repeatMode == REPEAT_CUSTOM_DATES || repeatMode == REPEAT_DEADLINE;
    }

    /** True for types tracked as work to finish (the rest simply ring). */
    public boolean isTracked() {
        return type == TYPE_TASK || type == TYPE_DEADLINE || type == TYPE_TAX || type == TYPE_BILL
                || type == TYPE_APPOINTMENT || deadlineAt > 0;
    }

    public List<LocalDate> customDateList() {
        return parseDates(customDates);
    }

    public static List<LocalDate> parseDates(String csv) {
        List<LocalDate> out = new ArrayList<>();
        if (csv == null || csv.trim().isEmpty()) return out;
        for (String p : csv.split(",")) {
            try {
                out.add(LocalDate.ofEpochDay(Long.parseLong(p.trim())));
            } catch (NumberFormatException ignored) {
                // Skip a damaged entry and keep the rest.
            }
        }
        Collections.sort(out);
        return out;
    }

    public static String formatDates(List<LocalDate> dates) {
        StringBuilder sb = new StringBuilder();
        for (LocalDate d : dates) {
            if (sb.length() > 0) sb.append(',');
            sb.append(d.toEpochDay());
        }
        return sb.toString();
    }

    public List<Integer> leadDayList() {
        List<Integer> out = new ArrayList<>();
        if (leadDays == null || leadDays.trim().isEmpty()) return out;
        for (String p : leadDays.split(",")) {
            try {
                int v = Integer.parseInt(p.trim());
                if (v >= 0 && !out.contains(v)) out.add(v);
            } catch (NumberFormatException ignored) {
                // Skip a damaged entry and keep the rest.
            }
        }
        Collections.sort(out, Collections.reverseOrder());
        return out;
    }

    /** Clears per-occurrence runtime state, e.g. after the schedule is edited. */
    public void resetRuntimeState() {
        occStart = 0;
        lastRingAt = 0;
        dismissedOcc = 0;
        snoozeUntil = 0;
        ringActiveUntil = 0;
        nextTriggerAt = 0;
        nextExact = false;
        lastOutcome = OUTCOME_NONE;
    }

    public Schedule copy() {
        Schedule s = new Schedule();
        s.id = id;
        s.title = title;
        s.body = body;
        s.type = type;
        s.alertMode = alertMode;
        s.categoryKey = categoryKey;
        s.repeatMode = repeatMode;
        s.startDate = startDate;
        s.startMinute = startMinute;
        s.endMinute = endMinute;
        s.daysMask = daysMask;
        s.customDates = customDates;
        s.zoneId = zoneId;
        s.reminderMinutes = reminderMinutes;
        s.soundUri = soundUri;
        s.soundName = soundName;
        s.volumePercent = volumePercent;
        s.loopSound = loopSound;
        s.soundDurationMinutes = soundDurationMinutes;
        s.vibrate = vibrate;
        s.snoozeMinutes = snoozeMinutes;
        s.autoDismiss = autoDismiss;
        s.enabled = enabled;
        s.quote = quote;
        s.quoteRandom = quoteRandom;
        s.deadlineAt = deadlineAt;
        s.leadDays = leadDays;
        s.followUpDaily = followUpDaily;
        s.overdueDaily = overdueDaily;
        s.status = status;
        s.completedAt = completedAt;
        s.financeJson = financeJson;
        s.createdAt = createdAt;
        s.updatedAt = updatedAt;
        s.occStart = occStart;
        s.lastRingAt = lastRingAt;
        s.dismissedOcc = dismissedOcc;
        s.snoozeUntil = snoozeUntil;
        s.ringActiveUntil = ringActiveUntil;
        s.nextTriggerAt = nextTriggerAt;
        s.nextExact = nextExact;
        s.lastOutcome = lastOutcome;
        return s;
    }

    public String displayTitle() {
        if (title != null && !title.trim().isEmpty()) return title.trim();
        String b = body == null ? "" : body.trim();
        if (b.isEmpty() && quote != null) b = quote.trim();
        int nl = b.indexOf('\n');
        if (nl > 0) b = b.substring(0, nl);
        return b.length() > 40 ? b.substring(0, 40) + "…" : b;
    }
}
