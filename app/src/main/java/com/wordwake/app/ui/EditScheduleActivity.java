package com.wordwake.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.datepicker.CalendarConstraints;
import com.google.android.material.datepicker.DateValidatorPointForward;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;
import com.wordwake.app.R;
import com.wordwake.app.alarm.ActiveAlarm;
import com.wordwake.app.alarm.AlarmController;
import com.wordwake.app.alarm.AlarmScheduler;
import com.wordwake.app.data.AppExecutors;
import com.wordwake.app.data.AppSettings;
import com.wordwake.app.data.Presets;
import com.wordwake.app.data.ScheduleRepository;
import com.wordwake.app.engine.ScheduleEngine;
import com.wordwake.app.model.Schedule;
import com.wordwake.app.sound.AlarmPlayer;
import com.wordwake.app.sound.SoundCatalog;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Create or edit a scheduled message. */
public class EditScheduleActivity extends AppCompatActivity {

    public static final String EXTRA_ID = "schedule_id";
    public static final String EXTRA_PRESET = "preset";

    private static final int[] REMINDER_CHOICES = {2, 5, 10, 15, 20, 30, 60};
    private static final int[] SOUND_DURATION_CHOICES = {0, 1, 2, 3, 5, 10, 15, 30};
    private static final int[] SNOOZE_CHOICES = {0, 5, 10, 15, 20, 30};
    private static final int[] QUICK_DURATIONS = {15, 30, 60, 120};
    private static final int[] DAY_CHIP_IDS = {R.id.day_mon, R.id.day_tue, R.id.day_wed, R.id.day_thu,
            R.id.day_fri, R.id.day_sat, R.id.day_sun};

    private Schedule draft;
    private String originalSignature;
    private boolean isNew;

    private TextInputEditText titleInput, bodyInput;
    private TextInputLayout bodyLayout;
    private MaterialButtonToggleGroup repeatGroup, styleGroup;
    private View dateRow, daysRow, reminderRow, soundWarning, volumeWarning;
    private MaterialButton dateButton, startButton, endButton, soundButton;
    private ChipGroup daysGroup;
    private TextView windowSummary, reminderValue, soundDurationValue, snoozeValue, zoneValue, errorsText, soundWarningText;
    private Slider volumeSlider;
    private MaterialSwitch loopSwitch, vibrateSwitch, autoDismissSwitch, enabledSwitch;
    private NestedScrollView scroll;
    private boolean binding;

