package com.wordwake.app.alarm;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CopyOnWriteArrayList;

/** In-process view of the ring session that is currently playing (at most one at a time). */
public final class ActiveAlarm {

    public static final class Session {
        public final long scheduleId;
        public final long occStart;
        public final long occEnd;
        public final long ringAt;
        public final long sessionEnd;
        public final long soundEnd;
        public final int kind;
        public final boolean test;
        public volatile boolean soundPlaying = true;
        public volatile boolean fallbackSound;
        public volatile boolean soundFailed;

        public Session(long scheduleId, long occStart, long occEnd, long ringAt, long sessionEnd,
                       long soundEnd, int kind, boolean test) {
            this.scheduleId = scheduleId;
            this.occStart = occStart;
            this.occEnd = occEnd;
            this.ringAt = ringAt;
            this.sessionEnd = sessionEnd;
            this.soundEnd = soundEnd;
            this.kind = kind;
            this.test = test;
        }
    }

    public interface Listener {
        /** Called on the main thread whenever the session starts, changes or ends. */
        void onActiveAlarmChanged(Session current);
    }

    private static volatile Session current;
    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private static final Handler main = new Handler(Looper.getMainLooper());

    private ActiveAlarm() {}

    public static Session get() {
        return current;
    }

    private static volatile long startingId;
    private static volatile long startingUntil;

    public static boolean isRinging(long scheduleId) {
        Session s = current;
        if (s != null && s.scheduleId == scheduleId && !s.test) return true;
        // A ring was requested and the service is still starting.
        return startingId == scheduleId && System.currentTimeMillis() < startingUntil;
    }

    static void markStarting(long scheduleId) {
        startingId = scheduleId;
        startingUntil = System.currentTimeMillis() + 10_000L;
    }

    static void clearStarting() {
        startingId = 0;
        startingUntil = 0;
    }

    static void set(Session s) {
        clearStarting();
        current = s;
        notifyListeners();
    }

    static void clear(Session expected) {
        if (expected == null || current == expected) {
            current = null;
            notifyListeners();
        }
    }

    static void notifyListeners() {
        Session s = current;
        main.post(() -> {
            for (Listener l : listeners) l.onActiveAlarmChanged(s);
        });
    }

    public static void addListener(Listener l) {
        listeners.addIfAbsent(l);
    }

    public static void removeListener(Listener l) {
        listeners.remove(l);
    }
}
