package com.headqlink.link;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.andrerinas.openheadunit.R;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * Home Assistant en el móvil (⚙ › Contenido del modo extendido › Home Assistant): la dirección de la casa y el token
 * (cifrado; si ya hay uno, el campo vacío lo deja como está), «Probar y guardar», y la lista de elementos de la casa por
 * tipos para elegir los que salen en el coche (Casa). Cada casilla se guarda al momento.
 */
public class HomeAssistantActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private HomeAssistant ha;
    private TextView status;
    private ChipGroup filters;
    private LinearLayout list;
    private View pickTitle;
    private final List<HomeAssistant.Entity> all = new ArrayList<>();
    private String filter = "chosen";

    /** Grupos del filtro: id, texto y los tipos que entran. */
    private static final Object[][] GROUPS = {
            {"chosen", R.string.hql_ha_f_chosen, null},
            {"light", R.string.hql_ha_f_lights, new String[]{"light"}},
            {"switch", R.string.hql_ha_f_switches, new String[]{"switch", "input_boolean", "fan"}},
            {"cover", R.string.hql_ha_f_covers, new String[]{"cover", "lock"}},
            {"scene", R.string.hql_ha_f_scenes, new String[]{"scene", "script", "button", "input_button", "automation"}},
            {"sensor", R.string.hql_ha_f_sensors, new String[]{"sensor", "binary_sensor", "climate", "alarm_control_panel", "media_player"}},
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.hql_activity_ha);
        ha = new HomeAssistant(this);
        TextInputEditText url = findViewById(R.id.hql_ha_url);
        TextInputEditText token = findViewById(R.id.hql_ha_token);
        TextInputLayout tokenBox = findViewById(R.id.hql_ha_token_box);
        status = findViewById(R.id.hql_ha_status);
        filters = findViewById(R.id.hql_ha_filters);
        list = findViewById(R.id.hql_ha_list);
        pickTitle = findViewById(R.id.hql_ha_pick_title);
        url.setText(ha.url());
        if (ha.configured()) tokenBox.setHelperText(Str.get(R.string.hql_ha_token_saved));
        MaterialButton test = findViewById(R.id.hql_ha_test);
        test.setOnClickListener(v -> {
            String u = String.valueOf(url.getText());
            String t = String.valueOf(token.getText()).trim();
            if (HomeAssistant.normalizeUrl(u).isEmpty() || (t.isEmpty() && !ha.configured())) {
                status.setText(Str.get(R.string.hql_ha_need_both));
                return;
            }
            status.setText(Str.get(R.string.hql_ha_testing));
            test.setEnabled(false);
            new Thread(() -> {
                String err;
                try {
                    ha.save(u, t);
                    err = ha.test();
                } catch (Exception e) {
                    err = HomeAssistant.reason(e);
                }
                String fe = err;
                main.post(() -> {
                    test.setEnabled(true);
                    if (fe != null) {
                        status.setText(Str.get(R.string.hql_ha_failed, fe));
                        status.setTextColor(getColor(R.color.hql_error));
                        return;
                    }
                    token.setText("");
                    tokenBox.setHelperText(Str.get(R.string.hql_ha_token_saved));
                    status.setTextColor(getColor(R.color.hql_ok));
                    status.setText(Str.get(R.string.hql_ha_ok));
                    L.i("Casa: Home Assistant conectado");
                    loadEntities();
                });
            }, "hql-ha-test").start();
        });
        findViewById(R.id.hql_ha_forget).setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                .setTitle(Str.get(R.string.hql_ha_forget))
                .setMessage(Str.get(R.string.hql_ha_forget_q))
                .setPositiveButton(Str.get(R.string.hql_ha_forget), (d, w) -> {
                    ha.forget();
                    url.setText("");
                    tokenBox.setHelperText(null);
                    all.clear();
                    showList();
                    status.setText("");
                })
                .setNegativeButton(Str.get(R.string.hql_cancel), null)
                .show());
        if (ha.configured()) loadEntities();
    }

    private void loadEntities() {
        new Thread(() -> {
            try {
                List<HomeAssistant.Entity> got = ha.all();
                main.post(() -> {
                    all.clear();
                    all.addAll(got);
                    if (ha.entities().isEmpty()) filter = "light";
                    buildFilters();
                    showList();
                });
            } catch (Exception e) {
                String why = HomeAssistant.reason(e);
                main.post(() -> status.setText(Str.get(R.string.hql_ha_failed, why)));
            }
        }, "hql-ha-list").start();
    }

    private void buildFilters() {
        filters.removeAllViews();
        for (Object[] g : GROUPS) {
            Chip c = new Chip(this, null, com.google.android.material.R.attr.chipStyle);
            String id = (String) g[0];
            int n = count(id);
            c.setText(Str.get((Integer) g[1]) + (n > 0 ? " · " + n : ""));
            c.setCheckable(true);
            c.setChecked(id.equals(filter));
            c.setOnClickListener(v -> {
                filter = id;
                buildFilters();
                showList();
            });
            filters.addView(c);
        }
    }

    private int count(String group) {
        if ("chosen".equals(group)) return ha.entities().size();
        int n = 0;
        for (HomeAssistant.Entity e : all) if (in(group, e)) n++;
        return n;
    }

    private static boolean in(String group, HomeAssistant.Entity e) {
        for (Object[] g : GROUPS) {
            if (!g[0].equals(group) || g[2] == null) continue;
            for (String d : (String[]) g[2]) if (d.equals(e.domain)) return true;
        }
        return false;
    }

    private void showList() {
        list.removeAllViews();
        boolean has = !all.isEmpty();
        pickTitle.setVisibility(has ? View.VISIBLE : View.GONE);
        filters.setVisibility(has ? View.VISIBLE : View.GONE);
        if (!has) return;
        List<String> chosen = ha.entities();
        List<HomeAssistant.Entity> rows = new ArrayList<>();
        if ("chosen".equals(filter)) {
            for (String id : chosen) for (HomeAssistant.Entity e : all) if (e.id.equals(id)) rows.add(e);
        } else {
            for (HomeAssistant.Entity e : all) if (in(filter, e)) rows.add(e);
        }
        if (rows.isEmpty()) {
            TextView t = new TextView(this);
            t.setText(Str.get(R.string.hql_ha_none_here));
            t.setTextColor(getColor(R.color.hql_text_dim));
            t.setPadding(0, dp(12), 0, 0);
            list.addView(t);
            return;
        }
        for (HomeAssistant.Entity e : rows) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            MaterialCheckBox cb = new MaterialCheckBox(this);
            cb.setText(e.name);
            cb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            cb.setTextColor(getColor(R.color.hql_text));
            cb.setChecked(chosen.contains(e.id));
            cb.setOnCheckedChangeListener((v, on) -> {
                List<String> ids = new ArrayList<>(ha.entities());
                if (on && !ids.contains(e.id)) ids.add(e.id);
                if (!on) ids.remove(e.id);
                ha.setEntities(ids);
                buildFilters();
            });
            row.addView(cb, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            TextView st = new TextView(this);
            st.setText(e.value());
            st.setTextColor(getColor(R.color.hql_text_faint));
            st.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            st.setSingleLine(true);
            row.addView(st);
            list.addView(row);
        }
    }

    private int dp(float d) {
        return Math.round(d * getResources().getDisplayMetrics().density);
    }
}
