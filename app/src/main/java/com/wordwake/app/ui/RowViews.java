package com.wordwake.app.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.wordwake.app.R;

/** Small helpers to build list rows for the settings and permission screens. */
final class RowViews {

    private RowViews() {}

    static TextView header(ViewGroup parent, CharSequence text) {
        TextView t = (TextView) LayoutInflater.from(parent.getContext()).inflate(R.layout.item_section_header, parent, false);
        t.setText(text);
        parent.addView(t);
        return t;
    }

    /** A tappable row with title and summary. Returns the summary view for later updates. */
    static TextView row(ViewGroup parent, CharSequence title, CharSequence summary, View.OnClickListener click) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_row, parent, false);
        ((TextView) v.findViewById(R.id.row_title)).setText(title);
        TextView s = v.findViewById(R.id.row_summary);
        s.setText(summary);
        s.setVisibility(summary == null || summary.length() == 0 ? View.GONE : View.VISIBLE);
        v.findViewById(R.id.row_switch).setVisibility(View.GONE);
        if (click != null) v.setOnClickListener(click);
        else v.setClickable(false);
        parent.addView(v);
        return s;
    }

    interface Toggle { void onToggle(boolean checked); }

    static MaterialSwitch toggle(ViewGroup parent, CharSequence title, CharSequence summary, boolean checked, Toggle onToggle) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_row, parent, false);
        ((TextView) v.findViewById(R.id.row_title)).setText(title);
        TextView s = v.findViewById(R.id.row_summary);
        s.setText(summary);
        s.setVisibility(summary == null || summary.length() == 0 ? View.GONE : View.VISIBLE);
        MaterialSwitch sw = v.findViewById(R.id.row_switch);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener((b, c) -> onToggle.onToggle(c));
        v.setOnClickListener(x -> sw.toggle());
        parent.addView(v);
        return sw;
    }

    /** A permission/status card: title, state label, explanation and an optional action button. */
    static View statusCard(ViewGroup parent, CharSequence title, CharSequence state, boolean good,
                           CharSequence explanation, CharSequence actionLabel, View.OnClickListener action) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_permission, parent, false);
        ((TextView) v.findViewById(R.id.perm_title)).setText(title);
        TextView st = v.findViewById(R.id.perm_state);
        st.setText(state);
        st.setTextColor(parent.getContext().getColor(good ? R.color.ok_text : R.color.warn_text));
        st.setBackgroundResource(good ? R.drawable.bg_chip_ok : R.drawable.bg_chip_warn);
        ((TextView) v.findViewById(R.id.perm_explanation)).setText(explanation);
        MaterialButton b = v.findViewById(R.id.perm_action);
        if (actionLabel == null) {
            b.setVisibility(View.GONE);
        } else {
            b.setText(actionLabel);
            b.setOnClickListener(action);
        }
        parent.addView(v);
        return v;
    }
}
