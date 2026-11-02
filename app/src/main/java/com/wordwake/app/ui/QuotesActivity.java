package com.wordwake.app.ui;

import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.wordwake.app.R;
import com.wordwake.app.data.AppExecutors;
import com.wordwake.app.data.AppSettings;
import com.wordwake.app.data.ContentRepository;

import java.util.List;

/** Saved quotes: the user's own and the built-in ones. Favorites come first in random rotation. */
public class QuotesActivity extends AppCompatActivity {

    private LinearLayout list;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list_screen);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.quotes_title);
        toolbar.setNavigationOnClickListener(v -> finish());
        list = findViewById(R.id.list);
        load();
    }

    private void load() {
        final Context app = getApplicationContext();
        AppExecutors.io(() -> {
            ContentRepository c = new ContentRepository(app);
            c.seedQuotes(new AppSettings(app));
            return c.quotes();
        }, this::render);
    }

    private void render(List<ContentRepository.Quote> quotes) {
        if (isFinishing()) return;
        list.removeAllViews();
        int p = Ui.dp(this, 16);
        MaterialButton add = new MaterialButton(this);
        add.setText(R.string.quotes_add);
        add.setOnClickListener(v -> addQuote());
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.setMargins(p, p, p, Ui.dp(this, 8));
        add.setLayoutParams(alp);
        list.addView(add);
        for (ContentRepository.Quote q : quotes) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(p, Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8));
            TextView text = Ui.text(this, q.text, com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
            text.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(text);
            ImageButton fav = iconButton(R.drawable.ic_star, getString(R.string.quotes_favorite));
            fav.setAlpha(q.favorite ? 1f : 0.3f);
            fav.setOnClickListener(v -> run(() -> new ContentRepository(this).setQuoteFavorite(q.id, !q.favorite)));
            row.addView(fav);
            ImageButton del = iconButton(R.drawable.ic_close, getString(R.string.delete));
            del.setOnClickListener(v -> run(() -> new ContentRepository(this).deleteQuote(q.id)));
            row.addView(del);
            list.addView(row);
        }
    }

    private ImageButton iconButton(int icon, String description) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(icon);
        b.setContentDescription(description);
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true);
        b.setBackgroundResource(tv.resourceId);
        int s = Ui.dp(this, 44);
        b.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        return b;
    }

    private void run(Runnable work) {
        AppExecutors.io(() -> {
            work.run();
            return true;
        }, ok -> load());
    }

    private void addQuote() {
        EditText input = new EditText(this);
        input.setHint(R.string.quotes_hint);
        input.setMinLines(2);
        LinearLayout box = Ui.vertical(this);
        int p = Ui.dp(this, 20);
        box.setPadding(p, p / 2, p, 0);
        box.addView(input);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.quotes_add)
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.save, (d, w) -> {
                    String text = input.getText().toString().trim();
                    if (!text.isEmpty()) run(() -> new ContentRepository(this).insertQuote(text, true, false));
                })
                .show();
    }
}
