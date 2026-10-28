package com.wordwake.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.LinearLayout;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.wordwake.app.R;
import com.wordwake.app.data.AppSettings;

public class SettingsActivity extends AppCompatActivity {

    private static final int[] SNOOZE = {0, 5, 10, 15, 20, 30};
    private static final int[] WINDOW = {5, 10, 15, 30, 45, 60, 90, 120};

    private AppSettings settings;
    private LinearLayout list;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        settings = new AppSettings(this);
        list = findViewById(R.id.settings_list);
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        list.removeAllViews();
        RowViews.header(list, getString(R.string.settings_defaults));
        RowViews.row(list, getString(R.string.settings_default_sound), settings.defaultSoundName(),
                v -> startActivity(new Intent(this, SoundLibraryActivity.class)));
        RowViews.row(list, getString(R.string.settings_default_snooze), minutesLabel(settings.defaultSnoozeMinutes(), true),
                v -> choose(R.string.settings_default_snooze, SNOOZE, settings.defaultSnoozeMinutes(), true,
                        m -> { settings.setDefaultSnoozeMinutes(m); render(); }));
        RowViews.row(list, getString(R.string.settings_default_window), minutesLabel(settings.defaultWindowMinutes(), false),
                v -> choose(R.string.settings_default_window, WINDOW, settings.defaultWindowMinutes(), false,
                        m -> { settings.setDefaultWindowMinutes(m); render(); }));
        RowViews.toggle(list, getString(R.string.settings_vibrate), getString(R.string.settings_vibrate_summary),
                settings.defaultVibrate(), settings::setDefaultVibrate);

        RowViews.header(list, getString(R.string.settings_display));
        RowViews.toggle(list, getString(R.string.settings_high_contrast), getString(R.string.settings_high_contrast_summary),
                settings.highContrast(), settings::setHighContrast);
        String[] sizes = {getString(R.string.text_normal), getString(R.string.text_large), getString(R.string.text_huge)};
        RowViews.row(list, getString(R.string.settings_text_size), sizes[settings.alarmTextSize()], v ->
                new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.settings_text_size)
                        .setSingleChoiceItems(sizes, settings.alarmTextSize(), (d, w) -> {
                            settings.setAlarmTextSize(w);
                            d.dismiss();
                            render();
                        })
                        .show());
        RowViews.toggle(list, getString(R.string.settings_keep_screen_on), getString(R.string.settings_keep_screen_on_summary),
                settings.keepScreenOn(), settings::setKeepScreenOn);
        RowViews.row(list, getString(R.string.settings_time_format), getString(R.string.settings_time_format_summary),
                v -> PermissionStatus.open(this, new Intent(Settings.ACTION_DATE_SETTINGS)));

        RowViews.header(list, getString(R.string.settings_reliability));
        RowViews.row(list, getString(R.string.permissions_title), getString(R.string.settings_permissions_summary),
                v -> startActivity(new Intent(this, PermissionsActivity.class)));
        RowViews.row(list, getString(R.string.settings_battery_guide), getString(R.string.settings_battery_guide_summary),
                v -> info(R.string.settings_battery_guide, R.string.battery_guide_text));

        RowViews.header(list, getString(R.string.settings_about));
        RowViews.row(list, getString(R.string.settings_privacy), getString(R.string.settings_privacy_summary),
                v -> info(R.string.settings_privacy, R.string.privacy_text));
        String version;
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            version = "";
        }
        RowViews.row(list, getString(R.string.app_name), getString(R.string.settings_version, version), null);
    }

    private String minutesLabel(int m, boolean zeroIsOff) {
        if (m == 0 && zeroIsOff) return getString(R.string.snooze_off);
        return Formats.duration(this, m);
    }

    interface Chosen { void chosen(int v); }

    private void choose(int title, int[] values, int current, boolean zeroIsOff, Chosen chosen) {
        String[] labels = new String[values.length];
        int checked = -1;
        for (int i = 0; i < values.length; i++) {
            labels[i] = minutesLabel(values[i], zeroIsOff);
            if (values[i] == current) checked = i;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setSingleChoiceItems(labels, checked, (d, w) -> {
                    chosen.chosen(values[w]);
                    d.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void info(int title, int text) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setMessage(text)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }
}
