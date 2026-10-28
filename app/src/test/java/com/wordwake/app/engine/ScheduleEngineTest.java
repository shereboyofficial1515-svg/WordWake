package com.wordwake.app.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.wordwake.app.model.Schedule;

import org.junit.Test;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

public class ScheduleEngineTest {

    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");
    private static final ZoneId LONDON = ZoneId.of("Europe/London");
    private static final long MIN = 60_000L;

    private static long at(String isoLocal, ZoneId zone) {
        return LocalDateTime.parse(isoLocal).atZone(zone).toInstant().toEpochMilli();
    }

    private static Schedule daily(int startMin, int endMin) {
        Schedule s = new Schedule();
        s.id = 1;
        s.title = "Good Morning Motivation";
        s.body = "Good morning.\nToday is a new opportunity.";
        s.repeatMode = Schedule.REPEAT_DAILY;
        s.startMinute = startMin;
        s.endMinute = endMin;
        s.enabled = true;
        return s;
    }

    private static Schedule once(LocalDate date, int startMin, int endMin) {
        Schedule s = daily(startMin, endMin);
        s.repeatMode = Schedule.REPEAT_ONCE;
        s.startDate = date.toEpochDay();
        return s;
    }

    /** Simulates what AlarmController.ring() records. */
    private static void ring(Schedule s, ScheduleEngine.Event e, long now) {
        s.occStart = e.occStart;
        s.lastRingAt = now;
        s.snoozeUntil = 0;
        s.ringActiveUntil = ScheduleEngine.sessionEnd(s, now, e.occStart, e.occEnd);
    }

    // ---- 1/5: one-time and daily alerts ----

    @Test
    public void oneTimeAlertFiresAtStartTime() {
        Schedule s = once(LocalDate.of(2026, 11, 2), 6 * 60, 6 * 60 + 30);
        long now = at("2026-11-01T12:00", UTC);
        ScheduleEngine.Event e = ScheduleEngine.nextEvent(s, now, UTC);
        assertNotNull(e);
        assertEquals(at("2026-11-02T06:00", UTC), e.at);
        assertEquals(ScheduleEngine.KIND_START, e.kind);
    }

    @Test
    public void oneTimeAlertHasNothingLeftAfterItsWindow() {
        Schedule s = once(LocalDate.of(2026, 11, 2), 6 * 60, 6 * 60 + 30);
        assertNull(ScheduleEngine.nextEvent(s, at("2026-11-02T06:31", UTC), UTC));
        assertTrue(ScheduleEngine.isFinished(s, at("2026-11-02T06:31", UTC), UTC));
    }

    @Test
    public void dailyAlertMovesToNextDayAfterRinging() {
        Schedule s = daily(6 * 60, 6 * 60 + 30);
        long now = at("2026-11-02T06:00", UTC);
        ScheduleEngine.Event e = ScheduleEngine.nextEvent(s, now, UTC);
        assertEquals(now, e.at);
        ring(s, e, now);
        ScheduleEngine.Event next = ScheduleEngine.nextEvent(s, now + 1000, UTC, true);
        assertEquals(at("2026-11-03T06:00", UTC), next.at);
    }

    // ---- 4: the schedule never needs network; 12: end time ----

    @Test
    public void sixToSixThirtyLastsThirtyMinutesNotTwentyFourHours() {
        ScheduleEngine.Window w = ScheduleEngine.windowFor(LocalDate.of(2026, 11, 2), 6 * 60, 6 * 60 + 30, UTC);
        assertEquals(30 * MIN, w.end - w.start);
        Schedule s = daily(6 * 60, 6 * 60 + 30);
        assertEquals(w.end, ScheduleEngine.sessionEnd(s, w.start, w.start, w.end));
    }

    @Test
    public void overnightWindowRollsOverToNextDay() {
        assertTrue(ScheduleEngine.endsNextDay(23 * 60 + 30, 15));
        ScheduleEngine.Window w = ScheduleEngine.windowFor(LocalDate.of(2026, 11, 2), 23 * 60 + 30, 15, UTC);
        assertEquals(at("2026-11-02T23:30", UTC), w.start);
        assertEquals(at("2026-11-03T00:15", UTC), w.end);
    }

    @Test
    public void overnightWindowStillRunningAfterMidnightIsFound() {
        Schedule s = daily(23 * 60 + 30, 30);
        long now = at("2026-11-03T00:10", UTC);
        ScheduleEngine.Event e = ScheduleEngine.nextEvent(s, now, UTC);
        assertEquals(at("2026-11-02T23:30", UTC), e.occStart);
        assertEquals(at("2026-11-03T00:30", UTC), e.occEnd);
        assertEquals(ScheduleEngine.KIND_LATE_START, e.kind);
    }

