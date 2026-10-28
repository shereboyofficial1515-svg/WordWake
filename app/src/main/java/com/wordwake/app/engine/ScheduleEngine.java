package com.wordwake.app.engine;

import com.wordwake.app.model.Schedule;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure scheduling logic (no Android dependencies) so it can be unit-tested on the JVM.
 *
 * Model: each occurrence is a window [start, end). The alert rings at start (or every
 * {@code reminderMinutes} inside the window) and everything stops at end. Runtime state on the
 * schedule (lastRingAt, snoozeUntil, dismissedOcc) decides what the next event is, so recomputing
 * after a reboot, time change or duplicate delivery never produces a second ring for the same slot.
 */
public final class ScheduleEngine {

    public static final int MAX_WINDOW_MINUTES = 12 * 60;
    public static final int MAX_BODY_CHARS = 20000;
    /** A start that fires more than this late is reported as "late" (e.g. phone was off). */
    public static final long LATE_TOLERANCE_MS = 60_000L;
    /** An event is considered due when it is at most this far in the future. */
    public static final long DUE_TOLERANCE_MS = 5_000L;

    public static final int KIND_START = 1;
    public static final int KIND_REMINDER = 2;
    public static final int KIND_SNOOZE = 3;
    public static final int KIND_LATE_START = 4;
    /** A ring that was active when the process died or the phone rebooted; restart it now. */
    public static final int KIND_RESUME = 5;

    public static final int ERR_EMPTY_MESSAGE = 1;
    public static final int ERR_SAME_START_END = 2;
    public static final int ERR_WINDOW_TOO_LONG = 3;
    public static final int ERR_NO_DAYS = 4;
    public static final int ERR_IN_PAST = 5;
    public static final int ERR_INTERVAL_TOO_LONG = 6;
    public static final int ERR_MESSAGE_TOO_LONG = 7;
    public static final int ERR_BAD_TIME = 8;

    private ScheduleEngine() {}

    public static final class Window {
        public final long start;
        public final long end;

        Window(long start, long end) {
            this.start = start;
            this.end = end;
        }
    }

    public static final class Event {
        public final long at;
        public final long occStart;
        public final long occEnd;
        public final int kind;

        Event(long at, long occStart, long occEnd, int kind) {
            this.at = at;
            this.occStart = occStart;
            this.occEnd = occEnd;
            this.kind = kind;
        }

        @Override
        public String toString() {
            return "Event{at=" + at + ", occ=" + occStart + ".." + occEnd + ", kind=" + kind + "}";
        }
    }

    /** Nominal window length in minutes (1..1439), or 0 when start == end. */
    public static int windowMinutes(int startMinute, int endMinute) {
        return ((endMinute - startMinute) % 1440 + 1440) % 1440;
    }

    public static boolean endsNextDay(int startMinute, int endMinute) {
        return endMinute <= startMinute;
    }

    public static LocalTime toLocalTime(int minuteOfDay) {
        return LocalTime.of(minuteOfDay / 60, minuteOfDay % 60);
    }

    /**
     * Computes the real window for an occurrence that starts on {@code date}. Uses local wall-clock
     * times so DST transitions are handled by java.time (times inside a DST gap move forward).
     */
    public static Window windowFor(LocalDate date, int startMinute, int endMinute, ZoneId zone) {
        ZonedDateTime start = ZonedDateTime.of(date, toLocalTime(startMinute), zone);
        LocalDate endDate = endsNextDay(startMinute, endMinute) ? date.plusDays(1) : date;
        ZonedDateTime end = ZonedDateTime.of(endDate, toLocalTime(endMinute), zone);
        if (!end.isAfter(start)) {
            end = start.plusMinutes(Math.max(1, windowMinutes(startMinute, endMinute)));
        }
        return new Window(start.toInstant().toEpochMilli(), end.toInstant().toEpochMilli());
    }

    /** Validates the user-editable part of a schedule. Returns error codes (empty = valid). */
    public static List<Integer> validate(Schedule s, long nowMs, ZoneId deviceZone) {
        List<Integer> errors = new ArrayList<>();
        String title = s.title == null ? "" : s.title.trim();
        String body = s.body == null ? "" : s.body.trim();
        if (title.isEmpty() && body.isEmpty()) errors.add(ERR_EMPTY_MESSAGE);
        if (s.body != null && s.body.length() > MAX_BODY_CHARS) errors.add(ERR_MESSAGE_TOO_LONG);
        if (s.startMinute < 0 || s.startMinute >= 1440 || s.endMinute < 0 || s.endMinute >= 1440) {
            errors.add(ERR_BAD_TIME);
            return errors;
        }
        int window = windowMinutes(s.startMinute, s.endMinute);
        if (window == 0) {
            errors.add(ERR_SAME_START_END);
        } else if (window > MAX_WINDOW_MINUTES) {
            errors.add(ERR_WINDOW_TOO_LONG);
        }
        if (s.repeatMode == Schedule.REPEAT_DAYS && (s.daysMask & Schedule.ALL_DAYS) == 0) {
            errors.add(ERR_NO_DAYS);
        }
        if (s.reminderMinutes > 0 && window > 0 && s.reminderMinutes >= window) {
            errors.add(ERR_INTERVAL_TOO_LONG);
        }
        if (s.repeatMode == Schedule.REPEAT_ONCE && window > 0) {
            Window w = windowFor(s.onceDate(), s.startMinute, s.endMinute, s.zone(deviceZone));
            if (w.start <= nowMs) errors.add(ERR_IN_PAST);
        }
        return errors;
    }

