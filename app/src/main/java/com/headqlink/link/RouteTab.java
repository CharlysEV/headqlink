package com.headqlink.link;

import com.andrerinas.openheadunit.R;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.andrerinas.openheadunit.aap.NavTap;

import java.util.Locale;

/**
 * Pestaña "Ruta": destino y llegada (de Android Auto o elegido aquí), fichas con lo que falta, la hora de llegada, la
 * energía y el consumo previstos, y el perfil de elevación de la ruta coloreado por la pendiente (subidas en ámbar y
 * rojo, bajadas en verde: ahí se recupera), con el viento por tramos, los cargadores, la batería prevista y «estás
 * aquí»; debajo, el consumo de cada tramo. A la derecha, la batería al llegar en un anillo (con el % actual que indica
 * el usuario, ±5), el tiempo en el destino a la hora de llegada y los cargadores junto a la ruta («Ir» abre la
 * navegación de Google Maps en el móvil y AA la muestra). Todo estimado: ver RoutePlanner y EnergyModel.
 */
final class RouteTab implements CarScreen {
    private static final long TICK_MS = 1000;
    /** Tramos de la barra de consumo bajo el perfil. */
    private static final int SEGMENTS = 12;
    /** Bajada fuerte, bajada, llano, subida y subida fuerte. */
    private static final int[] SLOPE_COLORS = {0xFF3DDC84, 0xFF2FA86A, 0xFF5E7A96, CarKit.AMBER, CarKit.RED};

    private RoutePlanner planner;
    private Context ctx;
    private FrameLayout root;
    private boolean running;
    private CarKit.Card routeCard;
    private CarKit.Card batteryCard;
    private CarKit.Card weatherCard;
    private LinearLayout chargers;
    private TextView search;
    private TextView goMaps;
    private TextView clear;
    private TextView cta;
    private int shownChargersFor = -1;
    private RoutePlanner.Plan plan;
    private double soc = Double.NaN;
    private String status = "";
    private NavTap.Info nav = new NavTap.Info();
    private final Runnable tickTask = this::tick;
    private final RectF tmp = new RectF();
    private final CarKit.Para para = new CarKit.Para(27, CarKit.DIM, CarKit.REGULAR);
    private final CarKit.Para statusPara = new CarKit.Para(22, CarKit.DIM, CarKit.MEDIUM);
    // Perfil precalculado (cambia con la ruta o el tamaño): áreas por pendiente, la cresta y el degradado.
    private RoutePlanner.Plan cachedPlan;
    private float cachedW;
    private final Path[] slopePaths = new Path[5];
    private final Path[] ridgePaths = new Path[5];
    private final LinearGradient[] slopeFills = new LinearGradient[5];
    private final Path socLine = new Path();
    private final Path road = new Path();
    private final RectF chartRect = new RectF();
    private final RectF segRect = new RectF();
    private final android.graphics.DashPathEffect dash = new android.graphics.DashPathEffect(new float[]{14, 14}, 0);
    private float minE;
    private float maxE;

    @Override
    public View create(Host h) {
        ctx = h.context();
        planner = RoutePlanner.start(ctx);
        for (int i = 0; i < slopePaths.length; i++) {
            slopePaths[i] = new Path();
            ridgePaths[i] = new Path();
        }

        LinearLayout row = CarKit.row(ctx);
        routeCard = CarKit.add(row, new CarKit.Card(ctx, null, this::paintRoute), 1.95f, 0);
        // Destino elegido aquí (AA no nos manda el suyo): buscar, abrir en Google Maps, quitar.
        LinearLayout actions = CarKit.row(ctx);
        search = CarKit.pill(ctx, Str.get(R.string.hql_route_search_dest), true);
        goMaps = CarKit.pill(ctx, Str.get(R.string.hql_route_guide_maps), false);
        clear = CarKit.pill(ctx, Str.get(R.string.hql_remove), false);
        actions.addView(search);
        actions.addView(goMaps, spaced());
        actions.addView(clear, spaced());
        routeCard.addView(actions, CarKit.at(Gravity.TOP | Gravity.END, 0, 0, 0, 0));
        search.setOnClickListener(v -> openSearch());
        goMaps.setOnClickListener(v -> {
            RoutePlanner.Place m = RoutePlanner.manualDestination();
            if (m != null) navigateTo(m.lat, m.lon);
        });
        clear.setOnClickListener(v -> {
            RoutePlanner.setManualDestination(null);
            tick();
        });
        // Sin destino: el botón grande en el centro.
        cta = CarKit.pill(ctx, Str.get(R.string.hql_route_search_dest), true);
        cta.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 28);
        cta.setPadding(44, 20, 44, 20);
        routeCard.addView(cta, CarKit.at(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 0, 0, 90));
        cta.setOnClickListener(v -> openSearch());

