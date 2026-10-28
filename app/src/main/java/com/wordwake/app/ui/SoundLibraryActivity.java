package com.wordwake.app.ui;

import android.content.Intent;
import android.database.Cursor;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.wordwake.app.R;
import com.wordwake.app.data.AppExecutors;
import com.wordwake.app.data.AppSettings;
import com.wordwake.app.sound.AlarmPlayer;
import com.wordwake.app.sound.SoundCatalog;

import java.util.ArrayList;
import java.util.List;

/**
 * Alarm sound library: built-in tones, phone alarm sounds and ringtones, and the user's own audio
 * files (picked through the Storage Access Framework, no storage permission needed).
 *
 * With {@link #EXTRA_PICK} the chosen sound is returned to the caller; otherwise it becomes the
 * default sound for new messages.
 */
public class SoundLibraryActivity extends AppCompatActivity {

    public static final String EXTRA_PICK = "pick";
    public static final String EXTRA_CURRENT = "current";
    public static final String RESULT_URI = "uri";
    public static final String RESULT_NAME = "name";

    private AppSettings settings;
    private AlarmPlayer previewPlayer;
    private String previewingUri;
    private String selectedUri;
    private boolean pickMode;
    private LinearLayout container;
    private final List<ImageButton> previewButtons = new ArrayList<>();
    private final List<RadioButton> radios = new ArrayList<>();