    private final ActivityResultLauncher<Intent> soundPicker = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    draft.soundUri = result.getData().getStringExtra(SoundLibraryActivity.RESULT_URI);
                    draft.soundName = result.getData().getStringExtra(SoundLibraryActivity.RESULT_NAME);
                    bindSound();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit);
        com.google.android.material.appbar.MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> attemptClose());
        findViews();
        wireListeners();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                attemptClose();
            }
        });

        long id = getIntent().getLongExtra(EXTRA_ID, 0);
        AppSettings settings = new AppSettings(this);
        if (savedInstanceState != null && savedInstanceState.containsKey("draft_sig")) {
            // Activity recreated (rotation etc.): views restore text; rebuild the draft from state.
            isNew = savedInstanceState.getBoolean("is_new");
            draft = SavedDraft.restore(savedInstanceState);
            originalSignature = savedInstanceState.getString("draft_sig");
            onLoaded();
        } else if (id != 0) {
            isNew = false;
            AppExecutors.io(() -> new ScheduleRepository(getApplicationContext()).get(id), s -> {
                if (s == null) {
                    Toast.makeText(this, R.string.schedule_not_found, Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }
                draft = s;
                originalSignature = signature(s);
                onLoaded();
            });
        } else {
            isNew = true;
            draft = getIntent().getBooleanExtra(EXTRA_PRESET, false)
                    ? Presets.motivation(settings) : Presets.newSchedule(settings);
            originalSignature = getIntent().getBooleanExtra(EXTRA_PRESET, false) ? "" : signature(draft);
            onLoaded();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (draft == null) return;
        readTextFields();
        SavedDraft.save(draft, out);
        out.putString("draft_sig", originalSignature);
        out.putBoolean("is_new", isNew);
    }

    private void findViews() {
        scroll = findViewById(R.id.scroll);
        titleInput = findViewById(R.id.title_input);
        bodyInput = findViewById(R.id.body_input);
        bodyLayout = findViewById(R.id.body_layout);
        repeatGroup = findViewById(R.id.repeat_group);
        styleGroup = findViewById(R.id.style_group);
        dateRow = findViewById(R.id.date_row);
        daysRow = findViewById(R.id.days_row);
        reminderRow = findViewById(R.id.reminder_row);
        dateButton = findViewById(R.id.date_button);
        startButton = findViewById(R.id.start_button);
        endButton = findViewById(R.id.end_button);
        soundButton = findViewById(R.id.sound_button);
        daysGroup = findViewById(R.id.days_group);
        windowSummary = findViewById(R.id.window_summary);
        reminderValue = findViewById(R.id.reminder_value);
        soundDurationValue = findViewById(R.id.sound_duration_value);
        snoozeValue = findViewById(R.id.snooze_value);
        zoneValue = findViewById(R.id.zone_value);
        errorsText = findViewById(R.id.errors_text);
        soundWarning = findViewById(R.id.sound_warning);
        soundWarningText = findViewById(R.id.sound_warning_text);
        volumeWarning = findViewById(R.id.volume_warning);
        volumeSlider = findViewById(R.id.volume_slider);
        loopSwitch = findViewById(R.id.loop_switch);
        vibrateSwitch = findViewById(R.id.vibrate_switch);
        autoDismissSwitch = findViewById(R.id.auto_dismiss_switch);
        enabledSwitch = findViewById(R.id.enabled_switch);
    }

    private void wireListeners() {
        repeatGroup.addOnButtonCheckedListener((g, checkedId, isChecked) -> {
            if (!isChecked || binding) return;
            if (checkedId == R.id.repeat_once) draft.repeatMode = Schedule.REPEAT_ONCE;
            else if (checkedId == R.id.repeat_daily) draft.repeatMode = Schedule.REPEAT_DAILY;
            else draft.repeatMode = Schedule.REPEAT_DAYS;
            bindSchedulePart();
        });
        styleGroup.addOnButtonCheckedListener((g, checkedId, isChecked) -> {
            if (!isChecked || binding) return;
            if (checkedId == R.id.style_continuous) {
                draft.reminderMinutes = 0;
            } else if (draft.reminderMinutes == 0) {
                draft.reminderMinutes = defaultReminderFor(ScheduleEngine.windowMinutes(draft.startMinute, draft.endMinute));
                if (draft.soundDurationMinutes == 0) draft.soundDurationMinutes = 1;
            }
            bindSchedulePart();
            bindSound();
        });
        for (int i = 0; i < DAY_CHIP_IDS.length; i++) {
            final int bit = Schedule.DAY_MONDAY << i;
            Chip chip = findViewById(DAY_CHIP_IDS[i]);
            chip.setText(DayOfWeek.of(i + 1).getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault()));
            chip.setOnCheckedChangeListener((b, checked) -> {
                if (binding) return;
                draft.daysMask = checked ? (draft.daysMask | bit) : (draft.daysMask & ~bit);
                bindSchedulePart();
            });
        }
        findViewById(R.id.days_weekdays).setOnClickListener(v -> { draft.daysMask = Schedule.WEEKDAYS; bindSchedulePart(); });
        findViewById(R.id.days_weekend).setOnClickListener(v -> { draft.daysMask = 0x60; bindSchedulePart(); });
        findViewById(R.id.days_all).setOnClickListener(v -> { draft.daysMask = Schedule.ALL_DAYS; bindSchedulePart(); });

        dateButton.setOnClickListener(v -> pickDate());
        startButton.setOnClickListener(v -> pickTime(true));
        endButton.setOnClickListener(v -> pickTime(false));
        int[] quickIds = {R.id.quick_15, R.id.quick_30, R.id.quick_60, R.id.quick_120};
        for (int i = 0; i < quickIds.length; i++) {
            final int mins = QUICK_DURATIONS[i];
            findViewById(quickIds[i]).setOnClickListener(v -> {
                draft.endMinute = (draft.startMinute + mins) % 1440;
                bindSchedulePart();
            });
        }
        findViewById(R.id.reminder_row).setOnClickListener(v -> chooseInt(R.string.reminder_every_title,
                REMINDER_CHOICES, draft.reminderMinutes, m -> getString(R.string.every_n_min, m), m -> {
                    draft.reminderMinutes = m;
                    bindSchedulePart();
                }));
        soundButton.setOnClickListener(v -> soundPicker.launch(new Intent(this, SoundLibraryActivity.class)
                .putExtra(SoundLibraryActivity.EXTRA_PICK, true)
                .putExtra(SoundLibraryActivity.EXTRA_CURRENT, draft.soundUri)));
        findViewById(R.id.sound_duration_row).setOnClickListener(v -> chooseInt(R.string.sound_duration_title,
                SOUND_DURATION_CHOICES, draft.soundDurationMinutes, this::soundDurationLabel, m -> {
                    draft.soundDurationMinutes = m;
                    bindSound();
                }));
        findViewById(R.id.snooze_row).setOnClickListener(v -> chooseInt(R.string.snooze_title,
                SNOOZE_CHOICES, draft.snoozeMinutes, this::snoozeLabel, m -> {
                    draft.snoozeMinutes = m;
                    bindOptions();
                }));
        findViewById(R.id.zone_row).setOnClickListener(v -> pickZone());
        findViewById(R.id.volume_settings).setOnClickListener(v -> PermissionStatus.open(this, PermissionStatus.soundSettings()));
        volumeSlider.addOnChangeListener((sl, value, fromUser) -> { if (fromUser) draft.volumePercent = (int) value; });
        loopSwitch.setOnCheckedChangeListener((b, c) -> { if (!binding) draft.loopSound = c; });
        vibrateSwitch.setOnCheckedChangeListener((b, c) -> { if (!binding) draft.vibrate = c; });
        autoDismissSwitch.setOnCheckedChangeListener((b, c) -> { if (!binding) draft.autoDismiss = c; });
        enabledSwitch.setOnCheckedChangeListener((b, c) -> { if (!binding) draft.enabled = c; });
        findViewById(R.id.save_button).setOnClickListener(v -> save());

        bodyInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) {
                bodyLayout.setHelperText(getString(R.string.body_counter, e.length(), ScheduleEngine.MAX_BODY_CHARS));
            }
        });
    }

    private void onLoaded() {
        setTitle(isNew ? R.string.title_new : R.string.title_edit);
        if (titleInput.getText() == null || titleInput.getText().length() == 0) titleInput.setText(draft.title);
        if (bodyInput.getText() == null || bodyInput.getText().length() == 0) bodyInput.setText(draft.body);
        bindSchedulePart();
        bindSound();
        bindOptions();
    }

    private void bindSchedulePart() {
        binding = true;
        repeatGroup.check(draft.repeatMode == Schedule.REPEAT_ONCE ? R.id.repeat_once
                : draft.repeatMode == Schedule.REPEAT_DAILY ? R.id.repeat_daily : R.id.repeat_days);
        dateRow.setVisibility(draft.repeatMode == Schedule.REPEAT_ONCE ? View.VISIBLE : View.GONE);
        daysRow.setVisibility(draft.repeatMode == Schedule.REPEAT_DAYS ? View.VISIBLE : View.GONE);
        dateButton.setText(Formats.date(draft.onceDate()));
        for (int i = 0; i < DAY_CHIP_IDS.length; i++) {
            ((Chip) findViewById(DAY_CHIP_IDS[i])).setChecked((draft.daysMask & (Schedule.DAY_MONDAY << i)) != 0);
        }
        startButton.setText(Formats.minuteOfDay(this, draft.startMinute));
        String end = Formats.minuteOfDay(this, draft.endMinute);
        if (ScheduleEngine.endsNextDay(draft.startMinute, draft.endMinute)) end += " " + getString(R.string.next_day_marker);
        endButton.setText(end);
        styleGroup.check(draft.reminderMinutes > 0 ? R.id.style_reminders : R.id.style_continuous);
        reminderRow.setVisibility(draft.reminderMinutes > 0 ? View.VISIBLE : View.GONE);
        reminderValue.setText(getString(R.string.every_n_min, draft.reminderMinutes));
        binding = false;
        updateSummary();
    }

    private void updateSummary() {
        int mins = ScheduleEngine.windowMinutes(draft.startMinute, draft.endMinute);
        if (mins == 0) {
            windowSummary.setText(R.string.err_same_start_end);
            windowSummary.setTextColor(getColor(R.color.error_text));
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.summary_starts, Formats.minuteOfDay(this, draft.startMinute)));
        sb.append('\n');
        String endText = Formats.minuteOfDay(this, draft.endMinute);
        if (ScheduleEngine.endsNextDay(draft.startMinute, draft.endMinute)) endText += " " + getString(R.string.next_day_marker);
        sb.append(getString(R.string.summary_ends, endText, Formats.duration(this, mins)));
        sb.append('\n');
        sb.append(getString(R.string.summary_repeats, Formats.repeat(this, draft)));
        if (draft.reminderMinutes > 0) {
            int count = (mins + draft.reminderMinutes - 1) / draft.reminderMinutes;
            sb.append('\n').append(getResources().getQuantityString(R.plurals.summary_reminders, count, count, draft.reminderMinutes));
        }
        windowSummary.setText(sb.toString());
        boolean tooLong = mins > ScheduleEngine.MAX_WINDOW_MINUTES;
        windowSummary.setTextColor(getColor(tooLong ? R.color.error_text : R.color.text_secondary));
        if (tooLong) windowSummary.append("\n" + getString(R.string.err_window_too_long));
    }

    private void bindSound() {
        binding = true;
        soundButton.setText(draft.soundName == null || draft.soundName.isEmpty() ? getString(R.string.sound_default) : draft.soundName);
        volumeSlider.setValue(Math.max(10, Math.min(100, Math.round(draft.volumePercent / 10f) * 10)));
        loopSwitch.setChecked(draft.loopSound);
        soundDurationValue.setText(soundDurationLabel(draft.soundDurationMinutes));
        binding = false;
        final String uri = draft.soundUri;
        AppExecutors.io(() -> SoundCatalog.isAvailable(getApplicationContext(), uri), ok -> {
            soundWarning.setVisibility(ok ? View.GONE : View.VISIBLE);
            soundWarningText.setText(R.string.sound_unavailable_edit);
        });
        volumeWarning.setVisibility(PermissionStatus.alarmVolumeLow(this) ? View.VISIBLE : View.GONE);
    }

    private void bindOptions() {
        binding = true;
        vibrateSwitch.setChecked(draft.vibrate);
        autoDismissSwitch.setChecked(draft.autoDismiss);
        enabledSwitch.setChecked(draft.enabled);
        snoozeValue.setText(snoozeLabel(draft.snoozeMinutes));
        zoneValue.setText(draft.zoneId == null || draft.zoneId.isEmpty()
                ? getString(R.string.zone_device, ZoneId.systemDefault().getId()) : draft.zoneId);
        binding = false;
    }

    private String soundDurationLabel(int m) {
        if (m == 0) return getString(draft != null && draft.reminderMinutes > 0 ? R.string.sound_until_next : R.string.sound_whole_window);
        return getResources().getQuantityString(R.plurals.minutes, m, m);
    }

    private String snoozeLabel(int m) {
        return m == 0 ? getString(R.string.snooze_off) : getResources().getQuantityString(R.plurals.minutes, m, m);
    }

    private static int defaultReminderFor(int windowMinutes) {
        if (windowMinutes <= 10) return 2;
        if (windowMinutes <= 30) return 5;
        if (windowMinutes <= 120) return 15;
        return 30;
    }

    // ---- pickers ----

    private void pickDate() {
        long selection = draft.onceDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        MaterialDatePicker<Long> picker = MaterialDatePicker.Builder.datePicker()
                .setTitleText(R.string.pick_date)
                .setSelection(selection)
                .setCalendarConstraints(new CalendarConstraints.Builder()
                        .setValidator(DateValidatorPointForward.from(
                                LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()))
                        .build())
                .build();
        picker.addOnPositiveButtonClickListener(ms -> {
            draft.startDate = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay();
            bindSchedulePart();
        });
        picker.show(getSupportFragmentManager(), "date");
    }

    private void pickTime(boolean start) {
        int minute = start ? draft.startMinute : draft.endMinute;
        MaterialTimePicker picker = new MaterialTimePicker.Builder()
                .setTimeFormat(DateFormat.is24HourFormat(this) ? TimeFormat.CLOCK_24H : TimeFormat.CLOCK_12H)
                .setHour(minute / 60)
                .setMinute(minute % 60)
                .setTitleText(start ? R.string.pick_start : R.string.pick_end)
                .build();
        picker.addOnPositiveButtonClickListener(v -> {
            int m = picker.getHour() * 60 + picker.getMinute();
            if (start) {
                // Keep the same window length when the start moves.
                int len = ScheduleEngine.windowMinutes(draft.startMinute, draft.endMinute);
                draft.startMinute = m;
                if (len > 0) draft.endMinute = (m + len) % 1440;
            } else {
                draft.endMinute = m;
            }
            bindSchedulePart();
        });
        picker.show(getSupportFragmentManager(), start ? "start" : "end");
    }

    interface IntLabel { String label(int v); }
    interface IntChosen { void chosen(int v); }

    private void chooseInt(int title, int[] values, int current, IntLabel label, IntChosen chosen) {
        String[] labels = new String[values.length];
        int checked = -1;
        for (int i = 0; i < values.length; i++) {
            labels[i] = label.label(values[i]);
            if (values[i] == current) checked = i;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    chosen.chosen(values[which]);
                    d.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void pickZone() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_zone, null);
        EditText filter = view.findViewById(R.id.zone_filter);
        ListView list = view.findViewById(R.id.zone_list);
        List<String> zones = new ArrayList<>(ZoneId.getAvailableZoneIds());
        Collections.sort(zones);
        String deviceLabel = getString(R.string.zone_device, ZoneId.systemDefault().getId());
        zones.add(0, deviceLabel);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, zones);
        list.setAdapter(adapter);
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.zone_title)
                .setView(view)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        filter.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { adapter.getFilter().filter(s); }
            @Override public void afterTextChanged(Editable e) {}
        });
        list.setOnItemClickListener((p, v, pos, id) -> {
            String picked = adapter.getItem(pos);
            draft.zoneId = deviceLabel.equals(picked) ? "" : picked;
            bindOptions();
            bindSchedulePart();
            dialog.dismiss();
        });
        dialog.show();
    }

    // ---- save ----

    private void readTextFields() {
        draft.title = titleInput.getText() == null ? "" : titleInput.getText().toString();
        draft.body = bodyInput.getText() == null ? "" : bodyInput.getText().toString();
    }

    private void save() {
        if (draft == null) return;
        readTextFields();
        List<Integer> errors = ScheduleEngine.validate(draft, System.currentTimeMillis(), ZoneId.systemDefault());
        if (!draft.enabled) errors.remove(Integer.valueOf(ScheduleEngine.ERR_IN_PAST));
        if (!errors.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int e : errors) {
                if (sb.length() > 0) sb.append('\n');
                sb.append("• ").append(errorText(e));
            }
            errorsText.setText(sb.toString());
            errorsText.setVisibility(View.VISIBLE);
            scroll.post(() -> scroll.smoothScrollTo(0, errorsText.getTop()));
            return;
        }
        errorsText.setVisibility(View.GONE);
        final Schedule toSave = draft.copy();
        final android.content.Context app = getApplicationContext();
        AppExecutors.io(() -> {
            ScheduleRepository repo = new ScheduleRepository(app);
            if (toSave.id != 0) {
                Schedule stored = repo.get(toSave.id);
                if (stored == null) return null; // deleted meanwhile: do not resurrect
                if (timingChanged(stored, toSave) || !stored.enabled) {
                    if (ActiveAlarm.isRinging(toSave.id)) AlarmController.dismiss(app, toSave.id);
                    toSave.resetRuntimeState();
                    skipInProgressOccurrence(toSave);
                } else {
                    copyRuntimeState(stored, toSave);
                }
            } else {
                toSave.resetRuntimeState();
                skipInProgressOccurrence(toSave);
            }
            if (repo.save(toSave) == 0) return null;
            if (toSave.enabled) {
                AlarmScheduler.reschedule(app, toSave);
            } else {
                AlarmController.onDisabledOrDeleted(app, toSave.id);
            }
            return repo.get(toSave.id);
        }, saved -> {
            if (saved == null) {
                Toast.makeText(this, R.string.schedule_not_found, Toast.LENGTH_LONG).show();
                finish();
                return;
            }
            String msg;
            if (!saved.enabled) msg = getString(R.string.saved_disabled);
            else if (saved.nextTriggerAt > 0) {
                msg = getString(R.string.saved_next, Formats.dateTime(this, saved.nextTriggerAt));
                if (!saved.nextExact) msg += " " + getString(R.string.saved_inexact);
            } else msg = getString(R.string.saved_no_next);
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            finish();
        });
    }

    /**
     * A schedule saved while its window is already running starts at the next occurrence, rather
     * than ringing immediately as a "late" alarm.
     */
    static void skipInProgressOccurrence(Schedule s) {
        ScheduleEngine.Event ev = ScheduleEngine.nextEvent(s, System.currentTimeMillis(), ZoneId.systemDefault());
        if (ev != null && ev.kind == ScheduleEngine.KIND_LATE_START) s.dismissedOcc = ev.occStart;
    }

    private static boolean timingChanged(Schedule a, Schedule b) {
        return a.repeatMode != b.repeatMode || a.startDate != b.startDate || a.startMinute != b.startMinute
                || a.endMinute != b.endMinute || a.daysMask != b.daysMask || a.reminderMinutes != b.reminderMinutes
                || !a.zoneId.equals(b.zoneId);
    }

    private static void copyRuntimeState(Schedule from, Schedule to) {
        to.occStart = from.occStart;
        to.lastRingAt = from.lastRingAt;
        to.dismissedOcc = from.dismissedOcc;
        to.snoozeUntil = from.snoozeUntil;
        to.ringActiveUntil = from.ringActiveUntil;
        to.lastOutcome = from.lastOutcome;
        to.createdAt = from.createdAt;
    }

    private String errorText(int code) {
        switch (code) {
            case ScheduleEngine.ERR_EMPTY_MESSAGE: return getString(R.string.err_empty_message);
            case ScheduleEngine.ERR_SAME_START_END: return getString(R.string.err_same_start_end);
            case ScheduleEngine.ERR_WINDOW_TOO_LONG: return getString(R.string.err_window_too_long);
            case ScheduleEngine.ERR_NO_DAYS: return getString(R.string.err_no_days);
            case ScheduleEngine.ERR_IN_PAST: return getString(R.string.err_in_past);
            case ScheduleEngine.ERR_INTERVAL_TOO_LONG: return getString(R.string.err_interval_too_long);
            case ScheduleEngine.ERR_MESSAGE_TOO_LONG: return getString(R.string.err_message_too_long, ScheduleEngine.MAX_BODY_CHARS);
            default: return getString(R.string.err_bad_time);
        }
    }

    private void attemptClose() {
        if (draft == null) {
            finish();
            return;
        }
        readTextFields();
        if (signature(draft).equals(originalSignature)) {
            finish();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.discard_title)
                .setMessage(R.string.discard_message)
                .setNegativeButton(R.string.keep_editing, null)
                .setNeutralButton(R.string.discard, (d, w) -> finish())
                .setPositiveButton(R.string.save, (d, w) -> save())
                .show();
    }

    private static String signature(Schedule s) {
        return s.title + "\u0001" + s.body + "\u0001" + s.repeatMode + "," + s.startDate + "," + s.startMinute + ","
                + s.endMinute + "," + s.daysMask + "," + s.zoneId + "," + s.reminderMinutes + "," + s.soundUri + ","
                + s.volumePercent + "," + s.loopSound + "," + s.soundDurationMinutes + "," + s.vibrate + ","
                + s.snoozeMinutes + "," + s.autoDismiss + "," + s.enabled;
    }

    /** Minimal Bundle persistence of the draft across configuration changes. */
    static final class SavedDraft {
        static void save(Schedule s, Bundle b) {
            b.putLong("d_id", s.id);
            b.putString("d_title", s.title);
            b.putString("d_body", s.body);
            b.putInt("d_repeat", s.repeatMode);
            b.putLong("d_date", s.startDate);
            b.putInt("d_start", s.startMinute);
            b.putInt("d_end", s.endMinute);
            b.putInt("d_days", s.daysMask);
            b.putString("d_zone", s.zoneId);
            b.putInt("d_rem", s.reminderMinutes);
            b.putString("d_uri", s.soundUri);
            b.putString("d_sname", s.soundName);
            b.putInt("d_vol", s.volumePercent);
            b.putBoolean("d_loop", s.loopSound);
            b.putInt("d_sdur", s.soundDurationMinutes);
            b.putBoolean("d_vib", s.vibrate);
            b.putInt("d_snooze", s.snoozeMinutes);
            b.putBoolean("d_auto", s.autoDismiss);
            b.putBoolean("d_enabled", s.enabled);
            b.putLong("d_created", s.createdAt);
        }

        static Schedule restore(Bundle b) {
            Schedule s = new Schedule();
            s.id = b.getLong("d_id");
            s.title = b.getString("d_title", "");
            s.body = b.getString("d_body", "");
            s.repeatMode = b.getInt("d_repeat");
            s.startDate = b.getLong("d_date");
            s.startMinute = b.getInt("d_start");
            s.endMinute = b.getInt("d_end");
            s.daysMask = b.getInt("d_days");
            s.zoneId = b.getString("d_zone", "");
            s.reminderMinutes = b.getInt("d_rem");
            s.soundUri = b.getString("d_uri", "");
            s.soundName = b.getString("d_sname", "");
            s.volumePercent = b.getInt("d_vol", 100);
            s.loopSound = b.getBoolean("d_loop", true);
            s.soundDurationMinutes = b.getInt("d_sdur");
            s.vibrate = b.getBoolean("d_vib", true);
            s.snoozeMinutes = b.getInt("d_snooze", 10);
            s.autoDismiss = b.getBoolean("d_auto", true);
            s.enabled = b.getBoolean("d_enabled", true);
            s.createdAt = b.getLong("d_created");
            return s;
        }
    }
}
