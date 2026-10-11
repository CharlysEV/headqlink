package com.headqlink.link;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.andrerinas.openheadunit.R;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * Pestaña «Casa» de la app del móvil: los mismos elementos de Home Assistant que en el coche (HomeScreen), en tarjetas
 * de Material 3 de dos en dos. Un toque hace lo suyo (cerraduras y puertas piden confirmar); se actualiza cada 5 s
 * mientras se ve. Sin configurar, explica qué es y lleva a configurarlo.
 */
final class PhoneHome {
    private static final long REFRESH_MS = 5_000;
    private final Activity act;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ScrollView root;
    private final LinearLayout col;
    private final HomeAssistant ha;
    private boolean visible;
    private String busy;
    private final List<HomeAssistant.Entity> shown = new ArrayList<>();
    private String error;
    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            if (!visible) return;
            load();
            main.postDelayed(this, REFRESH_MS);
        }
    };

    PhoneHome(Activity act) {
        this.act = act;
        ha = new HomeAssistant(act);
        root = new ScrollView(act);
        root.setFillViewport(true);
        root.setBackgroundColor(0xFF0A0E14);
        col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(16), dp(16), dp(28));
        root.addView(col);
    }

    View view() {
        return root;
    }

    void show() {
        visible = true;
        render();
        main.removeCallbacks(refresher);
        if (ha.configured() && !ha.entities().isEmpty()) main.post(refresher);
    }

    void hide() {
        visible = false;
        main.removeCallbacks(refresher);
    }

    private void load() {
        new Thread(() -> {
            try {
                List<HomeAssistant.Entity> list = ha.chosen();
                main.post(() -> {
                    shown.clear();
                    shown.addAll(list);
                    error = null;
                    if (visible) render();
                });
            } catch (Exception e) {
                String why = HomeAssistant.reason(e);
                main.post(() -> {
                    error = why;
                    if (visible) render();
                });
            }
        }, "hql-phone-home").start();
    }

    private void render() {
        col.removeAllViews();
        TextView title = text(Str.get(R.string.hql_ha_title), com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall,
                PhoneCharts.TEXT);
        title.setPadding(dp(4), 0, 0, dp(12));
        col.addView(title);
        if (!ha.configured() || ha.entities().isEmpty()) {
            MaterialCardView card = card();
            LinearLayout in = new LinearLayout(act);
            in.setOrientation(LinearLayout.VERTICAL);
            in.setPadding(dp(18), dp(18), dp(18), dp(18));
            in.addView(text(Str.get(R.string.hql_ha_intro), com.google.android.material.R.style.TextAppearance_Material3_BodyMedium,
                    PhoneCharts.DIM));
            MaterialButton go = new MaterialButton(act);
            go.setText(Str.get(ha.configured() ? R.string.hql_ha_pick : R.string.hql_ha_setup));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(14);
            go.setOnClickListener(v -> act.startActivity(new Intent(act, HomeAssistantActivity.class)));
            in.addView(go, lp);
            card.addView(in);
            col.addView(card);
            return;
        }
        LinearLayout head = new LinearLayout(act);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView status = text(error != null ? Str.get(R.string.hql_ha_error, error) : shown.isEmpty() ? Str.get(R.string.hql_ha_loading) : "",
                com.google.android.material.R.style.TextAppearance_Material3_BodySmall, error != null ? PhoneCharts.BAD : PhoneCharts.FAINT);
        head.addView(status, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        MaterialButton edit = new MaterialButton(act, null, androidx.appcompat.R.attr.borderlessButtonStyle);
        edit.setText(Str.get(R.string.hql_ha_edit));
        edit.setOnClickListener(v -> act.startActivity(new Intent(act, HomeAssistantActivity.class)));
        head.addView(edit);
        col.addView(head);
        GridLayout grid = new GridLayout(act);
        grid.setColumnCount(2);
        int w = (act.getResources().getDisplayMetrics().widthPixels - dp(32)) / 2 - dp(8);
        for (HomeAssistant.Entity e : shown) {
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = w;
            lp.setMargins(dp(4), dp(4), dp(4), dp(4));
            grid.addView(tile(e), lp);
        }
        col.addView(grid);
    }

    private View tile(HomeAssistant.Entity e) {
        boolean on = HomeAssistant.active(e);
        boolean light = "light".equals(e.domain);
        MaterialCardView card = card();
        card.setCardBackgroundColor(on ? (light ? 0xFF4A3B12 : 0xFF0E3B37) : 0xFF111821);
        card.setStrokeColor(on ? (light ? 0xFF7A6020 : 0xFF14605A) : 0xFF243140);
        LinearLayout in = new LinearLayout(act);
        in.setOrientation(LinearLayout.VERTICAL);
        in.setPadding(dp(14), dp(12), dp(14), dp(14));
        in.setMinimumHeight(dp(104));
        LinearLayout top = new LinearLayout(act);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView icon = new TextView(act);
        icon.setText(HomeScreen.glyph(e));
        icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        top.addView(icon);
        TextView state = text(e.id.equals(busy) ? "…" : HomeScreen.stateText(e), com.google.android.material.R.style.TextAppearance_Material3_LabelLarge,
                on ? (light ? 0xFFFFD27A : PhoneCharts.ACCENT) : PhoneCharts.DIM);
        state.setGravity(Gravity.END);
        state.setSingleLine(true);
        top.addView(state, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        in.addView(top);
        TextView name = text(e.name, com.google.android.material.R.style.TextAppearance_Material3_TitleSmall, PhoneCharts.TEXT);
        name.setMaxLines(2);
        name.setPadding(0, dp(10), 0, 0);
        in.addView(name);
        card.addView(in);
        String[] act0 = HomeAssistant.action(e);
        if (act0 != null) {
            card.setClickable(true);
            card.setOnClickListener(v -> {
                if (!HomeAssistant.needsConfirm(e)) {
                    run(e, act0);
                    return;
                }
                boolean open = "unlock".equals(act0[1]) || "open_cover".equals(act0[1]);
                new MaterialAlertDialogBuilder(act)
                        .setTitle(Str.get(open ? R.string.hql_ha_confirm_open : R.string.hql_ha_confirm_close, e.name))
                        .setPositiveButton(Str.get(R.string.hql_ha_yes), (d, w) -> run(e, act0))
                        .setNegativeButton(Str.get(R.string.hql_cancel), null)
                        .show();
            });
        }
        return card;
    }

    private void run(HomeAssistant.Entity e, String[] a) {
        busy = e.id;
        render();
        new Thread(() -> {
            try {
                ha.call(a[0], a[1], e.id);
                Thread.sleep(700);
            } catch (Exception ex) {
                String why = HomeAssistant.reason(ex);
                main.post(() -> error = why);
            }
            main.post(() -> busy = null);
            load();
        }, "hql-phone-home-call").start();
    }

    private MaterialCardView card() {
        MaterialCardView c = new MaterialCardView(act, null, com.google.android.material.R.attr.materialCardViewOutlinedStyle);
        c.setCardBackgroundColor(0xFF111821);
        c.setStrokeColor(0xFF243140);
        c.setRadius(dp(20));
        c.setRippleColor(ColorStateList.valueOf(0x3300E5C7));
        return c;
    }

    private TextView text(String s, int appearance, int color) {
        TextView t = new TextView(act);
        t.setTextAppearance(appearance);
        t.setText(s);
        t.setTextColor(color);
        return t;
    }

    private int dp(float d) {
        return Math.round(d * act.getResources().getDisplayMetrics().density);
    }
}
