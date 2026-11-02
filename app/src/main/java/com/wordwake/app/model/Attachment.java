package com.wordwake.app.model;

/** A picture or document stored in the app's private files directory. */
public final class Attachment {
    public long id;
    public long scheduleId;
    /** File name inside the attachments directory. */
    public String fileName = "";
    public String displayName = "";
    public String mime = "";

    public boolean isImage() {
        return mime != null && mime.startsWith("image/");
    }
}
