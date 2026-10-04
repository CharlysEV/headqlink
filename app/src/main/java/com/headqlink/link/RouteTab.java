package com.headqlink.link;

import com.andrerinas.openheadunit.R;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.andrerinas.openheadunit.aap.NavTap;

import java.util.Locale;

/**
 * Pestaña "Ruta": destino y llegada (de Android Auto), gráfica de elevación de la ruta con el
 * viento por tramos y el % de batería previsto, tiempo en el destino a la hora de llegada y
 * cargadores junto a la ruta (con "Ir", que abre la navegación de Google Maps en el móvil y AA la
 * muestra). Todo estimado: ver RoutePlanner y EnergyModel.
 */
final class RouteTab implements CarScreen {
    private RoutePlanner planner;
    private Chart chart;
    private TextView socValue;
    private TextView weather;
    private LinearLayout chargers;
    private int shownChargersFor;
    private boolean running;
    private Context ctx;
    private android.widget.FrameLayout root;
    private TextView goMaps;
    private TextView clear;
    private final Runnable tickTask = this::tick;

    @Override
    public View create(Host h) {
        ctx = h.context();
        planner = RoutePlanner.start(ctx);
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(24, 8, 24, 24);

        chart = new Chart(ctx);
        LinearLayout left = CarStyle.card(ctx);
        // Destino elegido aquí (AA no nos manda el suyo): buscar, abrir en Google Maps, quitar.
        LinearLayout actions = new LinearLayout(ctx);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, 0, 0, 12);
        TextView search = CarStyle.pill(ctx, Str.get(R.string.hql_route_search_dest));
        search.setBackground(CarStyle.round(CarStyle.ACCENT, 28));
        search.setTextColor(0xFF0B1D36);
        goMaps = CarStyle.pill(ctx, Str.get(R.string.hql_route_guide_maps));
        clear = CarStyle.pill(ctx, Str.get(R.string.hql_remove));
        actions.addView(search);
        actions.addView(goMaps);
        actions.addView(clear);
        search.setOnClickListener(v -> openSearch());
        goMaps.setOnClickListener(v -> {
            RoutePlanner.Place m = RoutePlanner.manualDestination();
            if (m != null) navigateTo(m.lat, m.lon);
        });
        clear.setOnClickListener(v -> {
            RoutePlanner.setManualDestination(null);
            tick();
        });
        left.addView(actions);
        left.addView(chart, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.7f);
        llp.rightMargin = 20;
        row.addView(left, llp);

        LinearLayout right = new LinearLayout(ctx);
        right.setOrientation(LinearLayout.VERTICAL);
        row.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        // Batería: el usuario indica el % actual (hasta tener datos del coche).
        LinearLayout bat = CarStyle.card(ctx);
        bat.addView(CarStyle.label(ctx, Str.get(R.string.hql_route_battery_now)));
        LinearLayout ctl = new LinearLayout(ctx);
        ctl.setGravity(Gravity.CENTER_VERTICAL);
        TextView minus = CarStyle.pill(ctx, "−5");
        socValue = CarStyle.text(ctx, "—", 44, CarStyle.TEXT);
        socValue.setPadding(10, 0, 20, 0);
        TextView plus = CarStyle.pill(ctx, "+5");
        ctl.addView(minus);
        ctl.addView(socValue);
        ctl.addView(plus);
        bat.addView(ctl);
        minus.setOnClickListener(v -> bump(-5));
        plus.setOnClickListener(v -> bump(5));
        right.addView(bat, spaced());

        LinearLayout wx = CarStyle.card(ctx);
        wx.addView(CarStyle.label(ctx, Str.get(R.string.hql_route_weather)));
        weather = CarStyle.text(ctx, "—", 26, CarStyle.TEXT);
        wx.addView(weather);
        right.addView(wx, spaced());

        LinearLayout ch = CarStyle.card(ctx);
        ch.addView(CarStyle.label(ctx, Str.get(R.string.hql_route_chargers)));
        chargers = new LinearLayout(ctx);
        chargers.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(ctx);
        sv.addView(chargers);
        ch.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        right.addView(ch, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        running = true;
        tick();
        root = new android.widget.FrameLayout(ctx);
        root.addView(row, CarStyle.match());
        return root;
    }