    private final ActivityResultLauncher<String[]> filePicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), this::onFilePicked);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sounds);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        settings = new AppSettings(this);
        previewPlayer = new AlarmPlayer(this);
        pickMode = getIntent().getBooleanExtra(EXTRA_PICK, false);
        selectedUri = pickMode ? getIntent().getStringExtra(EXTRA_CURRENT) : settings.defaultSoundUri();
        toolbar.setTitle(pickMode ? R.string.sounds_pick_title : R.string.sounds_library_title);
        ((TextView) findViewById(R.id.sounds_hint)).setText(pickMode ? R.string.sounds_pick_hint : R.string.sounds_default_hint);
        container = findViewById(R.id.sound_container);
        findViewById(R.id.choose_file).setOnClickListener(v -> filePicker.launch(new String[]{"audio/*"}));
        load();
    }

    private void load() {
        AppExecutors.io(() -> {
            List<Object> items = new ArrayList<>();
            List<String[]> files = settings.pickedFiles();
            if (!files.isEmpty()) {
                items.add(getString(R.string.sounds_section_files));
                for (String[] f : files) {
                    boolean ok = SoundCatalog.isAvailable(getApplicationContext(), f[0]);
                    items.add(new Item(new SoundCatalog.SoundOption(f[0], f[1], SoundCatalog.SOURCE_FILE), ok));
                }
            }
            items.add(getString(R.string.sounds_section_builtin));
            for (SoundCatalog.SoundOption o : SoundCatalog.builtIns()) items.add(new Item(o, true));
            List<SoundCatalog.SoundOption> alarms = SoundCatalog.systemSounds(getApplicationContext(), RingtoneManager.TYPE_ALARM);
            if (!alarms.isEmpty()) {
                items.add(getString(R.string.sounds_section_alarms));
                for (SoundCatalog.SoundOption o : alarms) items.add(new Item(o, true));
            }
            List<SoundCatalog.SoundOption> rings = SoundCatalog.systemSounds(getApplicationContext(), RingtoneManager.TYPE_RINGTONE);
            if (!rings.isEmpty()) {
                items.add(getString(R.string.sounds_section_ringtones));
                for (SoundCatalog.SoundOption o : rings) items.add(new Item(o, true));
            }
            return items;
        }, this::render);
    }

    private static final class Item {
        final SoundCatalog.SoundOption option;
        final boolean available;

        Item(SoundCatalog.SoundOption option, boolean available) {
            this.option = option;
            this.available = available;
        }
    }

    private void render(List<Object> items) {
        if (isFinishing()) return;
        container.removeAllViews();
        previewButtons.clear();
        radios.clear();
        LayoutInflater inf = LayoutInflater.from(this);
        for (Object o : items) {
            if (o instanceof String) {
                TextView header = (TextView) inf.inflate(R.layout.item_section_header, container, false);
                header.setText((String) o);
                container.addView(header);
                continue;
            }
            Item item = (Item) o;
            View row = inf.inflate(R.layout.item_sound, container, false);
            TextView name = row.findViewById(R.id.sound_name);
            TextView detail = row.findViewById(R.id.sound_detail);
            RadioButton radio = row.findViewById(R.id.sound_radio);
            ImageButton preview = row.findViewById(R.id.sound_preview);
            name.setText(item.option.name);
            if (!item.available) {
                detail.setVisibility(View.VISIBLE);
                detail.setText(R.string.sound_unavailable_short);
            }
            radio.setChecked(item.option.uri.equals(selectedUri));
            radio.setTag(item.option.uri);
            radios.add(radio);
            preview.setTag(item.option.uri);
            previewButtons.add(preview);
            preview.setEnabled(item.available);
            preview.setOnClickListener(v -> togglePreview(item.option.uri));
            View.OnClickListener select = v -> {
                if (!item.available) {
                    Toast.makeText(this, R.string.sound_unavailable_pick, Toast.LENGTH_LONG).show();
                    return;
                }
                choose(item.option);
            };
            row.setOnClickListener(select);
            radio.setOnClickListener(select);
            if (item.option.source == SoundCatalog.SOURCE_FILE) {
                row.setOnLongClickListener(v -> {
                    new MaterialAlertDialogBuilder(this)
                            .setTitle(item.option.name)
                            .setMessage(R.string.sound_remove_message)
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton(R.string.remove, (d, w) -> {
                                settings.removePickedFile(item.option.uri);
                                releasePersistedPermission(item.option.uri);
                                load();
                            })
                            .show();
                    return true;
                });
            }
            container.addView(row);
        }
        updatePreviewIcons();
    }

    private void choose(SoundCatalog.SoundOption option) {
        stopPreview();
        selectedUri = option.uri;
        for (RadioButton r : radios) r.setChecked(option.uri.equals(r.getTag()));
        if (pickMode) {
            setResult(RESULT_OK, new Intent().putExtra(RESULT_URI, option.uri).putExtra(RESULT_NAME, option.name));
            finish();
        } else {
            settings.setDefaultSound(option.uri, option.name);
            Toast.makeText(this, getString(R.string.sound_default_set, option.name), Toast.LENGTH_SHORT).show();
        }
    }

    private void togglePreview(String uri) {
        if (uri.equals(previewingUri)) {
            stopPreview();
            return;
        }
        int result = previewPlayer.start(uri, false, 100, () -> runOnUiThread(this::stopPreview));
        if (result != AlarmPlayer.RESULT_SELECTED) {
            previewPlayer.stop();
            Toast.makeText(this, R.string.sound_cannot_play, Toast.LENGTH_LONG).show();
            previewingUri = null;
        } else {
            previewingUri = uri;
            if (PermissionStatus.alarmVolumeLow(this)) {
                Toast.makeText(this, R.string.preview_volume_low, Toast.LENGTH_LONG).show();
            }
        }
        updatePreviewIcons();
    }

    private void stopPreview() {
        previewPlayer.stop();
        previewingUri = null;
        updatePreviewIcons();
    }

    private void updatePreviewIcons() {
        for (ImageButton b : previewButtons) {
            boolean playing = b.getTag().equals(previewingUri);
            b.setImageResource(playing ? R.drawable.ic_stop : R.drawable.ic_play);
            b.setContentDescription(getString(playing ? R.string.preview_stop : R.string.preview_play));
        }
    }

    private void onFilePicked(Uri uri) {
        if (uri == null) return;
        try {
            // Keep read access after reboot so the alarm can still play this file.
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException e) {
            Toast.makeText(this, R.string.sound_permission_not_persisted, Toast.LENGTH_LONG).show();
        }
        String name = displayName(uri);
        settings.addPickedFile(uri.toString(), name);
        if (!SoundCatalog.isAvailable(this, uri.toString())) {
            Toast.makeText(this, R.string.sound_cannot_play, Toast.LENGTH_LONG).show();
            load();
            return;
        }
        choose(new SoundCatalog.SoundOption(uri.toString(), name, SoundCatalog.SOURCE_FILE));
        if (!pickMode) load();
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null) {
                    int dot = n.lastIndexOf('.');
                    return dot > 0 ? n.substring(0, dot) : n;
                }
            }
        } catch (RuntimeException ignored) {
        }
        return getString(R.string.sound_custom_file);
    }

    private void releasePersistedPermission(String uri) {
        try {
            getContentResolver().releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopPreview();
    }

    @Override
    protected void onDestroy() {
        previewPlayer.stop();
        super.onDestroy();
    }
}
