package com.wordwake.app.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.card.MaterialCardView;
import com.wordwake.app.R;
import com.wordwake.app.engine.ReminderStatus;

/** Small builders for views that are assembled from data. */
final class Ui {

    private Ui() {}

    static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static LinearLayout vertical(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return l;
    }

    static TextView text(Context c, CharSequence s, int appearance) {
        TextView t = new TextView(c);
        t.setTextAppearance(appearance);
        t.setText(s);
        return t;
    }

    static TextView secondary(Context c, CharSequence s) {
        TextView t = text(c, s, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        t.setTextColor(c.getColor(R.color.text_secondary));
        return t;
    }

    static TextView sectionHeader(Context c, CharSequence s) {
        TextView t = text(c, s, R.style.SectionTitle);
        int p = dp(c, 16);
        t.setPadding(p, dp(c, 20), p, dp(c, 6));
        return t;
    }

    static MaterialCardView card(Context c) {
        MaterialCardView card = new MaterialCardView(c);
        card.setCardBackgroundColor(c.getColor(R.color.card_bg));
        card.setStrokeColor(c.getColor(R.color.card_stroke));
        card.setStrokeWidth(dp(c, 1));
        card.setCardElevation(0);
        card.setRadius(dp(c, 16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int m = dp(c, 16);
        lp.setMargins(m, dp(c, 6), m, dp(c, 6));
        card.setLayoutParams(lp);
        return card;
    }

    /** A pill-shaped label with the given color. */
    static TextView badge(Context c, CharSequence s, int color) {
        TextView t = text(c, s, com.google.android.material.R.style.TextAppearance_Material3_LabelSmall);
        t.setTextColor(color);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(c, 10));
        bg.setColor(withAlpha(color, 0.14f));
        t.setBackground(bg);
        t.setPadding(dp(c, 8), dp(c, 3), dp(c, 8), dp(c, 3));
        return t;
    }

    static int withAlpha(int color, float alpha) {
        return (color & 0x00FFFFFF) | (Math.round(alpha * 255) << 24);
    }

    /** A round tinted icon, used for category identity. */
    static ImageView categoryIcon(Context c, int iconRes, int color, int sizeDp) {
        ImageView i = new ImageView(c);
        i.setImageResource(iconRes);
        i.setImageTintList(ColorStateList.valueOf(color));
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(withAlpha(color, 0.14f));
        i.setBackground(bg);
        int p = dp(c, sizeDp / 5);
        i.setPadding(p, p, p, p);
        i.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        i.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return i;
    }

    static View iconRow(Context c, int iconRes, CharSequence title, CharSequence summary, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int p = dp(c, 12);
        row.setPadding(p, p, p, p);
        row.setMinimumHeight(dp(c, 56));
        TypedValue tv = new TypedValue();
        c.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        row.setBackgroundResource(tv.resourceId);
        row.setOnClickListener(click);
        ImageView icon = categoryIcon(c, iconRes, c.getColor(R.color.brand_primary), 40);
        row.addView(icon);
        LinearLayout texts = vertical(c);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginStart(dp(c, 14));
        texts.setLayoutParams(lp);
        texts.addView(text(c, title, com.google.android.material.R.style.TextAppearance_Material3_BodyLarge));
        if (summary != null && summary.length() > 0) texts.addView(secondary(c, summary));
        row.addView(texts);
        return row;
    }

    static int statusLabel(int status) {
        switch (status) {
            case ReminderStatus.DUE_TODAY: return R.string.badge_due_today;
            case ReminderStatus.URGENT: return R.string.badge_urgent;
            case ReminderStatus.OVERDUE: return R.string.badge_overdue;
            case ReminderStatus.COMPLETED: return R.string.badge_completed;
            case ReminderStatus.PAUSED: return R.string.badge_paused;
            default: return R.string.badge_upcoming;
        }
    }

    static int statusColor(Context c, int status) {
        switch (status) {
            case ReminderStatus.DUE_TODAY: return c.getColor(R.color.brand_secondary);
            case ReminderStatus.URGENT: return c.getColor(R.color.warn_text);
            case ReminderStatus.OVERDUE: return c.getColor(R.color.error_text);
            case ReminderStatus.COMPLETED: return c.getColor(R.color.ok_text);
            case ReminderStatus.PAUSED: return c.getColor(R.color.text_secondary);
            default: return c.getColor(R.color.brand_primary);
        }
    }
}
