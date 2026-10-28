package com.wordwake.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.wordwake.app.R;
import com.wordwake.app.alarm.ActiveAlarm;
import com.wordwake.app.alarm.AlarmController;
import com.wordwake.app.alarm.AlarmScheduler;
import com.wordwake.app.data.AppExecutors;
import com.wordwake.app.data.AppSettings;
import com.wordwake.app.data.ScheduleRepository;
import com.wordwake.app.data.WordWakeDatabase;
import com.wordwake.app.engine.ScheduleEngine;
import com.wordwake.app.model.Schedule;
import com.wordwake.app.sound.SoundCatalog;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** Home dashboard: clock, next alert, alarm health, and the list of scheduled messages. */
public class MainActivity extends AppCompatActivity implements ScheduleAdapter.Callbacks, ActiveAlarm.Listener {

    private AppSettings settings;
    private ScheduleAdapter adapter;
    private TextView nextTitle, nextDetail, statusTitle, statusDetail, ringingText;
    private MaterialCardView statusCard, ringingCard;
    private View emptyState;
    private final android.os.Handler ticker = new android.os.Handler(android.os.Looper.getMainLooper());
    private List<ScheduleAdapter.Row> rows = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        settings = new AppSettings(this);
        setContentView(R.layout.activity_main);
        setSupportActionBar(findViewById(R.id.toolbar));

        nextTitle = findViewById(R.id.next_title);
        nextDetail = findViewById(R.id.next_detail);
        statusCard = findViewById(R.id.status_card);
        statusTitle = findViewById(R.id.status_title);
        statusDetail = findViewById(R.id.status_detail);
        ringingCard = findViewById(R.id.ringing_card);
        ringingText = findViewById(R.id.ringing_text);
        emptyState = findViewById(R.id.empty_state);

        RecyclerView list = findViewById(R.id.schedule_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setNestedScrollingEnabled(false);
        adapter = new ScheduleAdapter(this, this);
        list.setAdapter(adapter);

        findViewById(R.id.fab_new).setOnClickListener(v -> startActivity(new Intent(this, EditScheduleActivity.class)));
        findViewById(R.id.empty_create).setOnClickListener(v -> startActivity(new Intent(this, EditScheduleActivity.class)));
        findViewById(R.id.empty_example).setOnClickListener(v -> startActivity(
                new Intent(this, EditScheduleActivity.class).putExtra(EditScheduleActivity.EXTRA_PRESET, true)));
        statusCard.setOnClickListener(v -> startActivity(new Intent(this, PermissionsActivity.class)));
        ringingCard.setOnClickListener(v -> openRinging());

        if (!settings.onboardingDone()) {
            startActivity(new Intent(this, PermissionsActivity.class).putExtra(PermissionsActivity.EXTRA_ONBOARDING, true));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        ActiveAlarm.addListener(this);
        onActiveAlarmChanged(ActiveAlarm.get());
        refresh();
        updateStatus();
        if (WordWakeDatabase.consumeWasReset()) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.db_reset_title)
                    .setMessage(R.string.db_reset_message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        }
        ticker.postDelayed(tick, 30_000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ActiveAlarm.removeListener(this);
        ticker.removeCallbacks(tick);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateNext();
            ticker.postDelayed(this, 30_000);
        }
    };

    private void refresh() {
        AppExecutors.io(() -> {
            // Reconcile first so "next alert" reflects reality (also repairs alarms after force-stop).
            AlarmScheduler.reconcileAll(getApplicationContext(), "dashboard");
            List<Schedule> all = new ScheduleRepository(getApplicationContext()).getAll();
            List<ScheduleAdapter.Row> out = new ArrayList<>();
            for (Schedule s : all) {
                boolean missing = !SoundCatalog.isAvailable(getApplicationContext(), s.soundUri);
                out.add(new ScheduleAdapter.Row(s, missing));
            }
            return out;
        }, result -> {
            if (isFinishing() || isDestroyed()) return;
            rows = result;
            adapter.submit(result);
            emptyState.setVisibility(result.isEmpty() ? View.VISIBLE : View.GONE);
            updateNext();
        });
    }

    private void updateNext() {
        Schedule next = null;
        for (ScheduleAdapter.Row r : rows) {
            if (r.schedule.enabled && r.schedule.nextTriggerAt > 0
                    && (next == null || r.schedule.nextTriggerAt < next.nextTriggerAt)) {
                next = r.schedule;
            }
        }
        if (next == null) {
            nextTitle.setText(R.string.next_none);
            nextDetail.setText(rows.isEmpty() ? R.string.next_none_hint_empty : R.string.next_none_hint);
            return;
        }
        nextTitle.setText(getString(R.string.next_title_format, next.displayTitle()));
        long in = Math.max(0, next.nextTriggerAt - System.currentTimeMillis());
        String detail = getString(R.string.next_detail_format, Formats.dateTime(this, next.nextTriggerAt),
                Formats.duration(this, (int) Math.max(1, (in + 59_999) / 60_000)));
        if (!next.nextExact) detail += "\n" + getString(R.string.next_inexact_warning);
        nextDetail.setText(detail);
    }

