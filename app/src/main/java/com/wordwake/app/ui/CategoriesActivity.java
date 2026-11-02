package com.wordwake.app.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.wordwake.app.R;
import com.wordwake.app.data.AppExecutors;
import com.wordwake.app.data.AppSettings;
import com.wordwake.app.data.ContentRepository;
import com.wordwake.app.model.Category;

import java.util.List;

/** Categories: add custom ones and choose a default sound per category. */
public class CategoriesActivity extends AppCompatActivity {

    private static final int[] PALETTE = {0xFF3B3A98, 0xFF00796B, 0xFFB45309, 0xFFC62828, 0xFF6A1B9A, 0xFF1565C0, 0xFF2E7D32, 0xFFAD1457};

    private LinearLayout list;
    private AppSettings settings;
    private String soundForCategory;

    private final ActivityResultLauncher<Intent> soundPicker = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null && soundForCategory != null) {
                    settings.setCategorySound(soundForCategory,
                            result.getData().getStringExtra(SoundLibraryActivity.RESULT_URI),
                            result.getData().getStringExtra(SoundLibraryActivity.RESULT_NAME));
                    load();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list_screen);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.categories_title);
        toolbar.setNavigationOnClickListener(v -> finish());
        list = findViewById(R.id.list);
        settings = new AppSettings(this);
        load();
    }

    private void load() {
        final Context app = getApplicationContext();
        AppExecutors.io(() -> new ContentRepository(app).customCategories(), this::render);
    }

    private void render(List<Category> custom) {
        if (isFinishing()) return;
        list.removeAllViews();
        int p = Ui.dp(this, 16);
        MaterialButton add = new MaterialButton(this);
        add.setText(R.string.category_add);
        add.setOnClickListener(v -> addCategory(custom.size()));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.setMargins(p, p, p, Ui.dp(this, 8));
        add.setLayoutParams(alp);
        list.addView(add);
        list.addView(Ui.secondary(this, getString(R.string.category_sound_hint)));
        for (Category c : Category.all(custom)) {
            String[] sound = settings.categorySound(c.key);
            String summary = sound == null ? getString(R.string.category_sound_default) : sound[1];
            android.view.View row = Ui.iconRow(this, c.iconRes, c.name(this), summary, v -> pickSound(c));
            if (c.key.startsWith(Category.CUSTOM_PREFIX)) {
                row.setOnLongClickListener(v -> {
                    confirmDelete(c);
                    return true;
                });
            }
            list.addView(row);
        }
    }

    private void pickSound(Category c) {
        soundForCategory = c.key;
        String[] current = settings.categorySound(c.key);
        soundPicker.launch(new Intent(this, SoundLibraryActivity.class)
                .putExtra(SoundLibraryActivity.EXTRA_PICK, true)
                .putExtra(SoundLibraryActivity.EXTRA_CURRENT, current == null ? "" : current[0]));
    }

    private void addCategory(int index) {
        EditText input = new EditText(this);
        input.setHint(R.string.category_name_hint);
        input.setSingleLine(true);
        LinearLayout box = Ui.vertical(this);
        int p = Ui.dp(this, 20);
        box.setPadding(p, p / 2, p, 0);
        box.addView(input);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.category_add)
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.save, (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) return;
                    final Context app = getApplicationContext();
                    AppExecutors.io(() -> new ContentRepository(app).addCategory(name, PALETTE[index % PALETTE.length]), id -> load());
                })
                .show();
    }

    private void confirmDelete(Category c) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(c.name(this))
                .setMessage(R.string.category_delete_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    final Context app = getApplicationContext();
                    long id = Long.parseLong(c.key.substring(Category.CUSTOM_PREFIX.length()));
                    AppExecutors.io(() -> {
                        new ContentRepository(app).deleteCategory(id);
                        return true;
                    }, ok -> load());
                })
                .show();
    }
}