        LinearLayout right = CarKit.add(row, CarKit.col(ctx), 1f, 0);
        batteryCard = CarKit.add(right, new CarKit.Card(ctx, Str.get(R.string.hql_battery_arrival), this::paintBattery), 0, 290);
        // Batería: el usuario indica el % actual (hasta tener datos del coche).
        LinearLayout ctl = CarKit.row(ctx);
        TextView minus = CarKit.pill(ctx, "−5", false);
        TextView plus = CarKit.pill(ctx, "+5", false);
        minus.setMinWidth(92);
        plus.setMinWidth(92);
        ctl.addView(minus);
        ctl.addView(plus, spaced());
        batteryCard.addView(ctl, CarKit.at(Gravity.BOTTOM | Gravity.END, 0, 0, 0, 0));
        minus.setOnClickListener(v -> bump(-5));
        plus.setOnClickListener(v -> bump(5));
        weatherCard = CarKit.add(right, new CarKit.Card(ctx, Str.get(R.string.hql_route_weather), this::paintWeather), 0, 172);
        CarKit.Card ch = CarKit.add(right, new CarKit.Card(ctx, Str.get(R.string.hql_route_chargers), null), 1f, 0);
        chargers = CarKit.col(ctx);
        ScrollView sv = new ScrollView(ctx);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(chargers);
        ch.addView(sv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        running = true;
        root = CarKit.page(ctx, row);
        tick();
        return root;
    }