    /** Dates whose occurrence could still be relevant at {@code nowMs}, in chronological order. */
    static List<LocalDate> candidateDates(Schedule s, long nowMs, ZoneId zone) {
        List<LocalDate> dates = new ArrayList<>();
        if (s.repeatMode == Schedule.REPEAT_ONCE) {
            dates.add(s.onceDate());
            return dates;
        }
        LocalDate today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate();
        // Start one day back: a window that began yesterday can still be running (overnight).
        for (int i = -1; i <= 8; i++) {
            LocalDate d = today.plusDays(i);
            if (s.runsOn(d.getDayOfWeek())) dates.add(d);
        }
        return dates;
    }

    /**
     * Returns the next alarm event for this schedule at or after {@code nowMs}, or null if nothing
     * remains (disabled, one-time alert completed, invalid window).
     */
    public static Event nextEvent(Schedule s, long nowMs, ZoneId deviceZone) {
        return nextEvent(s, nowMs, deviceZone, false);
    }

    /**
     * @param ringingInProcess true when a ring session for this schedule is currently playing in
     *                         this process; suppresses the RESUME event.
     */
    public static Event nextEvent(Schedule s, long nowMs, ZoneId deviceZone, boolean ringingInProcess) {
        if (!s.enabled) return null;
        if (windowMinutes(s.startMinute, s.endMinute) == 0) return null;
        if (s.repeatMode == Schedule.REPEAT_DAYS && (s.daysMask & Schedule.ALL_DAYS) == 0) return null;
        ZoneId zone = s.zone(deviceZone);
        for (LocalDate d : candidateDates(s, nowMs, zone)) {
            Window w = windowFor(d, s.startMinute, s.endMinute, zone);
            Event e = eventIn(s, w, nowMs, ringingInProcess);
            if (e != null) return e;
        }
        return null;
    }

    static Event eventIn(Schedule s, Window w, long now, boolean ringingInProcess) {
        if (w.end <= now) return null;
        if (s.dismissedOcc == w.start) return null;
        if (now < w.start) return new Event(w.start, w.start, w.end, KIND_START);

        boolean same = s.occStart == w.start;
        if (same && !ringingInProcess && s.ringActiveUntil > now) {
            return new Event(now, w.start, w.end, KIND_RESUME);
        }
        long last = same ? s.lastRingAt : 0;
        long snooze = (same && s.snoozeUntil > 0 && s.snoozeUntil < w.end) ? s.snoozeUntil : 0;

        long candidate = Long.MAX_VALUE;
        int kind = 0;
        if (s.reminderMinutes > 0) {
            long slot = last <= 0 ? w.start : nextSlotAfter(w.start, s.reminderMinutes, last);
            if (slot < w.end) {
                candidate = slot;
                kind = last <= 0 ? KIND_START : KIND_REMINDER;
            }
        } else if (last <= 0) {
            candidate = w.start;
            kind = KIND_START;
        }
        if (snooze > 0 && snooze <= candidate) {
            candidate = snooze;
            kind = KIND_SNOOZE;
        }
        if (candidate == Long.MAX_VALUE) return null;
        if (kind == KIND_START && now - candidate > LATE_TOLERANCE_MS) kind = KIND_LATE_START;
        return new Event(Math.max(candidate, now), w.start, w.end, kind);
    }

    /** First reminder slot strictly after {@code t} on the grid start + k * interval. */
    public static long nextSlotAfter(long start, int intervalMinutes, long t) {
        long iv = intervalMinutes * 60_000L;
        if (t < start) return start;
        long k = (t - start) / iv + 1;
        return start + k * iv;
    }

    public static boolean isDue(Event e, long nowMs) {
        return e != null && e.at <= nowMs + DUE_TOLERANCE_MS;
    }

    /** When the ring session that starts at {@code ringAt} should end. */
    public static long sessionEnd(Schedule s, long ringAt, long occStart, long occEnd) {
        if (s.reminderMinutes > 0) {
            return Math.min(nextSlotAfter(occStart, s.reminderMinutes, ringAt), occEnd);
        }
        return occEnd;
    }

    /** When the sound should stop (the message stays visible until the session ends). */
    public static long soundEnd(Schedule s, long ringAt, long sessionEnd) {
        if (s.soundDurationMinutes > 0) {
            return Math.min(ringAt + s.soundDurationMinutes * 60_000L, sessionEnd);
        }
        return sessionEnd;
    }

    public static boolean canSnooze(Schedule s, long nowMs, long occEnd) {
        return s.snoozeMinutes > 0 && nowMs + s.snoozeMinutes * 60_000L < occEnd;
    }

    /** The window of the occurrence that started at {@code occStart}. */
    public static Window windowOf(Schedule s, long occStart, ZoneId deviceZone) {
        ZoneId zone = s.zone(deviceZone);
        LocalDate d = Instant.ofEpochMilli(occStart).atZone(zone).toLocalDate();
        Window w = windowFor(d, s.startMinute, s.endMinute, zone);
        if (w.start != occStart) {
            // Schedule was edited or the zone changed; keep the nominal length from occStart.
            int minutes = Math.max(1, windowMinutes(s.startMinute, s.endMinute));
            return new Window(occStart, occStart + minutes * 60_000L);
        }
        return w;
    }

    /** True when a one-time schedule has nothing left to do. */
    public static boolean isFinished(Schedule s, long nowMs, ZoneId deviceZone) {
        return s.repeatMode == Schedule.REPEAT_ONCE && nextEvent(s, nowMs, deviceZone) == null;
    }
}
