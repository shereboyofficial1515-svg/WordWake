package com.wordwake.app.data;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** A single background thread for all database and scheduling work, so writes never race. */
public final class AppExecutors {

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "wordwake-io");
        t.setDaemon(true);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private AppExecutors() {}

    public static void io(Runnable r) {
        IO.execute(r);
    }

    public static <T> void io(Supplier<T> work, Consumer<T> onMain) {
        IO.execute(() -> {
            T result = work.get();
            MAIN.post(() -> onMain.accept(result));
        });
    }

    public static void main(Runnable r) {
        MAIN.post(r);
    }
}
