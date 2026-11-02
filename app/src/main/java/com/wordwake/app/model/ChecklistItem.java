package com.wordwake.app.model;

/** One step of a reminder's action plan. */
public final class ChecklistItem {
    public long id;
    public long scheduleId;
    public String text = "";
    public boolean done;

    public ChecklistItem() {}

    public ChecklistItem(String text) {
        this.text = text;
    }
}