    // ------------------------------------------------------------------ búsqueda de destino

    /** Buscador a pantalla completa de la pestaña: texto, resultados y teclado del coche. */
    private void openSearch() {
        LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(CarStyle.BG);
        panel.setClickable(true);
        LinearLayout bar = CarStyle.bar(ctx);
        TextView query = CarStyle.text(ctx, "", 28, CarStyle.TEXT);
        query.setHint(Str.get(R.string.hql_route_query_hint));
        query.setHintTextColor(CarStyle.TEXT_DIM);
        query.setSingleLine(true);
        query.setPadding(24, 12, 24, 12);
        query.setBackground(CarStyle.round(CarStyle.ITEM_BG, 28));
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        qlp.rightMargin = 10;
        bar.addView(query, qlp);
        TextView find = CarStyle.pill(ctx, Str.get(R.string.hql_search));
        TextView close = CarStyle.pill(ctx, Str.get(R.string.hql_close));
        bar.addView(find);
        bar.addView(close);
        panel.addView(bar);
        LinearLayout results = new LinearLayout(ctx);
        results.setOrientation(LinearLayout.VERTICAL);
        results.setPadding(24, 8, 24, 8);
        ScrollView rs = new ScrollView(ctx);
        rs.addView(results);
        panel.addView(rs, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        StringBuilder buf = new StringBuilder();
        Runnable doSearch = () -> {
            String q = buf.toString().trim();
            if (q.isEmpty()) return;
            results.removeAllViews();
            results.addView(CarStyle.text(ctx, Str.get(R.string.hql_searching), 24, CarStyle.TEXT_DIM));
            double[] pos = planner.position();
            new Thread(() -> {
                java.util.List<RoutePlanner.Place> found;
                String err = null;
                try {
                    found = RoutePlanner.search(q, pos[0], pos[1]);
                } catch (Exception e) {
                    found = new java.util.ArrayList<>();
                    err = e.getMessage();
                }
                java.util.List<RoutePlanner.Place> f = found;
                String fe = err;
                results.post(() -> showResults(results, f, fe, panel));
            }, "route-search").start();
        };
        CarKeyboard kb = new CarKeyboard(ctx, new CarKeyboard.Listener() {
            @Override
            public void onText(String t) {
                buf.append(t);
                query.setText(buf);
            }

            @Override
            public void onBackspace() {
                if (buf.length() > 0) buf.setLength(buf.length() - 1);
                query.setText(buf);
            }

            @Override
            public void onEnter() {
                doSearch.run();
            }

            @Override
            public void onHide() {
            }
        });
        panel.addView(kb);
        find.setOnClickListener(v -> doSearch.run());
        close.setOnClickListener(v -> root.removeView(panel));
        root.addView(panel, CarStyle.match());
    }

    private void showResults(LinearLayout results, java.util.List<RoutePlanner.Place> found, String err, View panel) {
        results.removeAllViews();
        if (found.isEmpty()) {
            results.addView(CarStyle.text(ctx, err != null ? Str.get(R.string.hql_search_failed, err) : Str.get(R.string.hql_no_results), 24, CarStyle.TEXT_DIM));
            return;
        }
        for (RoutePlanner.Place pl : found) {
            LinearLayout item = new LinearLayout(ctx);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(22, 14, 22, 14);
            item.setBackground(CarStyle.round(CarStyle.ITEM_BG, 18));
            TextView n = CarStyle.text(ctx, pl.name, 26, CarStyle.TEXT);
            n.setSingleLine(true);
            TextView d = CarStyle.text(ctx, pl.detail, 20, CarStyle.TEXT_DIM);
            d.setSingleLine(true);
            d.setEllipsize(android.text.TextUtils.TruncateAt.END);
            item.addView(n);
            item.addView(d);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = 8;
            results.addView(item, lp);
            item.setOnClickListener(v -> {
                L.i("ruta: destino elegido en el coche: " + pl.name);
                RoutePlanner.setManualDestination(pl);
                root.removeView(panel);
                tick();
            });
        }
    }

    private static LinearLayout.LayoutParams spaced() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = 16;
        return lp;
    }