    @Test
    public void soundStopsAfterPlaybackDurationButSessionLastsToEnd() {
        Schedule s = daily(6 * 60, 6 * 60 + 30);
        s.soundDurationMinutes = 5;
        long start = at("2026-11-02T06:00", UTC);
        long end = ScheduleEngine.sessionEnd(s, start, start, start + 30 * MIN);
        assertEquals(start + 30 * MIN, end);
        assertEquals(start + 5 * MIN, ScheduleEngine.soundEnd(s, start, end));
    }

    // ---- 6: selected weekdays ----

    @Test
    public void selectedWeekdaysSkipOtherDays() {
        Schedule s = daily(7 * 60, 7 * 60 + 15);
        s.repeatMode = Schedule.REPEAT_DAYS;
        s.daysMask = Schedule.dayBit(DayOfWeek.MONDAY) | Schedule.dayBit(DayOfWeek.WEDNESDAY);
        long tuesday = at("2026-11-03T08:00", UTC); // 2026-11-03 is a Tuesday
        assertEquals(DayOfWeek.TUESDAY, LocalDate.of(2026, 11, 3).getDayOfWeek());
        assertEquals(at("2026-11-04T07:00", UTC), ScheduleEngine.nextEvent(s, tuesday, UTC).at);
        long wednesdayLate = at("2026-11-04T08:00", UTC);
        assertEquals(at("2026-11-09T07:00", UTC), ScheduleEngine.nextEvent(s, wednesdayLate, UTC).at);
    }

    @Test
    public void noSelectedDaysMeansNothingScheduled() {
        Schedule s = daily(7 * 60, 7 * 60 + 15);
        s.repeatMode = Schedule.REPEAT_DAYS;
        s.daysMask = 0;
        assertNull(ScheduleEngine.nextEvent(s, at("2026-11-03T08:00", UTC), UTC));
    }

    // ---- 10/11/21: dismissal, snooze, duplicates ----

    @Test
    public void duplicateDeliveryAfterRingIsNotDue() {
        Schedule s = daily(6 * 60, 6 * 60 + 30);
        long now = at("2026-11-02T06:00", UTC);
        ring(s, ScheduleEngine.nextEvent(s, now, UTC), now);
        // A second broadcast for the same slot (e.g. reconcile raced the first one) must not ring.
        ScheduleEngine.Event again = ScheduleEngine.nextEvent(s, now + 2000, UTC, true);
        assertFalse(ScheduleEngine.isDue(again, now + 2000));
    }

    @Test
    public void dismissedOccurrenceNeverRingsAgain() {
        Schedule s = daily(6 * 60, 6 * 60 + 30);
        s.reminderMinutes = 5;
        long now = at("2026-11-02T06:00", UTC);
        ScheduleEngine.Event e = ScheduleEngine.nextEvent(s, now, UTC);
        ring(s, e, now);
        s.dismissedOcc = e.occStart;
        s.ringActiveUntil = 0;
        ScheduleEngine.Event next = ScheduleEngine.nextEvent(s, now + MIN, UTC);
        assertEquals(at("2026-11-03T06:00", UTC), next.at);
    }

    @Test
    public void snoozeRingsAgainInsideWindow() {
        Schedule s = daily(6 * 60, 6 * 60 + 30);
        s.snoozeMinutes = 10;
        long now = at("2026-11-02T06:00", UTC);
        ScheduleEngine.Event e = ScheduleEngine.nextEvent(s, now, UTC);
        ring(s, e, now);
        long pressed = now + 2 * MIN;
        assertTrue(ScheduleEngine.canSnooze(s, pressed, e.occEnd));
        s.snoozeUntil = pressed + 10 * MIN;
        s.ringActiveUntil = 0;
        ScheduleEngine.Event snooze = ScheduleEngine.nextEvent(s, pressed + 1000, UTC);
        assertEquals(ScheduleEngine.KIND_SNOOZE, snooze.kind);
        assertEquals(pressed + 10 * MIN, snooze.at);
    }

    @Test
    public void snoozeIsNotOfferedWhenItWouldPassTheEndTime() {
        Schedule s = daily(6 * 60, 6 * 60 + 30);
        s.snoozeMinutes = 10;
        long occEnd = at("2026-11-02T06:30", UTC);
        assertFalse(ScheduleEngine.canSnooze(s, at("2026-11-02T06:25", UTC), occEnd));
        s.snoozeMinutes = 0;
        assertFalse(ScheduleEngine.canSnooze(s, at("2026-11-02T06:01", UTC), occEnd));
    }

