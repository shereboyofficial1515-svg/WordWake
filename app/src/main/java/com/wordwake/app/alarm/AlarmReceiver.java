package com.wordwake.app.alarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.wordwake.app.data.AppExecutors;

/** Receives AlarmManager broadcasts: an alarm is due, or a ring session reached its deadline. */
public class AlarmReceiver extends BroadcastReceiver {

    public static final String ACTION_FIRE = "com.wordwake.app.action.FIRE";
    public static final String ACTION_END = "com.wordwake.app.action.END";
    public static final String EXTRA_SCHEDULE_ID = "schedule_id";

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();
        final long id = intent.getLongExtra(EXTRA_SCHEDULE_ID, 0);
        if (id == 0 || action == null) return;
        final Context app = context.getApplicationContext();
        final PendingResult result = goAsync();
        AppExecutors.io(() -> {
            try {
                if (ACTION_FIRE.equals(action)) {
                    AlarmController.onFire(app, id);
                } else if (ACTION_END.equals(action)) {
                    AlarmController.endSession(app, id);
                }
            } finally {
                result.finish();
            }
        });
    }
}
