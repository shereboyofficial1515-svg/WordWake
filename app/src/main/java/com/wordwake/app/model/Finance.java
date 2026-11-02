package com.wordwake.app.model;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Details the user records about a tax or bill obligation. Everything here is user-entered:
 * WordWake never supplies tax rules, rates, deadlines or payment instructions of its own, and it
 * never initiates a payment.
 */
public final class Finance {
    public String taxType = "";
    public String jurisdiction = "";
    public String authority = "";
    public String taxYear = "";
    /** Epoch ms; 0 = not set. */
    public long filingDeadline;
    public long paymentDeadline;
    public String amount = "";
    public String contact = "";
    public String website = "";
    public String documents = "";

    public static Finance parse(String json) {
        Finance f = new Finance();
        if (json == null || json.isEmpty()) return f;
        try {
            JSONObject o = new JSONObject(json);
            f.taxType = o.optString("taxType");
            f.jurisdiction = o.optString("jurisdiction");
            f.authority = o.optString("authority");
            f.taxYear = o.optString("taxYear");
            f.filingDeadline = o.optLong("filingDeadline");
            f.paymentDeadline = o.optLong("paymentDeadline");
            f.amount = o.optString("amount");
            f.contact = o.optString("contact");
            f.website = o.optString("website");
            f.documents = o.optString("documents");
        } catch (JSONException e) {
            // Damaged details must not stop the reminder itself from working.
            return new Finance();
        }
        return f;
    }

    public String toJson() {
        try {
            return new JSONObject()
                    .put("taxType", taxType)
                    .put("jurisdiction", jurisdiction)
                    .put("authority", authority)
                    .put("taxYear", taxYear)
                    .put("filingDeadline", filingDeadline)
                    .put("paymentDeadline", paymentDeadline)
                    .put("amount", amount)
                    .put("contact", contact)
                    .put("website", website)
                    .put("documents", documents)
                    .toString();
        } catch (JSONException e) {
            return "";
        }
    }

    public boolean isEmpty() {
        return taxType.isEmpty() && jurisdiction.isEmpty() && authority.isEmpty() && taxYear.isEmpty()
                && filingDeadline == 0 && paymentDeadline == 0 && amount.isEmpty() && contact.isEmpty()
                && website.isEmpty() && documents.isEmpty();
    }

    /** The earliest of the recorded deadlines, or 0. */
    public long earliestDeadline() {
        if (filingDeadline > 0 && paymentDeadline > 0) return Math.min(filingDeadline, paymentDeadline);
        return Math.max(filingDeadline, paymentDeadline);
    }
}
