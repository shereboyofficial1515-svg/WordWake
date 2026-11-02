package com.wordwake.app.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.wordwake.app.model.Finance;
import com.wordwake.app.model.Schedule;

import org.junit.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Weekly/monthly/yearly/custom/deadline scheduling, missed-alert detection and dashboard stats. */
public class ReminderSchedulingTest {

    private static final ZoneId UTC = ZoneId.of("UTC");

    private static long at(String iso) {
        return LocalDateTime.parse(iso).atZone(UTC).toInstant().toEpochMilli();
    }

    private static Schedule make(int repeat, LocalDate anchor) {
        Schedule s = new Schedule();
        s.id = 1;
        s.title = "Pay Personal Income Tax";
        s.repeatMode = repeat;
        s.startDate = anchor.toEpochDay();
        s.startMinute = 9 * 60;
        s.endMinute = 10 * 60;
        return s;
    }

    @Test
    public void weeklyRepeatsOnAnchorWeekdayFromAnchorOnwards() {
        Schedule s = make(Schedule.REPEAT_WEEKLY, LocalDate.of(2026, 11, 4)); // a Wednesday
        assertEquals(at("2026-11-04T09:00"), ScheduleEngine.nextEvent(s, at("2026-11-01T12:00"), UTC).at);
        assertEquals(at("2026-11-11T09:00"), ScheduleEngine.nextEvent(s, at("2026-11-04T12:00"), UTC).at);
    }

    @Test
    public void monthlyOnThe31stFallsOnLastDayOfShorterMonths() {
        Schedule s = make(Schedule.REPEAT_MONTHLY, LocalDate.of(2026, 1, 31));
        assertEquals(at("2026-02-28T09:00"), ScheduleEngine.nextEvent(s, at("2026-02-01T00:00"), UTC).at);
        assertEquals(at("2026-03-31T09:00"), ScheduleEngine.nextEvent(s, at("2026-03-01T00:00"), UTC).at);
        assertEquals(at("2026-04-30T09:00"), ScheduleEngine.nextEvent(s, at("2026-04-01T00:00"), UTC).at);
    }

    @Test
    public void monthlyDoesNotStartBeforeItsAnchor() {
        Schedule s = make(Schedule.REPEAT_MONTHLY, LocalDate.of(2027, 3, 15));
        assertEquals(at("2027-03-15T09:00"), ScheduleEngine.nextEvent(s, at("2026-11-01T00:00"), UTC).at);
    }

    @Test
    public void yearlyFindsNextYearAndHandlesLeapDay() {
        Schedule s = make(Schedule.REPEAT_YEARLY, LocalDate.of(2024, 2, 29));
        assertEquals(at("2027-02-28T09:00"), ScheduleEngine.nextEvent(s, at("2026-11-01T00:00"), UTC).at);
        assertEquals(at("2028-02-29T09:00"), ScheduleEngine.nextEvent(s, at("2027-03-01T00:00"), UTC).at);
    }

    @Test
    public void customDatesRunInOrderThenFinish() {
        Schedule s = make(Schedule.REPEAT_CUSTOM_DATES, LocalDate.of(2026, 1, 1));
        s.customDates = Schedule.formatDates(Arrays.asList(LocalDate.of(2026, 12, 20), LocalDate.of(2026, 11, 20)));
        assertEquals(at("2026-11-20T09:00"), ScheduleEngine.nextEvent(s, at("2026-11-01T00:00"), UTC).at);
        assertEquals(at("2026-12-20T09:00"), ScheduleEngine.nextEvent(s, at("2026-11-21T00:00"), UTC).at);
        assertNull(ScheduleEngine.nextEvent(s, at("2026-12-21T00:00"), UTC));
        assertTrue(ScheduleEngine.isFinished(s, at("2026-12-21T00:00"), UTC));
    }

    @Test
    public void damagedCustomDateEntryIsIgnored() {
        Schedule s = make(Schedule.REPEAT_CUSTOM_DATES, LocalDate.of(2026, 1, 1));
        s.customDates = "garbage," + LocalDate.of(2026, 11, 20).toEpochDay();
        assertEquals(1, s.customDateList().size());
    }

    private static Schedule taxDeadline() {
        Schedule s = make(Schedule.REPEAT_DEADLINE, LocalDate.of(2026, 11, 1));
        s.type = Schedule.TYPE_TAX;
        s.deadlineAt = at("2026-12-30T17:00");
        s.leadDays = "30,14,7,3,0";
        return s;
    }

    @Test
    public void deadlineLeadDaysAlertOnEachConfiguredDay() {
        Schedule s = taxDeadline();
        String[][] steps = {
                {"2026-11-01T00:00", "2026-11-30T09:00"},
                {"2026-11-30T12:00", "2026-12-16T09:00"},
                {"2026-12-16T12:00", "2026-12-23T09:00"},
                {"2026-12-23T12:00", "2026-12-27T09:00"},
                {"2026-12-27T12:00", "2026-12-30T09:00"}};
        for (String[] step : steps) {
            assertEquals(step[0], at(step[1]), ScheduleEngine.nextEvent(s, at(step[0]), UTC).at);
        }
        assertNull("no overdue alerts unless enabled", ScheduleEngine.nextEvent(s, at("2026-12-30T12:00"), UTC));
    }

