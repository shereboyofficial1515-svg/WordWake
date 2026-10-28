package com.wordwake.app.ui;

import android.Manifest;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.wordwake.app.R;
import com.wordwake.app.alarm.AlarmScheduler;
import com.wordwake.app.data.AppExecutors;
import com.wordwake.app.data.AppSettings;
import com.wordwake.app.sound.AlarmPlayer;

/**
 * First-run onboarding and the in-app permission status page. Each item explains what it is for
 * and what happens without it; permissions are only requested when the user taps the button.
 */
public class PermissionsActivity extends AppCompatActivity {

    public static final String EXTRA_ONBOARDING = "onboarding";

    private AppSettings settings;
    private LinearLayout list;
    private boolean onboarding;

    private final ActivityResultLauncher<String> notificationRequest = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> render());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_permissions);
        settings = new AppSettings(this);
        onboarding = getIntent().getBooleanExtra(EXTRA_ONBOARDING, false);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finishOnboarding());
        toolbar.setTitle(onboarding ? R.string.onboarding_title : R.string.permissions_title);
        list = findViewById(R.id.perm_list);
        View intro = findViewById(R.id.onboarding_intro);
        intro.setVisibility(onboarding ? View.VISIBLE : View.GONE);
        View cont = findViewById(R.id.continue_button);
        cont.setVisibility(onboarding ? View.VISIBLE : View.GONE);
        cont.setOnClickListener(v -> finishOnboarding());
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                finishOnboarding();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
        // Permission changes (e.g. exact alarms) may change how alarms must be registered.
        AppExecutors.io(() -> AlarmScheduler.reconcileAll(getApplicationContext(), "permissions screen"));
    }

    private void finishOnboarding() {
        if (onboarding) settings.setOnboardingDone(true);
        finish();
    }

    private void render() {
        list.removeAllViews();

        // 1. Notifications
        boolean notif = PermissionStatus.notificationsAllowed(this);
        String notifAction = null;
        View.OnClickListener notifClick = null;
        if (!notif) {
            boolean canAsk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    && !settings.notificationPermissionAsked() && !PermissionStatus.alarmChannelLowered(this);
            if (canAsk) {
                notifAction = getString(R.string.perm_allow);
                notifClick = v -> {
                    settings.setNotificationPermissionAsked();
                    notificationRequest.launch(Manifest.permission.POST_NOTIFICATIONS);
                };
            } else {
                notifAction = getString(R.string.perm_open_settings);
                notifClick = v -> PermissionStatus.open(this, PermissionStatus.appNotificationSettings(this));
            }
        }
        RowViews.statusCard(list, getString(R.string.perm_notif_title),
                getString(notif ? R.string.state_allowed : R.string.state_not_allowed), notif,
                getString(notif ? R.string.perm_notif_ok : R.string.perm_notif_missing), notifAction, notifClick);

        // 2. Exact alarms
        boolean exact = PermissionStatus.exactAlarmsAllowed(this);
        RowViews.statusCard(list, getString(R.string.perm_exact_title),
                getString(exact ? R.string.state_allowed : R.string.state_not_allowed), exact,
                getString(exact ? R.string.perm_exact_ok : R.string.perm_exact_missing),
                exact ? null : getString(R.string.perm_open_settings),
                v -> PermissionStatus.open(this, PermissionStatus.exactAlarmSettings(this)));

        // 3. Full-screen alarm display
        boolean fsi = PermissionStatus.fullScreenAllowed(this);
        RowViews.statusCard(list, getString(R.string.perm_fsi_title),
                getString(fsi ? R.string.state_allowed : R.string.state_not_allowed), fsi,
                getString(fsi ? R.string.perm_fsi_ok : R.string.perm_fsi_missing),
                fsi ? null : getString(R.string.perm_open_settings),
                v -> PermissionStatus.open(this, PermissionStatus.fullScreenSettings(this)));

        // 4. Background restriction (only shown when it is a problem)
        if (PermissionStatus.backgroundRestricted(this)) {
            RowViews.statusCard(list, getString(R.string.perm_bg_title), getString(R.string.state_restricted), false,
                    getString(R.string.perm_bg_missing), getString(R.string.perm_open_settings),
                    v -> PermissionStatus.open(this, PermissionStatus.appDetails(this)));
        }

        // 5. Alarm volume
        int pct = Math.round(AlarmPlayer.alarmStreamLevel(this) * 100);
        boolean volOk = !PermissionStatus.alarmVolumeLow(this);
        RowViews.statusCard(list, getString(R.string.perm_volume_title), getString(R.string.state_percent, pct), volOk,
                getString(volOk ? R.string.perm_volume_ok : R.string.perm_volume_low),
                volOk ? null : getString(R.string.perm_open_sound_settings),
                v -> PermissionStatus.open(this, PermissionStatus.soundSettings()));

        // 6. Battery optimisation: required on phones whose power managers hold back alarms.
        boolean battery = PermissionStatus.ignoringBatteryOptimizations(this);
        boolean strict = PermissionStatus.aggressiveOem();
        String batteryText = getString(strict && !battery ? R.string.perm_battery_required_text : R.string.perm_battery_text);
        if (PermissionStatus.powerSaveOn(this)) batteryText += "\n\n" + getString(R.string.perm_power_save_on);
        RowViews.statusCard(list, getString(R.string.perm_battery_title),
                getString(battery ? R.string.state_unrestricted : strict ? R.string.state_required : R.string.state_recommended),
                battery, batteryText,
                battery ? null : getString(R.string.perm_allow_background),
                v -> PermissionStatus.open(this, PermissionStatus.requestBatteryExemption(this)));
        if (strict) {
            RowViews.statusCard(list, getString(R.string.perm_autostart_title), getString(R.string.state_check_manually), false,
                    getString(R.string.perm_autostart_text), getString(R.string.perm_open_app_settings),
                    v -> PermissionStatus.open(this, PermissionStatus.appDetails(this)));
        }

        // 7. Honest limits
        RowViews.header(list, getString(R.string.limits_header));
        TextView limits = new TextView(this);
        limits.setText(R.string.limits_text);
        limits.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        limits.setTextColor(getColor(R.color.text_secondary));
        int pad = Math.round(16 * getResources().getDisplayMetrics().density);
        limits.setPadding(pad, 0, pad, pad);
        list.addView(limits);
    }
}
