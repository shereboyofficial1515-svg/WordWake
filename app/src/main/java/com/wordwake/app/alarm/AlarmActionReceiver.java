package com.wordwake.app.alarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.wordwake.app.data.AppExecutors;

/** Stop / Snooze / Dismiss buttons on alarm notifications. */
public class AlarmActionReceiver extends BroadcastReceiver {

    public static final String ACTION_STOP = "com.wordwake.app.action.STOP";
    public static final String ACTION_SNOOZE = "com.wordwake.app.action.SNOOZE";
    public static final String ACTION_DISMISS = "com.wordwake.app.action.DISMISS";
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
                switch (action) {
                    case ACTION_STOP:
                        AlarmController.silence(app, id);
                        break;
                    case ACTION_SNOOZE:
                        AlarmController.snooze(app, id);
                        break;
                    case ACTION_DISMISS:
                        AlarmController.dismiss(app, id);
                        break;
                    default:
                        break;
                }
            } finally {
                result.finish();
            }
        });
    }
}
