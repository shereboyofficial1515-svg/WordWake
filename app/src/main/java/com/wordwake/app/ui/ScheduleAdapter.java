package com.wordwake.app.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.wordwake.app.R;
import com.wordwake.app.data.ContentRepository;
import com.wordwake.app.engine.ReminderStatus;
import com.wordwake.app.model.Category;
import com.wordwake.app.model.ChecklistItem;
import com.wordwake.app.model.Schedule;
import com.wordwake.app.sound.SoundCatalog;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** Reminder cards, used by the dashboard, the reminder list and the calendar. */
public class ScheduleAdapter extends RecyclerView.Adapter<ScheduleAdapter.Holder> {

    public static final class Row {
        public final Schedule schedule;
        public final boolean soundMissing;
        public final int stepsDone;
        public final int stepsTotal;
        public final Category category;
        /** Overrides the "next alert" line, e.g. the date shown in the calendar's week view. */
        public String when;

        public Row(Schedule schedule, boolean soundMissing, int stepsDone, int stepsTotal, Category category) {
            this.schedule = schedule;
            this.soundMissing = soundMissing;
            this.stepsDone = stepsDone;
            this.stepsTotal = stepsTotal;
            this.category = category;
        }
    }

    /** Builds a row. Does disk I/O, so call it on the background thread. */
    public static Row buildRow(Context app, Schedule s, List<Category> custom) {
        ContentRepository content = new ContentRepository(app);
        List<ChecklistItem> items = content.checklist(s.id);
        int done = 0;
        for (ChecklistItem i : items) if (i.done) done++;
        boolean missing = s.alertMode == Schedule.ALERT_ALARM && !SoundCatalog.isAvailable(app, s.soundUri);
        return new Row(s, missing, done, items.size(), Category.find(s.categoryKey, custom));
    }

    private final Context context;
    private final ReminderActions actions;
    private final List<Row> rows = new ArrayList<>();

    public ScheduleAdapter(Context context, ReminderActions actions) {
        this.context = context;
        this.actions = actions;
    }

    public void submit(List<Row> newRows) {
        rows.clear();
        rows.addAll(newRows);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_schedule, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Row row = rows.get(position);
        Schedule s = row.schedule;
        long now = System.currentTimeMillis();

        h.iconHolder.removeAllViews();
        h.iconHolder.addView(Ui.categoryIcon(context, row.category.iconRes, row.category.color(context), 40));
        h.title.setText(s.displayTitle());
        h.category.setText(context.getString(R.string.item_category_format, Formats.typeName(context, s.type), row.category.name(context)));

        int status = ReminderStatus.of(s, now, ZoneId.systemDefault());
        h.badgeHolder.removeAllViews();
        h.badgeHolder.addView(Ui.badge(context, context.getString(Ui.statusLabel(status)), Ui.statusColor(context, status)));

        h.window.setText(Formats.window(context, s));
        h.repeat.setText(context.getString(R.string.item_repeat_format, Formats.repeat(context, s), Formats.alertStyle(context, s)));
        String preview = s.body == null ? "" : s.body.trim().replace('\n', ' ');
        h.preview.setText(preview);
        h.preview.setVisibility(preview.isEmpty() ? View.GONE : View.VISIBLE);

        String next;
        if (row.when != null) {
            next = row.when;
        } else if (s.enabled && s.nextTriggerAt > 0) {
            next = context.getString(R.string.item_next, Formats.dateTime(context, s.nextTriggerAt));
        } else if (s.lastOutcome == Schedule.OUTCOME_MISSED) {
            next = context.getString(R.string.item_missed);
        } else if (s.isCompleted() || (!s.enabled && s.isFiniteRepeat() && s.lastOutcome != Schedule.OUTCOME_NONE)) {
            next = context.getString(R.string.item_completed);
        } else if (!s.enabled) {
            next = context.getString(R.string.item_off);
        } else {
            next = context.getString(R.string.item_no_next);
        }
        h.next.setText(next);

        StringBuilder details = new StringBuilder();
        if (s.deadlineAt > 0) details.append(Formats.deadlineLine(context, s));
        if (row.stepsTotal > 0) {
            if (details.length() > 0) details.append(" · ");
            details.append(context.getString(R.string.item_steps, row.stepsDone, row.stepsTotal));
        }
        h.details.setText(details);
        h.details.setVisibility(details.length() == 0 ? View.GONE : View.VISIBLE);

        if (row.soundMissing) {
            h.warning.setVisibility(View.VISIBLE);
            h.warning.setText(R.string.item_sound_missing);
        } else if (s.enabled && s.nextTriggerAt > 0 && !s.nextExact) {
            h.warning.setVisibility(View.VISIBLE);
            h.warning.setText(R.string.item_inexact);
        } else {
            h.warning.setVisibility(View.GONE);
        }

        h.enabled.setOnCheckedChangeListener(null);
        h.enabled.setChecked(s.enabled);
        h.enabled.setEnabled(!s.isCompleted());
        h.enabled.setOnCheckedChangeListener((b, checked) -> actions.setActive(s, checked));
        h.itemView.setAlpha(s.enabled || s.deadlineAt > 0 ? 1f : 0.6f);
        h.itemView.setOnClickListener(v -> actions.open(s));
        h.more.setOnClickListener(v -> actions.showMenu(s, v));
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final TextView title, category, window, repeat, preview, next, details, warning;
        final MaterialSwitch enabled;
        final View more;
        final FrameLayout iconHolder, badgeHolder;

        Holder(View v) {
            super(v);
            title = v.findViewById(R.id.item_title);
            category = v.findViewById(R.id.item_category);
            window = v.findViewById(R.id.item_window);
            repeat = v.findViewById(R.id.item_repeat);
            preview = v.findViewById(R.id.item_preview);
            next = v.findViewById(R.id.item_next);
            details = v.findViewById(R.id.item_details);
            warning = v.findViewById(R.id.item_warning);
            enabled = v.findViewById(R.id.item_enabled);
            more = v.findViewById(R.id.item_more);
            iconHolder = v.findViewById(R.id.item_icon);
            badgeHolder = v.findViewById(R.id.item_badge);
        }
    }
}