    @Test
    public void followUpsRunDailyFromFirstDateToDeadline() {
        Schedule s = make(Schedule.REPEAT_DEADLINE, LocalDate.of(2026, 11, 3));
        s.deadlineAt = at("2026-11-10T17:00");
        s.followUpDaily = true;
        assertEquals("not before the first alert date", at("2026-11-03T09:00"), ScheduleEngine.nextEvent(s, at("2026-11-01T00:00"), UTC).at);
        assertEquals(at("2026-11-04T09:00"), ScheduleEngine.nextEvent(s, at("2026-11-03T12:00"), UTC).at);
        assertEquals(at("2026-11-10T09:00"), ScheduleEngine.nextEvent(s, at("2026-11-09T12:00"), UTC).at);
        assertNull(ScheduleEngine.nextEvent(s, at("2026-11-10T12:00"), UTC));
    }

    @Test
    public void overdueRemindersContinueUntilCompleted() {
        Schedule s = taxDeadline();
        s.overdueDaily = true;
        long afterDeadline = at("2026-12-31T12:00");
        assertTrue(ScheduleEngine.isOverdue(s, afterDeadline));
        assertEquals(at("2027-01-01T09:00"), ScheduleEngine.nextEvent(s, afterDeadline, UTC).at);
        s.status = Schedule.STATUS_COMPLETED;
        assertNull("completing stops all future alerts", ScheduleEngine.nextEvent(s, afterDeadline, UTC));
        assertFalse(ScheduleEngine.isOverdue(s, afterDeadline));
    }

    @Test
    public void leadDaysBeforeFirstAlertDateAreSkipped() {
        Schedule s = taxDeadline();
        s.startDate = LocalDate.of(2026, 12, 20).toEpochDay();
        assertEquals(at("2026-12-23T09:00"), ScheduleEngine.nextEvent(s, at("2026-11-01T00:00"), UTC).at);
    }

    @Test
    public void validationRequiresDeadlineDatesAndLeads() {
        long now = at("2026-11-01T00:00");
        Schedule noDeadline = make(Schedule.REPEAT_DEADLINE, LocalDate.of(2026, 11, 1));
        assertTrue(ScheduleEngine.validate(noDeadline, now, UTC).contains(ScheduleEngine.ERR_NO_DEADLINE));
        Schedule noLeads = make(Schedule.REPEAT_DEADLINE, LocalDate.of(2026, 11, 1));
        noLeads.deadlineAt = at("2026-12-01T00:00");
        assertTrue(ScheduleEngine.validate(noLeads, now, UTC).contains(ScheduleEngine.ERR_NO_LEADS));
        Schedule noDates = make(Schedule.REPEAT_CUSTOM_DATES, LocalDate.of(2026, 11, 1));
        assertTrue(ScheduleEngine.validate(noDates, now, UTC).contains(ScheduleEngine.ERR_NO_DATES));
    }

    @Test
    public void quoteOnlyReminderIsValid() {
        Schedule s = make(Schedule.REPEAT_DAILY, LocalDate.of(2026, 11, 1));
        s.title = "";
        s.quote = "Show up anyway.";
        assertTrue(ScheduleEngine.validate(s, at("2026-11-01T00:00"), UTC).isEmpty());
        assertEquals("Show up anyway.", s.displayTitle());
    }