    private void bump(int d) {
        double now = planner.socNow();
        planner.setSoc(Double.isNaN(now) ? 80 : Math.round(now) + d);
        tick();
    }

    private void tick() {
        if (!running) return;
        boolean manual = RoutePlanner.manualDestination() != null;
        goMaps.setVisibility(manual ? View.VISIBLE : View.GONE);
        clear.setVisibility(manual ? View.VISIBLE : View.GONE);
        RoutePlanner.Plan p = planner.plan();
        double soc = planner.socNow();
        socValue.setText(Double.isNaN(soc) ? "— %" : String.format(Locale.getDefault(), "%.0f %%", soc));
        chart.plan = p;
        chart.soc = soc;
        chart.status = planner.status();
        chart.nav = NavTap.getInfo();
        chart.invalidate();
        if (p != null) {
            if (Double.isNaN(p.destTemp)) {
                weather.setText(Str.get(R.string.hql_route_no_forecast));
            } else {
                weather.setText(Str.get(R.string.hql_route_forecast, weatherText(p.destCode), p.destTemp, p.destWind, p.destRain));
            }
            int key = System.identityHashCode(p) + p.progress / 10;
            if (key != shownChargersFor) {
                shownChargersFor = key;
                fillChargers(p);
            }
        }
        chart.removeCallbacks(tickTask);
        chart.postDelayed(tickTask, 1000);
    }

