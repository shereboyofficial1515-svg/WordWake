package com.wordwake.app.ui;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.PopupMenu;
import android.widget.Toast;

import androidx.fragment.app.FragmentActivity;

import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.wordwake.app.R;
import com.wordwake.app.alarm.AlarmController;
import com.wordwake.app.alarm.AlarmScheduler;
import com.wordwake.app.alarm.ClockBackup;
import com.wordwake.app.data.AppExecutors;
import com.wordwake.app.data.AttachmentStore;
import com.wordwake.app.data.ContentRepository;
import com.wordwake.app.data.DiagLog;
import com.wordwake.app.data.ScheduleRepository;
import com.wordwake.app.engine.ScheduleEngine;
import com.wordwake.app.model.Attachment;
import com.wordwake.app.model.ChecklistItem;
import com.wordwake.app.model.Schedule;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

/** Actions on one reminder, shared by the dashboard, the reminder list and the calendar. */
public final class ReminderActions {

    private final FragmentActivity activity;
    private final Context app;
    private final Runnable onChanged;

    public ReminderActions(FragmentActivity activity, Runnable onChanged) {
        this.activity = activity;
        this.app = activity.getApplicationContext();
        this.onChanged = onChanged;
    }

    public void open(Schedule s) {
        activity.startActivity(new Intent(activity, ReminderDetailActivity.class).putExtra(ReminderDetailActivity.EXTRA_ID, s.id));
    }

    public void edit(Schedule s) {
        activity.startActivity(new Intent(activity, EditScheduleActivity.class).putExtra(EditScheduleActivity.EXTRA_ID, s.id));
    }

    /** Pause (false) or resume (true). */
    public void setActive(Schedule s, boolean active) {
        AppExecutors.io(() -> {
            ScheduleRepository repo = new ScheduleRepository(app);
            Schedule cur = repo.get(s.id);
            if (cur == null) return 0;
            if (active) {
                if (cur.isCompleted()) return R.string.toggle_completed;
                if (cur.repeatMode == Schedule.REPEAT_ONCE
                        && ScheduleEngine.validate(cur, System.currentTimeMillis(), ZoneId.systemDefault())
                        .contains(ScheduleEngine.ERR_IN_PAST)) {
                    return R.string.toggle_once_in_past;
                }
                cur.enabled = true;
                cur.resetRuntimeState();
                EditScheduleActivity.skipInProgressOccurrence(cur);
                repo.save(cur);
                AlarmScheduler.reschedule(app, cur);
            } else {
                AlarmController.onDisabledOrDeleted(app, cur.id);
                cur.enabled = false;
                cur.nextTriggerAt = 0;
                repo.save(cur);
            }
            AlarmScheduler.reconcileAll(app, "pause or resume");
            return 0;
        }, msg -> {
            if (msg != 0) Toast.makeText(activity, msg, Toast.LENGTH_LONG).show();
            onChanged.run();
        });
    }