    @Test
    public void snoozeBeyondWindowEndIsIgnored() {
        Schedule s = daily(6 * 60, 6 * 60 + 30);
        long start = at("2026-11-02T06:00", UTC);
        s.occStart = start;
        s.lastRingAt = start;
        s.snoozeUntil = at("2026-11-02T06:40", UTC);
        assertEquals(at("2026-11-03T06:00", UTC), ScheduleEngine.nextEvent(s, start + 5 * MIN, UTC).at);
    }

    // ---- E: repeated reminders inside a window ----

    @Test
    public void remindersRepeatAtIntervalUntilEnd() {
        Schedule s = daily(6 * 60, 6 * 60 + 30);
        s.reminderMinutes = 10;
        long t = at("2026-11-02T06:00", UTC);
        long[] expected = {at("2026-11-02T06:00", UTC), at("2026-11-02T06:10", UTC), at("2026-11-02T06:20", UTC)};
        for (long exp : expected) {
            ScheduleEngine.Event e = ScheduleEngine.nextEvent(s, t, UTC);
            assertEquals(exp, e.at);
            ring(s, e, e.at);
            assertEquals(Math.min(exp + 10 * MIN, at("2026-11-02T06:30", UTC)), s.ringActiveUntil);
            t = e.at + 1000;
            s.ringActiveUntil = 0;
        }
        // No reminder at 06:30: that is the end time.
        assertEquals(at("2026-11-03T06:00", UTC), ScheduleEngine.nextEvent(s, t, UTC).at);
    }

    @Test
    public void missedRemindersCatchUpOnceThenFollowGrid() {
        Schedule s = daily(6 * 60, 7 * 60);
        s.reminderMinutes = 10;
        long start = at("2026-11-02T06:00", UTC);
        s.occStart = start;
        s.lastRingAt = start;
        // Phone was unreachable until 06:34: one catch-up ring now, then 06:40.
        long now = at("2026-11-02T06:34", UTC);
        ScheduleEngine.Event e = ScheduleEngine.nextEvent(s, now, UTC);
        assertEquals(now, e.at);
        ring(s, e, now);
        s.ringActiveUntil = 0;
        assertEquals(at("2026-11-02T06:40", UTC), ScheduleEngine.nextEvent(s, now + 1000, UTC).at);
    }

    // ---- 15/22: reboot / process death recovery ----

    @Test
    public void ringActiveWhenProcessDiedIsResumed() {
        Schedule s = daily(6 * 60, 6 * 60 + 30);
        long start = at("2026-11-02T06:00", UTC);
        ring(s, ScheduleEngine.nextEvent(s, start, UTC), start);
        long now = start + 3 * MIN;
        ScheduleEngine.Event e = ScheduleEngine.nextEvent(s, now, UTC, false);
        assertEquals(ScheduleEngine.KIND_RESUME, e.kind);
        assertEquals(now, e.at);
        // While the ring is alive in this process there is nothing to resume.
        ScheduleEngine.Event alive = ScheduleEngine.nextEvent(s, now, UTC, true);
        assertEquals(at("2026-11-03T06:00", UTC), alive.at);
    }

    @Test
    public void bootDuringWindowThatNeverRangFiresLate() {
        Schedule s = daily(6 * 60, 6 * 60 + 30);
        long now = at("2026-11-02T06:12", UTC);
        ScheduleEngine.Event e = ScheduleEngine.nextEvent(s, now, UTC);
        assertEquals(ScheduleEngine.KIND_LATE_START, e.kind);
        assertEquals(now, e.at);
        assertEquals(at("2026-11-02T06:30", UTC), e.occEnd);
    }

    @Test
    public void disabledScheduleHasNoEvent() {
        Schedule s = daily(6 * 60, 6 * 60 + 30);
        s.enabled = false;
        assertNull(ScheduleEngine.nextEvent(s, at("2026-11-02T05:00", UTC), UTC));
    }

    // ---- 20: time zones and DST ----

    @Test
    public void followsDeviceTimeZoneWhenNoneSet() {
        Schedule s = daily(6 * 60, 6 * 60 + 30);
        long now = at("2026-11-02T00:00", UTC);
        long inUtc = ScheduleEngine.nextEvent(s, now, UTC).at;
        long inLagos = ScheduleEngine.nextEvent(s, now, LAGOS).at;
        assertEquals(at("2026-11-02T06:00", UTC), inUtc);
        assertEquals(at("2026-11-02T06:00", LAGOS), inLagos);
        assertEquals(60 * MIN, inUtc - inLagos); // Lagos is UTC+1
    }

