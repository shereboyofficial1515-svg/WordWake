package com.wordwake.app.ui;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.wordwake.app.R;
import com.wordwake.app.alarm.AlarmScheduler;
import com.wordwake.app.alarm.ReliabilityService;
import com.wordwake.app.alarm.SystemTest;
import com.wordwake.app.data.AppExecutors;
import com.wordwake.app.data.AppSettings;
import com.wordwake.app.data.DiagLog;
import com.wordwake.app.data.ScheduleRepository;
import com.wordwake.app.model.Schedule;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Alarm Diagnostics: real status of the alarm system, a real end-to-end test, and exportable logs. */
public class DiagnosticsActivity extends AppCompatActivity {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private LinearLayout list;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final ActivityResultLauncher<String> exportLauncher = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("text/plain"), this::export);

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            load();
            handler.postDelayed(this, 2000);
        }
    };

    private static final class Snapshot {
        int total, enabled, registered;
        Schedule next;
        DiagLog.Entry lastTriggered, lastNotification, lastFailure, lastReboot, lastExit;
        List<DiagLog.Entry> recent = new ArrayList<>();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list_screen);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.diag_title);
        toolbar.setNavigationOnClickListener(v -> finish());
        list = findViewById(R.id.list);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
    }

    private void load() {
        final Context app = getApplicationContext();
        AppExecutors.io(() -> {
            Snapshot s = new Snapshot();
            for (Schedule r : new ScheduleRepository(app).getAll()) {
                s.total++;
                if (!r.enabled || r.nextTriggerAt <= 0) continue;
                s.enabled++;
                if (AlarmScheduler.isRegistered(app, r.id)) s.registered++;
                if (s.next == null || r.nextTriggerAt < s.next.nextTriggerAt) s.next = r;
            }
            s.lastTriggered = DiagLog.last(app, DiagLog.TRIGGERED);
            s.lastNotification = DiagLog.last(app, DiagLog.NOTIFICATION_POSTED);
            s.lastFailure = DiagLog.last(app, DiagLog.FAILURE_TYPES.toArray(new String[0]));
            s.lastReboot = DiagLog.last(app, DiagLog.REBOOT_RECOVERY);
            s.lastExit = DiagLog.last(app, DiagLog.PROCESS_EXIT);
            s.recent = DiagLog.recent(app, 25);
            return s;
        }, this::render);
    }

    private void render(Snapshot s) {
        if (isFinishing()) return;
        list.removeAllViews();

        list.addView(Ui.sectionHeader(this, getString(R.string.diag_test_header)));
        addTestCard();

        list.addView(Ui.sectionHeader(this, getString(R.string.diag_scheduling)));
        addRow(R.string.diag_total, String.valueOf(s.total));
        addRow(R.string.diag_registered, getString(R.string.diag_registered_value, s.registered, s.enabled));
        addRow(R.string.diag_next, s.next == null ? getString(R.string.diag_none)
                : TS.format(Instant.ofEpochMilli(s.next.nextTriggerAt)) + (s.next.nextExact ? "" : " " + getString(R.string.diag_inexact)));
        addRow(R.string.diag_engine, getString(s.registered == s.enabled ? R.string.diag_engine_ok : R.string.diag_engine_mismatch));

        list.addView(Ui.sectionHeader(this, getString(R.string.diag_recent)));
        addRow(R.string.diag_last_trigger, entryText(s.lastTriggered));
        addRow(R.string.diag_last_notification, entryText(s.lastNotification));
        addRow(R.string.diag_last_failure, s.lastFailure == null ? getString(R.string.diag_none)
                : s.lastFailure.type + " · " + TS.format(Instant.ofEpochMilli(s.lastFailure.at)) + "\n" + s.lastFailure.detail);
        addRow(R.string.diag_last_reboot, entryText(s.lastReboot));
        addRow(R.string.diag_last_exit, s.lastExit == null ? getString(R.string.diag_none) : describeExit(s.lastExit));

        list.addView(Ui.sectionHeader(this, getString(R.string.diag_permissions)));
        addRow(R.string.perm_exact_title, allowed(PermissionStatus.exactAlarmsAllowed(this)));
        addRow(R.string.perm_notif_title, allowed(PermissionStatus.notificationsAllowed(this)));
        addRow(R.string.perm_fsi_title, allowed(PermissionStatus.fullScreenAllowed(this)));
        addRow(R.string.perm_battery_title, getString(PermissionStatus.backgroundRestricted(this) ? R.string.state_restricted
                : PermissionStatus.ignoringBatteryOptimizations(this) ? R.string.state_unrestricted : R.string.diag_battery_optimized));
        addRow(R.string.diag_reliable, getString(ReliabilityService.isRunning() ? R.string.diag_reliable_running
                : ReliabilityService.isEnabled(this) ? R.string.diag_reliable_idle : R.string.diag_reliable_off));
        addRow(R.string.diag_device, android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + " · Android "
                + android.os.Build.VERSION.RELEASE);

        list.addView(Ui.sectionHeader(this, getString(R.string.diag_log)));
        MaterialButton exportButton = outlined(getString(R.string.diag_export), v -> exportLauncher.launch("wordwake-diagnostics.txt"));
        list.addView(exportButton);
        for (DiagLog.Entry e : s.recent) {
            TextView t = Ui.secondary(this, TS.format(Instant.ofEpochMilli(e.at)) + "  " + e.type
                    + (e.scheduleId != 0 ? " #" + e.scheduleId : "") + (e.detail.isEmpty() ? "" : "  " + e.detail));
            int p = Ui.dp(this, 16);
            t.setPadding(p, Ui.dp(this, 2), p, Ui.dp(this, 2));
            t.setTextSize(12);
            list.addView(t);
        }
    }

    private void addTestCard() {
        AppSettings st = new AppSettings(this);
        long expected = st.testExpectedAt();
        long fired = st.testFiredAt();
        long now = System.currentTimeMillis();
        int p = Ui.dp(this, 16);
        LinearLayout box = Ui.vertical(this);
        box.setPadding(p, 0, p, 0);
        box.addView(Ui.secondary(this, getString(R.string.diag_test_explain, SystemTest.LEAD_SECONDS)));
        MaterialButton run = new MaterialButton(this);
        run.setText(R.string.diag_run_test);
        run.setEnabled(expected == 0 || fired != 0 || now > expected + 120_000L);
        run.setOnClickListener(v -> {
            final Context app = getApplicationContext();
            AppExecutors.io(() -> SystemTest.start(app), at -> {
                if (at == 0) Toast.makeText(this, R.string.diag_test_register_failed, Toast.LENGTH_LONG).show();
                load();
            });
        });
        box.addView(run);
        if (expected > 0) {
            TextView result = Ui.text(this, testResult(st, expected, fired, now), com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
            result.setPadding(0, Ui.dp(this, 8), 0, 0);
            box.addView(result);
        }
        list.addView(box);
    }

    /** Reports only what was observed; audible output is explicitly marked as unverifiable. */
    private String testResult(AppSettings st, long expected, long fired, long now) {
        StringBuilder sb = new StringBuilder();
        if (fired == 0) {
            if (now < expected) {
                sb.append(getString(R.string.diag_test_waiting, (expected - now) / 1000));
            } else {
                sb.append(getString(R.string.diag_test_not_delivered, (now - expected) / 1000));
            }
            return sb.toString();
        }
        long delay = fired - expected;
        sb.append(getString(delay <= SystemTest.ON_TIME_MS ? R.string.diag_test_on_time : R.string.diag_test_late,
                delay / 1000.0));
        sb.append('\n').append(getString(st.testScreenWasOff() ? R.string.diag_test_screen_off : R.string.diag_test_screen_on));
        sb.append('\n').append(getString(st.testServiceAt() > 0 ? R.string.diag_test_service_ok : R.string.diag_test_service_missing));
        sb.append('\n').append(getString(st.testNotificationPosted() ? R.string.diag_test_notif_ok : R.string.diag_test_notif_missing));
        int audio = st.testAudio();
        sb.append('\n').append(getString(audio == SystemTest.AUDIO_ENGINE_RUNNING ? R.string.diag_test_audio_ok
                : audio == SystemTest.AUDIO_NOT_RUNNING ? R.string.diag_test_audio_failed : R.string.diag_test_audio_pending));
        sb.append('\n').append(getString(R.string.diag_test_audible_note));
        return sb.toString();
    }

    private String entryText(DiagLog.Entry e) {
        if (e == null) return getString(R.string.diag_none);
        return TS.format(Instant.ofEpochMilli(e.at)) + (e.detail.isEmpty() ? "" : "\n" + e.detail);
    }

    private String describeExit(DiagLog.Entry e) {
        return TS.format(Instant.ofEpochMilli(e.at)) + "\n" + e.detail;
    }

    private String allowed(boolean ok) {
        return getString(ok ? R.string.state_allowed : R.string.state_not_allowed);
    }

    private void addRow(int label, String value) {
        RowViews.row(list, getString(label), value, null);
    }

    private MaterialButton outlined(String text, android.view.View.OnClickListener click) {
        MaterialButton b = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        b.setText(text);
        b.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        int p = Ui.dp(this, 16);
        lp.setMargins(p, 0, p, Ui.dp(this, 8));
        b.setLayoutParams(lp);
        return b;
    }

    /** Writes status and logs. Titles and message text are deliberately left out. */
    private void export(Uri uri) {
        if (uri == null) return;
        final Context app = getApplicationContext();
        AppExecutors.io(() -> {
            StringBuilder sb = new StringBuilder();
            sb.append("WordWake diagnostics\nExported: ").append(TS.format(Instant.now())).append('\n');
            sb.append("Device: ").append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL)
                    .append(" Android ").append(android.os.Build.VERSION.RELEASE).append(" API ").append(android.os.Build.VERSION.SDK_INT).append('\n');
            sb.append("Exact alarms: ").append(PermissionStatus.exactAlarmsAllowed(app)).append('\n');
            sb.append("Notifications: ").append(PermissionStatus.notificationsAllowed(app)).append('\n');
            sb.append("Full screen: ").append(PermissionStatus.fullScreenAllowed(app)).append('\n');
            sb.append("Battery unrestricted: ").append(PermissionStatus.ignoringBatteryOptimizations(app)).append('\n');
            sb.append("Background restricted: ").append(PermissionStatus.backgroundRestricted(app)).append('\n');
            sb.append("Reliable delivery: enabled=").append(ReliabilityService.isEnabled(app))
                    .append(" running=").append(ReliabilityService.isRunning()).append("\n\n");
            for (Schedule s : new ScheduleRepository(app).getAll()) {
                sb.append("Schedule #").append(s.id).append(" enabled=").append(s.enabled).append(" repeat=").append(s.repeatMode)
                        .append(" mode=").append(s.alertMode).append(" next=").append(s.nextTriggerAt)
                        .append(" exact=").append(s.nextExact).append(" registered=").append(AlarmScheduler.isRegistered(app, s.id)).append('\n');
            }
            sb.append('\n');
            for (DiagLog.Entry e : DiagLog.recent(app, 3000)) {
                sb.append(TS.format(Instant.ofEpochMilli(e.at))).append('\t').append(e.type).append('\t')
                        .append(e.scheduleId).append('\t').append(e.detail).append('\n');
            }
            try (OutputStream out = app.getContentResolver().openOutputStream(uri)) {
                if (out == null) return false;
                out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                return true;
            } catch (java.io.IOException | SecurityException e) {
                return false;
            }
        }, ok -> Toast.makeText(this, ok ? R.string.diag_exported : R.string.diag_export_failed, Toast.LENGTH_LONG).show());
    }
}
