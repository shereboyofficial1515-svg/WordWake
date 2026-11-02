package com.wordwake.app.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.wordwake.app.model.Attachment;
import com.wordwake.app.model.Category;
import com.wordwake.app.model.ChecklistItem;

import java.util.ArrayList;
import java.util.List;

/**
 * A reminder's content other than its schedule: checklist, attachments, quotes and custom
 * categories. Synchronous; call from {@link AppExecutors#io(Runnable)}.
 */
public final class ContentRepository {

    public static final class Quote {
        public long id;
        public String text;
        public boolean favorite;
        public boolean builtIn;
    }

    /** Original wording written for WordWake, so no real person is misquoted. */
    private static final String[] BUILT_IN_QUOTES = {
            "Success is built through discipline, consistency, and the courage to keep going.",
            "Small steps taken every day travel further than big plans never started.",
            "Do the next right thing. Then do the one after that.",
            "Your future is being built by what you do today.",
            "Consistency beats motivation. Show up anyway.",
            "You have done hard things before. You can do this one too.",
            "Progress does not need to be loud to be real.",
            "Start where you are, with what you have.",
            "Discipline is choosing what you want most over what you want now.",
            "A finished task is worth more than a perfect plan."
    };

    private final WordWakeDatabase db;

    public ContentRepository(Context context) {
        this(WordWakeDatabase.get(context));
    }

    public ContentRepository(WordWakeDatabase db) {
        this.db = db;
    }

    // ---- checklist ----

    public List<ChecklistItem> checklist(long scheduleId) {
        List<ChecklistItem> out = new ArrayList<>();
        try (Cursor c = db.getReadableDatabase().query(WordWakeDatabase.T_CHECKLIST,
                new String[]{"id", "text", "done"}, "schedule_id=?", new String[]{String.valueOf(scheduleId)},
                null, null, "position ASC, id ASC")) {
            while (c.moveToNext()) {
                ChecklistItem i = new ChecklistItem();
                i.id = c.getLong(0);
                i.scheduleId = scheduleId;
                i.text = c.getString(1);
                i.done = c.getInt(2) != 0;
                out.add(i);
            }
        }
        return out;
    }

    /** Replaces the whole checklist, keeping the given order. */
    public void saveChecklist(long scheduleId, List<ChecklistItem> items) {
        SQLiteDatabase w = db.getWritableDatabase();
        w.beginTransaction();
        try {
            w.delete(WordWakeDatabase.T_CHECKLIST, "schedule_id=?", new String[]{String.valueOf(scheduleId)});
            int pos = 0;
            for (ChecklistItem i : items) {
                if (i.text == null || i.text.trim().isEmpty()) continue;
                ContentValues v = new ContentValues();
                v.put("schedule_id", scheduleId);
                v.put("position", pos++);
                v.put("text", i.text.trim());
                v.put("done", i.done ? 1 : 0);
                i.id = w.insert(WordWakeDatabase.T_CHECKLIST, null, v);
                i.scheduleId = scheduleId;
            }
            w.setTransactionSuccessful();
        } finally {
            w.endTransaction();
        }
    }

    public void setChecklistDone(long itemId, boolean done) {
        ContentValues v = new ContentValues();
        v.put("done", done ? 1 : 0);
        db.getWritableDatabase().update(WordWakeDatabase.T_CHECKLIST, v, "id=?", new String[]{String.valueOf(itemId)});
    }

    /** Clears the ticks so a recurring reminder's next occurrence starts fresh. */
    public void resetChecklist(long scheduleId) {
        ContentValues v = new ContentValues();
        v.put("done", 0);
        db.getWritableDatabase().update(WordWakeDatabase.T_CHECKLIST, v, "schedule_id=?", new String[]{String.valueOf(scheduleId)});
    }

    // ---- attachments ----