    @Test
    public void explicitZoneIgnoresDeviceZone() {
        Schedule s = daily(6 * 60, 6 * 60 + 30);
        s.zoneId = "Africa/Lagos";
        long now = at("2026-11-02T00:00", UTC);
        assertEquals(ScheduleEngine.nextEvent(s, now, UTC).at, ScheduleEngine.nextEvent(s, now, LONDON).at);
    }

    @Test
    public void springForwardGapMovesStartForward() {
        // Europe/London 2027-03-28: 01:00 -> 02:00. 01:30 does not exist.
        ScheduleEngine.Window w = ScheduleEngine.windowFor(LocalDate.of(2027, 3, 28), 90, 120 + 30, LONDON);
        assertEquals(Instant.parse("2027-03-28T01:30:00Z").toEpochMilli(), w.start); // 02:30 BST
        assertTrue(w.end > w.start);
    }

    @Test
    public void fallBackWindowKeepsWallClockEnd() {
        // Europe/London 2026-10-25: 02:00 BST -> 01:00 GMT. A 00:30-02:30 window is 3 real hours.
        ScheduleEngine.Window w = ScheduleEngine.windowFor(LocalDate.of(2026, 10, 25), 30, 150, LONDON);
        assertEquals(180 * MIN, w.end - w.start);
    }

    @Test
    public void dailyAlertAcrossDstKeepsLocalTime() {
        Schedule s = daily(6 * 60, 6 * 60 + 30);
        s.zoneId = "Europe/London";
        long before = at("2026-10-24T07:00", LONDON);
        ScheduleEngine.Event e = ScheduleEngine.nextEvent(s, before, UTC);
        assertEquals(at("2026-10-25T06:00", LONDON), e.at);
    }

    // ---- validation ----

    @Test
    public void validationCatchesBadSchedules() {
        long now = at("2026-11-02T12:00", UTC);
        Schedule ok = daily(6 * 60, 6 * 60 + 30);
        assertTrue(ScheduleEngine.validate(ok, now, UTC).isEmpty());

        Schedule same = daily(6 * 60, 6 * 60);
        assertTrue(ScheduleEngine.validate(same, now, UTC).contains(ScheduleEngine.ERR_SAME_START_END));

        Schedule tooLong = daily(6 * 60, 5 * 60); // 23 hours: almost certainly a mistake
        assertTrue(ScheduleEngine.validate(tooLong, now, UTC).contains(ScheduleEngine.ERR_WINDOW_TOO_LONG));

        Schedule empty = daily(6 * 60, 6 * 60 + 30);
        empty.title = " ";
        empty.body = "\n";
        assertTrue(ScheduleEngine.validate(empty, now, UTC).contains(ScheduleEngine.ERR_EMPTY_MESSAGE));

        Schedule past = once(LocalDate.of(2026, 11, 2), 6 * 60, 6 * 60 + 30);
        assertTrue(ScheduleEngine.validate(past, now, UTC).contains(ScheduleEngine.ERR_IN_PAST));

        Schedule interval = daily(6 * 60, 6 * 60 + 30);
        interval.reminderMinutes = 30;
        assertTrue(ScheduleEngine.validate(interval, now, UTC).contains(ScheduleEngine.ERR_INTERVAL_TOO_LONG));

        Schedule noDays = daily(6 * 60, 6 * 60 + 30);
        noDays.repeatMode = Schedule.REPEAT_DAYS;
        noDays.daysMask = 0;
        assertTrue(ScheduleEngine.validate(noDays, now, UTC).contains(ScheduleEngine.ERR_NO_DAYS));

        Schedule huge = daily(6 * 60, 6 * 60 + 30);
        StringBuilder sb = new StringBuilder();
        while (sb.length() <= ScheduleEngine.MAX_BODY_CHARS) sb.append("I never quit. ");
        huge.body = sb.toString();
        assertTrue(ScheduleEngine.validate(huge, now, UTC).contains(ScheduleEngine.ERR_MESSAGE_TOO_LONG));
    }

    @Test
    public void twelveHourOvernightWindowIsAllowed() {
        Schedule s = daily(18 * 60, 6 * 60);
        List<Integer> errors = ScheduleEngine.validate(s, at("2026-11-02T12:00", UTC), UTC);
        assertTrue(errors.toString(), errors.isEmpty());
    }

    // ---- 7: long multiline message survives model copies ----

    @Test
    public void longMultilineMessageWithEmojiIsPreserved() {
        Schedule s = daily(6 * 60, 6 * 60 + 30);
        s.body = "Good morning. 🌅\n\nI am disciplined.\r\nI choose action over excuses!\n\t— Me";
        Schedule c = s.copy();
        assertEquals(s.body, c.body);
        assertEquals("Good Morning Motivation", c.displayTitle());
        c.title = "";
        assertEquals("Good morning. 🌅", c.displayTitle());
    }
}
