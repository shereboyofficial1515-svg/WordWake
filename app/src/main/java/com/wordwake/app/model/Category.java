package com.wordwake.app.model;

import com.wordwake.app.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** A reminder category with its visual identity. Custom categories use the key "c:&lt;id&gt;". */
public final class Category {

    public static final String KEY_PERSONAL = "personal";
    public static final String KEY_TAX = "tax";
    public static final String KEY_BILLS = "bills";
    public static final String KEY_APPOINTMENTS = "appointments";
    public static final String KEY_MOTIVATION = "motivation";
    public static final String CUSTOM_PREFIX = "c:";

    public final String key;
    /** Name resource for built-ins, 0 for custom categories. */
    public final int nameRes;
    public final String customName;
    public final int colorRes;
    public final int iconRes;
    /** Packed ARGB for custom categories, 0 for built-ins. */
    public final int customColor;

    private Category(String key, int nameRes, String customName, int colorRes, int customColor, int iconRes) {
        this.key = key;
        this.nameRes = nameRes;
        this.customName = customName;
        this.colorRes = colorRes;
        this.customColor = customColor;
        this.iconRes = iconRes;
    }

    private static final List<Category> BUILT_IN = Collections.unmodifiableList(Arrays.asList(
            new Category(KEY_PERSONAL, R.string.cat_personal, null, R.color.cat_personal, 0, R.drawable.ic_person),
            new Category("business", R.string.cat_business, null, R.color.cat_business, 0, R.drawable.ic_work),
            new Category("finance", R.string.cat_finance, null, R.color.cat_finance, 0, R.drawable.ic_money),
            new Category(KEY_TAX, R.string.cat_tax, null, R.color.cat_tax, 0, R.drawable.ic_bank),
            new Category(KEY_APPOINTMENTS, R.string.cat_appointments, null, R.color.cat_appointments, 0, R.drawable.ic_event),
            new Category("education", R.string.cat_education, null, R.color.cat_education, 0, R.drawable.ic_school),
            new Category("health", R.string.cat_health, null, R.color.cat_health, 0, R.drawable.ic_favorite),
            new Category("travel", R.string.cat_travel, null, R.color.cat_travel, 0, R.drawable.ic_flight),
            new Category(KEY_BILLS, R.string.cat_bills, null, R.color.cat_bills, 0, R.drawable.ic_receipt),
            new Category("documents", R.string.cat_documents, null, R.color.cat_documents, 0, R.drawable.ic_description),
            new Category(KEY_MOTIVATION, R.string.cat_motivation, null, R.color.cat_motivation, 0, R.drawable.ic_star)));

    public static List<Category> builtIns() {
        return BUILT_IN;
    }

    public static Category custom(long id, String name, int color) {
        return new Category(CUSTOM_PREFIX + id, 0, name, 0, color, R.drawable.ic_label);
    }

    /** Finds a category, falling back to Personal for unknown or deleted keys. */
    public static Category find(String key, List<Category> custom) {
        for (Category c : BUILT_IN) if (c.key.equals(key)) return c;
        if (custom != null) for (Category c : custom) if (c.key.equals(key)) return c;
        return BUILT_IN.get(0);
    }

    public static List<Category> all(List<Category> custom) {
        List<Category> out = new ArrayList<>(BUILT_IN);
        if (custom != null) out.addAll(custom);
        return out;
    }

    public String name(android.content.Context c) {
        return nameRes != 0 ? c.getString(nameRes) : customName;
    }

    public int color(android.content.Context c) {
        return colorRes != 0 ? c.getColor(colorRes) : customColor;
    }
}