    private void updateStatus() {
        int issues = PermissionStatus.criticalIssues(this);
        if (issues == 0) {
            statusTitle.setText(R.string.status_ok_title);
            statusDetail.setText(R.string.status_ok_detail);
            statusCard.setCardBackgroundColor(getColor(R.color.ok_container));
        } else {
            statusTitle.setText(getResources().getQuantityString(R.plurals.status_issues_title, issues, issues));
            statusDetail.setText(R.string.status_issues_detail);
            statusCard.setCardBackgroundColor(getColor(R.color.warn_container));
        }
    }

    @Override
    public void onActiveAlarmChanged(ActiveAlarm.Session current) {
        if (current == null) {
            ringingCard.setVisibility(View.GONE);
        } else {
            ringingCard.setVisibility(View.VISIBLE);
            ringingText.setText(current.test ? R.string.ringing_now_test : R.string.ringing_now);
        }
        refresh();
    }

    private void openRinging() {
        ActiveAlarm.Session s = ActiveAlarm.get();
        if (s == null) return;
        startActivity(new Intent(this, AlarmActivity.class)
                .putExtra(AlarmActivity.EXTRA_SCHEDULE_ID, s.scheduleId)
                .putExtra(AlarmActivity.EXTRA_OCC_START, s.occStart)
                .putExtra(AlarmActivity.EXTRA_OCC_END, s.occEnd)
                .putExtra(AlarmActivity.EXTRA_TEST, s.test));
    }

    // ---- list callbacks ----

    @Override
    public void onOpen(Schedule s) {
        startActivity(new Intent(this, EditScheduleActivity.class).putExtra(EditScheduleActivity.EXTRA_ID, s.id));
    }

    @Override
    public void onToggle(Schedule s, boolean enabled) {
        final android.content.Context app = getApplicationContext();
        AppExecutors.io(() -> {
            ScheduleRepository repo = new ScheduleRepository(app);
            Schedule cur = repo.get(s.id);
            if (cur == null) return 0;
            if (enabled) {
                if (cur.repeatMode == Schedule.REPEAT_ONCE
                        && ScheduleEngine.validate(cur, System.currentTimeMillis(), ZoneId.systemDefault())
                        .contains(ScheduleEngine.ERR_IN_PAST)) {
                    return R.string.toggle_once_in_past;
                }
                cur.enabled = true;
                cur.resetRuntimeState();
                EditScheduleActivity.skipInProgressOccurrence(cur);
                repo.save(cur);
                AlarmScheduler.reschedule(app, cur);
            } else {
                AlarmController.onDisabledOrDeleted(app, cur.id);
                cur.enabled = false;
                cur.nextTriggerAt = 0;
                repo.save(cur);
            }
            return 0;
        }, msg -> {
            if (msg != 0) Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            refresh();
        });
    }

    @Override
    public void onMenu(Schedule s, View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenuInflater().inflate(R.menu.schedule_item, menu.getMenu());
        menu.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_edit) {
                onOpen(s);
            } else if (id == R.id.action_duplicate) {
                duplicate(s);
            } else if (id == R.id.action_test) {
                AppExecutors.io(() -> AlarmController.test(getApplicationContext(), s.id));
                Toast.makeText(this, R.string.test_started, Toast.LENGTH_SHORT).show();
            } else if (id == R.id.action_delete) {
                confirmDelete(s);
            }
            return true;
        });
        menu.show();
    }

    private void duplicate(Schedule s) {
        AppExecutors.io(() -> {
            Schedule copy = s.copy();
            copy.id = 0;
            copy.createdAt = 0;
            copy.title = getString(R.string.copy_title_format, s.displayTitle());
            copy.resetRuntimeState();
            // Duplicates start disabled so the user never gets two identical alarms by surprise.
            copy.enabled = false;
            new ScheduleRepository(getApplicationContext()).save(copy);
            return copy.id;
        }, id -> {
            Toast.makeText(this, R.string.duplicated, Toast.LENGTH_LONG).show();
            refresh();
        });
    }

    private void confirmDelete(Schedule s) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.delete_title)
                .setMessage(getString(R.string.delete_message, s.displayTitle()))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    final android.content.Context app = getApplicationContext();
                    AppExecutors.io(() -> {
                        AlarmController.onDisabledOrDeleted(app, s.id);
                        new ScheduleRepository(app).delete(s.id);
                        return true;
                    }, ok -> refresh());
                })
                .show();
    }

    // ---- toolbar ----

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_sounds) {
            startActivity(new Intent(this, SoundLibraryActivity.class));
            return true;
        } else if (id == R.id.action_permissions) {
            startActivity(new Intent(this, PermissionsActivity.class));
            return true;
        } else if (id == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