    private static LinearLayout.LayoutParams spaced() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = 12;
        return lp;
    }

    // ------------------------------------------------------------------ búsqueda de destino

    /** Buscador a pantalla completa de la pestaña: texto, resultados y teclado del coche. */
    private void openSearch() {
        LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(CarKit.BG);
        panel.setClickable(true);
        LinearLayout bar = CarStyle.bar(ctx);
        bar.setBackgroundColor(CarKit.SURFACE);
        TextView query = CarStyle.text(ctx, "", 28, CarKit.TEXT);
        query.setHint(Str.get(R.string.hql_route_query_hint));
        query.setHintTextColor(CarKit.FAINT);
        query.setSingleLine(true);
        query.setPadding(24, 12, 24, 12);
        query.setBackground(CarKit.outlined(CarKit.SURFACE_HI, CarKit.OUTLINE, 28));
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        qlp.rightMargin = 10;
        bar.addView(query, qlp);
        TextView find = CarKit.pill(ctx, Str.get(R.string.hql_search), true);
        TextView close = CarKit.pill(ctx, Str.get(R.string.hql_close), false);
        bar.addView(find);
        bar.addView(close, spaced());
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
            results.addView(CarStyle.text(ctx, Str.get(R.string.hql_searching), 24, CarKit.DIM));
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
            results.addView(CarStyle.text(ctx, err != null ? Str.get(R.string.hql_search_failed, err) : Str.get(R.string.hql_no_results), 24, CarKit.DIM));
            return;
        }
        for (RoutePlanner.Place pl : found) {
            LinearLayout item = new LinearLayout(ctx);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(22, 14, 22, 14);
            item.setBackground(CarKit.outlined(CarKit.SURFACE, CarKit.OUTLINE, 18));
            TextView n = CarStyle.text(ctx, pl.name, 26, CarKit.TEXT);
            n.setSingleLine(true);
            TextView d = CarStyle.text(ctx, pl.detail, 20, CarKit.DIM);
            d.setSingleLine(true);
            d.setEllipsize(android.text.TextUtils.TruncateAt.END);
            item.addView(n);
            item.addView(d);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = 8;
            results.addView(item, lp);
            item.setOnClickListener(v -> {
                L.i("ruta: destino elegido en el coche"); // sin el nombre: el log se exporta (qdauto §7.5)
                RoutePlanner.setManualDestination(pl);
                root.removeView(panel);
                tick();
            });
        }
    }

    private void bump(int d) {
        double now = planner.socNow();
        planner.setSoc(Double.isNaN(now) ? 80 : Math.round(now) + d);
        tick();
    }

    private void tick() {
        if (!running) return;
        boolean manual = RoutePlanner.manualDestination() != null;
        plan = planner.plan();
        soc = planner.socNow();
        status = planner.status();
        nav = DemoMode.navInfo();
        boolean have = plan != null && plan.n >= 2;
        goMaps.setVisibility(manual && have ? View.VISIBLE : View.GONE);
        clear.setVisibility(manual ? View.VISIBLE : View.GONE);
        cta.setVisibility(have ? View.GONE : View.VISIBLE);
        search.setVisibility(have ? View.VISIBLE : View.GONE);
        routeCard.invalidate();
        batteryCard.invalidate();
        weatherCard.invalidate();
        if (have) {
            int key = System.identityHashCode(plan) * 31 + plan.progress / 10;
            if (key != shownChargersFor) {
                shownChargersFor = key;
                fillChargers(plan);
            }
        } else if (shownChargersFor != 0) {
            shownChargersFor = 0;
            chargers.removeAllViews();
            chargers.addView(hint(Str.get(R.string.hql_route_chargers_hint)));
        }
        routeCard.removeCallbacks(tickTask);
        routeCard.postDelayed(tickTask, TICK_MS);
    }

    private TextView hint(String s) {
        TextView t = CarStyle.text(ctx, s, 22, CarKit.FAINT);
        t.setPadding(0, 6, 0, 0);
        return t;
    }

    private void fillChargers(RoutePlanner.Plan p) {
        chargers.removeAllViews();
        double here = p.km[Math.min(p.progress, p.n - 1)];
        int shown = 0;
        for (RoutePlanner.Charger c : p.chargers) {
            if (c.kmAlong < here - 0.5 || shown >= 12) continue;
            shown++;
            LinearLayout item = CarKit.row(ctx);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(0, 10, 0, 10);
            ChargerDot dot = new ChargerDot(ctx, c.maxKw);
            item.addView(dot, new LinearLayout.LayoutParams(52, 52));
            LinearLayout txt = CarKit.col(ctx);
            txt.setPadding(16, 0, 10, 0);
            TextView name = CarStyle.text(ctx, c.name, 24, CarKit.TEXT);
            name.setTypeface(CarKit.MEDIUM);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            String sub = Str.get(R.string.hql_charger_line, c.kmAlong, Math.max(0, c.kmAlong - here))
                    + (c.maxKw > 0 ? String.format(Locale.getDefault(), " · %.0f kW", c.maxKw) : "")
                    + (c.detail.isEmpty() ? "" : " · " + c.detail);
            TextView detail = CarStyle.text(ctx, sub, 20, CarKit.DIM);
            // Dos renglones: con la potencia y el detalle, uno solo cortaba el texto («350 k…»).
            detail.setMaxLines(2);
            detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
            txt.addView(name);
            txt.addView(detail);
            item.addView(txt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView go = CarKit.pill(ctx, Str.get(R.string.hql_go), false);
            go.setOnClickListener(v -> navigateTo(c.lat, c.lon));
            item.addView(go);
            chargers.addView(item);
        }
        if (shown == 0) chargers.addView(hint(Str.get(R.string.hql_route_no_chargers)));
    }

    /** Círculo con el rayo, del color según la potencia del cargador. */
    private static final class ChargerDot extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int color;

        ChargerDot(Context c, double kw) {
            super(c);
            color = kw >= 150 ? CarKit.GREEN : kw >= 50 ? CarKit.ACCENT : CarKit.DIM;
        }

        @Override
        protected void onDraw(Canvas cv) {
            float r = Math.min(getWidth(), getHeight()) / 2f;
            p.setColor(CarKit.alpha(color, 0.14f));
            cv.drawCircle(getWidth() / 2f, getHeight() / 2f, r, p);
            CarIcons.bolt(cv, getWidth() / 2f, getHeight() / 2f, r * 1.1f, color, p);
        }
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
            // Solo el tipo: el mensaje lleva el Intent, con las coordenadas del destino.
            L.w("ruta: no se pudo abrir Google Maps: " + e.getClass().getSimpleName());
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

    // ------------------------------------------------------------------ datos derivados

    /** kWh hasta el destino (desde la posición actual), o NaN. */
    private double remainingKwh() {
        return RoutePlanner.remainingKwh(plan);
    }

    /** % de batería al llegar, o NaN si no se sabe. */
    private double arrivalPct() {
        double rem = remainingKwh();
        if (Double.isNaN(rem) || Double.isNaN(soc)) return Double.NaN;
        return soc - rem / EnergyModel.USABLE_KWH * 100;
    }

    private static int levelColor(double pct) {
        return pct >= 20 ? CarKit.ACCENT : pct >= 8 ? CarKit.AMBER : CarKit.RED;
    }

    // ------------------------------------------------------------------ ruta

    private void paintRoute(Canvas cv, RectF r, Paint p) {
        RoutePlanner.Plan pl = plan;
        float x0 = r.left;
        float y0 = r.top;
        String dest = nav.destination != null ? nav.destination : pl != null ? pl.destination : null;
        if (pl == null || pl.n < 2) {
            paintEmpty(cv, r, p, dest);
            return;
        }
        // Destino.
        CarIcons.pin(cv, x0 + 16, y0 + 30, 40, CarKit.ACCENT, CarKit.SURFACE, p);
        CarKit.label(cv, Str.get(R.string.hql_destination), x0 + 48, y0 + 14, p);
        p.setTypeface(CarKit.MEDIUM);
        p.setTextSize(40);
        float pillsW = goMaps.getVisibility() == View.VISIBLE ? 620 : clear.getVisibility() == View.VISIBLE ? 360 : 240;
        CarKit.text(cv, CarKit.ellipsize(dest != null ? dest : "", r.width() - pillsW - 60, p), x0 + 48, y0 + 58, 40, CarKit.TEXT, CarKit.MEDIUM, p, Paint.Align.LEFT);
        // Fichas: lo que falta, llegada, energía y consumo previstos.
        int prog = Math.min(pl.progress, pl.n - 1);
        double remKm = nav.remainingMeters >= 0 ? nav.remainingMeters / 1000.0 : pl.totalKm - pl.km[prog];
        long remSec = nav.remainingSeconds >= 0 ? nav.remainingSeconds
                : Math.round(pl.totalSeconds * (pl.totalKm - pl.km[prog]) / Math.max(0.1, pl.totalKm));
        String eta = nav.eta != null ? nav.eta : android.text.format.DateFormat.getTimeFormat(ctx)
                .format(new java.util.Date(DemoMode.wallClockMs() + remSec * 1000));
        double kwh = remainingKwh();
        double per100 = pl.totalKm - pl.km[prog] > 1 ? kwh / (pl.totalKm - pl.km[prog]) * 100 : Double.NaN;
        float cw = (r.width() - 3 * 14) / 4f;
        float cy0 = y0 + 92;
        tmp.set(x0, cy0, x0 + cw, cy0 + 88);
        CarKit.chip(cv, tmp, Str.get(R.string.hql_remaining), String.format(Locale.getDefault(), "%.0f", remKm), "km", CarKit.TEXT, p);
        tmp.offset(cw + 14, 0);
        CarKit.chip(cv, tmp, Str.get(R.string.hql_drive_arrival), eta, DriveTab.duration(remSec), CarKit.TEXT, p);
        tmp.offset(cw + 14, 0);
        CarKit.chip(cv, tmp, Str.get(R.string.hql_energy_needed), "~" + String.format(Locale.getDefault(), "%.1f", kwh), "kWh", CarKit.TEXT, p);
        tmp.offset(cw + 14, 0);
        CarKit.chip(cv, tmp, Str.get(R.string.hql_expected_use), Double.isNaN(per100) ? "—" : String.format(Locale.getDefault(), "%.1f", per100),
                "kWh/100", CarKit.TEXT, p);
        // Perfil, viento, tramos y leyenda.
        float gx0 = x0 + 74;
        RectF chart = chartRect;
        chart.set(gx0, y0 + 222, r.right, y0 + 530);
        profile(cv, chart, pl, prog, p);
        float wy = chart.bottom + 36;
        for (int i = 1; i < pl.n; i++) {
            double hw = pl.headwind[i];
            p.setColor(hw > 15 ? CarKit.RED : hw > 5 ? CarKit.AMBER : hw < -5 ? CarKit.BLUE : CarKit.SURFACE_TOP);
            cv.drawRect(xFor(pl, i - 1, chart), wy, xFor(pl, i, chart) + 1, wy + 10, p);
        }
        CarKit.text(cv, Str.get(R.string.hql_wind), x0, wy + 11, 18, CarKit.FAINT, CarKit.MEDIUM, p, Paint.Align.LEFT);
        segRect.set(gx0, wy + 26, r.right, r.bottom - 40);
        segments(cv, segRect, pl, prog, p);
        CarKit.text(cv, "kWh/100", x0, r.bottom - 60, 16, CarKit.FAINT, CarKit.MEDIUM, p, Paint.Align.LEFT);
        legend(cv, x0, r.bottom - 4, r.right, p);
    }

    private static float xFor(RoutePlanner.Plan pl, int i, RectF c) {
        return (float) (c.left + pl.km[i] / Math.max(0.1, pl.totalKm) * c.width());
    }

    private float yFor(double e, RectF c) {
        return (float) (c.bottom - (e - minE) / (maxE - minE) * c.height());
    }

    /** Recalcula las áreas por pendiente y la cresta si cambió la ruta o el tamaño. */
    private void buildProfile(RoutePlanner.Plan pl, RectF c) {
        if (pl == cachedPlan && c.width() == cachedW) return;
        cachedPlan = pl;
        cachedW = c.width();
        double lo = Double.MAX_VALUE;
        double hi = -Double.MAX_VALUE;
        for (double e : pl.elev) {
            lo = Math.min(lo, e);
            hi = Math.max(hi, e);
        }
        double span = Math.max(60, hi - lo);
        minE = (float) Math.max(lo - span * 0.08, Math.min(0, lo));
        if (lo > 0) minE = (float) Math.max(0, lo - span * 0.08);
        maxE = (float) (hi + span * 0.12);
        for (Path path : slopePaths) path.rewind();
        for (Path path : ridgePaths) path.rewind();
        // Pendiente de cada tramo, media de unos puntos alrededor (el relieve de una ruta larga cambia poco de un
        // punto al siguiente); los tramos seguidos del mismo color van en un solo polígono (sin costuras).
        double window = Math.max(0.6, pl.totalKm / 60);
        int[] bucket = new int[pl.n];
        for (int i = 1; i < pl.n; i++) bucket[i] = slopeBucket(pl, i, window);
        // Los tramos muy cortos toman el color del anterior: sin rayas en un relieve con ruido.
        double minRun = pl.totalKm / 90;
        int from = 1;
        for (int i = 2; i <= pl.n; i++) {
            if (i < pl.n && bucket[i] == bucket[from]) continue;
            if (from > 1 && pl.km[i - 1] - pl.km[from - 1] < minRun) {
                for (int k = from; k < i; k++) bucket[k] = bucket[from - 1];
            }
            from = i;
        }
        int run = -1;
        int runStart = 0;
        for (int i = 1; i <= pl.n; i++) {
            int b = i < pl.n ? bucket[i] : -2;
            if (b != run) {
                if (run >= 0) addRun(slopePaths[run], ridgePaths[run], pl, runStart, i - 1, c);
                run = b;
                runStart = i - 1;
            }
        }
        // Cada color, en degradado vertical: intenso arriba (en la cresta) y casi transparente abajo.
        for (int b = 0; b < slopeFills.length; b++) {
            slopeFills[b] = new LinearGradient(0, c.top, 0, c.bottom, CarKit.alpha(SLOPE_COLORS[b], b == 2 ? 0.42f : 0.62f),
                    CarKit.alpha(SLOPE_COLORS[b], 0.04f), Shader.TileMode.CLAMP);
        }
    }

    /**
     * Color de pendiente del tramo i-1 → i (bajada fuerte, bajada, llano, subida, subida fuerte), con la pendiente media
     * de unos km alrededor: el relieve de una ruta larga cambia poco de un punto al siguiente.
     */
    private static int slopeBucket(RoutePlanner.Plan pl, int i, double windowKm) {
        int a = i - 1;
        int b = i;
        while (a > 0 && pl.km[i] - pl.km[a] < windowKm / 2) a--;
        while (b < pl.n - 1 && pl.km[b] - pl.km[i] < windowKm / 2) b++;
        double dkm = pl.km[b] - pl.km[a];
        double grade = dkm > 0 ? (pl.elev[b] - pl.elev[a]) / (dkm * 1000) * 100 : 0;
        return grade <= -1.0 ? 0 : grade <= -0.25 ? 1 : grade < 0.25 ? 2 : grade < 1.0 ? 3 : 4;
    }

    /** Polígono del relieve entre los puntos from y to (cerrado por abajo) y su tramo de cresta. */
    private void addRun(Path path, Path top, RoutePlanner.Plan pl, int from, int to, RectF c) {
        path.moveTo(xFor(pl, from, c), c.bottom);
        top.moveTo(xFor(pl, from, c), yFor(pl.elev[from], c));
        for (int k = from; k <= to; k++) {
            path.lineTo(xFor(pl, k, c), yFor(pl.elev[k], c));
            if (k > from) top.lineTo(xFor(pl, k, c), yFor(pl.elev[k], c));
        }
        path.lineTo(xFor(pl, to, c), c.bottom);
        path.close();
    }

    private void profile(Canvas cv, RectF c, RoutePlanner.Plan pl, int prog, Paint p) {
        buildProfile(pl, c);
        // Rejilla y ejes.
        p.setColor(CarKit.OUTLINE);
        for (int k = 0; k <= 2; k++) {
            float y = c.top + c.height() * k / 2f;
            cv.drawRect(c.left, y - 1, c.right, y + 1, p);
        }
        CarKit.text(cv, String.format(Locale.getDefault(), "%.0f m", maxE), c.left - 10, c.top + 7, 18, CarKit.FAINT, CarKit.MEDIUM, p, Paint.Align.RIGHT);
        CarKit.text(cv, String.format(Locale.getDefault(), "%.0f m", minE), c.left - 10, c.bottom, 18, CarKit.FAINT, CarKit.MEDIUM, p, Paint.Align.RIGHT);
        // Relieve por pendiente: cada color en su degradado, y la cresta con el color de la pendiente.
        p.setColor(0xFFFFFFFF);
        for (int b = 0; b < slopePaths.length; b++) {
            p.setShader(slopeFills[b]);
            cv.drawPath(slopePaths[b], p);
        }
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(4f);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeCap(Paint.Cap.ROUND);
        for (int b = 0; b < ridgePaths.length; b++) {
            p.setColor(b == 2 ? 0xFF9FB4C8 : SLOPE_COLORS[b]);
            cv.drawPath(ridgePaths[b], p);
        }
        p.setStrokeCap(Paint.Cap.BUTT);
        p.setStyle(Paint.Style.FILL);
        CarKit.text(cv, Str.get(R.string.hql_route_source), c.right, c.top - 10, 18, CarKit.FAINT, CarKit.MEDIUM, p, Paint.Align.RIGHT);
        // Lo ya recorrido, apagado.
        float xp = xFor(pl, prog, c);
        p.setColor(CarKit.alpha(CarKit.SURFACE, 0.5f));
        cv.drawRect(c.left, c.top, xp, c.bottom + 1, p);
        // Cargadores sobre el eje.
        double here = pl.km[prog];
        for (RoutePlanner.Charger ch : pl.chargers) {
            float x = (float) (c.left + ch.kmAlong / Math.max(0.1, pl.totalKm) * c.width());
            boolean ahead = ch.kmAlong >= here - 0.5;
            p.setColor(ahead ? CarKit.SURFACE_TOP : CarKit.SURFACE_HI);
            cv.drawCircle(x, c.bottom - 18, 15, p);
            CarIcons.bolt(cv, x, c.bottom - 18, 20, ahead ? (ch.maxKw >= 100 ? CarKit.GREEN : CarKit.ACCENT) : CarKit.MUTED, p);
        }
        // Batería prevista a lo largo de la ruta (escala 0-100 % en el alto del gráfico).
        if (!Double.isNaN(soc)) {
            socLine.rewind();
            double last = soc;
            for (int i = prog; i < pl.n; i++) {
                double sp = soc - (pl.kwhCum[i] - pl.kwhCum[prog]) / EnergyModel.USABLE_KWH * 100;
                last = sp;
                float y = (float) (c.bottom - Math.max(0, Math.min(100, sp)) / 100 * c.height());
                if (i == prog) socLine.moveTo(xFor(pl, i, c), y);
                else socLine.lineTo(xFor(pl, i, c), y);
            }
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(4);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(CarKit.ACCENT);
            cv.drawPath(socLine, p);
            p.setStyle(Paint.Style.FILL);
            p.setStrokeCap(Paint.Cap.BUTT);
            float ly = (float) (c.bottom - Math.max(0, Math.min(100, last)) / 100 * c.height());
            String lt = String.format(Locale.getDefault(), "%.0f %%", Math.max(0, last));
            p.setTypeface(CarKit.MEDIUM);
            p.setTextSize(22);
            float tw = p.measureText(lt) + 20;
            tmp.set(c.right - tw, ly - 44, c.right, ly - 12);
            p.setColor(CarKit.SURFACE_TOP);
            cv.drawRoundRect(tmp, 10, 10, p);
            CarKit.text(cv, lt, tmp.centerX(), tmp.bottom - 9, 22, levelColor(last), CarKit.MEDIUM, p, Paint.Align.CENTER);
        }
        // Estás aquí.
        float yp = yFor(pl.elev[prog], c);
        p.setColor(CarKit.alpha(CarKit.TEXT, 0.55f));
        cv.drawRect(xp - 1, c.top + 30, xp + 1, c.bottom, p);
        p.setColor(CarKit.alpha(CarKit.ACCENT, 0.3f));
        cv.drawCircle(xp, yp, 17, p);
        p.setColor(CarKit.ACCENT);
        cv.drawCircle(xp, yp, 9, p);
        String here2 = Str.get(R.string.hql_here_km, here);
        p.setTypeface(CarKit.MEDIUM);
        p.setTextSize(20);
        float tw = p.measureText(here2) + 22;
        float lx = Math.max(c.left, Math.min(c.right - tw, xp - tw / 2));
        tmp.set(lx, c.top - 4, lx + tw, c.top + 28);
        p.setColor(CarKit.SURFACE_TOP);
        cv.drawRoundRect(tmp, 10, 10, p);
        CarKit.text(cv, here2, tmp.centerX(), tmp.bottom - 9, 20, CarKit.TEXT, CarKit.MEDIUM, p, Paint.Align.CENTER);
        // Km bajo el eje.
        double stepKm = pl.totalKm > 300 ? 100 : pl.totalKm > 120 ? 50 : pl.totalKm > 40 ? 10 : 5;
        for (double k = 0; k <= pl.totalKm + 0.1; k += stepKm) {
            float x = (float) (c.left + k / pl.totalKm * c.width());
            CarKit.text(cv, String.format(Locale.getDefault(), "%.0f", k), x, c.bottom + 24, 18, CarKit.FAINT, CarKit.MEDIUM, p,
                    k == 0 ? Paint.Align.LEFT : Paint.Align.CENTER);
        }
        CarKit.text(cv, "km", c.right, c.bottom + 24, 18, CarKit.FAINT, CarKit.MEDIUM, p, Paint.Align.RIGHT);
    }

    /** Consumo previsto de cada tramo (kWh/100 km); lo ya recorrido, apagado. */
    private void segments(Canvas cv, RectF c, RoutePlanner.Plan pl, int prog, Paint p) {
        double len = pl.totalKm / SEGMENTS;
        double max = 30;
        double[] v = new double[SEGMENTS];
        for (int j = 0; j < SEGMENTS; j++) {
            double a = kwhAt(pl, j * len);
            double b = kwhAt(pl, (j + 1) * len);
            v[j] = (b - a) / len * 100;
            max = Math.max(max, v[j]);
        }
        float w = c.width() / SEGMENTS;
        double here = pl.km[prog];
        for (int j = 0; j < SEGMENTS; j++) {
            float h = (float) (Math.max(0, v[j]) / max * (c.height() - 20));
            float x = c.left + j * w + 4;
            boolean past = (j + 1) * len <= here;
            int col = v[j] < 15 ? CarKit.GREEN : v[j] < 21 ? CarKit.ACCENT : v[j] < 27 ? CarKit.AMBER : CarKit.RED;
            p.setColor(CarKit.alpha(col, past ? 0.25f : 0.85f));
            tmp.set(x, c.bottom - Math.max(4, h), x + w - 8, c.bottom);
            cv.drawRoundRect(tmp, 5, 5, p);
            CarKit.text(cv, String.format(Locale.getDefault(), "%.0f", v[j]), x + (w - 8) / 2, tmp.top - 6, 17,
                    past ? CarKit.MUTED : CarKit.DIM, CarKit.MEDIUM, p, Paint.Align.CENTER);
        }
    }

    private static double kwhAt(RoutePlanner.Plan pl, double km) {
        if (km <= 0) return 0;
        for (int i = 1; i < pl.n; i++) {
            if (pl.km[i] >= km) {
                double f = (km - pl.km[i - 1]) / Math.max(1e-6, pl.km[i] - pl.km[i - 1]);
                return pl.kwhCum[i - 1] + (pl.kwhCum[i] - pl.kwhCum[i - 1]) * f;
            }
        }
        return pl.kwhCum[pl.n - 1];
    }

    private void legend(Canvas cv, float x, float y, float right, Paint p) {
        x = swatch(cv, x, y, CarKit.RED, Str.get(R.string.hql_legend_up), p);
        x = swatch(cv, x, y, CarKit.GREEN, Str.get(R.string.hql_legend_down), p);
        x = swatch(cv, x, y, CarKit.AMBER, Str.get(R.string.hql_legend_headwind), p);
        x = swatch(cv, x, y, CarKit.BLUE, Str.get(R.string.hql_legend_tailwind), p);
        p.setColor(CarKit.ACCENT);
        cv.drawRect(x, y - 9, x + 22, y - 5, p);
        CarKit.text(cv, Str.get(R.string.hql_legend_battery), x + 30, y, 19, CarKit.FAINT, CarKit.MEDIUM, p, Paint.Align.LEFT);
    }

    private float swatch(Canvas cv, float x, float y, int color, String s, Paint p) {
        p.setColor(color);
        tmp.set(x, y - 15, x + 15, y);
        cv.drawRoundRect(tmp, 4, 4, p);
        float w = CarKit.text(cv, s, x + 23, y, 19, CarKit.FAINT, CarKit.MEDIUM, p, Paint.Align.LEFT);
        return x + 23 + w + 22;
    }

    /** Sin ruta: un camino que sube hasta la chincheta, la pregunta y el estado (calculando, error…). */
    private void paintEmpty(Canvas cv, RectF r, Paint p, String dest) {
        float cx = r.centerX();
        float top = r.top + 70;
        road.rewind();
        road.moveTo(cx - 230, top + 230);
        road.cubicTo(cx - 60, top + 230, cx - 150, top + 120, cx, top + 120);
        road.cubicTo(cx + 120, top + 120, cx + 40, top + 60, cx + 70, top + 40);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(34);
        p.setColor(CarKit.SURFACE_TOP);
        cv.drawPath(road, p);
        p.setStrokeWidth(4);
        p.setColor(CarKit.MUTED);
        p.setPathEffect(dash);
        cv.drawPath(road, p);
        p.setPathEffect(null);
        p.setStyle(Paint.Style.FILL);
        p.setStrokeCap(Paint.Cap.BUTT);
        CarIcons.pin(cv, cx + 74, top + 6, 64, CarKit.ACCENT, CarKit.SURFACE, p);
        CarKit.text(cv, Str.get(R.string.hql_route_where), cx, top + 310, 42, CarKit.TEXT, CarKit.MEDIUM, p, Paint.Align.CENTER);
        String msg = status.isEmpty() ? Str.get(R.string.hql_calculating) : status;
        para.draw(cv, msg, r.left + 80, top + 336, (int) r.width() - 160, true);
    }

    // ------------------------------------------------------------------ batería y tiempo

    private void paintBattery(Canvas cv, RectF r, Paint p) {
        double arr = arrivalPct();
        float rad = Math.min(r.height() / 2f, 98) - 4;
        float cx = r.left + rad + 4;
        float cy = r.top + r.height() / 2f;
        tmp.set(cx - rad, cy - rad, cx + rad, cy + rad);
        int col = Double.isNaN(arr) ? CarKit.MUTED : levelColor(arr);
        CarKit.ring(cv, tmp, 135, 270, Double.isNaN(arr) ? 0 : (float) Math.max(0, arr) / 100f, 18, CarKit.SURFACE_TOP, col, p);
        if (Double.isNaN(arr)) {
            CarKit.text(cv, "—", cx, cy + 16, 50, CarKit.MUTED, CarKit.REGULAR, p, Paint.Align.CENTER);
        } else {
            CarKit.number(cv, String.format(Locale.getDefault(), "%.0f", Math.max(0, arr)), "%", cx, cy + 18, 56, col, CarKit.REGULAR, p, Paint.Align.CENTER);
        }
        CarKit.text(cv, Str.get(R.string.hql_route_on_arrival), cx, cy + rad - 6, 19, CarKit.FAINT, CarKit.MEDIUM, p, Paint.Align.CENTER);
        float tx = cx + rad + 30;
        CarKit.label(cv, Str.get(R.string.hql_now), tx, r.top + 18, p);
        CarKit.number(cv, Double.isNaN(soc) ? "—" : String.format(Locale.getDefault(), "%.0f", soc), "%", tx, r.top + 70, 50, CarKit.TEXT,
                CarKit.REGULAR, p, Paint.Align.LEFT);
        String st;
        int sc;
        if (Double.isNaN(soc)) {
            st = Str.get(R.string.hql_route_set_battery);
            sc = CarKit.DIM;
        } else if (Double.isNaN(arr)) {
            st = "";
            sc = CarKit.DIM;
        } else if (arr < 0) {
            st = Str.get(R.string.hql_route_battery_no);
            sc = CarKit.RED;
        } else if (arr < 15) {
            st = Str.get(R.string.hql_route_battery_tight);
            sc = CarKit.AMBER;
        } else {
            st = Str.get(R.string.hql_route_battery_ok);
            sc = CarKit.GREEN;
        }
        statusPara.color(sc);
        statusPara.draw(cv, st, tx, r.top + 86, (int) (r.right - tx), false);
    }

    private void paintWeather(Canvas cv, RectF r, Paint p) {
        RoutePlanner.Plan pl = plan;
        if (pl == null || Double.isNaN(pl.destTemp)) {
            CarKit.text(cv, pl == null ? "—" : Str.get(R.string.hql_route_no_forecast), r.left, r.centerY() + 10, 26, CarKit.DIM, CarKit.REGULAR, p, Paint.Align.LEFT);
            return;
        }
        CarIcons.weather(cv, pl.destCode, r.left + 40, r.centerY(), 76, p);
        float tx = r.left + 100;
        float w = CarKit.number(cv, String.format(Locale.getDefault(), "%.0f", pl.destTemp), "°C", tx, r.top + 40, 46, CarKit.TEXT, CarKit.REGULAR, p, Paint.Align.LEFT);
        p.setTypeface(CarKit.MEDIUM);
        p.setTextSize(25);
        CarKit.text(cv, CarKit.ellipsize(weatherText(pl.destCode), r.right - tx - w - 20, p), tx + w + 18, r.top + 38, 25, CarKit.TEXT, CarKit.MEDIUM, p, Paint.Align.LEFT);
        CarKit.text(cv, Str.get(R.string.hql_wind_rain, pl.destWind, pl.destRain), tx, r.bottom, 22, CarKit.DIM, CarKit.REGULAR, p, Paint.Align.LEFT);
    }
}
