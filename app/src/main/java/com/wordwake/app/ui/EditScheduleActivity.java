package com.wordwake.app.ui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
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
import com.wordwake.app.data.AttachmentStore;
import com.wordwake.app.data.ContentRepository;
import com.wordwake.app.data.DiagLog;
import com.wordwake.app.data.Presets;
import com.wordwake.app.data.ScheduleRepository;
import com.wordwake.app.engine.ScheduleEngine;
import com.wordwake.app.model.Attachment;
import com.wordwake.app.model.Category;
import com.wordwake.app.model.ChecklistItem;
import com.wordwake.app.model.Finance;
import com.wordwake.app.model.Schedule;
import com.wordwake.app.sound.SoundCatalog;

import java.io.File;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Create Reminder: a structured reminder, organized into collapsible sections. */
public class EditScheduleActivity extends AppCompatActivity {

    public static final String EXTRA_ID = "schedule_id";
    public static final String EXTRA_PRESET = "preset";
    public static final String EXTRA_TYPE = "type";

    private static final int[] REMINDER_CHOICES = {5, 10, 15, 30};
    private static final int[] SOUND_DURATION_CHOICES = {0, 1, 2, 3, 5, 10, 15, 30};
    private static final int[] SNOOZE_CHOICES = {0, 5, 10, 15, 20, 30};
    private static final int[] DAY_CHIP_IDS = {R.id.day_mon, R.id.day_tue, R.id.day_wed, R.id.day_thu,
            R.id.day_fri, R.id.day_sat, R.id.day_sun};
    private static final int[][] TYPE_CHIPS = {
            {Schedule.TYPE_ALARM, R.id.type_alarm}, {Schedule.TYPE_REMINDER, R.id.type_reminder},
            {Schedule.TYPE_TASK, R.id.type_task}, {Schedule.TYPE_APPOINTMENT, R.id.type_appointment},
            {Schedule.TYPE_DEADLINE, R.id.type_deadline}, {Schedule.TYPE_TAX, R.id.type_tax},
            {Schedule.TYPE_BILL, R.id.type_bill}, {Schedule.TYPE_QUOTE, R.id.type_quote}};
    private static final int[][] REPEAT_CHIPS = {
            {Schedule.REPEAT_ONCE, R.id.repeat_once}, {Schedule.REPEAT_DAILY, R.id.repeat_daily},
            {Schedule.REPEAT_DAYS, R.id.repeat_days}, {Schedule.REPEAT_WEEKLY, R.id.repeat_weekly},
            {Schedule.REPEAT_MONTHLY, R.id.repeat_monthly}, {Schedule.REPEAT_YEARLY, R.id.repeat_yearly},
            {Schedule.REPEAT_CUSTOM_DATES, R.id.repeat_custom}, {Schedule.REPEAT_DEADLINE, R.id.repeat_deadline}};

    private Schedule draft;
    private Finance finance = new Finance();
    private String originalSignature;
    private boolean isNew;
    private boolean binding;
    private int originalStatus;

    private final List<ChecklistItem> steps = new ArrayList<>();
    private final List<Attachment> attachments = new ArrayList<>();
    private final List<Attachment> addedAttachments = new ArrayList<>();
    private final List<Attachment> removedAttachments = new ArrayList<>();
    private List<Category> customCategories = new ArrayList<>();
    private volatile boolean offerClockBackup;
    private Uri pendingCameraUri;
    private File pendingCameraFile;

    private TextInputEditText titleInput, bodyInput, quoteInput, leadInput;
    private TextInputEditText finType, finJurisdiction, finAuthority, finYear, finAmount, finContact, finWebsite, finDocuments;
    private TextInputLayout bodyLayout;
    private ChipGroup typeGroup, repeatGroup, datesGroup;
    private MaterialButtonToggleGroup styleGroup, alertGroup;
    private View dateRow, daysRow, customRow, reminderRow, soundWarning, volumeWarning, financeSection, alarmSoundBox;
    private MaterialButton dateButton, startButton, endButton, soundButton, deadlineDateButton, deadlineTimeButton, filingButton, paymentButton;
    private TextView dateLabel, startLabel, windowSummary, reminderValue, soundDurationValue, snoozeValue, zoneValue, errorsText, soundWarningText, categoryValue, statusValue, deadlineSummary, quickCustom;
    private ImageView categoryIconView;
    private LinearLayout stepsBox, attachmentsBox;
    private Slider volumeSlider;
    private MaterialSwitch loopSwitch, vibrateSwitch, autoDismissSwitch, enabledSwitch, deadlineSwitch, followUpSwitch, overdueSwitch, quoteRandomSwitch;
    private View deadlineBox;
    private NestedScrollView scroll;

