package com.wordwake.app.ui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.wordwake.app.R;
import com.wordwake.app.alarm.AlarmScheduler;
import com.wordwake.app.alarm.ReliabilityService;
import com.wordwake.app.data.AppExecutors;
import com.wordwake.app.data.AppSettings;
import com.wordwake.app.data.BackupManager;

public class SettingsFragment extends Fragment {

    private static final int[] SNOOZE = {0, 5, 10, 15, 20, 30};
    private static final int[] WINDOW = {5, 10, 15, 30, 45, 60, 90, 120};

    private AppSettings settings;
    private LinearLayout list;

    private final ActivityResultLauncher<String> backupLauncher = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/zip"), this::backup);
    private final ActivityResultLauncher<String[]> restoreLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), this::confirmRestore);

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle state) {
        settings = new AppSettings(requireContext());
        list = v.findViewById(R.id.settings_list);
    }

    @Override
    public void onResume() {
        super.onResume();
        render();
    }

    private void go(Class<?> activity) {
        startActivity(new Intent(requireContext(), activity));
    }

    private void render() {
        Context c = requireContext();
        list.removeAllViews();

        RowViews.header(list, getString(R.string.settings_reliability));
        RowViews.row(list, getString(R.string.diag_title), getString(R.string.settings_diag_summary), v -> go(DiagnosticsActivity.class));
        RowViews.toggle(list, getString(R.string.settings_reliable), getString(R.string.settings_reliable_summary),
                ReliabilityService.isEnabled(c), checked -> {
                    settings.setReliableDelivery(checked);
                    final Context app = c.getApplicationContext();
                    AppExecutors.io(() -> AlarmScheduler.reconcileAll(app, "reliable delivery changed"));
                });
        RowViews.row(list, getString(R.string.permissions_title), getString(R.string.settings_permissions_summary), v -> go(PermissionsActivity.class));
        RowViews.row(list, getString(R.string.settings_battery_guide), getString(R.string.settings_battery_guide_summary),
                v -> info(R.string.settings_battery_guide, R.string.battery_guide_text));

        RowViews.header(list, getString(R.string.settings_content));
        RowViews.row(list, getString(R.string.categories_title), getString(R.string.settings_categories_summary), v -> go(CategoriesActivity.class));
        RowViews.row(list, getString(R.string.quotes_title), getString(R.string.settings_quotes_summary), v -> go(QuotesActivity.class));
        RowViews.row(list, getString(R.string.history_title), getString(R.string.settings_history_summary), v -> go(HistoryActivity.class));

        RowViews.header(list, getString(R.string.settings_defaults));
        RowViews.row(list, getString(R.string.settings_default_sound), settings.defaultSoundName(), v -> go(SoundLibraryActivity.class));
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
                new MaterialAlertDialogBuilder(c)
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
                v -> PermissionStatus.open(c, new Intent(Settings.ACTION_DATE_SETTINGS)));

        RowViews.header(list, getString(R.string.settings_backup));
        RowViews.row(list, getString(R.string.settings_backup_export), getString(R.string.settings_backup_export_summary),
                v -> backupLauncher.launch("wordwake-backup.zip"));
        RowViews.row(list, getString(R.string.settings_backup_restore), getString(R.string.settings_backup_restore_summary),
                v -> restoreLauncher.launch(new String[]{"application/zip", "application/octet-stream"}));

        RowViews.header(list, getString(R.string.settings_about));
        RowViews.row(list, getString(R.string.settings_privacy), getString(R.string.settings_privacy_summary),
                v -> info(R.string.settings_privacy, R.string.privacy_text));
        String version;
        try {
            version = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            version = "";
        }
        RowViews.row(list, getString(R.string.app_name), getString(R.string.settings_version, version), null);
    }

    private void backup(Uri uri) {
        if (uri == null) return;
        final Context app = requireContext().getApplicationContext();
        AppExecutors.io(() -> BackupManager.export(app, uri), ok -> {
            if (isAdded()) Toast.makeText(requireContext(), ok ? R.string.backup_done : R.string.backup_failed, Toast.LENGTH_LONG).show();
        });
    }

    private void confirmRestore(Uri uri) {
        if (uri == null) return;
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.restore_title)
                .setMessage(R.string.restore_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.restore_confirm, (d, w) -> {
                    final Context app = requireContext().getApplicationContext();
                    AppExecutors.io(() -> BackupManager.restore(app, uri), ok -> {
                        if (isAdded()) Toast.makeText(requireContext(), ok ? R.string.restore_done : R.string.restore_failed, Toast.LENGTH_LONG).show();
                    });
                })
                .show();
    }

    private String minutesLabel(int m, boolean zeroIsOff) {
        if (m == 0 && zeroIsOff) return getString(R.string.snooze_off);
        return Formats.duration(requireContext(), m);
    }

    interface Chosen { void chosen(int v); }

    private void choose(int title, int[] values, int current, boolean zeroIsOff, Chosen chosen) {
        String[] labels = new String[values.length];
        int checked = -1;
        for (int i = 0; i < values.length; i++) {
            labels[i] = minutesLabel(values[i], zeroIsOff);
            if (values[i] == current) checked = i;
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(title)
                .setSingleChoiceItems(labels, checked, (d, w) -> {
                    chosen.chosen(values[w]);
                    d.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void info(int title, int text) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(title)
                .setMessage(text)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }
}
