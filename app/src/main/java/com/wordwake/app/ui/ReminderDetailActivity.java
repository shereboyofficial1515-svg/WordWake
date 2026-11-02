package com.wordwake.app.ui;

import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.wordwake.app.R;
import com.wordwake.app.alarm.AlarmController;
import com.wordwake.app.alarm.AlarmScheduler;
import com.wordwake.app.data.AppExecutors;
import com.wordwake.app.data.AttachmentStore;
import com.wordwake.app.data.ContentRepository;
import com.wordwake.app.data.ScheduleRepository;
import com.wordwake.app.engine.ReminderStatus;
import com.wordwake.app.model.Attachment;
import com.wordwake.app.model.Category;
import com.wordwake.app.model.ChecklistItem;
import com.wordwake.app.model.Finance;
import com.wordwake.app.model.Schedule;

import java.time.ZoneId;
import java.util.List;

/** What to do, when, why and how: the full reminder with its checklist and attachments. */
public class ReminderDetailActivity extends AppCompatActivity {

    public static final String EXTRA_ID = "id";

    private long id;
    private LinearLayout body;
    private MaterialToolbar toolbar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list_screen);
        toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.details_title);
        toolbar.setNavigationOnClickListener(v -> finish());
        body = findViewById(R.id.list);
        id = getIntent().getLongExtra(EXTRA_ID, 0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private static final class Data {
        Schedule s;
        Category category;
        String quote;
        List<ChecklistItem> checklist;
        List<Attachment> attachments;
    }

    private void load() {
        final android.content.Context app = getApplicationContext();
        AppExecutors.io(() -> {
            Data d = new Data();
            d.s = new ScheduleRepository(app).get(id);
            if (d.s == null) return d;
            ContentRepository content = new ContentRepository(app);
            d.category = Category.find(d.s.categoryKey, content.customCategories());
            d.quote = d.s.quoteRandom ? null : d.s.quote;
            d.checklist = content.checklist(id);
            d.attachments = content.attachments(id);
            return d;
        }, this::render);
    }

    private void render(Data d) {
        if (isFinishing()) return;
        if (d.s == null) {
            Toast.makeText(this, R.string.schedule_not_found, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        Schedule s = d.s;
        body.removeAllViews();

        // Heading
        MaterialCardView head = Ui.card(this);
        LinearLayout hb = Ui.vertical(this);
        int p = Ui.dp(this, 16);
        hb.setPadding(p, p, p, p);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.addView(Ui.categoryIcon(this, d.category.iconRes, d.category.color(this), 40));
        TextView cat = Ui.secondary(this, getString(R.string.item_category_format, Formats.typeName(this, s.type), d.category.name(this)));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clp.setMarginStart(Ui.dp(this, 10));
        cat.setLayoutParams(clp);
        row.addView(cat);
        int status = ReminderStatus.of(s, System.currentTimeMillis(), ZoneId.systemDefault());
        row.addView(Ui.badge(this, getString(Ui.statusLabel(status)), Ui.statusColor(this, status)));
        hb.addView(row);
        TextView title = Ui.text(this, s.displayTitle(), com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall);
        title.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 4));
        title.setTextIsSelectable(true);
        hb.addView(title);
        hb.addView(Ui.secondary(this, Formats.window(this, s)));
        hb.addView(Ui.secondary(this, Formats.repeat(this, s)));
        if (s.enabled && s.nextTriggerAt > 0) {
            hb.addView(Ui.secondary(this, getString(R.string.item_next, Formats.dateTime(this, s.nextTriggerAt))));
        }
        head.addView(hb);
        body.addView(head);

        // Deadline
        if (s.deadlineAt > 0) {
            TextView dl = Ui.text(this, Formats.deadlineLine(this, s), com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
            dl.setTextColor(getColor(status == ReminderStatus.OVERDUE ? R.color.error_text : R.color.brand_secondary));
            dl.setPadding(p, p, p, 0);
            body.addView(dl);
        }

        // What to do
        if (s.body != null && !s.body.trim().isEmpty()) {
            body.addView(Ui.sectionHeader(this, getString(R.string.details_what)));
            TextView t = Ui.text(this, s.body, com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
            t.setPadding(p, 0, p, 0);
            t.setTextIsSelectable(true);
            body.addView(t);
        }

        // Checklist
        if (!d.checklist.isEmpty()) {
            int done = 0;
            for (ChecklistItem i : d.checklist) if (i.done) done++;
            final TextView header = Ui.sectionHeader(this, getString(R.string.details_checklist, done, d.checklist.size()));
            body.addView(header);
            final android.content.Context app = getApplicationContext();
            for (ChecklistItem i : d.checklist) {
                CheckBox box = new CheckBox(this);
                box.setText(i.text);
                box.setChecked(i.done);
                box.setPadding(Ui.dp(this, 8), 0, 0, 0);
                box.setOnCheckedChangeListener((b, checked) -> {
                    i.done = checked;
                    int n = 0;
                    for (ChecklistItem x : d.checklist) if (x.done) n++;
                    header.setText(getString(R.string.details_checklist, n, d.checklist.size()));
                    AppExecutors.io(() -> new ContentRepository(app).setChecklistDone(i.id, checked));
                });
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                blp.setMarginStart(p);
                box.setLayoutParams(blp);
                body.addView(box);
            }
        }

        // Quote
        if (s.quoteRandom || (d.quote != null && !d.quote.trim().isEmpty())) {
            body.addView(Ui.sectionHeader(this, getString(R.string.section_quote)));
            TextView q = Ui.text(this, s.quoteRandom ? getString(R.string.details_quote_random) : getString(R.string.alarm_quote_format, d.quote.trim()),
                    com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
            q.setPadding(p, 0, p, 0);
            q.setTypeface(q.getTypeface(), android.graphics.Typeface.ITALIC);
            body.addView(q);
        }

        // Financial details
        Finance f = Finance.parse(s.financeJson);
        if (!f.isEmpty()) renderFinance(f);

        // Attachments
        if (!d.attachments.isEmpty()) {
            body.addView(Ui.sectionHeader(this, getString(R.string.section_attachments)));
            for (Attachment a : d.attachments) renderAttachment(a);
        }

        // Actions
        body.addView(Ui.sectionHeader(this, getString(R.string.details_actions)));
        LinearLayout actions = Ui.vertical(this);
        actions.setPadding(p, 0, p, p);
        if (s.isTracked()) {
            actions.addView(button(getString(R.string.details_status_format, Formats.statusName(this, s.status)), v -> chooseStatus(s)));
        }
        actions.addView(button(getString(s.isCompleted() ? R.string.menu_reopen : R.string.menu_complete), v -> setCompleted(s)));
        actions.addView(button(getString(R.string.menu_edit), v -> new ReminderActions(this, this::load).edit(s)));
        if (com.wordwake.app.alarm.ClockBackup.isSupported(s, System.currentTimeMillis())) {
            actions.addView(button(getString(R.string.menu_clock_backup), v -> new ReminderActions(this, this::load).addClockBackup(s)));
        }
        actions.addView(button(getString(R.string.menu_history), v -> startActivity(
                new Intent(this, HistoryActivity.class).putExtra(HistoryActivity.EXTRA_ID, s.id))));
        body.addView(actions);
    }

    private MaterialButton button(String text, View.OnClickListener click) {
        MaterialButton b = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        b.setText(text);
        b.setOnClickListener(click);
        return b;
    }

    private void renderFinance(Finance f) {
        body.addView(Ui.sectionHeader(this, getString(R.string.section_finance)));
        addField(R.string.finance_type, f.taxType);
        addField(R.string.finance_jurisdiction, f.jurisdiction);
        addField(R.string.finance_authority, f.authority);
        addField(R.string.finance_year, f.taxYear);
        if (f.filingDeadline > 0) addField(R.string.finance_filing, Formats.dateTime(this, f.filingDeadline));
        if (f.paymentDeadline > 0) addField(R.string.finance_payment, Formats.dateTime(this, f.paymentDeadline));
        addField(R.string.finance_amount, f.amount);
        addField(R.string.finance_contact, f.contact);
        addField(R.string.finance_documents, f.documents);
        int p = Ui.dp(this, 16);
        if (!f.website.isEmpty()) {
            MaterialButton open = button(getString(R.string.finance_open_website), v -> openWebsite(f.website));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(p, Ui.dp(this, 4), p, 0);
            open.setLayoutParams(lp);
            body.addView(open);
        }
        if (!f.contact.isEmpty() && f.contact.matches(".*\\d{5,}.*")) {
            MaterialButton call = button(getString(R.string.finance_call), v -> {
                String digits = f.contact.replaceAll("[^0-9+]", "");
                try {
                    startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + digits)));
                } catch (RuntimeException e) {
                    Toast.makeText(this, R.string.details_cannot_open, Toast.LENGTH_LONG).show();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(p, Ui.dp(this, 4), p, 0);
            call.setLayoutParams(lp);
            body.addView(call);
        }
    }

    /** Opens the saved site in the browser. WordWake itself has no internet access. */
    private void openWebsite(String url) {
        String u = url.trim();
        if (!u.matches("(?i)^https?://.*")) u = "https://" + u;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u)));
        } catch (RuntimeException e) {
            Toast.makeText(this, R.string.details_cannot_open, Toast.LENGTH_LONG).show();
        }
    }

    private void addField(int label, String value) {
        if (value == null || value.isEmpty()) return;
        int p = Ui.dp(this, 16);
        TextView l = Ui.text(this, getString(label), com.google.android.material.R.style.TextAppearance_Material3_LabelMedium);
        l.setTextColor(getColor(R.color.text_secondary));
        l.setPadding(p, Ui.dp(this, 6), p, 0);
        body.addView(l);
        TextView v = Ui.text(this, value, com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        v.setPadding(p, 0, p, 0);
        v.setTextIsSelectable(true);
        body.addView(v);
    }

    private void renderAttachment(Attachment a) {
        if (a.isImage()) {
            ImageView image = new ImageView(this);
            image.setAdjustViewBounds(true);
            image.setContentDescription(a.displayName);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            int p = Ui.dp(this, 16);
            lp.setMargins(p, Ui.dp(this, 4), p, Ui.dp(this, 4));
            image.setLayoutParams(lp);
            image.setOnClickListener(v -> openAttachment(a));
            body.addView(image);
            final android.content.Context app = getApplicationContext();
            AppExecutors.io(() -> {
                Bitmap b = AttachmentStore.loadBitmap(app, a, 1440);
                return b == null ? new Bitmap[0] : new Bitmap[]{b};
            }, b -> {
                if (b.length > 0) image.setImageBitmap(b[0]);
                else image.setVisibility(View.GONE);
            });
        } else {
            body.addView(Ui.iconRow(this, R.drawable.ic_description, a.displayName, a.mime, v -> openAttachment(a)));
        }
    }

    private void openAttachment(Attachment a) {
        if (!AttachmentStore.file(this, a).exists()) {
            Toast.makeText(this, R.string.details_attachment_missing, Toast.LENGTH_LONG).show();
            return;
        }
        try {
            startActivity(AttachmentStore.viewIntent(this, a));
        } catch (RuntimeException e) {
            Toast.makeText(this, R.string.details_cannot_open, Toast.LENGTH_LONG).show();
        }
    }

    private void chooseStatus(Schedule s) {
        int[] values = {Schedule.STATUS_NOT_STARTED, Schedule.STATUS_IN_PROGRESS, Schedule.STATUS_WAITING,
                Schedule.STATUS_READY, Schedule.STATUS_COMPLETED};
        String[] labels = new String[values.length];
        for (int i = 0; i < values.length; i++) labels[i] = Formats.statusName(this, values[i]);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.status_title)
                .setSingleChoiceItems(labels, s.status, (d, w) -> {
                    d.dismiss();
                    changeStatus(s, values[w]);
                })
                .show();
    }

    private void changeStatus(Schedule s, int status) {
        final android.content.Context app = getApplicationContext();
        AppExecutors.io(() -> {
            ScheduleRepository repo = new ScheduleRepository(app);
            Schedule cur = repo.get(s.id);
            if (cur == null) return true;
            if (status == Schedule.STATUS_COMPLETED) {
                AlarmController.complete(app, s.id);
            } else if (cur.isCompleted()) {
                AlarmController.reopen(app, s.id);
                cur = repo.get(s.id);
                cur.status = status;
                repo.saveState(cur);
            } else {
                cur.status = status;
                repo.saveState(cur);
            }
            AlarmScheduler.reconcileAll(app, "status changed");
            return true;
        }, ok -> load());
    }

    private void setCompleted(Schedule s) {
        new ReminderActions(this, this::load).setCompleted(s, !s.isCompleted());
    }
}
