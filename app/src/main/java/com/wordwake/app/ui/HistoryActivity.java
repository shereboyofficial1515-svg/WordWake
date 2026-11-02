package com.wordwake.app.ui;

import android.content.Context;
import android.os.Bundle;
import android.widget.LinearLayout;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.wordwake.app.R;
import com.wordwake.app.data.AppExecutors;
import com.wordwake.app.data.DiagLog;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Reminder history: when alerts rang, were missed, snoozed, dismissed or completed. */
public class HistoryActivity extends AppCompatActivity {

    public static final String EXTRA_ID = "id";
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm").withZone(ZoneId.systemDefault());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list_screen);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.history_title);
        toolbar.setNavigationOnClickListener(v -> finish());
        LinearLayout list = findViewById(R.id.list);
        long id = getIntent().getLongExtra(EXTRA_ID, 0);
        final Context app = getApplicationContext();
        AppExecutors.io(() -> DiagLog.history(app, id, 200), rows -> render(list, rows));
    }

    private void render(LinearLayout list, List<DiagLog.Entry> rows) {
        if (isFinishing()) return;
        if (rows.isEmpty()) {
            RowViews.row(list, getString(R.string.history_empty), "", null);
            return;
        }
        for (DiagLog.Entry e : rows) {
            String title = e.title == null || e.title.isEmpty() ? getString(eventName(e.type)) : e.title + " · " + getString(eventName(e.type));
            RowViews.row(list, title, TS.format(Instant.ofEpochMilli(e.at)), null);
        }
    }

    private static int eventName(String type) {
        switch (type) {
            case DiagLog.CREATED: return R.string.event_created;
            case DiagLog.TRIGGERED: return R.string.event_rang;
            case DiagLog.MISSED: return R.string.event_missed;
            case DiagLog.STOPPED: return R.string.event_stopped;
            case DiagLog.SNOOZED: return R.string.event_snoozed;
            case DiagLog.DISMISSED: return R.string.event_dismissed;
            case DiagLog.AUTO_DISMISSED: return R.string.event_auto_ended;
            case DiagLog.COMPLETED: return R.string.event_completed;
            case DiagLog.REOPENED: return R.string.event_reopened;
            default: return R.string.event_other;
        }
    }
}