    private final ActivityResultLauncher<Intent> soundPicker = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    draft.soundUri = result.getData().getStringExtra(SoundLibraryActivity.RESULT_URI);
                    draft.soundName = result.getData().getStringExtra(SoundLibraryActivity.RESULT_NAME);
                    bindSound();
                }
            });

    private final ActivityResultLauncher<PickVisualMediaRequest> galleryPicker = registerForActivityResult(
            new ActivityResultContracts.PickMultipleVisualMedia(10), uris -> {
                for (Uri u : uris) importAttachment(u);
            });

    private final ActivityResultLauncher<String[]> documentPicker = registerForActivityResult(
            new ActivityResultContracts.OpenMultipleDocuments(), uris -> {
                for (Uri u : uris) importAttachment(u);
            });

    private final ActivityResultLauncher<Uri> cameraLauncher = registerForActivityResult(
            new ActivityResultContracts.TakePicture(), taken -> {
                if (taken && pendingCameraFile != null) {
                    Attachment a = new Attachment();
                    a.fileName = pendingCameraFile.getName();
                    a.displayName = getString(R.string.attachment_camera_name);
                    a.mime = "image/jpeg";
                    addedAttachments.add(a);
                    attachments.add(a);
                    renderAttachments();
                } else if (pendingCameraFile != null && !pendingCameraFile.delete()) {
                    android.util.Log.w("WordWakeEdit", "Could not remove unused camera file");
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

        final Context app = getApplicationContext();
        AppExecutors.io(() -> new ContentRepository(app).customCategories(), c -> {
            customCategories = c;
            if (draft != null) bindCategory();
        });

        long id = getIntent().getLongExtra(EXTRA_ID, 0);
        AppSettings settings = new AppSettings(this);
        if (id != 0) {
            isNew = false;
            AppExecutors.io(() -> {
                Object[] r = new Object[3];
                r[0] = new ScheduleRepository(app).get(id);
                ContentRepository content = new ContentRepository(app);
                r[1] = content.checklist(id);
                r[2] = content.attachments(id);
                return r;
            }, r -> {
                if (r[0] == null) {
                    Toast.makeText(this, R.string.schedule_not_found, Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }
                draft = (Schedule) r[0];
                originalStatus = draft.status;
                steps.addAll(castSteps(r[1]));
                attachments.addAll(castAttachments(r[2]));
                finance = Finance.parse(draft.financeJson);
                onLoaded();
                originalSignature = currentSignature();
            });
        } else {
            isNew = true;
            if (getIntent().getBooleanExtra(EXTRA_PRESET, false)) {
                draft = Presets.motivation(settings);
            } else {
                draft = Presets.newSchedule(settings);
                applyTypeDefaults(getIntent().getIntExtra(EXTRA_TYPE, Schedule.TYPE_REMINDER));
            }
            onLoaded();
            originalSignature = currentSignature();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<ChecklistItem> castSteps(Object o) {
        return (List<ChecklistItem>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Attachment> castAttachments(Object o) {
        return (List<Attachment>) o;
    }

    private void findViews() {
        scroll = findViewById(R.id.scroll);
        titleInput = findViewById(R.id.title_input);
        bodyInput = findViewById(R.id.body_input);
        bodyLayout = findViewById(R.id.body_layout);
        quoteInput = findViewById(R.id.quote_input);
        leadInput = findViewById(R.id.lead_input);
        finType = findViewById(R.id.fin_type);
        finJurisdiction = findViewById(R.id.fin_jurisdiction);
        finAuthority = findViewById(R.id.fin_authority);
        finYear = findViewById(R.id.fin_year);
        finAmount = findViewById(R.id.fin_amount);
        finContact = findViewById(R.id.fin_contact);
        finWebsite = findViewById(R.id.fin_website);
        finDocuments = findViewById(R.id.fin_documents);
        typeGroup = findViewById(R.id.type_group);
        repeatGroup = findViewById(R.id.repeat_group);
        datesGroup = findViewById(R.id.dates_group);
        styleGroup = findViewById(R.id.style_group);
        alertGroup = findViewById(R.id.alert_group);
        dateRow = findViewById(R.id.date_row);
        daysRow = findViewById(R.id.days_row);
        customRow = findViewById(R.id.custom_row);
        reminderRow = findViewById(R.id.reminder_row);
        dateLabel = findViewById(R.id.date_label);
        startLabel = findViewById(R.id.start_label);
        dateButton = findViewById(R.id.date_button);
        startButton = findViewById(R.id.start_button);
        endButton = findViewById(R.id.end_button);
        soundButton = findViewById(R.id.sound_button);
        deadlineDateButton = findViewById(R.id.deadline_date);
        deadlineTimeButton = findViewById(R.id.deadline_time);
        filingButton = findViewById(R.id.fin_filing);
        paymentButton = findViewById(R.id.fin_payment);
        windowSummary = findViewById(R.id.window_summary);
        reminderValue = findViewById(R.id.reminder_value);
        soundDurationValue = findViewById(R.id.sound_duration_value);
        snoozeValue = findViewById(R.id.snooze_value);
        zoneValue = findViewById(R.id.zone_value);
        errorsText = findViewById(R.id.errors_text);
        soundWarning = findViewById(R.id.sound_warning);
        soundWarningText = findViewById(R.id.sound_warning_text);
        volumeWarning = findViewById(R.id.volume_warning);
        financeSection = findViewById(R.id.finance_section);
        alarmSoundBox = findViewById(R.id.alarm_sound_box);
        categoryValue = findViewById(R.id.category_value);
        categoryIconView = findViewById(R.id.category_icon);
        statusValue = findViewById(R.id.status_value);
        deadlineSummary = findViewById(R.id.deadline_summary);
        deadlineBox = findViewById(R.id.deadline_box);
        quickCustom = findViewById(R.id.quick_custom);
        stepsBox = findViewById(R.id.steps_box);
        attachmentsBox = findViewById(R.id.attachments_box);
        volumeSlider = findViewById(R.id.volume_slider);
        loopSwitch = findViewById(R.id.loop_switch);
        vibrateSwitch = findViewById(R.id.vibrate_switch);
        autoDismissSwitch = findViewById(R.id.auto_dismiss_switch);
        enabledSwitch = findViewById(R.id.enabled_switch);
        deadlineSwitch = findViewById(R.id.deadline_switch);
        followUpSwitch = findViewById(R.id.follow_up_switch);
        overdueSwitch = findViewById(R.id.overdue_switch);
        quoteRandomSwitch = findViewById(R.id.quote_random_switch);
    }

    private void wireCollapsible(int headerId, int bodyId, int chevronId) {
        View header = findViewById(headerId);
        View body = findViewById(bodyId);
        ImageView chevron = findViewById(chevronId);
        header.setOnClickListener(v -> {
            boolean show = body.getVisibility() != View.VISIBLE;
            body.setVisibility(show ? View.VISIBLE : View.GONE);
            chevron.setRotation(show ? 180 : 0);
        });
        chevron.setRotation(body.getVisibility() == View.VISIBLE ? 180 : 0);
    }

    private void expand(int bodyId, int chevronId) {
        findViewById(bodyId).setVisibility(View.VISIBLE);
        ((ImageView) findViewById(chevronId)).setRotation(180);
    }

    private void wireListeners() {
        wireCollapsible(R.id.head_plan, R.id.body_plan, R.id.chev_plan);
        wireCollapsible(R.id.head_quote, R.id.body_quote, R.id.chev_quote);
        wireCollapsible(R.id.head_attach, R.id.body_attach, R.id.chev_attach);
        wireCollapsible(R.id.head_deadline, R.id.body_deadline, R.id.chev_deadline);
        wireCollapsible(R.id.head_sound, R.id.body_sound, R.id.chev_sound);
        wireCollapsible(R.id.head_options, R.id.body_options, R.id.chev_options);

        for (int[] t : TYPE_CHIPS) {
            final int type = t[0];
            findViewById(t[1]).setOnClickListener(v -> {
                if (binding) return;
                readTextFields();
                if (isNew) applyTypeDefaults(type);
                else draft.type = type;
                onTypeChanged();
            });
        }
        for (int[] r : REPEAT_CHIPS) {
            final int mode = r[0];
            findViewById(r[1]).setOnClickListener(v -> {
                if (binding) return;
                draft.repeatMode = mode;
                if (mode == Schedule.REPEAT_DEADLINE) {
                    if (draft.deadlineAt == 0) setDefaultDeadline();
                    if (draft.leadDays.isEmpty()) draft.leadDays = Schedule.DEFAULT_LEAD_DAYS;
                    bindDeadline();
                    expand(R.id.body_deadline, R.id.chev_deadline);
                }
                bindSchedulePart();
            });
        }
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
        alertGroup.addOnButtonCheckedListener((g, checkedId, isChecked) -> {
            if (!isChecked || binding) return;
            draft.alertMode = checkedId == R.id.alert_alarm ? Schedule.ALERT_ALARM : Schedule.ALERT_NOTIFICATION;
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
        findViewById(R.id.add_custom_date).setOnClickListener(v -> addCustomDate());

        dateButton.setOnClickListener(v -> pickDate());
        startButton.setOnClickListener(v -> pickTime(true));
        endButton.setOnClickListener(v -> pickTime(false));
        int[][] quick = {{R.id.quick_5, 5}, {R.id.quick_10, 10}, {R.id.quick_15, 15}, {R.id.quick_30, 30},
                {R.id.quick_60, 60}, {R.id.quick_120, 120}};
        for (int[] q : quick) {
            final int mins = q[1];
            findViewById(q[0]).setOnClickListener(v -> setWindowLength(mins));
        }
        quickCustom.setOnClickListener(v -> askNumber(R.string.custom_duration_title, ScheduleEngine.windowMinutes(draft.startMinute, draft.endMinute),
                1, ScheduleEngine.MAX_WINDOW_MINUTES, this::setWindowLength));
        findViewById(R.id.reminder_row).setOnClickListener(v -> chooseReminderInterval());
        findViewById(R.id.category_row).setOnClickListener(v -> chooseCategory());
        findViewById(R.id.status_row).setOnClickListener(v -> chooseStatus());
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
        followUpSwitch.setOnCheckedChangeListener((b, c) -> { if (!binding) draft.followUpDaily = c; });
        overdueSwitch.setOnCheckedChangeListener((b, c) -> { if (!binding) draft.overdueDaily = c; });
        quoteRandomSwitch.setOnCheckedChangeListener((b, c) -> {
            if (binding) return;
            draft.quoteRandom = c;
            quoteInput.setEnabled(!c);
        });
        deadlineSwitch.setOnCheckedChangeListener((b, c) -> {
            if (binding) return;
            if (c && draft.deadlineAt == 0) setDefaultDeadline();
            if (!c) draft.deadlineAt = 0;
            bindDeadline();
        });
        deadlineDateButton.setOnClickListener(v -> pickDeadlineDate());
        deadlineTimeButton.setOnClickListener(v -> pickDeadlineTime());
        filingButton.setOnClickListener(v -> pickFinanceDate(true));
        paymentButton.setOnClickListener(v -> pickFinanceDate(false));
        findViewById(R.id.add_step).setOnClickListener(v -> {
            readSteps();
            steps.add(new ChecklistItem(""));
            renderSteps(true);
        });
        findViewById(R.id.use_tax_steps).setOnClickListener(v -> {
            readSteps();
            for (String s : getResources().getStringArray(R.array.tax_checklist_example)) steps.add(new ChecklistItem(s));
            renderSteps(false);
        });
        findViewById(R.id.pick_quote).setOnClickListener(v -> pickSavedQuote());
        findViewById(R.id.add_gallery).setOnClickListener(v -> galleryPicker.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE).build()));
        findViewById(R.id.add_camera).setOnClickListener(v -> takePhoto());
        findViewById(R.id.add_document).setOnClickListener(v -> documentPicker.launch(new String[]{"*/*"}));
        findViewById(R.id.save_button).setOnClickListener(v -> save());

        bodyInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) {
                bodyLayout.setHelperText(getString(R.string.body_counter, e.length(), ScheduleEngine.MAX_BODY_CHARS));
            }
        });
    }

    /** Sensible starting values when creating a reminder of this type. Everything stays editable. */
    private void applyTypeDefaults(int type) {
        draft.type = type;
        ZoneId zone = ZoneId.systemDefault();
        LocalTime now = LocalTime.now(zone);
        // Next half hour, so a new reminder never starts in the past by accident.
        int nextHalf = ((now.getHour() * 60 + now.getMinute()) / 30 + 1) * 30;
        boolean rolled = nextHalf >= 1440;
        int startToday = nextHalf % 1440;
        LocalDate date = rolled ? LocalDate.now(zone).plusDays(1) : LocalDate.now(zone);
        switch (type) {
            case Schedule.TYPE_ALARM:
                draft.alertMode = Schedule.ALERT_ALARM;
                draft.repeatMode = Schedule.REPEAT_DAILY;
                draft.startMinute = 6 * 60;
                draft.endMinute = 6 * 60 + 30;
                draft.categoryKey = Category.KEY_PERSONAL;
                break;
            case Schedule.TYPE_QUOTE:
                draft.alertMode = Schedule.ALERT_NOTIFICATION;
                draft.repeatMode = Schedule.REPEAT_DAILY;
                draft.startMinute = 7 * 60;
                draft.endMinute = 7 * 60 + 30;
                draft.categoryKey = Category.KEY_MOTIVATION;
                draft.quoteRandom = true;
                break;
            case Schedule.TYPE_APPOINTMENT:
                draft.alertMode = Schedule.ALERT_ALARM;
                draft.repeatMode = Schedule.REPEAT_ONCE;
                draft.startMinute = startToday;
                draft.endMinute = (startToday + 30) % 1440;
                draft.startDate = date.toEpochDay();
                draft.categoryKey = Category.KEY_APPOINTMENTS;
                break;
            case Schedule.TYPE_DEADLINE:
            case Schedule.TYPE_TAX:
            case Schedule.TYPE_BILL:
                draft.alertMode = Schedule.ALERT_NOTIFICATION;
                draft.repeatMode = Schedule.REPEAT_DEADLINE;
                draft.startMinute = 9 * 60;
                draft.endMinute = 10 * 60;
                draft.startDate = LocalDate.now(zone).toEpochDay();
                draft.leadDays = Schedule.DEFAULT_LEAD_DAYS;
                draft.overdueDaily = true;
                if (draft.deadlineAt == 0) setDefaultDeadline();
                draft.categoryKey = type == Schedule.TYPE_TAX ? Category.KEY_TAX
                        : type == Schedule.TYPE_BILL ? Category.KEY_BILLS : Category.KEY_PERSONAL;
                break;
            default:
                draft.alertMode = Schedule.ALERT_NOTIFICATION;
                draft.repeatMode = Schedule.REPEAT_ONCE;
                draft.startMinute = startToday;
                draft.endMinute = (startToday + 60) % 1440;
                draft.startDate = date.toEpochDay();
                draft.categoryKey = Category.KEY_PERSONAL;
                break;
        }
        String[] catSound = new AppSettings(this).categorySound(draft.categoryKey);
        if (catSound != null) {
            draft.soundUri = catSound[0];
            draft.soundName = catSound[1];
        }
    }

    private void setDefaultDeadline() {
        ZoneId zone = draft.zone(ZoneId.systemDefault());
        draft.deadlineAt = LocalDate.now(zone).plusDays(7).atTime(17, 0).atZone(zone).toInstant().toEpochMilli();
    }

    private void onLoaded() {
        setTitle(isNew ? R.string.title_new : R.string.title_edit);
        titleInput.setText(draft.title);
        bodyInput.setText(draft.body);
        quoteInput.setText(draft.quote);
        leadInput.setText(draft.leadDays);
        finType.setText(finance.taxType);
        finJurisdiction.setText(finance.jurisdiction);
        finAuthority.setText(finance.authority);
        finYear.setText(finance.taxYear);
        finAmount.setText(finance.amount);
        finContact.setText(finance.contact);
        finWebsite.setText(finance.website);
        finDocuments.setText(finance.documents);
        onTypeChanged();
        renderSteps(false);
        renderAttachments();
        if (!steps.isEmpty()) expand(R.id.body_plan, R.id.chev_plan);
        if (!attachments.isEmpty()) expand(R.id.body_attach, R.id.chev_attach);
        if (draft.quoteRandom || !draft.quote.isEmpty()) expand(R.id.body_quote, R.id.chev_quote);
    }

    private void onTypeChanged() {
        binding = true;
        for (int[] t : TYPE_CHIPS) ((Chip) findViewById(t[1])).setChecked(draft.type == t[0]);
        binding = false;
        boolean financial = draft.type == Schedule.TYPE_TAX || draft.type == Schedule.TYPE_BILL;
        financeSection.setVisibility(financial ? View.VISIBLE : View.GONE);
        if (draft.deadlineAt > 0) expand(R.id.body_deadline, R.id.chev_deadline);
        bindCategory();
        bindSchedulePart();
        bindSound();
        bindOptions();
        bindDeadline();
    }

    private void bindCategory() {
        Category c = Category.find(draft.categoryKey, customCategories);
        categoryValue.setText(c.name(this));
        categoryIconView.setImageResource(c.iconRes);
        categoryIconView.setImageTintList(android.content.res.ColorStateList.valueOf(c.color(this)));
        statusValue.setText(Formats.statusName(this, draft.status));
    }

    private void bindSchedulePart() {
        binding = true;
        for (int[] r : REPEAT_CHIPS) ((Chip) findViewById(r[1])).setChecked(draft.repeatMode == r[0]);
        boolean usesDate = draft.repeatMode == Schedule.REPEAT_ONCE || draft.repeatMode == Schedule.REPEAT_WEEKLY
                || draft.repeatMode == Schedule.REPEAT_MONTHLY || draft.repeatMode == Schedule.REPEAT_YEARLY
                || draft.repeatMode == Schedule.REPEAT_DEADLINE;
        dateRow.setVisibility(usesDate ? View.VISIBLE : View.GONE);
        dateLabel.setText(draft.repeatMode == Schedule.REPEAT_ONCE ? R.string.label_date
                : draft.repeatMode == Schedule.REPEAT_DEADLINE ? R.string.label_first_alert : R.string.label_starting_on);
        daysRow.setVisibility(draft.repeatMode == Schedule.REPEAT_DAYS ? View.VISIBLE : View.GONE);
        customRow.setVisibility(draft.repeatMode == Schedule.REPEAT_CUSTOM_DATES ? View.VISIBLE : View.GONE);
        if (usesDate && draft.startDate == 0) draft.startDate = LocalDate.now().toEpochDay();
        dateButton.setText(Formats.date(draft.onceDate()));
        for (int i = 0; i < DAY_CHIP_IDS.length; i++) {
            ((Chip) findViewById(DAY_CHIP_IDS[i])).setChecked((draft.daysMask & (Schedule.DAY_MONDAY << i)) != 0);
        }
        startLabel.setText(draft.repeatMode == Schedule.REPEAT_DEADLINE ? R.string.label_alert_time : R.string.label_start);
        startButton.setText(Formats.minuteOfDay(this, draft.startMinute));
        String end = Formats.minuteOfDay(this, draft.endMinute);
        if (ScheduleEngine.endsNextDay(draft.startMinute, draft.endMinute)) end += " " + getString(R.string.next_day_marker);
        endButton.setText(end);
        styleGroup.check(draft.reminderMinutes > 0 ? R.id.style_reminders : R.id.style_continuous);
        reminderRow.setVisibility(draft.reminderMinutes > 0 ? View.VISIBLE : View.GONE);
        reminderValue.setText(getString(R.string.every_n_min, draft.reminderMinutes));
        binding = false;
        renderCustomDates();
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

    private void setWindowLength(int minutes) {
        draft.endMinute = (draft.startMinute + minutes) % 1440;
        bindSchedulePart();
    }

    private void renderCustomDates() {
        datesGroup.removeAllViews();
        for (LocalDate d : draft.customDateList()) {
            Chip chip = new Chip(this);
            chip.setText(Formats.date(d));
            chip.setCloseIconVisible(true);
            chip.setOnCloseIconClickListener(v -> {
                List<LocalDate> dates = draft.customDateList();
                dates.remove(d);
                draft.customDates = Schedule.formatDates(dates);
                renderCustomDates();
            });
            datesGroup.addView(chip);
        }
    }

    private void addCustomDate() {
        MaterialDatePicker<Long> picker = MaterialDatePicker.Builder.datePicker().setTitleText(R.string.pick_date).build();
        picker.addOnPositiveButtonClickListener(ms -> {
            List<LocalDate> dates = draft.customDateList();
            LocalDate d = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate();
            if (!dates.contains(d)) dates.add(d);
            Collections.sort(dates);
            draft.customDates = Schedule.formatDates(dates);
            renderCustomDates();
        });
        picker.show(getSupportFragmentManager(), "custom_date");
    }

    private void bindSound() {
        binding = true;
        alertGroup.check(draft.alertMode == Schedule.ALERT_ALARM ? R.id.alert_alarm : R.id.alert_notification);
        alarmSoundBox.setVisibility(draft.alertMode == Schedule.ALERT_ALARM ? View.VISIBLE : View.GONE);
        soundButton.setText(draft.soundName == null || draft.soundName.isEmpty() ? getString(R.string.sound_default) : draft.soundName);
        volumeSlider.setValue(Math.max(10, Math.min(100, Math.round(draft.volumePercent / 10f) * 10)));
        loopSwitch.setChecked(draft.loopSound);
        soundDurationValue.setText(soundDurationLabel(draft.soundDurationMinutes));
        binding = false;
        final String uri = draft.soundUri;
        final boolean alarm = draft.alertMode == Schedule.ALERT_ALARM;
        AppExecutors.io(() -> SoundCatalog.isAvailable(getApplicationContext(), uri), ok -> {
            soundWarning.setVisibility(ok || !alarm ? View.GONE : View.VISIBLE);
            soundWarningText.setText(R.string.sound_unavailable_edit);
        });
        volumeWarning.setVisibility(alarm && PermissionStatus.alarmVolumeLow(this) ? View.VISIBLE : View.GONE);
    }

    private void bindOptions() {
        binding = true;
        vibrateSwitch.setChecked(draft.vibrate);
        autoDismissSwitch.setChecked(draft.autoDismiss);
        enabledSwitch.setChecked(draft.enabled);
        snoozeValue.setText(snoozeLabel(draft.snoozeMinutes));
        zoneValue.setText(draft.zoneId == null || draft.zoneId.isEmpty()
                ? getString(R.string.zone_device, ZoneId.systemDefault().getId()) : draft.zoneId);
        quoteRandomSwitch.setChecked(draft.quoteRandom);
        quoteInput.setEnabled(!draft.quoteRandom);
        binding = false;
    }

    private void bindDeadline() {
        binding = true;
        boolean has = draft.deadlineAt > 0;
        deadlineSwitch.setChecked(has);
        deadlineBox.setVisibility(has ? View.VISIBLE : View.GONE);
        followUpSwitch.setChecked(draft.followUpDaily);
        overdueSwitch.setChecked(draft.overdueDaily);
        if (has) {
            ZonedDateTime z = Instant.ofEpochMilli(draft.deadlineAt).atZone(draft.zone(ZoneId.systemDefault()));
            deadlineDateButton.setText(Formats.date(z.toLocalDate()));
            deadlineTimeButton.setText(Formats.minuteOfDay(this, z.getHour() * 60 + z.getMinute()));
            deadlineSummary.setText(Formats.deadlineLine(this, draft));
        }
        filingButton.setText(finance.filingDeadline > 0
                ? Formats.date(Instant.ofEpochMilli(finance.filingDeadline).atZone(ZoneId.systemDefault()).toLocalDate())
                : getString(R.string.finance_pick_date));
        paymentButton.setText(finance.paymentDeadline > 0
                ? Formats.date(Instant.ofEpochMilli(finance.paymentDeadline).atZone(ZoneId.systemDefault()).toLocalDate())
                : getString(R.string.finance_pick_date));
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

    // ---- action plan ----

    private void readSteps() {
        for (int i = 0; i < stepsBox.getChildCount() && i < steps.size(); i++) {
            View row = stepsBox.getChildAt(i);
            steps.get(i).text = ((EditText) row.findViewById(R.id.step_text)).getText().toString();
            steps.get(i).done = ((CheckBox) row.findViewById(R.id.step_done)).isChecked();
        }
    }

    private void renderSteps(boolean focusLast) {
        stepsBox.removeAllViews();
        LayoutInflater inf = LayoutInflater.from(this);
        for (int i = 0; i < steps.size(); i++) {
            final int index = i;
            ChecklistItem item = steps.get(i);
            View row = inf.inflate(R.layout.item_step, stepsBox, false);
            ((CheckBox) row.findViewById(R.id.step_done)).setChecked(item.done);
            EditText text = row.findViewById(R.id.step_text);
            text.setText(item.text);
            ImageButton up = row.findViewById(R.id.step_up);
            ImageButton down = row.findViewById(R.id.step_down);
            up.setEnabled(i > 0);
            up.setAlpha(i > 0 ? 1f : 0.3f);
            down.setEnabled(i < steps.size() - 1);
            down.setAlpha(i < steps.size() - 1 ? 1f : 0.3f);
            up.setOnClickListener(v -> moveStep(index, -1));
            down.setOnClickListener(v -> moveStep(index, 1));
            row.findViewById(R.id.step_delete).setOnClickListener(v -> {
                readSteps();
                steps.remove(index);
                renderSteps(false);
            });
            stepsBox.addView(row);
            if (focusLast && i == steps.size() - 1) text.requestFocus();
        }
    }

    private void moveStep(int index, int delta) {
        readSteps();
        Collections.swap(steps, index, index + delta);
        renderSteps(false);
    }

    // ---- quote ----

    private void pickSavedQuote() {
        final Context app = getApplicationContext();
        AppExecutors.io(() -> {
            ContentRepository c = new ContentRepository(app);
            c.seedQuotes(new AppSettings(app));
            return c.quotes();
        }, quotes -> {
            String[] labels = new String[quotes.size()];
            for (int i = 0; i < labels.length; i++) labels[i] = quotes.get(i).text;
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.quote_pick_title)
                    .setItems(labels, (d, w) -> quoteInput.setText(labels[w]))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });
    }

    // ---- attachments ----

    private void importAttachment(Uri uri) {
        final Context app = getApplicationContext();
        AppExecutors.io(() -> {
            Attachment a = AttachmentStore.importUri(app, uri, 0);
            return a == null ? new Attachment[0] : new Attachment[]{a};
        }, r -> {
            if (isFinishing()) {
                return;
            }
            if (r.length == 0) {
                Toast.makeText(this, R.string.attachment_failed, Toast.LENGTH_LONG).show();
                return;
            }
            addedAttachments.add(r[0]);
            attachments.add(r[0]);
            renderAttachments();
        });
    }

    private void takePhoto() {
        pendingCameraFile = new File(AttachmentStore.dir(this), UUID.randomUUID() + ".jpg");
        pendingCameraUri = AttachmentStore.shareUri(this, pendingCameraFile);
        try {
            cameraLauncher.launch(pendingCameraUri);
        } catch (RuntimeException e) {
            Toast.makeText(this, R.string.attachment_no_camera, Toast.LENGTH_LONG).show();
        }
    }

    private void renderAttachments() {
        attachmentsBox.removeAllViews();
        for (Attachment a : attachments) {
            View row = LayoutInflater.from(this).inflate(R.layout.item_attachment, attachmentsBox, false);
            ((TextView) row.findViewById(R.id.attachment_name)).setText(a.displayName);
            ImageView thumb = row.findViewById(R.id.attachment_thumb);
            thumb.setImageResource(a.isImage() ? R.drawable.ic_image : R.drawable.ic_description);
            if (a.isImage()) {
                final Context app = getApplicationContext();
                AppExecutors.io(() -> {
                    android.graphics.Bitmap b = AttachmentStore.loadBitmap(app, a, 240);
                    return b == null ? new android.graphics.Bitmap[0] : new android.graphics.Bitmap[]{b};
                }, b -> {
                    if (b.length > 0) {
                        thumb.setImageTintList(null);
                        thumb.setImageBitmap(b[0]);
                    }
                });
            }
            row.setOnClickListener(v -> {
                try {
                    startActivity(AttachmentStore.viewIntent(this, a));
                } catch (RuntimeException e) {
                    Toast.makeText(this, R.string.details_cannot_open, Toast.LENGTH_LONG).show();
                }
            });
            row.findViewById(R.id.attachment_remove).setOnClickListener(v -> {
                attachments.remove(a);
                if (addedAttachments.remove(a)) AttachmentStore.deleteFile(this, a);
                else removedAttachments.add(a);
                renderAttachments();
            });
            attachmentsBox.addView(row);
        }
    }

    // ---- pickers ----

    private void pickDate() {
        long selection = draft.onceDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        MaterialDatePicker<Long> picker = MaterialDatePicker.Builder.datePicker()
                .setTitleText(R.string.pick_date)
                .setSelection(selection)
                .build();
        picker.addOnPositiveButtonClickListener(ms -> {
            draft.startDate = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay();
            bindSchedulePart();
        });
        picker.show(getSupportFragmentManager(), "date");
    }

    private void pickDeadlineDate() {
        ZoneId zone = draft.zone(ZoneId.systemDefault());
        ZonedDateTime cur = Instant.ofEpochMilli(draft.deadlineAt).atZone(zone);
        MaterialDatePicker<Long> picker = MaterialDatePicker.Builder.datePicker()
                .setTitleText(R.string.deadline_pick_date)
                .setSelection(cur.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
                .build();
        picker.addOnPositiveButtonClickListener(ms -> {
            LocalDate d = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate();
            draft.deadlineAt = cur.with(d).toInstant().toEpochMilli();
            bindDeadline();
        });
        picker.show(getSupportFragmentManager(), "deadline_date");
    }

    private void pickDeadlineTime() {
        ZoneId zone = draft.zone(ZoneId.systemDefault());
        ZonedDateTime cur = Instant.ofEpochMilli(draft.deadlineAt).atZone(zone);
        MaterialTimePicker picker = new MaterialTimePicker.Builder()
                .setTimeFormat(DateFormat.is24HourFormat(this) ? TimeFormat.CLOCK_24H : TimeFormat.CLOCK_12H)
                .setHour(cur.getHour())
                .setMinute(cur.getMinute())
                .setTitleText(R.string.deadline_pick_time)
                .build();
        picker.addOnPositiveButtonClickListener(v -> {
            draft.deadlineAt = cur.withHour(picker.getHour()).withMinute(picker.getMinute()).withSecond(0).toInstant().toEpochMilli();
            bindDeadline();
        });
        picker.show(getSupportFragmentManager(), "deadline_time");
    }

    private void pickFinanceDate(boolean filing) {
        MaterialDatePicker<Long> picker = MaterialDatePicker.Builder.datePicker()
                .setTitleText(filing ? R.string.finance_filing : R.string.finance_payment)
                .build();
        picker.addOnPositiveButtonClickListener(ms -> {
            LocalDate d = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate();
            ZoneId zone = draft.zone(ZoneId.systemDefault());
            long at = d.atTime(17, 0).atZone(zone).toInstant().toEpochMilli();
            if (filing) finance.filingDeadline = at;
            else finance.paymentDeadline = at;
            long earliest = finance.earliestDeadline();
            if (earliest > 0) draft.deadlineAt = earliest;
            bindDeadline();
        });
        picker.show(getSupportFragmentManager(), filing ? "filing" : "payment");
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

    private void chooseReminderInterval() {
        String[] labels = new String[REMINDER_CHOICES.length + 1];
        int checked = -1;
        for (int i = 0; i < REMINDER_CHOICES.length; i++) {
            labels[i] = getString(R.string.every_n_min, REMINDER_CHOICES[i]);
            if (REMINDER_CHOICES[i] == draft.reminderMinutes) checked = i;
        }
        labels[REMINDER_CHOICES.length] = getString(R.string.custom_interval);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.reminder_every_title)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    d.dismiss();
                    if (which < REMINDER_CHOICES.length) {
                        draft.reminderMinutes = REMINDER_CHOICES[which];
                        bindSchedulePart();
                    } else {
                        askNumber(R.string.custom_interval_title, Math.max(1, draft.reminderMinutes), 1,
                                ScheduleEngine.MAX_WINDOW_MINUTES, m -> {
                                    draft.reminderMinutes = m;
                                    bindSchedulePart();
                                });
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void askNumber(int title, int current, int min, int max, IntChosen chosen) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(current));
        input.setSelectAllOnFocus(true);
        LinearLayout box = Ui.vertical(this);
        int p = Ui.dp(this, 20);
        box.setPadding(p, p / 2, p, 0);
        box.addView(input);
        new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setMessage(getString(R.string.number_range, min, max))
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    try {
                        int v = Integer.parseInt(input.getText().toString().trim());
                        if (v >= min && v <= max) chosen.chosen(v);
                        else Toast.makeText(this, getString(R.string.number_range, min, max), Toast.LENGTH_LONG).show();
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, getString(R.string.number_range, min, max), Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }

    private void chooseCategory() {
        List<Category> all = Category.all(customCategories);
        String[] labels = new String[all.size()];
        int checked = -1;
        for (int i = 0; i < labels.length; i++) {
            labels[i] = all.get(i).name(this);
            if (all.get(i).key.equals(draft.categoryKey)) checked = i;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.label_category)
                .setSingleChoiceItems(labels, checked, (d, w) -> {
                    draft.categoryKey = all.get(w).key;
                    String[] sound = new AppSettings(this).categorySound(draft.categoryKey);
                    if (sound != null) {
                        draft.soundUri = sound[0];
                        draft.soundName = sound[1];
                        bindSound();
                    }
                    bindCategory();
                    d.dismiss();
                })
                .setNeutralButton(R.string.category_manage, (d, w) -> startActivity(new Intent(this, CategoriesActivity.class)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void chooseStatus() {
        int[] values = {Schedule.STATUS_NOT_STARTED, Schedule.STATUS_IN_PROGRESS, Schedule.STATUS_WAITING,
                Schedule.STATUS_READY, Schedule.STATUS_COMPLETED};
        String[] labels = new String[values.length];
        for (int i = 0; i < values.length; i++) labels[i] = Formats.statusName(this, values[i]);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.status_title)
                .setSingleChoiceItems(labels, draft.status, (d, w) -> {
                    draft.status = values[w];
                    bindCategory();
                    d.dismiss();
                })
                .show();
    }

    private void pickZone() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_zone, (ViewGroup) null);
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

    private static String str(TextInputEditText e) {
        return e.getText() == null ? "" : e.getText().toString().trim();
    }

    private void readTextFields() {
        if (draft == null) return;
        draft.title = titleInput.getText() == null ? "" : titleInput.getText().toString();
        draft.body = bodyInput.getText() == null ? "" : bodyInput.getText().toString();
        draft.quote = quoteInput.getText() == null ? "" : quoteInput.getText().toString();
        draft.leadDays = str(leadInput).replaceAll("[^0-9,]", "");
        finance.taxType = str(finType);
        finance.jurisdiction = str(finJurisdiction);
        finance.authority = str(finAuthority);
        finance.taxYear = str(finYear);
        finance.amount = str(finAmount);
        finance.contact = str(finContact);
        finance.website = str(finWebsite);
        finance.documents = str(finDocuments);
        draft.financeJson = finance.isEmpty() ? "" : finance.toJson();
        readSteps();
    }

    private void save() {
        if (draft == null) return;
        readTextFields();
        // A quote reminder may have no text of its own, but its notification still needs a title.
        if (draft.title.trim().isEmpty() && draft.body.trim().isEmpty() && draft.quote.trim().isEmpty() && draft.quoteRandom) {
            draft.title = getString(R.string.type_quote);
        }
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
        final boolean wasNew = isNew;
        final int oldStatus = originalStatus;
        final List<ChecklistItem> stepsCopy = new ArrayList<>(steps);
        final List<Attachment> added = new ArrayList<>(addedAttachments);
        final List<Attachment> removed = new ArrayList<>(removedAttachments);
        final Context app = getApplicationContext();
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
            ContentRepository content = new ContentRepository(app);
            content.saveChecklist(toSave.id, stepsCopy);
            for (Attachment a : added) {
                a.scheduleId = toSave.id;
                content.addAttachment(a);
            }
            for (Attachment a : removed) {
                content.removeAttachment(a.id);
                AttachmentStore.deleteFile(app, a);
            }
            DiagLog.log(app, wasNew ? DiagLog.CREATED : DiagLog.UPDATED, toSave.id, toSave.displayTitle(), "type=" + toSave.type + " repeat=" + toSave.repeatMode);
            if (toSave.status == Schedule.STATUS_COMPLETED && oldStatus != Schedule.STATUS_COMPLETED) {
                AlarmController.complete(app, toSave.id);
            } else if (toSave.status != Schedule.STATUS_COMPLETED && oldStatus == Schedule.STATUS_COMPLETED) {
                int chosen = toSave.status;
                AlarmController.reopen(app, toSave.id);
                Schedule reopened = repo.get(toSave.id);
                reopened.status = chosen;
                repo.saveState(reopened);
            } else if (toSave.enabled) {
                AlarmScheduler.reschedule(app, toSave);
            } else {
                AlarmController.onDisabledOrDeleted(app, toSave.id);
            }
            AlarmScheduler.reconcileAll(app, "saved");
            offerClockBackup = PermissionStatus.deliveryProblem(app);
            return repo.get(toSave.id);
        }, saved -> {
            addedAttachments.clear();
            if (saved == null) {
                Toast.makeText(this, R.string.schedule_not_found, Toast.LENGTH_LONG).show();
                finish();
                return;
            }
            String msg;
            if (saved.isCompleted()) msg = getString(R.string.saved_completed);
            else if (!saved.enabled) msg = getString(R.string.saved_disabled);
            else if (saved.nextTriggerAt > 0) {
                msg = getString(R.string.saved_next, Formats.dateTime(this, saved.nextTriggerAt));
                if (!saved.nextExact) msg += " " + getString(R.string.saved_inexact);
            } else msg = getString(R.string.saved_no_next);
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            if (offerClockBackup && saved.enabled && !saved.isCompleted() && saved.alertMode == Schedule.ALERT_ALARM
                    && com.wordwake.app.alarm.ClockBackup.isSupported(saved, System.currentTimeMillis())) {
                new ReminderActions(this, () -> { }).addClockBackup(saved, this::finish);
            } else {
                finish();
            }
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
                || !a.zoneId.equals(b.zoneId) || !a.customDates.equals(b.customDates) || a.deadlineAt != b.deadlineAt
                || !a.leadDays.equals(b.leadDays) || a.followUpDaily != b.followUpDaily || a.overdueDaily != b.overdueDaily
                || a.alertMode != b.alertMode;
    }

    private static void copyRuntimeState(Schedule from, Schedule to) {
        to.occStart = from.occStart;
        to.lastRingAt = from.lastRingAt;
        to.dismissedOcc = from.dismissedOcc;
        to.snoozeUntil = from.snoozeUntil;
        to.ringActiveUntil = from.ringActiveUntil;
        to.nextTriggerAt = from.nextTriggerAt;
        to.nextExact = from.nextExact;
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
            case ScheduleEngine.ERR_NO_DATES: return getString(R.string.err_no_dates);
            case ScheduleEngine.ERR_NO_DEADLINE: return getString(R.string.err_no_deadline);
            case ScheduleEngine.ERR_NO_LEADS: return getString(R.string.err_no_leads);
            default: return getString(R.string.err_bad_time);
        }
    }

    private void attemptClose() {
        if (draft == null) {
            finish();
            return;
        }
        if (currentSignature().equals(originalSignature)) {
            finish();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.discard_title)
                .setMessage(R.string.discard_message)
                .setNegativeButton(R.string.keep_editing, null)
                .setNeutralButton(R.string.discard, (d, w) -> {
                    // Files added in this session were never linked to the reminder.
                    for (Attachment a : addedAttachments) AttachmentStore.deleteFile(this, a);
                    addedAttachments.clear();
                    finish();
                })
                .setPositiveButton(R.string.save, (d, w) -> save())
                .show();
    }

    private String currentSignature() {
        readTextFields();
        Schedule s = draft;
        StringBuilder sb = new StringBuilder();
        sb.append(s.title).append('\u0001').append(s.body).append('\u0001').append(s.quote).append('\u0001')
                .append(s.type).append(',').append(s.alertMode).append(',').append(s.categoryKey).append(',')
                .append(s.repeatMode).append(',').append(s.startDate).append(',').append(s.startMinute).append(',')
                .append(s.endMinute).append(',').append(s.daysMask).append(',').append(s.customDates).append(',')
                .append(s.zoneId).append(',').append(s.reminderMinutes).append(',').append(s.soundUri).append(',')
                .append(s.volumePercent).append(',').append(s.loopSound).append(',').append(s.soundDurationMinutes).append(',')
                .append(s.vibrate).append(',').append(s.snoozeMinutes).append(',').append(s.autoDismiss).append(',')
                .append(s.enabled).append(',').append(s.quoteRandom).append(',').append(s.deadlineAt).append(',')
                .append(s.leadDays).append(',').append(s.followUpDaily).append(',').append(s.overdueDaily).append(',')
                .append(s.status).append(',').append(s.financeJson).append(',')
                .append(addedAttachments.size()).append(',').append(removedAttachments.size());
        for (ChecklistItem i : steps) sb.append('\u0001').append(i.text).append(i.done);
        return sb.toString();
    }
}
