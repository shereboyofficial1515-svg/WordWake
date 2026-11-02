package com.wordwake.app.engine;

import com.wordwake.app.model.Schedule;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

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
    /** How far ahead date scanning looks for monthly and yearly repeats. */
    private static final int SCAN_DAYS = 400;

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
    public static final int ERR_NO_DATES = 9;
    public static final int ERR_NO_DEADLINE = 10;
    public static final int ERR_NO_LEADS = 11;

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
        /** When this event should have happened; earlier than {@link #at} if it is late. */
        public final long scheduledFor;
        public final long occStart;
        public final long occEnd;
        public final int kind;

        Event(long at, long scheduledFor, long occStart, long occEnd, int kind) {
            this.at = at;
            this.scheduledFor = scheduledFor;
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
        String quote = s.quote == null ? "" : s.quote.trim();
        if (title.isEmpty() && body.isEmpty() && quote.isEmpty() && !s.quoteRandom) errors.add(ERR_EMPTY_MESSAGE);
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
        if (s.repeatMode == Schedule.REPEAT_CUSTOM_DATES && s.customDateList().isEmpty()) {
            errors.add(ERR_NO_DATES);
        }
        if (s.repeatMode == Schedule.REPEAT_DEADLINE) {
            if (s.deadlineAt <= 0) {
                errors.add(ERR_NO_DEADLINE);
            } else if (s.leadDayList().isEmpty() && !s.followUpDaily && !s.overdueDaily) {
                errors.add(ERR_NO_LEADS);
            }
        }
        return errors;
    }

    /** True when an occurrence of this schedule starts on {@code date}. */
    public static boolean occursOn(Schedule s, LocalDate date, ZoneId zone) {
        LocalDate anchor = LocalDate.ofEpochDay(s.startDate);
        switch (s.repeatMode) {
            case Schedule.REPEAT_ONCE:
                return date.equals(anchor);
            case Schedule.REPEAT_DAILY:
            case Schedule.REPEAT_DAYS:
                return s.runsOn(date.getDayOfWeek());
            case Schedule.REPEAT_WEEKLY:
                return !date.isBefore(anchor) && date.getDayOfWeek() == anchor.getDayOfWeek();
            case Schedule.REPEAT_MONTHLY:
                // A monthly alert on the 31st falls on the last day of shorter months.
                return !date.isBefore(anchor)
                        && date.getDayOfMonth() == Math.min(anchor.getDayOfMonth(), date.lengthOfMonth());
            case Schedule.REPEAT_YEARLY:
                return !date.isBefore(anchor) && date.getMonth() == anchor.getMonth()
                        && date.getDayOfMonth() == Math.min(anchor.getDayOfMonth(), date.lengthOfMonth());
            case Schedule.REPEAT_CUSTOM_DATES:
                return s.customDateList().contains(date);
            case Schedule.REPEAT_DEADLINE:
                return deadlineAlertOn(s, date, zone);
            default:
                return false;
        }
    }

    private static boolean deadlineAlertOn(Schedule s, LocalDate date, ZoneId zone) {
        if (s.deadlineAt <= 0) return false;
        LocalDate deadline = Instant.ofEpochMilli(s.deadlineAt).atZone(zone).toLocalDate();
        LocalDate first = LocalDate.ofEpochDay(s.startDate);
        if (date.isAfter(deadline)) return s.overdueDaily;
        if (s.followUpDaily && !date.isBefore(first)) return true;
        if (date.isBefore(first)) return false;
        for (int lead : s.leadDayList()) {
            if (date.equals(deadline.minusDays(lead))) return true;
        }
        return false;
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
        LocalDate from = today.minusDays(1);
        if (s.repeatMode == Schedule.REPEAT_CUSTOM_DATES) {
            for (LocalDate d : s.customDateList()) if (!d.isBefore(from)) dates.add(d);
            return dates;
        }
        if (s.repeatMode == Schedule.REPEAT_DEADLINE) {
            dates.addAll(deadlineDates(s, from, from.plusDays(SCAN_DAYS * 3L), zone));
            return dates;
        }
        LocalDate anchor = LocalDate.ofEpochDay(s.startDate);
        boolean anchored = s.repeatMode != Schedule.REPEAT_DAILY && s.repeatMode != Schedule.REPEAT_DAYS;
        if (anchored && anchor.isAfter(from)) from = anchor;
        for (int i = 0; i <= SCAN_DAYS; i++) {
            LocalDate d = from.plusDays(i);
            if (occursOn(s, d, zone)) {
                dates.add(d);
                // Enough to skip a dismissed occurrence and an overnight window from yesterday.
                if (dates.size() >= 3) break;
            }
        }
        return dates;
    }

    private static TreeSet<LocalDate> deadlineDates(Schedule s, LocalDate from, LocalDate to, ZoneId zone) {
        TreeSet<LocalDate> out = new TreeSet<>();
        if (s.deadlineAt <= 0) return out;
        LocalDate deadline = Instant.ofEpochMilli(s.deadlineAt).atZone(zone).toLocalDate();
        LocalDate first = LocalDate.ofEpochDay(s.startDate);
        for (int lead : s.leadDayList()) {
            LocalDate d = deadline.minusDays(lead);
            if (!d.isBefore(first) && !d.isBefore(from) && !d.isAfter(to)) out.add(d);
        }
        if (s.followUpDaily) {
            LocalDate d = first.isAfter(from) ? first : from;
            for (; !d.isAfter(deadline) && !d.isAfter(to); d = d.plusDays(1)) out.add(d);
        }
        if (s.overdueDaily) {
            LocalDate d = deadline.plusDays(1).isAfter(from) ? deadline.plusDays(1) : from;
            for (int i = 0; i < 3 && !d.isAfter(to); i++, d = d.plusDays(1)) out.add(d);
        }
        return out;
    }

    /** Every date in [from, to] with an occurrence; used by the calendar. */
    public static List<LocalDate> datesInRange(Schedule s, LocalDate from, LocalDate to, ZoneId deviceZone) {
        ZoneId zone = s.zone(deviceZone);
        List<LocalDate> out = new ArrayList<>();
        if (s.repeatMode == Schedule.REPEAT_DEADLINE) {
            out.addAll(deadlineDates(s, from, to, zone));
            if (s.overdueDaily) {
                for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                    if (occursOn(s, d, zone) && !out.contains(d)) out.add(d);
                }
            }
            java.util.Collections.sort(out);
            return out;
        }
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (occursOn(s, d, zone)) out.add(d);
        }
        return out;
    }

    public static Event nextEvent(Schedule s, long nowMs, ZoneId deviceZone) {
        return nextEvent(s, nowMs, deviceZone, false);
    }

    /**
     * Returns the next alarm event for this schedule at or after {@code nowMs}, or null if nothing
     * remains (disabled, completed, finished, invalid window).
     *
     * @param ringingInProcess true when a ring session for this schedule is currently playing in
     *                         this process; suppresses the RESUME event.
     */
    public static Event nextEvent(Schedule s, long nowMs, ZoneId deviceZone, boolean ringingInProcess) {
        if (!s.enabled || s.isCompleted()) return null;
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
        if (now < w.start) return new Event(w.start, w.start, w.start, w.end, KIND_START);

        boolean same = s.occStart == w.start;
        if (same && !ringingInProcess && s.ringActiveUntil > now) {
            return new Event(now, now, w.start, w.end, KIND_RESUME);
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
        return new Event(Math.max(candidate, now), candidate, w.start, w.end, kind);
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

    /**
     * The window of the occurrence that started at {@code occStart}. Keeps the nominal length if
     * the schedule was edited or the zone changed after that occurrence began.
     */
    public static Window windowOf(Schedule s, long occStart, ZoneId deviceZone) {
        ZoneId zone = s.zone(deviceZone);
        LocalDate d = Instant.ofEpochMilli(occStart).atZone(zone).toLocalDate();
        Window w = windowFor(d, s.startMinute, s.endMinute, zone);
        if (w.start != occStart) {
            int minutes = Math.max(1, windowMinutes(s.startMinute, s.endMinute));
            return new Window(occStart, occStart + minutes * 60_000L);
        }
        return w;
    }

    /** True when a finite schedule has nothing left to do. */
    public static boolean isFinished(Schedule s, long nowMs, ZoneId deviceZone) {
        return s.isFiniteRepeat() && nextEvent(s, nowMs, deviceZone) == null;
    }

    /**
     * True when the alert stored in {@code nextTriggerAt} passed without ringing and its window has
     * closed, e.g. the phone was off or a manufacturer power manager held the alarm back.
     *
     * @param next the event computed now for this schedule (may be null).
     */
    public static boolean wasMissed(Schedule s, Event next, long nowMs) {
        long expected = s.nextTriggerAt;
        if (expected <= 0 || expected + LATE_TOLERANCE_MS >= nowMs) return false;
        if (s.lastRingAt >= expected) return false;
        return next == null || next.occStart > expected;
    }

    /** True when the deadline has passed and the work is not completed. */
    public static boolean isOverdue(Schedule s, long nowMs) {
        return s.deadlineAt > 0 && !s.isCompleted() && nowMs > s.deadlineAt;
    }
}
