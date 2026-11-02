package com.wordwake.app.alarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.wordwake.app.data.AppExecutors;
import com.wordwake.app.data.DiagLog;
import com.wordwake.app.data.ScheduleRepository;

/**
 * Re-registers alarms after events that clear or invalidate them: reboot, app update, manual
 * clock change, time-zone change, and exact-alarm permission changes. AlarmManager alarms do
 * not survive a reboot, so this is required for recurring schedules to keep working.
 */
public class SystemEventReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        final String reason = intent.getAction() == null ? "unknown" : intent.getAction();
        final Context app = context.getApplicationContext();
        final PendingResult result = goAsync();
        AppExecutors.io(() -> {
            try {
                AlarmScheduler.reconcileAll(app, reason);
                if (reason.contains("BOOT") || reason.contains("QUICKBOOT")) {
                    int restored = 0;
                    for (com.wordwake.app.model.Schedule s : new ScheduleRepository(app).getAll()) {
                        if (s.enabled && AlarmScheduler.isRegistered(app, s.id)) restored++;
                    }
                    DiagLog.log(app, DiagLog.REBOOT_RECOVERY, 0, "trigger=" + reason + " restored=" + restored);
                }
            } finally {
                result.finish();
            }
        });
    }
}
