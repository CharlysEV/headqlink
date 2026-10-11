package com.headqlink.link;

import android.app.Activity;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.text.HtmlCompat;

import com.andrerinas.openheadunit.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/** Condiciones disponibles sin conexión, tanto durante la configuración como desde el menú. */
final class TermsOfUse {
    private TermsOfUse() { }

    static void show(Activity activity) {
        TextView text = new TextView(activity);
        text.setId(R.id.hql_terms_detail);
        text.setTextAppearance(R.style.HQL_Text_Body);
        text.setTextColor(activity.getColor(R.color.hql_text));
        text.setText(HtmlCompat.fromHtml(Str.get(R.string.hql_terms_detail), HtmlCompat.FROM_HTML_MODE_LEGACY));
        text.setTextIsSelectable(true);
        int padding = Math.round(24 * activity.getResources().getDisplayMetrics().density);
        text.setPadding(padding, padding / 3, padding, padding / 3);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(text);
        new MaterialAlertDialogBuilder(activity)
                .setTitle(Str.get(R.string.hql_terms_title))
                .setView(scroll)
                .setPositiveButton(Str.get(R.string.hql_close), null)
                .show();
    }
}
