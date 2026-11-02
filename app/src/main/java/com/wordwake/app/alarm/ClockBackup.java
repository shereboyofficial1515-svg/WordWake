package com.wordwake.app.alarm;

import android.content.Context;
import android.content.Intent;
import android.provider.AlarmClock;

import com.wordwake.app.data.DiagLog;
import com.wordwake.app.engine.ScheduleEngine;
import com.wordwake.app.model.Schedule;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;

/**
 * Adds a matching alarm to the phone's own Clock app through Android's public alarm API.
 *
 * Some manufacturers' power managers hold back third-party alarms but not the system Clock
 * (observed: TECNO HiOS "Hiber" froze WordWake and withheld an exact alarm-clock alarm even with
 * the battery exemption and App Launch allowed). The Clock alarm cannot show WordWake's message,
 * and Android offers no way to change it later, so the user must edit or delete it in Clock.
 */
public final class ClockBackup {

    private ClockBackup() {}

    /** True when one Clock alarm can express this schedule. Clock has no dates beyond 24 hours. */
    public static boolean isSupported(Schedule s, long nowMs) {
        switch (s.repeatMode) {
            case Schedule.REPEAT_DAILY:
            case Schedule.REPEAT_DAYS:
            case Schedule.REPEAT_WEEKLY:
                return true;
            case Schedule.REPEAT_ONCE: {
                ZoneId zone = s.zone(ZoneId.systemDefault());
                LocalDate d = s.onceDate();
                long start = ScheduleEngine.windowFor(d, s.startMinute, s.endMinute, zone).start;
                return start > nowMs && start - nowMs < 24 * 60 * 60 * 1000L;
            }
            default:
                return false;
        }
    }

    public static Intent intent(Schedule s) {
        Intent i = new Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, s.startMinute / 60)
                .putExtra(AlarmClock.EXTRA_MINUTES, s.startMinute % 60)
                .putExtra(AlarmClock.EXTRA_MESSAGE, s.displayTitle())
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                .putExtra(AlarmClock.EXTRA_VIBRATE, s.vibrate);
        ArrayList<Integer> days = days(s);
        if (!days.isEmpty()) i.putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, days);
        return i;
    }

    private static ArrayList<Integer> days(Schedule s) {
        ArrayList<Integer> out = new ArrayList<>();
        if (s.repeatMode == Schedule.REPEAT_ONCE) return out;
        if (s.repeatMode == Schedule.REPEAT_WEEKLY) {
            out.add(calendarDay(s.onceDate().getDayOfWeek()));
            return out;
        }
        for (DayOfWeek d : DayOfWeek.values()) {
            if (s.repeatMode == Schedule.REPEAT_DAILY || (s.daysMask & Schedule.dayBit(d)) != 0) out.add(calendarDay(d));
        }
        return out;
    }

    /** java.util.Calendar numbering, which the alarm API uses: Sunday = 1 ... Saturday = 7. */
    private static int calendarDay(DayOfWeek d) {
        return d.getValue() % 7 + 1;
    }

    /** Returns false when no Clock app on this phone accepts the request. */
    public static boolean add(Context activityContext, Schedule s) {
        try {
            activityContext.startActivity(intent(s));
            DiagLog.log(activityContext, DiagLog.UPDATED, s.id, "clock backup added at=" + Instant.now().toEpochMilli());
            return true;
        } catch (RuntimeException e) {
            DiagLog.log(activityContext, DiagLog.SCHEDULE_FAILURE, s.id, "clock backup: " + e.getClass().getSimpleName());
            return false;
        }
    }
}