    private void fillChargers(RoutePlanner.Plan p) {
        chargers.removeAllViews();
        double here = p.km[Math.min(p.progress, p.n - 1)];
        int shown = 0;
        for (RoutePlanner.Charger c : p.chargers) {
            if (c.kmAlong < here - 0.5 || shown >= 12) continue;
            shown++;
            LinearLayout item = new LinearLayout(ctx);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(0, 12, 0, 12);
            LinearLayout txt = new LinearLayout(ctx);
            txt.setOrientation(LinearLayout.VERTICAL);
            TextView name = CarStyle.text(ctx, c.name, 24, CarStyle.TEXT);
            name.setSingleLine(true);
            String sub = String.format(Locale.getDefault(), "%s%s%s", Str.get(R.string.hql_route_in_km, Math.max(0, c.kmAlong - here)),
                    c.maxKw > 0 ? String.format(Locale.getDefault(), " · %.0f kW", c.maxKw) : "",
                    c.detail.isEmpty() ? "" : " · " + c.detail);
            TextView detail = CarStyle.text(ctx, sub, 20, CarStyle.TEXT_DIM);
            detail.setSingleLine(true);
            txt.addView(name);
            txt.addView(detail);
            item.addView(txt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView go = CarStyle.pill(ctx, Str.get(R.string.hql_go));
            go.setOnClickListener(v -> navigateTo(c.lat, c.lon));
            item.addView(go);
            chargers.addView(item);
        }
        if (shown == 0) chargers.addView(CarStyle.text(ctx, Str.get(R.string.hql_route_no_chargers), 22, CarStyle.TEXT_DIM));
    }

    /** Abre la navegación de Google Maps en el móvil; Android Auto la muestra en el coche. */
    private void navigateTo(double lat, double lon) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(String.format(Locale.US, "google.navigation:q=%.6f,%.6f&mode=d", lat, lon)))
                    .setPackage("com.google.android.apps.maps")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.getApplicationContext().startActivity(i);
            CarUi.switchToAa();
        } catch (RuntimeException e) {
            L.w("ruta: no se pudo abrir Google Maps: " + e);
        }
    }

    @Override
    public void destroy() {
        running = false;
    }

    static String weatherText(int code) {
        if (code < 0) return "—";
        if (code == 0) return Str.get(R.string.hql_wx_clear);
        if (code <= 3) return Str.get(R.string.hql_wx_partly);
        if (code <= 48) return Str.get(R.string.hql_wx_fog);
        if (code <= 57) return Str.get(R.string.hql_wx_drizzle);
        if (code <= 67) return Str.get(R.string.hql_wx_rain);
        if (code <= 77) return Str.get(R.string.hql_wx_snow);
        if (code <= 82) return Str.get(R.string.hql_wx_showers);
        if (code <= 86) return Str.get(R.string.hql_wx_snow_showers);
        return Str.get(R.string.hql_wx_storm);
    }

    /** Destino, llegada y gráfica de la ruta. */
    private static final class Chart extends View {
        RoutePlanner.Plan plan;
        double soc = Double.NaN;
        String status = "";
        NavTap.Info nav = new NavTap.Info();
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Chart(Context c) {
            super(c);
        }

        @Override
        protected void onDraw(Canvas cv) {
            int w = getWidth();
            int h = getHeight();
            RoutePlanner.Plan pl = plan;
            String dest = nav.destination != null ? nav.destination : pl != null ? pl.destination : null;
            p.setTextAlign(Paint.Align.LEFT);
            p.setColor(CarStyle.TEXT_DIM);
            p.setTextSize(22);
            cv.drawText(Str.get(R.string.hql_destination), 0, 24, p);
            p.setColor(CarStyle.TEXT);
            p.setTextSize(32);
            cv.drawText(dest != null ? ellipsize(dest, w - 330) : Str.get(R.string.hql_no_active_route), 0, 66, p);

            // Llegada según AA (más fiable que nuestra ruta reconstruida).
            String arrive = "";
            if (nav.remainingMeters >= 0) {
                arrive = String.format(Locale.getDefault(), "%.0f km · %s%s", nav.remainingMeters / 1000.0,
                        duration(nav.remainingSeconds), nav.eta != null ? " · " + Str.get(R.string.hql_arrival, nav.eta) : "");
            } else if (pl != null) {
                arrive = String.format(Locale.getDefault(), "%.0f km", pl.totalKm - pl.km[Math.min(pl.progress, pl.n - 1)]);
            }
            p.setColor(CarStyle.TEXT_DIM);
            p.setTextSize(24);
            cv.drawText(arrive, 0, 104, p);

            // % al llegar, grande a la derecha.
            double rem = RoutePlanner.remainingKwh(pl);
            p.setTextAlign(Paint.Align.RIGHT);
            if (!Double.isNaN(rem)) {
                p.setColor(CarStyle.TEXT_DIM);
                p.setTextSize(22);
                cv.drawText(Str.get(R.string.hql_route_need_kwh, rem), w, 24, p);
                if (!Double.isNaN(soc)) {
                    double arrival = soc - rem / EnergyModel.USABLE_KWH * 100;
                    p.setColor(arrival >= 20 ? CarStyle.GOOD : arrival >= 8 ? CarStyle.WARN : CarStyle.BAD);
                    p.setTextSize(64);
                    cv.drawText(String.format(Locale.getDefault(), "%.0f %%", Math.max(0, arrival)), w, 92, p);
                    p.setTextSize(20);
                    p.setColor(CarStyle.TEXT_DIM);
                    cv.drawText(arrival < 0 ? Str.get(R.string.hql_route_wont_arrive) : Str.get(R.string.hql_route_on_arrival), w, 118, p);
                } else {
                    p.setColor(CarStyle.TEXT_DIM);
                    p.setTextSize(24);
                    cv.drawText(Str.get(R.string.hql_route_set_battery), w, 80, p);
                }
            }

            RectF box = new RectF(0, 150, w, h - 60);
            if (pl == null || pl.n < 2) {
                p.setTextAlign(Paint.Align.CENTER);
                p.setColor(CarStyle.TEXT_DIM);
                p.setTextSize(26);
                // Partido en líneas que quepan (los textos de ayuda son largos, sobre todo en inglés).
                String msg = status.isEmpty() ? Str.get(R.string.hql_calculating) : status;
                java.util.List<String> lines = new java.util.ArrayList<>();
                StringBuilder line = new StringBuilder();
                for (String word : msg.split(" ")) {
                    String t = line.length() == 0 ? word : line + " " + word;
                    if (p.measureText(t) > w - 80 && line.length() > 0) {
                        lines.add(line.toString());
                        line.setLength(0);
                        line.append(word);
                    } else {
                        line.setLength(0);
                        line.append(t);
                    }
                }
                if (line.length() > 0) lines.add(line.toString());
                float y = box.centerY() - (lines.size() - 1) * 18;
                for (String l : lines) {
                    cv.drawText(l, w / 2f, y, p);
                    y += 36;
                }
                return;
            }
            double minE = Double.MAX_VALUE;
            double maxE = -Double.MAX_VALUE;
            for (double e : pl.elev) {
                minE = Math.min(minE, e);
                maxE = Math.max(maxE, e);
            }
            double span = Math.max(50, maxE - minE);
            minE -= span * 0.1;
            maxE += span * 0.1;
            float x0 = box.left + 70;
            float x1 = box.right;
            float y0 = box.top;
            float y1 = box.bottom - 30;
            // Relieve.
            Path area = new Path();
            area.moveTo(x0, y1);
            for (int i = 0; i < pl.n; i++) {
                area.lineTo(xFor(pl, i, x0, x1), (float) (y1 - (pl.elev[i] - minE) / (maxE - minE) * (y1 - y0)));
            }
            area.lineTo(x1, y1);
            area.close();
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF3C4043);
            cv.drawPath(area, p);
            // Viento por tramos, en una franja bajo el relieve: azul de cola, naranja/rojo de cara.
            for (int i = 1; i < pl.n; i++) {
                double hw = pl.headwind[i];
                p.setColor(hw > 15 ? 0xFFF28B82 : hw > 5 ? 0xFFFCAD70 : hw < -5 ? 0xFF8AB4F8 : 0xFF5F6368);
                cv.drawRect(xFor(pl, i - 1, x0, x1), y1 + 8, xFor(pl, i, x0, x1) + 1, y1 + 22, p);
            }
            // % de batería previsto a lo largo de la ruta.
            int prog = Math.min(pl.progress, pl.n - 1);
            if (!Double.isNaN(soc)) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(5);
                p.setColor(CarStyle.ACCENT);
                Path line = new Path();
                for (int i = prog; i < pl.n; i++) {
                    double s = soc - (pl.kwhCum[i] - pl.kwhCum[prog]) / EnergyModel.USABLE_KWH * 100;
                    float y = (float) (y1 - Math.max(0, Math.min(100, s)) / 100 * (y1 - y0));
                    if (i == prog) line.moveTo(xFor(pl, i, x0, x1), y);
                    else line.lineTo(xFor(pl, i, x0, x1), y);
                }
                cv.drawPath(line, p);
                p.setStyle(Paint.Style.FILL);
            }
            // Posición actual.
            float xp = xFor(pl, prog, x0, x1);
            p.setColor(Color.WHITE);
            p.setStrokeWidth(3);
            cv.drawLine(xp, y0, xp, y1, p);
            cv.drawCircle(xp, (float) (y1 - (pl.elev[prog] - minE) / (maxE - minE) * (y1 - y0)), 9, p);
            // Ejes.
            p.setTextSize(20);
            p.setColor(CarStyle.TEXT_DIM);
            p.setTextAlign(Paint.Align.RIGHT);
            cv.drawText(String.format(Locale.getDefault(), "%.0f m", maxE), x0 - 10, y0 + 18, p);
            cv.drawText(String.format(Locale.getDefault(), "%.0f m", minE), x0 - 10, y1, p);
            p.setTextAlign(Paint.Align.LEFT);
            cv.drawText(Str.get(R.string.hql_route_footer),
                    0, h - 14, p);
        }

        private static float xFor(RoutePlanner.Plan pl, int i, float x0, float x1) {
            return (float) (x0 + pl.km[i] / Math.max(0.1, pl.totalKm) * (x1 - x0));
        }

        private String ellipsize(String s, float max) {
            if (p.measureText(s) <= max) return s;
            while (s.length() > 3 && p.measureText(s + "…") > max) s = s.substring(0, s.length() - 1);
            return s + "…";
        }

        private static String duration(long sec) {
            if (sec < 0) return "";
            long m = sec / 60;
            return m >= 60 ? String.format(Locale.getDefault(), "%d h %02d min", m / 60, m % 60) : m + " min";
        }
    }
}
