package com.wordwake.app.ui;

import android.app.KeyguardManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.wordwake.app.R;
import com.wordwake.app.alarm.ActiveAlarm;
import com.wordwake.app.alarm.AlarmController;
import com.wordwake.app.data.AppExecutors;
import com.wordwake.app.data.AppSettings;
import com.wordwake.app.data.AttachmentStore;
import com.wordwake.app.data.ContentRepository;
import com.wordwake.app.engine.ScheduleEngine;
import com.wordwake.app.model.Category;
import com.wordwake.app.model.ChecklistItem;
import com.wordwake.app.model.Schedule;
import com.wordwake.app.sound.AlarmPlayer;

import java.util.List;

/**
 * Full-screen alert: what is due, why and how, with the checklist and picture. Shown over the lock
 * screen and turns the screen on where Android permits. It never bypasses the keyguard; opening
 * details asks Android for the normal unlock.
 */
public class AlarmActivity extends AppCompatActivity implements ActiveAlarm.Listener {

    public static final String EXTRA_SCHEDULE_ID = "schedule_id";
    public static final String EXTRA_OCC_START = "occ_start";
    public static final String EXTRA_OCC_END = "occ_end";
    public static final String EXTRA_TEST = "test";

    private long scheduleId, occStart, occEnd;
    private boolean test;
    private Schedule schedule;
    private AppSettings settings;

