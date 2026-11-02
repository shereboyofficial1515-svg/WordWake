package com.wordwake.app.ui;

import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.wordwake.app.R;
import com.wordwake.app.data.AppExecutors;
import com.wordwake.app.data.ContentRepository;
import com.wordwake.app.data.ScheduleRepository;
import com.wordwake.app.engine.ReminderStatus;
import com.wordwake.app.engine.ScheduleEngine;
import com.wordwake.app.model.Category;
import com.wordwake.app.model.Schedule;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** All reminders: search, filter by status, type, category and date, in chronological order. */
public class RemindersFragment extends Fragment {

    private static final int STATUS_ALL = 0, STATUS_ACTIVE = 1, STATUS_DUE = 2, STATUS_OVERDUE = 3, STATUS_DONE = 4, STATUS_PAUSED = 5;
    private static final int DATE_ANY = 0, DATE_TODAY = 1, DATE_WEEK = 2, DATE_MONTH = 3;

    private ScheduleAdapter adapter;
    private TextView emptyText, categoryFilterButton, typeFilterButton, dateFilterButton;
    private String query = "";
    private int statusFilter = STATUS_ALL;
    private int dateFilter = DATE_ANY;
    private int typeFilter = -1;
    private String categoryFilter;
    private List<Category> customCategories = new ArrayList<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        return inflater.inflate(R.layout.fragment_reminders, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle state) {
        ReminderActions actions = new ReminderActions(requireActivity(), this::refresh);
        RecyclerView list = v.findViewById(R.id.reminder_list);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new ScheduleAdapter(requireContext(), actions);
        list.setAdapter(adapter);
        emptyText = v.findViewById(R.id.reminders_empty);

        ((EditText) v.findViewById(R.id.search_input)).addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) {
                query = e.toString().trim().toLowerCase(Locale.getDefault());
                refresh();
            }
        });

        ChipGroup statuses = v.findViewById(R.id.status_filters);
        int[][] options = {{STATUS_ALL, R.string.filter_all}, {STATUS_ACTIVE, R.string.filter_active},
                {STATUS_DUE, R.string.badge_due_today}, {STATUS_OVERDUE, R.string.badge_overdue},
                {STATUS_DONE, R.string.badge_completed}, {STATUS_PAUSED, R.string.badge_paused}};
        for (int[] o : options) {
            Chip chip = new Chip(requireContext(), null, com.google.android.material.R.attr.chipStyle);
            chip.setText(o[1]);
            chip.setCheckable(true);
            chip.setChecked(o[0] == statusFilter);
            chip.setId(View.generateViewId());
            chip.setOnClickListener(c -> {
                statusFilter = o[0];
                refresh();
            });
            statuses.addView(chip);
        }
        statuses.setSingleSelection(true);
        statuses.setSelectionRequired(true);

        categoryFilterButton = v.findViewById(R.id.filter_category);
        typeFilterButton = v.findViewById(R.id.filter_type);
        dateFilterButton = v.findViewById(R.id.filter_date);
        categoryFilterButton.setOnClickListener(c -> chooseCategory());
        typeFilterButton.setOnClickListener(c -> chooseType());
        dateFilterButton.setOnClickListener(c -> chooseDate());
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    private void chooseCategory() {
        List<Category> all = Category.all(customCategories);
        String[] labels = new String[all.size() + 1];
        labels[0] = getString(R.string.filter_all_categories);
        for (int i = 0; i < all.size(); i++) labels[i + 1] = all.get(i).name(requireContext());
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.filter_category)
                .setItems(labels, (d, w) -> {
                    categoryFilter = w == 0 ? null : all.get(w - 1).key;
                    categoryFilterButton.setText(labels[w]);
                    refresh();
                })
                .show();
    }

    private void chooseType() {
        int[] types = {Schedule.TYPE_ALARM, Schedule.TYPE_REMINDER, Schedule.TYPE_TASK, Schedule.TYPE_APPOINTMENT,
                Schedule.TYPE_DEADLINE, Schedule.TYPE_TAX, Schedule.TYPE_BILL, Schedule.TYPE_QUOTE};
        String[] labels = new String[types.length + 1];
        labels[0] = getString(R.string.filter_all_types);
        for (int i = 0; i < types.length; i++) labels[i + 1] = Formats.typeName(requireContext(), types[i]);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.filter_type)
                .setItems(labels, (d, w) -> {
                    typeFilter = w == 0 ? -1 : types[w - 1];
                    typeFilterButton.setText(labels[w]);
                    refresh();
                })
                .show();
    }

    private void chooseDate() {
        String[] labels = {getString(R.string.filter_any_date), getString(R.string.filter_today),
                getString(R.string.filter_this_week), getString(R.string.filter_this_month)};
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.filter_date)
                .setItems(labels, (d, w) -> {
                    dateFilter = w;
                    dateFilterButton.setText(labels[w]);
                    refresh();
                })
                .show();
    }

    private void refresh() {
        final Context app = requireContext().getApplicationContext();
        final String q = query;
        final int statusF = statusFilter, dateF = dateFilter, typeF = typeFilter;
        final String catF = categoryFilter;
        AppExecutors.io(() -> {
            List<Category> custom = new ContentRepository(app).customCategories();
            customCategories = custom;
            long now = System.currentTimeMillis();
            ZoneId zone = ZoneId.systemDefault();
            List<Schedule> match = new ArrayList<>();
            for (Schedule s : new ScheduleRepository(app).getAll()) {
                if (catF != null && !catF.equals(s.categoryKey)) continue;
                if (typeF >= 0 && s.type != typeF) continue;
                if (!matchesStatus(s, statusF, now, zone) || !matchesDate(s, dateF, zone)) continue;
                if (!q.isEmpty() && !(s.title + " " + s.body + " " + s.quote).toLowerCase(Locale.getDefault()).contains(q)) continue;
                match.add(s);
            }
            match.sort(Comparator.comparingLong(ReminderStatus::sortKey));
            List<ScheduleAdapter.Row> rows = new ArrayList<>();
            for (Schedule s : match) rows.add(ScheduleAdapter.buildRow(app, s, custom));
            return rows;
        }, rows -> {
            if (!isAdded()) return;
            adapter.submit(rows);
            emptyText.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        });
    }

    private static boolean matchesStatus(Schedule s, int filter, long now, ZoneId zone) {
        int status = ReminderStatus.of(s, now, zone);
        switch (filter) {
            case STATUS_ACTIVE: return s.enabled && !s.isCompleted();
            case STATUS_DUE: return status == ReminderStatus.DUE_TODAY;
            case STATUS_OVERDUE: return status == ReminderStatus.OVERDUE;
            case STATUS_DONE: return status == ReminderStatus.COMPLETED;
            case STATUS_PAUSED: return !s.enabled && !s.isCompleted();
            default: return true;
        }
    }

    private static boolean matchesDate(Schedule s, int filter, ZoneId deviceZone) {
        if (filter == DATE_ANY) return true;
        ZoneId zone = s.zone(deviceZone);
        LocalDate today = LocalDate.now(zone);
        LocalDate from = today;
        LocalDate to = today;
        if (filter == DATE_WEEK) {
            from = today.with(DayOfWeek.MONDAY);
            to = from.plusDays(6);
        } else if (filter == DATE_MONTH) {
            from = today.withDayOfMonth(1);
            to = today.withDayOfMonth(today.lengthOfMonth());
        }
        if (s.deadlineAt > 0) {
            LocalDate d = java.time.Instant.ofEpochMilli(s.deadlineAt).atZone(zone).toLocalDate();
            if (!d.isBefore(from) && !d.isAfter(to)) return true;
        }
        return !ScheduleEngine.datesInRange(s, from, to, deviceZone).isEmpty();
    }
}