    @Test
    public void calendarListsOccurrencesInRange() {
        Schedule s = make(Schedule.REPEAT_WEEKLY, LocalDate.of(2026, 11, 4));
        List<LocalDate> dates = ScheduleEngine.datesInRange(s, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), UTC);
        assertEquals(Arrays.asList(LocalDate.of(2026, 11, 4), LocalDate.of(2026, 11, 11),
                LocalDate.of(2026, 11, 18), LocalDate.of(2026, 11, 25)), dates);
        Schedule tax = taxDeadline();
        assertEquals(Collections.singletonList(LocalDate.of(2026, 12, 30)),
                ScheduleEngine.datesInRange(tax, LocalDate.of(2026, 12, 28), LocalDate.of(2026, 12, 31), UTC));
    }

    // ---- missed alerts ----

    @Test
    public void alertWhoseWindowClosedWithoutRingingIsMissed() {
        Schedule s = make(Schedule.REPEAT_DAILY, LocalDate.of(2026, 11, 1));
        s.nextTriggerAt = at("2026-11-02T09:00");
        long now = at("2026-11-02T10:30");
        ScheduleEngine.Event next = ScheduleEngine.nextEvent(s, now, UTC);
        assertEquals(at("2026-11-03T09:00"), next.occStart);
        assertTrue(ScheduleEngine.wasMissed(s, next, now));
    }

    @Test
    public void lateButStillInsideWindowIsNotMissed() {
        Schedule s = make(Schedule.REPEAT_DAILY, LocalDate.of(2026, 11, 1));
        s.nextTriggerAt = at("2026-11-02T09:00");
        long now = at("2026-11-02T09:30");
        ScheduleEngine.Event next = ScheduleEngine.nextEvent(s, now, UTC);
        assertFalse(ScheduleEngine.wasMissed(s, next, now));
        assertEquals("delay is measured from the real due time", at("2026-11-02T09:00"), next.scheduledFor);
        assertEquals(now, next.at);
    }

    @Test
    public void alertThatRangIsNotMissed() {
        Schedule s = make(Schedule.REPEAT_DAILY, LocalDate.of(2026, 11, 1));
        s.nextTriggerAt = at("2026-11-02T09:00");
        s.lastRingAt = at("2026-11-02T09:00");
        long now = at("2026-11-02T11:00");
        assertFalse(ScheduleEngine.wasMissed(s, ScheduleEngine.nextEvent(s, now, UTC), now));
    }

    @Test
    public void futureAlertIsNotMissed() {
        Schedule s = make(Schedule.REPEAT_DAILY, LocalDate.of(2026, 11, 1));
        s.nextTriggerAt = at("2026-11-03T09:00");
        long now = at("2026-11-02T11:00");
        assertFalse(ScheduleEngine.wasMissed(s, ScheduleEngine.nextEvent(s, now, UTC), now));
    }

    @Test
    public void endTimeNeverBecomesNextStart() {
        Schedule s = make(Schedule.REPEAT_DAILY, LocalDate.of(2026, 11, 1));
        s.startMinute = 6 * 60;
        s.endMinute = 6 * 60 + 30;
        long atEnd = at("2026-11-02T06:30");
        assertEquals(at("2026-11-03T06:00"), ScheduleEngine.nextEvent(s, atEnd, UTC).at);
    }

    // ---- dashboard ----

    @Test
    public void badgesReflectDeadlineAndCompletion() {
        long now = at("2026-11-02T12:00");
        Schedule s = make(Schedule.REPEAT_DEADLINE, LocalDate.of(2026, 11, 1));
        s.deadlineAt = at("2026-11-20T17:00");
        s.nextTriggerAt = at("2026-11-13T09:00");
        assertEquals(ReminderStatus.UPCOMING, ReminderStatus.of(s, now, UTC));
        s.deadlineAt = at("2026-11-03T17:00");
        assertEquals(ReminderStatus.URGENT, ReminderStatus.of(s, now, UTC));
        s.deadlineAt = at("2026-11-02T17:00");
        assertEquals(ReminderStatus.DUE_TODAY, ReminderStatus.of(s, now, UTC));
        s.deadlineAt = at("2026-11-01T17:00");
        assertEquals(ReminderStatus.OVERDUE, ReminderStatus.of(s, now, UTC));
        s.status = Schedule.STATUS_COMPLETED;
        assertEquals(ReminderStatus.COMPLETED, ReminderStatus.of(s, now, UTC));
    }

    @Test
    public void overviewCountsAndNextAlarm() {
        long now = at("2026-11-02T12:00");
        Schedule daily = make(Schedule.REPEAT_DAILY, LocalDate.of(2026, 11, 1));
        daily.nextTriggerAt = at("2026-11-03T09:00");
        Schedule overdue = make(Schedule.REPEAT_DEADLINE, LocalDate.of(2026, 10, 1));
        overdue.type = Schedule.TYPE_TAX;
        overdue.deadlineAt = at("2026-11-01T17:00");
        Schedule done = make(Schedule.REPEAT_ONCE, LocalDate.of(2026, 10, 1));
        done.type = Schedule.TYPE_TASK;
        done.status = Schedule.STATUS_COMPLETED;
        Schedule appt = make(Schedule.REPEAT_ONCE, LocalDate.of(2026, 11, 2));
        appt.type = Schedule.TYPE_APPOINTMENT;
        appt.nextTriggerAt = at("2026-11-02T18:00");

        ReminderStatus.Overview o = ReminderStatus.overview(Arrays.asList(daily, overdue, done, appt), now, UTC);
        assertEquals(4, o.total);
        assertEquals(1, o.overdue);
        assertEquals(1, o.completed);
        assertEquals(2, o.pending);
        assertEquals(1, o.appointments);
        assertEquals(33, o.completionPercent());
        assertNotNull(o.next);
        assertEquals(appt, o.next);
    }

    // ---- tax details ----

    @Test
    public void financeDetailsRoundTripAndPickEarliestDeadline() {
        Finance f = new Finance();
        f.taxType = "Personal income tax";
        f.jurisdiction = "Nigeria";
        f.website = "https://example.org";
        f.filingDeadline = 2000;
        f.paymentDeadline = 1000;
        Finance back = Finance.parse(f.toJson());
        assertEquals("Personal income tax", back.taxType);
        assertEquals("https://example.org", back.website);
        assertEquals(1000, back.earliestDeadline());
        assertTrue(Finance.parse("not json").isEmpty());
        assertTrue(Finance.parse("").isEmpty());
    }
}
