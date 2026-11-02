package com.wordwake.app.ui;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.wordwake.app.R;
import com.wordwake.app.data.AppExecutors;
import com.wordwake.app.data.AppSettings;
import com.wordwake.app.data.ContentRepository;
import com.wordwake.app.data.WordWakeDatabase;
import com.wordwake.app.model.Schedule;

/** App shell: bottom navigation between Home, Reminders, Calendar, Create and Settings. */
public class MainActivity extends AppCompatActivity {

    private BottomNavigationView nav;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        nav = findViewById(R.id.bottom_nav);
        nav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_create) {
                showCreateSheet();
                return false;
            }
            show(id);
            return true;
        });
        if (savedInstanceState == null) {
            nav.setSelectedItemId(R.id.nav_home);
            AppSettings settings = new AppSettings(this);
            if (!settings.onboardingDone()) {
                startActivity(new Intent(this, PermissionsActivity.class).putExtra(PermissionsActivity.EXTRA_ONBOARDING, true));
            }
            final android.content.Context app = getApplicationContext();
            AppExecutors.io(() -> new ContentRepository(app).seedQuotes(new AppSettings(app)));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (WordWakeDatabase.consumeWasReset()) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.db_reset_title)
                    .setMessage(R.string.db_reset_message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        }
    }

    public void selectTab(int navId) {
        nav.setSelectedItemId(navId);
    }

    private void show(int navId) {
        String tag = "tab" + navId;
        Fragment f = getSupportFragmentManager().findFragmentByTag(tag);
        if (f == null) {
            if (navId == R.id.nav_reminders) f = new RemindersFragment();
            else if (navId == R.id.nav_calendar) f = new CalendarFragment();
            else if (navId == R.id.nav_settings) f = new SettingsFragment();
            else f = new HomeFragment();
        }
        getSupportFragmentManager().beginTransaction().replace(R.id.content, f, tag).commit();
    }

    private void showCreateSheet() {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        android.widget.LinearLayout list = Ui.vertical(this);
        int pad = Ui.dp(this, 8);
        list.setPadding(pad, pad * 2, pad, pad * 3);
        android.widget.TextView heading = Ui.text(this, getString(R.string.create_heading),
                com.google.android.material.R.style.TextAppearance_Material3_TitleLarge);
        heading.setPadding(pad * 2, 0, pad * 2, pad);
        list.addView(heading);
        for (int[] t : QuickCreate.OPTIONS) {
            list.addView(Ui.iconRow(this, t[1], getString(t[2]), getString(t[3]), v -> {
                sheet.dismiss();
                QuickCreate.start(this, t[0]);
            }));
        }
        sheet.setContentView(list);
        sheet.show();
    }

    /** The reminder types offered when creating. */
    static final class QuickCreate {
        static final int[][] OPTIONS = {
                {Schedule.TYPE_REMINDER, R.drawable.ic_event, R.string.type_reminder, R.string.type_reminder_hint},
                {Schedule.TYPE_ALARM, R.drawable.ic_alarm, R.string.type_alarm, R.string.type_alarm_hint},
                {Schedule.TYPE_TASK, R.drawable.ic_check, R.string.type_task, R.string.type_task_hint},
                {Schedule.TYPE_APPOINTMENT, R.drawable.ic_person, R.string.type_appointment, R.string.type_appointment_hint},
                {Schedule.TYPE_DEADLINE, R.drawable.ic_description, R.string.type_deadline, R.string.type_deadline_hint},
                {Schedule.TYPE_TAX, R.drawable.ic_bank, R.string.type_tax, R.string.type_tax_hint},
                {Schedule.TYPE_BILL, R.drawable.ic_receipt, R.string.type_bill, R.string.type_bill_hint},
                {Schedule.TYPE_QUOTE, R.drawable.ic_star, R.string.type_quote, R.string.type_quote_hint},
        };

        private QuickCreate() {}

        static void start(android.content.Context c, int type) {
            c.startActivity(new Intent(c, EditScheduleActivity.class).putExtra(EditScheduleActivity.EXTRA_TYPE, type));
        }
    }
}
