package com.wordwake.app.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.wordwake.app.R;
import com.wordwake.app.alarm.ActiveAlarm;
import com.wordwake.app.alarm.AlarmScheduler;
import com.wordwake.app.data.AppExecutors;
import com.wordwake.app.data.ContentRepository;
import com.wordwake.app.data.ScheduleRepository;
import com.wordwake.app.engine.ReminderStatus;
import com.wordwake.app.model.Category;
import com.wordwake.app.model.Schedule;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Dashboard: what is happening today, what is next, and how the work is going. */
public class HomeFragment extends Fragment implements ActiveAlarm.Listener {

    private static final int UPCOMING_LIMIT = 6;

    private ScheduleAdapter adapter;
    private TextView greeting, nextTitle, nextDetail, statusTitle, statusDetail, ringingText, productivityText;
    private TextView statToday, statAppointments, statTasks, statOverdue, statCompleted, statPending;
    private MaterialCardView statusCard, ringingCard;
    private View emptyState, overviewSection;
    private LinearProgressIndicator progress;
    private ReminderStatus.Overview overview;
    private final Handler ticker = new Handler(Looper.getMainLooper());

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateNext();
            ticker.postDelayed(this, 30_000);
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle state) {
        greeting = v.findViewById(R.id.greeting);
        nextTitle = v.findViewById(R.id.next_title);
        nextDetail = v.findViewById(R.id.next_detail);
        statusCard = v.findViewById(R.id.status_card);
        statusTitle = v.findViewById(R.id.status_title);
        statusDetail = v.findViewById(R.id.status_detail);
        ringingCard = v.findViewById(R.id.ringing_card);
        ringingText = v.findViewById(R.id.ringing_text);
        emptyState = v.findViewById(R.id.empty_state);
        overviewSection = v.findViewById(R.id.overview_section);
        productivityText = v.findViewById(R.id.productivity_text);
        progress = v.findViewById(R.id.productivity_progress);
        LinearLayout grid = v.findViewById(R.id.stat_grid);
        statToday = addStat(grid, 0, R.string.stat_today);
        statAppointments = addStat(grid, 0, R.string.stat_appointments);
        statTasks = addStat(grid, 0, R.string.stat_tasks_today);
        statOverdue = addStat(grid, 1, R.string.stat_overdue);
        statCompleted = addStat(grid, 1, R.string.stat_completed);
        statPending = addStat(grid, 1, R.string.stat_pending);

        ReminderActions actions = new ReminderActions(requireActivity(), this::refresh);
        RecyclerView list = v.findViewById(R.id.schedule_list);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        list.setNestedScrollingEnabled(false);
        adapter = new ScheduleAdapter(requireContext(), actions);
        list.setAdapter(adapter);

        ChipGroup quick = v.findViewById(R.id.quick_actions);
        for (int[] t : new int[][]{
                {Schedule.TYPE_REMINDER, R.string.quick_reminder}, {Schedule.TYPE_ALARM, R.string.quick_alarm},
                {Schedule.TYPE_TAX, R.string.quick_tax}, {Schedule.TYPE_APPOINTMENT, R.string.quick_appointment},
                {Schedule.TYPE_TASK, R.string.quick_task}, {Schedule.TYPE_QUOTE, R.string.quick_quote}}) {
            Chip chip = new Chip(requireContext());
            chip.setText(t[1]);
            chip.setOnClickListener(c -> MainActivity.QuickCreate.start(requireContext(), t[0]));
            quick.addView(chip);
        }

        v.findViewById(R.id.empty_create).setOnClickListener(c -> MainActivity.QuickCreate.start(requireContext(), Schedule.TYPE_REMINDER));
        v.findViewById(R.id.empty_example).setOnClickListener(c -> startActivity(
                new Intent(requireContext(), EditScheduleActivity.class).putExtra(EditScheduleActivity.EXTRA_PRESET, true)));
        v.findViewById(R.id.see_all).setOnClickListener(c -> ((MainActivity) requireActivity()).selectTab(R.id.nav_reminders));
        ringingCard.setOnClickListener(c -> openRinging());
    }

    private TextView addStat(LinearLayout grid, int rowIndex, int labelRes) {
        LinearLayout row = (LinearLayout) grid.getChildAt(rowIndex);
        Context c = requireContext();
        MaterialCardView tile = Ui.card(c);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        int m = Ui.dp(c, 4);
        lp.setMargins(m, m, m, m);
        tile.setLayoutParams(lp);
        LinearLayout body = Ui.vertical(c);
        int p = Ui.dp(c, 12);
        body.setPadding(p, p, p, p);
        TextView value = Ui.text(c, "0", com.google.android.material.R.style.TextAppearance_Material3_HeadlineMedium);
        value.setTextColor(c.getColor(R.color.brand_primary));
        body.addView(value);
        TextView label = Ui.text(c, getString(labelRes), com.google.android.material.R.style.TextAppearance_Material3_LabelMedium);
        label.setTextColor(c.getColor(R.color.text_secondary));
        body.addView(label);
        tile.addView(body);
        row.addView(tile);
        return value;
    }

    @Override
    public void onResume() {
        super.onResume();
        ActiveAlarm.addListener(this);
        onActiveAlarmChanged(ActiveAlarm.get());
        int hour = LocalTime.now().getHour();
        greeting.setText(hour < 12 ? R.string.greeting_morning : hour < 17 ? R.string.greeting_afternoon : R.string.greeting_evening);
        refresh();
        ticker.postDelayed(tick, 30_000);
    }

    @Override
    public void onPause() {
        super.onPause();
        ActiveAlarm.removeListener(this);
        ticker.removeCallbacks(tick);
    }

    private static final class Data {
        boolean deliveryProblem;
        ReminderStatus.Overview overview;
        List<ScheduleAdapter.Row> upcoming = new ArrayList<>();
        boolean empty;
    }

    private void refresh() {
        final Context app = requireContext().getApplicationContext();
        AppExecutors.io(() -> {
            // Repairs alarms when the app is opened, including after a force stop or a held alarm.
            AlarmScheduler.reconcileAll(app, "dashboard");
            List<Schedule> all = new ScheduleRepository(app).getAll();
            List<Category> custom = new ContentRepository(app).customCategories();
            Data d = new Data();
            d.empty = all.isEmpty();
            d.deliveryProblem = PermissionStatus.deliveryProblem(app);
            d.overview = ReminderStatus.overview(all, System.currentTimeMillis(), ZoneId.systemDefault());
            List<Schedule> open = new ArrayList<>();
            for (Schedule s : all) {
                if (!s.isCompleted() && ReminderStatus.sortKey(s) != Long.MAX_VALUE) open.add(s);
            }
            open.sort(Comparator.comparingLong(ReminderStatus::sortKey));
            for (int i = 0; i < Math.min(UPCOMING_LIMIT, open.size()); i++) {
                d.upcoming.add(ScheduleAdapter.buildRow(app, open.get(i), custom));
            }
            return d;
        }, d -> {
            if (!isAdded()) return;
            overview = d.overview;
            updateStatus(d.deliveryProblem);
            adapter.submit(d.upcoming);
            emptyState.setVisibility(d.empty ? View.VISIBLE : View.GONE);
            overviewSection.setVisibility(d.empty ? View.GONE : View.VISIBLE);
            statToday.setText(String.valueOf(overview.today));
            statAppointments.setText(String.valueOf(overview.appointments));
            statTasks.setText(String.valueOf(overview.tasksDueToday));
            statOverdue.setText(String.valueOf(overview.overdue));
            statOverdue.setTextColor(requireContext().getColor(overview.overdue > 0 ? R.color.error_text : R.color.brand_primary));
            statCompleted.setText(String.valueOf(overview.completed));
            statPending.setText(String.valueOf(overview.pending));
            progress.setProgressCompat(overview.completionPercent(), true);
            productivityText.setText(getString(R.string.productivity_format, overview.completionPercent(), overview.total));
            updateNext();
        });
    }

    private void updateNext() {
        if (overview == null) return;
        Schedule next = overview.next;
        if (next == null) {
            nextTitle.setText(R.string.next_none);
            nextDetail.setText(overview.total == 0 ? R.string.next_none_hint_empty : R.string.next_none_hint);
            return;
        }
        nextTitle.setText(next.displayTitle());
        long in = Math.max(0, next.nextTriggerAt - System.currentTimeMillis());
        String detail = getString(R.string.next_detail_format, Formats.dateTime(requireContext(), next.nextTriggerAt),
                Formats.duration(requireContext(), (int) Math.max(1, (in + 59_999) / 60_000)));
        if (!next.nextExact) detail += "\n" + getString(R.string.next_inexact_warning);
        nextDetail.setText(detail);
    }

    private void updateStatus(boolean deliveryProblem) {
        Context c = requireContext();
        int issues = PermissionStatus.criticalIssues(c);
        statusCard.setOnClickListener(v -> startActivity(new Intent(c, deliveryProblem ? DiagnosticsActivity.class : PermissionsActivity.class)));
        if (deliveryProblem) {
            statusTitle.setText(R.string.status_delivery_title);
            statusDetail.setText(R.string.status_delivery_detail);
            statusCard.setCardBackgroundColor(c.getColor(R.color.warn_container));
        } else if (issues == 0) {
            statusTitle.setText(R.string.status_ok_title);
            statusDetail.setText(R.string.status_ok_detail);
            statusCard.setCardBackgroundColor(c.getColor(R.color.ok_container));
        } else {
            statusTitle.setText(getResources().getQuantityString(R.plurals.status_issues_title, issues, issues));
            statusDetail.setText(R.string.status_issues_detail);
            statusCard.setCardBackgroundColor(c.getColor(R.color.warn_container));
        }
    }

    @Override
    public void onActiveAlarmChanged(ActiveAlarm.Session current) {
        if (!isAdded()) return;
        if (current == null) {
            ringingCard.setVisibility(View.GONE);
        } else {
            ringingCard.setVisibility(View.VISIBLE);
            ringingText.setText(current.test ? R.string.ringing_now_test : R.string.ringing_now);
        }
    }

    private void openRinging() {
        ActiveAlarm.Session s = ActiveAlarm.get();
        if (s == null) return;
        startActivity(new Intent(requireContext(), AlarmActivity.class)
                .putExtra(AlarmActivity.EXTRA_SCHEDULE_ID, s.scheduleId)
                .putExtra(AlarmActivity.EXTRA_OCC_START, s.occStart)
                .putExtra(AlarmActivity.EXTRA_OCC_END, s.occEnd)
                .putExtra(AlarmActivity.EXTRA_TEST, s.test));
    }
}
