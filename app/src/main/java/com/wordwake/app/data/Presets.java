package com.wordwake.app.data;

import com.wordwake.app.model.Schedule;

import java.time.LocalDate;

/** Editable example content. Never forced on the user. */
public final class Presets {

    private Presets() {}

    public static final String MOTIVATION_TITLE = "Good Morning Motivation";

    public static final String MOTIVATION_BODY =
            "Good morning. Today is a new opportunity.\n\n"
            + "I am disciplined. I am consistent. I am learning and improving every day. "
            + "Every video I create brings me closer to my goals. Every business I contact is "
            + "another opportunity.\n\n"
            + "I am building a successful future through hard work and persistence. I do not give "
            + "up because results take time. My skills are becoming more valuable every day.\n\n"
            + "I attract the right opportunities and the right customers. I choose action over "
            + "excuses. Today, I will do what moves me forward.\n\n"
            + "I believe in my ability to succeed. One day, I will look back and be grateful that "
            + "I never quit.";

    /** A new schedule filled with the user's defaults. */
    public static Schedule newSchedule(AppSettings settings) {
        Schedule s = new Schedule();
        s.repeatMode = Schedule.REPEAT_DAILY;
        s.daysMask = Schedule.ALL_DAYS;
        s.startMinute = 6 * 60;
        s.endMinute = (s.startMinute + settings.defaultWindowMinutes()) % 1440;
        s.startDate = LocalDate.now().plusDays(1).toEpochDay();
        s.soundUri = settings.defaultSoundUri();
        s.soundName = settings.defaultSoundName();
        s.snoozeMinutes = settings.defaultSnoozeMinutes();
        s.vibrate = settings.defaultVibrate();
        s.enabled = true;
        return s;
    }

    public static Schedule motivation(AppSettings settings) {
        Schedule s = newSchedule(settings);
        s.title = MOTIVATION_TITLE;
        s.body = MOTIVATION_BODY;
        s.startMinute = 6 * 60;
        s.endMinute = 6 * 60 + 30;
        return s;
    }
}
