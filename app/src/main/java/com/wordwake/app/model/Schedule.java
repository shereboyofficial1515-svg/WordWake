package com.wordwake.app.model;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * A scheduled message plus its runtime alarm state.
 *
 * Times are stored as minute-of-day in the schedule's time zone. The alert window starts at
 * {@link #startMinute} and ends at {@link #endMinute}; when the end minute is less than or equal
 * to the start minute the window ends on the following day.
 */
public final class Schedule {

    public static final int REPEAT_ONCE = 0;
    public static final int REPEAT_DAILY = 1;
    public static final int REPEAT_DAYS = 2;

    /** Bit for Monday; Tuesday is MONDAY << 1, ... Sunday is MONDAY << 6. */
    public static final int DAY_MONDAY = 1;
    public static final int ALL_DAYS = 0x7F;
    public static final int WEEKDAYS = 0x1F;

    public static final int OUTCOME_NONE = 0;
    public static final int OUTCOME_DISMISSED = 1;
    public static final int OUTCOME_MISSED = 2;
    public static final int OUTCOME_COMPLETED = 3;
    public static final int OUTCOME_ENDED = 4;

    // ---- user-editable fields ----
    public long id;
    public String title = "";
    public String body = "";
    public int repeatMode = REPEAT_DAILY;
    /** Epoch day of the one-time alert date (used by REPEAT_ONCE only). */
    public long startDate;
    public int startMinute = 6 * 60;
    public int endMinute = 6 * 60 + 30;
    public int daysMask = ALL_DAYS;
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
        s.repeatMode = repeatMode;
        s.startDate = startDate;
        s.startMinute = startMinute;
        s.endMinute = endMinute;
        s.daysMask = daysMask;
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
        int nl = b.indexOf('\n');
        if (nl > 0) b = b.substring(0, nl);
        return b.length() > 40 ? b.substring(0, 40) + "…" : b;
    }
}