    public List<Attachment> attachments(long scheduleId) {
        List<Attachment> out = new ArrayList<>();
        try (Cursor c = db.getReadableDatabase().query(WordWakeDatabase.T_ATTACHMENTS,
                new String[]{"id", "file_name", "display_name", "mime"}, "schedule_id=?",
                new String[]{String.valueOf(scheduleId)}, null, null, "id ASC")) {
            while (c.moveToNext()) {
                Attachment a = new Attachment();
                a.id = c.getLong(0);
                a.scheduleId = scheduleId;
                a.fileName = c.getString(1);
                a.displayName = c.getString(2);
                a.mime = c.getString(3);
                out.add(a);
            }
        }
        return out;
    }

    public void addAttachment(Attachment a) {
        ContentValues v = new ContentValues();
        v.put("schedule_id", a.scheduleId);
        v.put("file_name", a.fileName);
        v.put("display_name", a.displayName);
        v.put("mime", a.mime);
        a.id = db.getWritableDatabase().insert(WordWakeDatabase.T_ATTACHMENTS, null, v);
    }

    public void removeAttachment(long id) {
        db.getWritableDatabase().delete(WordWakeDatabase.T_ATTACHMENTS, "id=?", new String[]{String.valueOf(id)});
    }

    // ---- quotes ----

    public void seedQuotes(AppSettings settings) {
        if (settings.quotesSeeded()) return;
        for (String text : BUILT_IN_QUOTES) insertQuote(text, false, true);
        settings.setQuotesSeeded();
    }

    public long insertQuote(String text, boolean favorite, boolean builtIn) {
        ContentValues v = new ContentValues();
        v.put("text", text);
        v.put("favorite", favorite ? 1 : 0);
        v.put("built_in", builtIn ? 1 : 0);
        return db.getWritableDatabase().insert(WordWakeDatabase.T_QUOTES, null, v);
    }

    public List<Quote> quotes() {
        List<Quote> out = new ArrayList<>();
        try (Cursor c = db.getReadableDatabase().query(WordWakeDatabase.T_QUOTES,
                new String[]{"id", "text", "favorite", "built_in"}, null, null, null, null,
                "favorite DESC, built_in ASC, id DESC")) {
            while (c.moveToNext()) {
                Quote q = new Quote();
                q.id = c.getLong(0);
                q.text = c.getString(1);
                q.favorite = c.getInt(2) != 0;
                q.builtIn = c.getInt(3) != 0;
                out.add(q);
            }
        }
        return out;
    }

    public void setQuoteFavorite(long id, boolean favorite) {
        ContentValues v = new ContentValues();
        v.put("favorite", favorite ? 1 : 0);
        db.getWritableDatabase().update(WordWakeDatabase.T_QUOTES, v, "id=?", new String[]{String.valueOf(id)});
    }

    public void deleteQuote(long id) {
        db.getWritableDatabase().delete(WordWakeDatabase.T_QUOTES, "id=?", new String[]{String.valueOf(id)});
    }

    /** A random quote, chosen from favorites when there are any. Null if there are no quotes. */
    public String randomQuote() {
        String[] sqls = {"favorite=1", null};
        for (String where : sqls) {
            try (Cursor c = db.getReadableDatabase().query(WordWakeDatabase.T_QUOTES, new String[]{"text"},
                    where, null, null, null, "RANDOM()", "1")) {
                if (c.moveToFirst()) return c.getString(0);
            }
        }
        return null;
    }

    // ---- custom categories ----

    public List<Category> customCategories() {
        List<Category> out = new ArrayList<>();
        try (Cursor c = db.getReadableDatabase().query(WordWakeDatabase.T_CATEGORIES,
                new String[]{"id", "name", "color"}, null, null, null, null, "name ASC")) {
            while (c.moveToNext()) out.add(Category.custom(c.getLong(0), c.getString(1), c.getInt(2)));
        }
        return out;
    }

    public long addCategory(String name, int color) {
        ContentValues v = new ContentValues();
        v.put("name", name);
        v.put("color", color);
        return db.getWritableDatabase().insert(WordWakeDatabase.T_CATEGORIES, null, v);
    }

    public void deleteCategory(long id) {
        db.getWritableDatabase().delete(WordWakeDatabase.T_CATEGORIES, "id=?", new String[]{String.valueOf(id)});
    }
}