    private TextView categoryView, titleView, bodyView, quoteView, scheduledView, remainingView, deadlineView, warningView, endedView, checklistTitle;
    private ImageView imageView;
    private LinearLayout checklistBox;
    private MaterialButton stopButton, snoozeButton, dismissButton, completeButton, detailsButton;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private static final class Content {
        Schedule schedule;
        String quote;
        Bitmap image;
        Category category;
        List<ChecklistItem> checklist;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        settings = new AppSettings(this);
        if (settings.highContrast()) setTheme(R.style.Theme_WordWake_Alarm_HighContrast);
        super.onCreate(savedInstanceState);
        showOverLockScreen();
        setContentView(R.layout.activity_alarm);

        categoryView = findViewById(R.id.alarm_category);
        titleView = findViewById(R.id.alarm_title);
        bodyView = findViewById(R.id.alarm_body);
        quoteView = findViewById(R.id.alarm_quote);
        imageView = findViewById(R.id.alarm_image);
        scheduledView = findViewById(R.id.alarm_scheduled);
        remainingView = findViewById(R.id.alarm_remaining);
        deadlineView = findViewById(R.id.alarm_deadline);
        warningView = findViewById(R.id.alarm_warning);
        endedView = findViewById(R.id.alarm_ended);
        checklistTitle = findViewById(R.id.alarm_checklist_title);
        checklistBox = findViewById(R.id.alarm_checklist);
        stopButton = findViewById(R.id.button_stop);
        snoozeButton = findViewById(R.id.button_snooze);
        dismissButton = findViewById(R.id.button_dismiss);
        completeButton = findViewById(R.id.button_complete);
        detailsButton = findViewById(R.id.button_details);

        float scale = settings.alarmTextSize() == AppSettings.TEXT_HUGE ? 1.35f
                : settings.alarmTextSize() == AppSettings.TEXT_NORMAL ? 0.85f : 1f;
        bodyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24 * scale);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30 * scale);
        quoteView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19 * scale);

        stopButton.setOnClickListener(v -> act(() -> AlarmController.silence(getApplicationContext(), scheduleId), false));
        snoozeButton.setOnClickListener(v -> act(() -> AlarmController.snooze(getApplicationContext(), scheduleId), true));
        dismissButton.setOnClickListener(v -> act(() -> AlarmController.dismiss(getApplicationContext(), scheduleId), true));
        completeButton.setOnClickListener(v -> act(() -> AlarmController.complete(getApplicationContext(), scheduleId), true));
        detailsButton.setOnClickListener(v -> openDetails());

        readIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        readIntent(intent);
    }

    private void showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
    }

    private void readIntent(Intent intent) {
        scheduleId = intent.getLongExtra(EXTRA_SCHEDULE_ID, 0);
        occStart = intent.getLongExtra(EXTRA_OCC_START, 0);
        occEnd = intent.getLongExtra(EXTRA_OCC_END, 0);
        test = intent.getBooleanExtra(EXTRA_TEST, false);
        final android.content.Context app = getApplicationContext();
        final long id = scheduleId;
        AppExecutors.io(() -> {
            Content c = new Content();
            c.schedule = AlarmController.load(app, id);
            if (c.schedule == null) return c;
            ContentRepository content = new ContentRepository(app);
            c.quote = c.schedule.quoteRandom ? content.randomQuote() : c.schedule.quote;
            c.checklist = content.checklist(id);
            c.category = Category.find(c.schedule.categoryKey, content.customCategories());
            if (id > 0) c.image = AttachmentStore.firstImage(app, id, 1440);
            return c;
        }, this::bind);
    }

    private void bind(Content c) {
        if (isFinishing()) return;
        Schedule s = c.schedule;
        if (s == null) {
            finish();
            return;
        }
        schedule = s;
        if (occStart == 0) occStart = s.occStart;
        if (occEnd == 0) occEnd = ScheduleEngine.windowOf(s, occStart, java.time.ZoneId.systemDefault()).end;
        categoryView.setText(getString(R.string.item_category_format, Formats.typeName(this, s.type), c.category.name(this)));
        titleView.setText(s.displayTitle());
        titleView.setVisibility(s.title == null || s.title.trim().isEmpty() ? View.GONE : View.VISIBLE);
        bodyView.setText(s.body);
        bodyView.setVisibility(s.body == null || s.body.trim().isEmpty() ? View.GONE : View.VISIBLE);
        boolean hasQuote = c.quote != null && !c.quote.trim().isEmpty();
        quoteView.setVisibility(hasQuote ? View.VISIBLE : View.GONE);
        if (hasQuote) quoteView.setText(getString(R.string.alarm_quote_format, c.quote.trim()));
        if (c.image != null) {
            imageView.setImageBitmap(c.image);
            imageView.setVisibility(View.VISIBLE);
        } else {
            imageView.setVisibility(View.GONE);
        }
        scheduledView.setText(getString(test ? R.string.alarm_test_label : R.string.alarm_scheduled,
                Formats.time(this, occStart), Formats.time(this, occEnd)));
        bindChecklist(c.checklist);
        boolean real = s.id > 0 && !test;
        completeButton.setVisibility(real && s.isTracked() ? View.VISIBLE : View.GONE);
        detailsButton.setVisibility(real ? View.VISIBLE : View.GONE);
        updateState();
    }

    private void bindChecklist(List<ChecklistItem> items) {
        checklistBox.removeAllViews();
        boolean any = items != null && !items.isEmpty();
        checklistTitle.setVisibility(any ? View.VISIBLE : View.GONE);
        if (!any) return;
        android.content.res.ColorStateList tint = android.content.res.ColorStateList.valueOf(bodyView.getCurrentTextColor());
        final android.content.Context app = getApplicationContext();
        for (ChecklistItem i : items) {
            CheckBox box = new CheckBox(this);
            box.setText(i.text);
            box.setChecked(i.done);
            box.setTextColor(bodyView.getCurrentTextColor());
            box.setButtonTintList(tint);
            box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            box.setOnCheckedChangeListener((b, checked) ->
                    AppExecutors.io(() -> new ContentRepository(app).setChecklistDone(i.id, checked)));
            checklistBox.addView(box);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        ActiveAlarm.addListener(this);
        handler.post(tick);
    }

    @Override
    protected void onStop() {
        super.onStop();
        ActiveAlarm.removeListener(this);
        handler.removeCallbacks(tick);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateState();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    public void onActiveAlarmChanged(ActiveAlarm.Session current) {
        updateState();
    }

    private boolean ringingHere() {
        ActiveAlarm.Session s = ActiveAlarm.get();
        return s != null && s.scheduleId == scheduleId;
    }

    private void updateState() {
        if (schedule == null) return;
        long now = System.currentTimeMillis();
        boolean ringing = ringingHere();
        boolean over = occEnd > 0 && now >= occEnd;

        if (over && schedule.autoDismiss && !ringing) {
            finish();
            return;
        }
        if (over) {
            remainingView.setText(R.string.alarm_window_over);
        } else {
            remainingView.setText(getString(R.string.alarm_remaining, Formats.remaining(occEnd - now)));
        }
        if (schedule.deadlineAt > 0) {
            deadlineView.setVisibility(View.VISIBLE);
            deadlineView.setText(Formats.deadlineLine(this, schedule));
        } else {
            deadlineView.setVisibility(View.GONE);
        }

        stopButton.setVisibility(ringing ? View.VISIBLE : View.GONE);
        boolean snoozable = !test && !over && ScheduleEngine.canSnooze(schedule, now, occEnd);
        snoozeButton.setVisibility(snoozable ? View.VISIBLE : View.GONE);
        if (snoozable) snoozeButton.setText(getString(R.string.action_snooze_n, schedule.snoozeMinutes));
        dismissButton.setText(over ? R.string.action_close : R.string.action_dismiss);
        endedView.setVisibility(!ringing && !over ? View.VISIBLE : View.GONE);
        endedView.setText(R.string.alarm_silenced);

        StringBuilder warn = new StringBuilder();
        ActiveAlarm.Session s = ActiveAlarm.get();
        if (ringing && s != null) {
            if (s.soundFailed) warn.append(getString(R.string.alarm_sound_failed));
            else if (s.fallbackSound) warn.append(getString(R.string.alarm_sound_fallback));
            if (AlarmPlayer.alarmStreamLevel(this) < 0.05f) {
                if (warn.length() > 0) warn.append('\n');
                warn.append(getString(R.string.alarm_volume_zero));
            }
        }
        warningView.setText(warn.toString());
        warningView.setVisibility(warn.length() > 0 ? View.VISIBLE : View.GONE);

        if (settings.keepScreenOn() && (ringing || !over)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void act(Runnable work, boolean closeAfter) {
        if (closeAfter && occEnd > 0 && System.currentTimeMillis() >= occEnd && !ringingHere()) {
            finish();
            return;
        }
        AppExecutors.io(() -> {
            work.run();
            return true;
        }, ok -> {
            if (closeAfter) finish();
            else updateState();
        });
    }

    /** Asks Android for the normal unlock, then opens the details. */
    private void openDetails() {
        Intent details = new Intent(this, ReminderDetailActivity.class).putExtra(ReminderDetailActivity.EXTRA_ID, scheduleId);
        KeyguardManager km = getSystemService(KeyguardManager.class);
        if (km != null && km.isKeyguardLocked() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            km.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
                @Override
                public void onDismissSucceeded() {
                    startActivity(details);
                }
            });
        } else {
            startActivity(details);
        }
    }
}
