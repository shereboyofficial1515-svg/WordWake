package com.wordwake.app.engine;

import com.wordwake.app.model.Schedule;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/** Dashboard badges and statistics, kept free of Android so they can be unit-tested. */
public final class ReminderStatus {

    public static final int UPCOMING = 0;
    public static final int DUE_TODAY = 1;
    public static final int URGENT = 2;
    public static final int OVERDUE = 3;
    public static final int COMPLETED = 4;
    public static final int PAUSED = 5;

    private static final long URGENT_MS = 48 * 60 * 60 * 1000L;

    private ReminderStatus() {}

    public static int of(Schedule s, long nowMs, ZoneId deviceZone) {
        if (s.isCompleted()) return COMPLETED;
        if (ScheduleEngine.isOverdue(s, nowMs)) return OVERDUE;
        ZoneId zone = s.zone(deviceZone);
        LocalDate today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate();
        if (s.deadlineAt > 0) {
            if (Instant.ofEpochMilli(s.deadlineAt).atZone(zone).toLocalDate().equals(today)) return DUE_TODAY;
            if (s.deadlineAt - nowMs <= URGENT_MS) return URGENT;
        }
        if (!s.enabled) return PAUSED;
        if (s.nextTriggerAt > 0 && Instant.ofEpochMilli(s.nextTriggerAt).atZone(zone).toLocalDate().equals(today)) {
            return DUE_TODAY;
        }
        return UPCOMING;
    }

    /** The time a reminder sorts by: its next alert, else its deadline, else last in the list. */
    public static long sortKey(Schedule s) {
        if (s.enabled && s.nextTriggerAt > 0) return s.nextTriggerAt;
        if (s.deadlineAt > 0 && !s.isCompleted()) return s.deadlineAt;
        return Long.MAX_VALUE;
    }

    public static final class Overview {
        public int total;
        public int today;
        public int appointments;
        public int tasksDueToday;
        public int overdue;
        public int completed;
        public int pending;
        public Schedule next;

        /** Finished tracked work as a share of all tracked work, 0..100. */
        public int completionPercent() {
            int tracked = completed + pending;
            return tracked == 0 ? 0 : Math.round(completed * 100f / tracked);
        }
    }

    public static Overview overview(List<Schedule> all, long nowMs, ZoneId deviceZone) {
        Overview o = new Overview();
        for (Schedule s : all) {
            o.total++;
            ZoneId zone = s.zone(deviceZone);
            LocalDate today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate();
            boolean alertToday = !s.isCompleted() && s.enabled && ScheduleEngine.occursOn(s, today, zone);
            boolean dueToday = s.deadlineAt > 0 && !s.isCompleted()
                    && Instant.ofEpochMilli(s.deadlineAt).atZone(zone).toLocalDate().equals(today);
            if (alertToday || dueToday) o.today++;
            if (s.type == Schedule.TYPE_APPOINTMENT && !s.isCompleted() && s.enabled && s.nextTriggerAt > 0) o.appointments++;
            if (s.isTracked() && (dueToday || (alertToday && s.deadlineAt == 0))) o.tasksDueToday++;
            if (ScheduleEngine.isOverdue(s, nowMs)) o.overdue++;
            if (s.isTracked()) {
                if (s.isCompleted()) o.completed++;
                else o.pending++;
            }
            if (s.enabled && s.nextTriggerAt > 0 && (o.next == null || s.nextTriggerAt < o.next.nextTriggerAt)) o.next = s;
        }
        return o;
    }
}