    public void showMenu(Schedule s, View anchor) {
        PopupMenu menu = new PopupMenu(activity, anchor);
        menu.getMenuInflater().inflate(R.menu.schedule_item, menu.getMenu());
        menu.getMenu().findItem(R.id.action_complete).setTitle(s.isCompleted() ? R.string.menu_reopen : R.string.menu_complete);
        menu.getMenu().findItem(R.id.action_pause).setTitle(s.enabled ? R.string.menu_pause : R.string.menu_resume);
        menu.getMenu().findItem(R.id.action_pause).setVisible(!s.isCompleted());
        menu.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_edit) {
                edit(s);
            } else if (id == R.id.action_duplicate) {
                duplicate(s);
            } else if (id == R.id.action_pause) {
                setActive(s, !s.enabled);
            } else if (id == R.id.action_complete) {
                setCompleted(s, !s.isCompleted());
            } else if (id == R.id.action_clock) {
                addClockBackup(s);
            } else if (id == R.id.action_reschedule) {
                reschedule(s);
            } else if (id == R.id.action_history) {
                activity.startActivity(new Intent(activity, HistoryActivity.class).putExtra(HistoryActivity.EXTRA_ID, s.id));
            } else if (id == R.id.action_test) {
                AppExecutors.io(() -> AlarmController.test(app, s.id));
                Toast.makeText(activity, R.string.test_started, Toast.LENGTH_SHORT).show();
            } else if (id == R.id.action_delete) {
                confirmDelete(s);
            }
            return true;
        });
        menu.show();
    }

    /** Adds a matching alarm to the system Clock, after explaining what it can and cannot do. */
    public void addClockBackup(Schedule s) {
        addClockBackup(s, null);
    }

    public void addClockBackup(Schedule s, Runnable onClosed) {
        if (!ClockBackup.isSupported(s, System.currentTimeMillis())) {
            Toast.makeText(activity, R.string.clock_backup_unsupported, Toast.LENGTH_LONG).show();
            if (onClosed != null) onClosed.run();
            return;
        }
        new MaterialAlertDialogBuilder(activity)
                .setOnDismissListener(d -> {
                    if (onClosed != null) onClosed.run();
                })
                .setTitle(R.string.clock_backup_title)
                .setMessage(activity.getString(R.string.clock_backup_message, Formats.minuteOfDay(activity, s.startMinute)))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.clock_backup_add, (d, w) -> {
                    boolean ok = ClockBackup.add(activity, s);
                    Toast.makeText(activity, ok ? R.string.clock_backup_done : R.string.clock_backup_failed, Toast.LENGTH_LONG).show();
                })
                .show();
    }

    public void setCompleted(Schedule s, boolean completed) {
        AppExecutors.io(() -> {
            if (completed) AlarmController.complete(app, s.id);
            else AlarmController.reopen(app, s.id);
            AlarmScheduler.reconcileAll(app, "completion changed");
            return true;
        }, ok -> onChanged.run());
    }

    /** One-off dates move straight away; recurring schedules go through the editor deliberately. */
    public void reschedule(Schedule s) {
        if (s.repeatMode != Schedule.REPEAT_ONCE && s.repeatMode != Schedule.REPEAT_DEADLINE) {
            new MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.reschedule_recurring_title)
                    .setMessage(R.string.reschedule_recurring_message)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.menu_edit, (d, w) -> edit(s))
                    .show();
            return;
        }
        ZoneId zone = s.zone(ZoneId.systemDefault());
        LocalDate current = s.repeatMode == Schedule.REPEAT_DEADLINE
                ? Instant.ofEpochMilli(s.deadlineAt).atZone(zone).toLocalDate() : s.onceDate();
        MaterialDatePicker<Long> picker = MaterialDatePicker.Builder.datePicker()
                .setTitleText(R.string.pick_new_date)
                .setSelection(current.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
                .build();
        picker.addOnPositiveButtonClickListener(ms -> {
            LocalDate date = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate();
            AppExecutors.io(() -> moveTo(s.id, date), msg -> {
                if (msg != 0) Toast.makeText(activity, msg, Toast.LENGTH_LONG).show();
                onChanged.run();
            });
        });
        picker.show(activity.getSupportFragmentManager(), "reschedule");
    }

    private int moveTo(long id, LocalDate date) {
        ScheduleRepository repo = new ScheduleRepository(app);
        Schedule cur = repo.get(id);
        if (cur == null) return R.string.schedule_not_found;
        ZoneId zone = cur.zone(ZoneId.systemDefault());
        if (cur.repeatMode == Schedule.REPEAT_DEADLINE) {
            ZonedDateTime old = Instant.ofEpochMilli(cur.deadlineAt).atZone(zone);
            cur.deadlineAt = old.with(date).toInstant().toEpochMilli();
        } else {
            cur.startDate = date.toEpochDay();
            if (ScheduleEngine.validate(cur, System.currentTimeMillis(), ZoneId.systemDefault())
                    .contains(ScheduleEngine.ERR_IN_PAST)) {
                return R.string.err_in_past;
            }
        }
        cur.enabled = !cur.isCompleted();
        cur.resetRuntimeState();
        repo.save(cur);
        DiagLog.log(app, DiagLog.UPDATED, id, "date moved");
        AlarmScheduler.reschedule(app, cur);
        AlarmScheduler.reconcileAll(app, "rescheduled");
        return 0;
    }

    private void duplicate(Schedule s) {
        AppExecutors.io(() -> {
            ScheduleRepository repo = new ScheduleRepository(app);
            ContentRepository content = new ContentRepository(app);
            Schedule copy = s.copy();
            copy.id = 0;
            copy.createdAt = 0;
            copy.title = app.getString(R.string.copy_title_format, s.displayTitle());
            copy.resetRuntimeState();
            copy.status = Schedule.STATUS_NOT_STARTED;
            copy.completedAt = 0;
            // Duplicates start paused so the user never gets two identical alarms by surprise.
            copy.enabled = false;
            long id = repo.save(copy);
            List<ChecklistItem> items = content.checklist(s.id);
            for (ChecklistItem i : items) i.done = false;
            content.saveChecklist(id, items);
            for (Attachment a : content.attachments(s.id)) {
                Attachment c = AttachmentStore.duplicate(app, a, id);
                if (c != null) content.addAttachment(c);
            }
            DiagLog.log(app, DiagLog.CREATED, id, copy.displayTitle(), "duplicate");
            return id;
        }, id -> {
            Toast.makeText(activity, R.string.duplicated, Toast.LENGTH_LONG).show();
            onChanged.run();
        });
    }

    private void confirmDelete(Schedule s) {
        boolean recurring = !s.isFiniteRepeat();
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.delete_title)
                .setMessage(activity.getString(recurring ? R.string.delete_message_recurring : R.string.delete_message, s.displayTitle()))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.delete, (d, w) -> AppExecutors.io(() -> {
                    AlarmController.onDisabledOrDeleted(app, s.id);
                    ContentRepository content = new ContentRepository(app);
                    for (Attachment a : content.attachments(s.id)) AttachmentStore.deleteFile(app, a);
                    new ScheduleRepository(app).delete(s.id);
                    AlarmScheduler.reconcileAll(app, "deleted");
                    return true;
                }, ok -> onChanged.run()))
                .show();
    }
}
