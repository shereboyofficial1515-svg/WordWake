package com.wordwake.app.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.wordwake.app.R;
import com.wordwake.app.model.Schedule;

import java.util.ArrayList;
import java.util.List;

public class ScheduleAdapter extends RecyclerView.Adapter<ScheduleAdapter.Holder> {

    public static final class Row {
        public final Schedule schedule;
        public final boolean soundMissing;

        public Row(Schedule schedule, boolean soundMissing) {
            this.schedule = schedule;
            this.soundMissing = soundMissing;
        }
    }

    public interface Callbacks {
        void onOpen(Schedule s);
        void onToggle(Schedule s, boolean enabled);
        void onMenu(Schedule s, View anchor);
    }

    private final Context context;
    private final Callbacks callbacks;
    private final List<Row> rows = new ArrayList<>();

    public ScheduleAdapter(Context context, Callbacks callbacks) {
        this.context = context;
        this.callbacks = callbacks;
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
        h.title.setText(s.displayTitle());
        h.window.setText(Formats.window(context, s));
        h.repeat.setText(Formats.repeat(context, s) + " · " + Formats.alertStyle(context, s));
        String preview = s.body == null ? "" : s.body.trim().replace('\n', ' ');
        h.preview.setText(preview);
        h.preview.setVisibility(preview.isEmpty() ? View.GONE : View.VISIBLE);

        String next;
        if (s.enabled && s.nextTriggerAt > 0) {
            next = context.getString(R.string.item_next, Formats.dateTime(context, s.nextTriggerAt));
        } else if (s.lastOutcome == Schedule.OUTCOME_MISSED) {
            next = context.getString(R.string.item_missed);
        } else if (!s.enabled && s.repeatMode == Schedule.REPEAT_ONCE && s.lastOutcome != Schedule.OUTCOME_NONE) {
            next = context.getString(R.string.item_completed);
        } else if (!s.enabled) {
            next = context.getString(R.string.item_off);
        } else {
            next = context.getString(R.string.item_no_next);
        }
        h.next.setText(next);

        if (row.soundMissing) {
            h.warning.setVisibility(View.VISIBLE);
            h.warning.setText(R.string.item_sound_missing);
        } else {
            h.warning.setVisibility(View.GONE);
        }

        h.enabled.setOnCheckedChangeListener(null);
        h.enabled.setChecked(s.enabled);
        h.enabled.setOnCheckedChangeListener((b, checked) -> callbacks.onToggle(s, checked));
        h.itemView.setAlpha(s.enabled ? 1f : 0.6f);
        h.itemView.setOnClickListener(v -> callbacks.onOpen(s));
        h.more.setOnClickListener(v -> callbacks.onMenu(s, v));
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final TextView title, window, repeat, preview, next, warning;
        final MaterialSwitch enabled;
        final View more;

        Holder(View v) {
            super(v);
            title = v.findViewById(R.id.item_title);
            window = v.findViewById(R.id.item_window);
            repeat = v.findViewById(R.id.item_repeat);
            preview = v.findViewById(R.id.item_preview);
            next = v.findViewById(R.id.item_next);
            warning = v.findViewById(R.id.item_warning);
            enabled = v.findViewById(R.id.item_enabled);
            more = v.findViewById(R.id.item_more);
        }
    }
}
