package com.wordwake.app.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButtonToggleGroup;
import com.wordwake.app.R;
import com.wordwake.app.data.AppExecutors;
import com.wordwake.app.data.ContentRepository;
import com.wordwake.app.data.ScheduleRepository;
import com.wordwake.app.engine.ScheduleEngine;
import com.wordwake.app.model.Category;
import com.wordwake.app.model.Schedule;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Month calendar with a planner for the selected day or week. */
public class CalendarFragment extends Fragment {

    private GridLayout grid;
    private TextView monthTitle, plannerTitle, plannerEmpty;
    private ScheduleAdapter adapter;
    private YearMonth month = YearMonth.now();
    private LocalDate selected = LocalDate.now();
    private boolean weekMode;
    private List<Schedule> all = new ArrayList<>();
    private List<Category> custom = new ArrayList<>();
    private final Map<LocalDate, Integer> counts = new HashMap<>();
    private final Map<LocalDate, Boolean> deadlines = new HashMap<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        return inflater.inflate(R.layout.fragment_calendar, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle state) {
        grid = v.findViewById(R.id.month_grid);
        monthTitle = v.findViewById(R.id.month_title);
        plannerTitle = v.findViewById(R.id.planner_title);
        plannerEmpty = v.findViewById(R.id.planner_empty);
        RecyclerView list = v.findViewById(R.id.planner_list);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        list.setNestedScrollingEnabled(false);
        adapter = new ScheduleAdapter(requireContext(), new ReminderActions(requireActivity(), this::load));
        list.setAdapter(adapter);
        v.findViewById(R.id.month_prev).setOnClickListener(c -> {
            month = month.minusMonths(1);
            render();
        });
        v.findViewById(R.id.month_next).setOnClickListener(c -> {
            month = month.plusMonths(1);
            render();
        });
        v.findViewById(R.id.month_today).setOnClickListener(c -> {
            month = YearMonth.now();
            selected = LocalDate.now();
            render();
        });
        MaterialButtonToggleGroup mode = v.findViewById(R.id.planner_mode);
        mode.check(R.id.mode_day);
        mode.addOnButtonCheckedListener((g, id, checked) -> {
            if (!checked) return;
            weekMode = id == R.id.mode_week;
            renderPlanner();
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        final Context app = requireContext().getApplicationContext();
        AppExecutors.io(() -> {
            custom = new ContentRepository(app).customCategories();
            return new ScheduleRepository(app).getAll();
        }, schedules -> {
            if (!isAdded()) return;
            all = schedules;
            render();
        });
    }

    private void render() {
        monthTitle.setText(month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())));
        LocalDate first = month.atDay(1);
        LocalDate last = month.atEndOfMonth();
        computeMarkers(first, last);
        grid.removeAllViews();
        Context c = requireContext();
        for (DayOfWeek d : DayOfWeek.values()) {
            TextView h = cell(d.getDisplayName(TextStyle.NARROW, Locale.getDefault()));
            h.setTextColor(c.getColor(R.color.text_secondary));
            grid.addView(h);
        }
        int offset = first.getDayOfWeek().getValue() - 1;
        for (int i = 0; i < offset; i++) grid.addView(cell(""));
        for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) grid.addView(dayCell(d));
        renderPlanner();
    }

    private void computeMarkers(LocalDate from, LocalDate to) {
        counts.clear();
        deadlines.clear();
        ZoneId zone = ZoneId.systemDefault();
        for (Schedule s : all) {
            if (s.isCompleted()) continue;
            if (s.enabled) {
                for (LocalDate d : ScheduleEngine.datesInRange(s, from, to, zone)) {
                    Integer n = counts.get(d);
                    counts.put(d, n == null ? 1 : n + 1);
                }
            }
            if (s.deadlineAt > 0) {
                LocalDate d = Instant.ofEpochMilli(s.deadlineAt).atZone(s.zone(zone)).toLocalDate();
                if (!d.isBefore(from) && !d.isAfter(to)) deadlines.put(d, true);
            }
        }
    }

    private TextView cell(String text) {
        Context c = requireContext();
        TextView t = Ui.text(c, text, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        t.setGravity(Gravity.CENTER);
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED),
                GridLayout.spec(GridLayout.UNDEFINED, 1f));
        lp.width = 0;
        lp.height = Ui.dp(c, 46);
        t.setLayoutParams(lp);
        return t;
    }

    private View dayCell(LocalDate d) {
        Context c = requireContext();
        boolean isSelected = d.equals(selected);
        boolean isToday = d.equals(LocalDate.now());
        Integer n = counts.get(d);
        boolean hasDeadline = deadlines.containsKey(d);

        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED),
                GridLayout.spec(GridLayout.UNDEFINED, 1f));
        lp.width = 0;
        lp.height = Ui.dp(c, 46);
        box.setLayoutParams(lp);
        if (isSelected) {
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(Ui.dp(c, 12));
            bg.setColor(c.getColor(R.color.brand_primary));
            box.setBackground(bg);
        }
        TextView day = Ui.text(c, String.valueOf(d.getDayOfMonth()), com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        day.setGravity(Gravity.CENTER);
        if (isToday) day.setTypeface(day.getTypeface(), Typeface.BOLD);
        if (isSelected) day.setTextColor(c.getColor(R.color.brand_on_primary));
        else if (isToday) day.setTextColor(c.getColor(R.color.brand_primary));
        box.addView(day);

        LinearLayout dots = new LinearLayout(c);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        dots.setGravity(Gravity.CENTER);
        if (n != null) dots.addView(dot(isSelected ? c.getColor(R.color.brand_on_primary) : c.getColor(R.color.brand_primary)));
        if (hasDeadline) dots.addView(dot(c.getColor(R.color.error_text)));
        box.addView(dots);

        StringBuilder desc = new StringBuilder(Formats.date(d));
        if (n != null) desc.append(", ").append(getResources().getQuantityString(R.plurals.calendar_reminders, n, n));
        if (hasDeadline) desc.append(", ").append(getString(R.string.calendar_deadline));
        box.setContentDescription(desc);
        box.setOnClickListener(v -> {
            selected = d;
            render();
        });
        return box;
    }

    private View dot(int color) {
        Context c = requireContext();
        View v = new View(c);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(color);
        v.setBackground(bg);
        int size = Ui.dp(c, 5);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        int m = Ui.dp(c, 1);
        lp.setMargins(m, m, m, 0);
        v.setLayoutParams(lp);
        return v;
    }

    private void renderPlanner() {
        final LocalDate from = weekMode ? selected.with(DayOfWeek.MONDAY) : selected;
        final LocalDate to = weekMode ? from.plusDays(6) : selected;
        plannerTitle.setText(weekMode
                ? getString(R.string.planner_week_format, Formats.date(from), Formats.date(to))
                : Formats.date(selected));
        final Context app = requireContext().getApplicationContext();
        final List<Schedule> snapshot = all;
        final List<Category> cats = custom;
        AppExecutors.io(() -> {
            List<ScheduleAdapter.Row> rows = new ArrayList<>();
            ZoneId zone = ZoneId.systemDefault();
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                List<ScheduleAdapter.Row> day = new ArrayList<>();
                for (Schedule s : snapshot) {
                    boolean alert = s.enabled && !s.isCompleted() && !ScheduleEngine.datesInRange(s, d, d, zone).isEmpty();
                    boolean deadline = s.deadlineAt > 0
                            && Instant.ofEpochMilli(s.deadlineAt).atZone(s.zone(zone)).toLocalDate().equals(d);
                    if (!alert && !deadline) continue;
                    ScheduleAdapter.Row row = ScheduleAdapter.buildRow(app, s, cats);
                    String time = alert ? Formats.minuteOfDay(app, s.startMinute) : app.getString(R.string.calendar_deadline);
                    if (alert && deadline) time += " · " + app.getString(R.string.calendar_deadline);
                    row.when = Formats.date(d) + " · " + time;
                    day.add(row);
                }
                day.sort((a, b) -> Integer.compare(a.schedule.startMinute, b.schedule.startMinute));
                rows.addAll(day);
            }
            return rows;
        }, rows -> {
            if (!isAdded()) return;
            adapter.submit(rows);
            plannerEmpty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        });
    }
}
