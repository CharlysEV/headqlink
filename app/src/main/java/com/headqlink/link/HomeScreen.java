package com.headqlink.link;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.andrerinas.openheadunit.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Casa (Home Assistant) en el coche: los elementos elegidos en el móvil en botones grandes (luces, enchufes, garaje,
 * persianas, cerraduras, escenas, scripts…) con su estado. Un toque hace lo suyo (HomeAssistant.action); las cerraduras y
 * puertas piden confirmar. Los sensores y el clima solo se miran. Se actualiza cada 5 s mientras se ve.
 */
final class HomeScreen implements CarScreen {
    private static final int COLUMNS = 4;
    private static final long REFRESH_MS = 5_000;

    private Host host;
    private HomeAssistant ha;
    private FrameLayout area;
    private TextView status;
    private GridLayout grid;
    private volatile boolean running;
    private final List<HomeAssistant.Entity> shown = new ArrayList<>();
    /** Elemento con un toque en curso (se pinta «…» hasta la respuesta). */
    private String busy;
    private final Runnable loop = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            load();
            host.post(() -> {
                if (running && area != null) area.postDelayed(this, REFRESH_MS);
            });
        }
    };

    @Override
    public View create(Host h) {
        host = h;
        Context c = h.context();
        ha = new HomeAssistant(c);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = new LinearLayout(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(28, 22, 28, 10);
        TextView title = CarStyle.text(c, Str.get(R.string.hql_ha_title), 34, CarStyle.TEXT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        head.addView(title);
        status = CarStyle.text(c, "", 22, CarStyle.TEXT_DIM);
        status.setPadding(24, 0, 0, 0);
        head.addView(status, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(head);
        area = new FrameLayout(c);
        col.addView(area, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        if (!ha.configured()) {
            area.addView(CarStyle.message(c, Str.get(R.string.hql_ha_not_set)));
            return col;
        }
        if (ha.entities().isEmpty()) {
            area.addView(CarStyle.message(c, Str.get(R.string.hql_ha_none_chosen)));
            return col;
        }
        grid = new GridLayout(c);
        grid.setColumnCount(COLUMNS);
        grid.setPadding(20, 4, 20, 20);
        ScrollView sv = new ScrollView(c);
        sv.addView(grid);
        area.addView(sv);
        status.setText(Str.get(R.string.hql_ha_loading));
        running = true;
        new Thread(loop, "hql-home").start();
        return col;
    }

    @Override
    public void destroy() {
        running = false;
    }

    /** Lee los estados (hilo propio) y repinta. */
    private void load() {
        try {
            List<HomeAssistant.Entity> list = ha.chosen();
            host.post(() -> {
                if (!running) return;
                shown.clear();
                shown.addAll(list);
                status.setText("");
                render();
            });
        } catch (Exception e) {
            String why = HomeAssistant.reason(e);
            L.w("Casa: Home Assistant no responde (" + why + ")");
            host.post(() -> {
                if (running) status.setText(Str.get(R.string.hql_ha_error, why));
            });
        }
    }

    private void render() {
        Context c = host.context();
        grid.removeAllViews();
        int w = Math.max(200, (host.width() - 40) / COLUMNS - 16);
        for (HomeAssistant.Entity e : shown) {
            View tile = tile(c, e, w);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = w;
            lp.height = 190;
            lp.setMargins(8, 8, 8, 8);
            grid.addView(tile, lp);
        }
    }

    private View tile(Context c, HomeAssistant.Entity e, int w) {
        boolean on = HomeAssistant.active(e);
        String[] act = HomeAssistant.action(e);
        LinearLayout t = new LinearLayout(c);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(22, 18, 22, 18);
        int bg = on ? (e.domain.equals("light") ? 0xFF4A3B12 : CarStyle.ACCENT_BG) : CarStyle.CARD;
        t.setBackground(CarStyle.round(bg, 28));
        LinearLayout top = new LinearLayout(c);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView icon = CarStyle.text(c, glyph(e), 40, 0xFFFFFFFF);
        top.addView(icon);
        TextView state = CarStyle.text(c, e.id.equals(busy) ? "…" : stateText(e), 22, on ? (e.domain.equals("light") ? 0xFFFFD27A
                : CarStyle.ACCENT) : CarStyle.TEXT_DIM);
        state.setGravity(Gravity.END);
        state.setSingleLine(true);
        top.addView(state, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        t.addView(top);
        TextView name = CarStyle.text(c, e.name, 26, CarStyle.TEXT);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setMaxLines(2);
        name.setPadding(0, 14, 0, 0);
        t.addView(name);
        if (act != null) {
            t.setOnClickListener(v -> {
                if (HomeAssistant.needsConfirm(e)) confirm(e, act);
                else run(e, act);
            });
        }
        return t;
    }

    /** Cerraduras y puertas: un segundo toque en «Sí» para hacerlo. */
    private void confirm(HomeAssistant.Entity e, String[] act) {
        Context c = host.context();
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(0xE60A0E14);
        box.setClickable(true);
        TextView q = CarStyle.text(c, Str.get("unlock".equals(act[1]) || "open_cover".equals(act[1]) ? R.string.hql_ha_confirm_open
                : R.string.hql_ha_confirm_close, e.name), 32, CarStyle.TEXT);
        q.setGravity(Gravity.CENTER);
        box.addView(q);
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, 30, 0, 0);
        TextView yes = CarStyle.pill(c, Str.get(R.string.hql_ha_yes));
        yes.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 30);
        yes.setPadding(60, 22, 60, 22);
        yes.setBackground(CarStyle.accent(34));
        yes.setTextColor(CarStyle.ON_ACCENT);
        TextView no = CarStyle.pill(c, Str.get(R.string.hql_cancel));
        no.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 30);
        no.setPadding(60, 22, 60, 22);
        row.addView(yes);
        row.addView(no);
        box.addView(row);
        area.addView(box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        yes.setOnClickListener(v -> {
            area.removeView(box);
            run(e, act);
        });
        no.setOnClickListener(v -> area.removeView(box));
    }

    private void run(HomeAssistant.Entity e, String[] act) {
        busy = e.id;
        render();
        new Thread(() -> {
            try {
                ha.call(act[0], act[1], e.id);
                L.i("Casa: " + act[0] + "." + act[1]);
                // Home Assistant tarda un poco en cambiar el estado (el garaje, más): se relee enseguida y otra vez luego.
                Thread.sleep(700);
            } catch (Exception ex) {
                String why = HomeAssistant.reason(ex);
                L.w("Casa: no se pudo (" + why + ")");
                host.post(() -> status.setText(Str.get(R.string.hql_ha_error, why)));
            }
            busy = null;
            load();
        }, "hql-home-call").start();
    }

    static String stateText(HomeAssistant.Entity e) {
        String s = e.state == null ? "" : e.state;
        switch (s) {
            case "on":
                return Str.get(R.string.hql_ha_on);
            case "off":
                return Str.get(R.string.hql_ha_off);
            case "open":
                return Str.get(R.string.hql_ha_open);
            case "closed":
                return Str.get(R.string.hql_ha_closed);
            case "opening":
                return Str.get(R.string.hql_ha_opening);
            case "closing":
                return Str.get(R.string.hql_ha_closing);
            case "locked":
                return Str.get(R.string.hql_ha_locked);
            case "unlocked":
                return Str.get(R.string.hql_ha_unlocked);
            case "unavailable":
            case "unknown":
                return "—";
            default:
                break;
        }
        if ("scene".equals(e.domain) || "script".equals(e.domain) || e.domain.endsWith("button")) return "";
        return e.value();
    }

    /** Un dibujo por tipo (y por clase en garajes, puertas, temperatura…). */
    static String glyph(HomeAssistant.Entity e) {
        String dc = e.deviceClass == null ? "" : e.deviceClass;
        switch (e.domain) {
            case "light":
                return "💡";
            case "switch":
            case "input_boolean":
                return "🔌";
            case "cover":
                return dc.equals("garage") ? "🚗" : dc.equals("door") || dc.equals("gate") ? "🚪" : "🪟";
            case "lock":
                return "🔒";
            case "scene":
                return "🎬";
            case "script":
            case "automation":
                return "⚙️";
            case "button":
            case "input_button":
                return "🔘";
            case "fan":
                return "🌀";
            case "climate":
                return "🌡️";
            case "alarm_control_panel":
                return "🛡️";
            case "media_player":
                return "🎵";
            case "sensor":
                return dc.equals("temperature") ? "🌡️" : dc.equals("humidity") ? "💧" : dc.equals("power") || dc.equals("energy") ? "⚡"
                        : dc.equals("battery") ? "🔋" : "📈";
            case "binary_sensor":
                return dc.equals("door") || dc.equals("window") || dc.equals("opening") ? "🚪" : dc.equals("motion") ? "🏃" : "●";
            default:
                return "●";
        }
    }
}
