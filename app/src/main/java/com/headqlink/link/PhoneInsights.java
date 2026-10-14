package com.headqlink.link;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.andrerinas.openheadunit.R;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Apartado «Viajes» de la app del móvil (pestaña junto a «Conexión»): lo útil para el día a día, fuera del coche.
 * - Tu coche ahora: batería, autonomía, cuentakilómetros y si carga (nube de Leapmotor, leída mientras se mira).
 * - Últimos 30 días: km, viajes, kWh, consumo medio y coste; km de cada día con la línea de consumo.
 * - Cómo gastas: consumo de los últimos viajes frente a la media, según lo largo del viaje y el mejor viaje.
 * - Cargas: cada carga guardada (ChargeLog) con su curva de potencia frente al %.
 * - Recorridos: los viajes hechos con HeadQLink con la silueta del trazado; al tocar, su mapa (TripMapActivity).
 * Los datos se leen en otro hilo al entrar y cada minuto mientras se ve.
 */
final class PhoneInsights {
    private static final String CLOUD_OWNER = "phone-trips";
    private final Activity act;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ScrollView root;
    private final LinearLayout col;
    private boolean visible;
    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            if (!visible) return;
            refresh();
            main.postDelayed(this, 60_000);
        }
    };

    PhoneInsights(Activity act) {
        this.act = act;
        root = new ScrollView(act);
        root.setFillViewport(true);
        root.setBackgroundColor(0xFF0A0E14);
        col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        col.setPadding(pad, dp(16), pad, dp(28));
        root.addView(col);
    }

    View view() {
        return root;
    }

    /** La pestaña se ve: se piden los datos del coche y se pinta. */
    void show() {
        visible = true;
        if (hasAccount(act)) CarCloud.acquire(act, CLOUD_OWNER);
        main.removeCallbacks(refresher);
        main.post(refresher);
        // A los pocos segundos ya suele haber dato nuevo de la nube.
        main.postDelayed(() -> {
            if (visible) refresh();
        }, 4000);
    }

    /** ¿Hay cuenta de Leapmotor configurada y activa? */
    static boolean hasAccount(android.content.Context c) {
        if (!CarCloudStore.supported()) return false;
        CarCloudStore st = new CarCloudStore(c);
        return st.hasIdentity() && st.hasSession() && st.enabled();
    }

    void hide() {
        visible = false;
        main.removeCallbacks(refresher);
        CarCloud.release(CLOUD_OWNER);
    }

    private void refresh() {
        new Thread(() -> {
            Data d = Data.load(act);
            main.post(() -> {
                if (!act.isFinishing()) build(d);
            });
        }, "hql-insights").start();
    }

    // ------------------------------------------------------------------ datos

    /** Todo lo que se pinta, leído de una vez fuera del hilo principal. */
    static final class Data {
        CarCloud.Snapshot cloud;
        boolean account;
        List<CloudHistory.Trip> history = new ArrayList<>();
        List<TripLog.Trip> trips = new ArrayList<>();
        List<ChargeSession> charges = new ArrayList<>();
        double price;

        static Data load(Activity a) {
            Data d = new Data();
            d.cloud = CarCloud.snapshot();
            d.account = hasAccount(a);
            d.history = new ArrayList<>(CarCloud.history());
            d.trips = TripLog.recent(a, 120);
            try {
                d.charges = ChargeLog.load(a);
            } catch (RuntimeException e) {
                d.charges = new ArrayList<>();
            }
            d.price = new Config(a).electricityPrice();
            return d;
        }
    }

    // ------------------------------------------------------------------ pantalla

    private void build(Data d) {
        col.removeAllViews();
        carCard(d);
        monthCard(d);
        howCard(d);
        chargesCard(d);
        drivesCard(d);
    }

    private void carCard(Data d) {
        LinearLayout c = card(Str.get(R.string.hql_ins_car));
        CarCloud.Snapshot s = d.cloud;
        if (!d.account) {
            c.addView(note(Str.get(R.string.hql_ins_no_cloud)));
            addCard(c);
            return;
        }
        if (s == null || !s.hasData()) {
            c.addView(note(Str.get(R.string.hql_ins_cloud_wait)));
            addCard(c);
            return;
        }
        LeapStatus st = s.status;
        LinearLayout row = new LinearLayout(act);
        row.setGravity(Gravity.CENTER_VERTICAL);
        PhoneCharts.Ring ring = new PhoneCharts.Ring(act);
        String state = st.charging() ? Str.get(R.string.hql_ins_charging, fmt0(Math.abs(st.powerKw())))
                : st.pluggedIn() ? Str.get(R.string.hql_ins_plugged) : Str.get(R.string.hql_ins_parked);
        ring.set(st.socBest(), state);
        row.addView(ring, new LinearLayout.LayoutParams(dp(150), dp(150)));
        LinearLayout facts = new LinearLayout(act);
        facts.setOrientation(LinearLayout.VERTICAL);
        facts.setPadding(dp(18), 0, 0, 0);
        facts.addView(fact(Str.get(R.string.hql_ins_range), Double.isNaN(st.rangeKm) ? "—" : fmt0(st.rangeKm), "km"));
        facts.addView(fact(Str.get(R.string.hql_ins_odo), Double.isNaN(st.odometerKm) ? "—" : fmtThousands(st.odometerKm), "km"));
        if (st.charging() && !Double.isNaN(st.chargeRemainMin) && st.chargeRemainMin > 0) {
            facts.addView(fact(Str.get(R.string.hql_ins_full_in), duration((long) st.chargeRemainMin), ""));
        }
        TextView ago = small(Str.get(R.string.hql_ins_ago, ago(s.ageMs(System.currentTimeMillis()))));
        ago.setPadding(0, dp(6), 0, 0);
        facts.addView(ago);
        row.addView(facts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        c.addView(row);
        addCard(c);
    }

    private void monthCard(Data d) {
        LinearLayout c = card(Str.get(R.string.hql_ins_30d));
        long now = System.currentTimeMillis();
        long from = now - 30L * 86_400_000L;
        TimeZone tz = TimeZone.getDefault();
        double km = 0, kwh = 0;
        int n = 0;
        double[] dayKm = new double[30];
        double[] dayKwh = new double[30];
        long today = TripStats.startOfDay(now, tz);
        boolean fromCar = !d.history.isEmpty();
        if (fromCar) {
            for (CloudHistory.Trip t : d.history) {
                if (t.startMs < from) continue;
                n++;
                km += t.km;
                if (!Double.isNaN(t.kwh)) kwh += t.kwh;
                int idx = 29 - (int) ((today - TripStats.startOfDay(t.startMs, tz)) / 86_400_000L);
                if (idx >= 0 && idx < 30) {
                    dayKm[idx] += t.km;
                    if (!Double.isNaN(t.kwh)) dayKwh[idx] += t.kwh;
                }
            }
        } else {
            for (TripLog.Trip t : d.trips) {
                if (t.startMs < from) continue;
                n++;
                km += t.km;
                double k = TripStats.bestKwhPer100(t) * t.km / 100;
                if (!Double.isNaN(k)) kwh += k;
                int idx = 29 - (int) ((today - TripStats.startOfDay(t.startMs, tz)) / 86_400_000L);
                if (idx >= 0 && idx < 30) {
                    dayKm[idx] += t.km;
                    if (!Double.isNaN(k)) dayKwh[idx] += k;
                }
            }
        }
        if (n == 0) {
            c.addView(note(Str.get(R.string.hql_ins_no_drives)));
            addCard(c);
            return;
        }
        double per100 = km >= 1 ? kwh / km * 100 : Double.NaN;
        LinearLayout big = new LinearLayout(act);
        big.addView(stat(fmt0(km), "km"), weight());
        big.addView(stat(String.valueOf(n), Str.plural(R.plurals.hql_ins_trips, n)), weight());
        big.addView(stat(Double.isNaN(per100) ? "—" : fmt1(per100), "kWh/100"), weight());
        big.addView(stat(kwh > 0 ? Money.format(kwh * d.price, 0) : "—", fmt0(kwh) + " kWh"), weight());
        c.addView(big);
        PhoneCharts.DailyBars bars = new PhoneCharts.DailyBars(act);
        double[] line = new double[30];
        String[] labels = new String[30];
        Calendar cal = Calendar.getInstance(tz);
        for (int i = 0; i < 30; i++) {
            line[i] = dayKm[i] >= 5 && dayKwh[i] > 0 ? dayKwh[i] / dayKm[i] * 100 : Double.NaN;
            if ((29 - i) % 7 == 0) {
                cal.setTimeInMillis(today - (29 - i) * 86_400_000L);
                labels[i] = String.valueOf(cal.get(Calendar.DAY_OF_MONTH));
            }
        }
        bars.set(dayKm, line, labels);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(140));
        blp.topMargin = dp(14);
        c.addView(bars, blp);
        TextView legend = small(Str.get(R.string.hql_ins_daily_legend) + " · " + Str.get(fromCar ? R.string.hql_ins_from_car
                : R.string.hql_ins_from_app));
        legend.setPadding(0, dp(6), 0, 0);
        c.addView(legend);
        addCard(c);
    }

    private void howCard(Data d) {
        // Viajes con consumo: los del coche (todos, también sin HeadQLink) o, si no hay cuenta, los de la app.
        List<double[]> rows = new ArrayList<>(); // km, kWh/100, inicio
        if (!d.history.isEmpty()) {
            for (CloudHistory.Trip t : d.history) {
                double v = t.km >= 1 && !Double.isNaN(t.kwh) ? t.kwh / t.km * 100 : Double.NaN;
                if (CloudHistory.plausible(v)) rows.add(new double[]{t.km, v, t.startMs});
            }
        } else {
            for (TripLog.Trip t : d.trips) {
                double v = TripStats.bestKwhPer100(t);
                if (t.km >= 1 && !Double.isNaN(v)) rows.add(new double[]{t.km, v, t.startMs});
            }
        }
        if (rows.size() < 3) return;
        rows.sort((a, b) -> Double.compare(b[2], a[2]));
        LinearLayout c = card(Str.get(R.string.hql_ins_how));
        double km = 0, kwh = 0;
        for (double[] r : rows) {
            km += r[0];
            kwh += r[0] * r[1] / 100;
        }
        double avg = kwh / km * 100;
        int nBars = Math.min(24, rows.size());
        double[] v = new double[nBars];
        for (int i = 0; i < nBars; i++) v[i] = rows.get(nBars - 1 - i)[1];
        c.addView(small(Str.get(R.string.hql_ins_per_trip)));
        PhoneCharts.TripBars tb = new PhoneCharts.TripBars(act);
        tb.set(v, avg);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(110));
        tlp.topMargin = dp(8);
        c.addView(tb, tlp);

        // Según lo largo del viaje: en los cortos pesa más calentar la batería y el habitáculo.
        double[][] edges = {{0, 5}, {5, 20}, {20, 50}, {50, 1e9}};
        String[] labels = {"< 5 km", "5–20 km", "20–50 km", "> 50 km"};
        double[] per = new double[4];
        String[] notes = new String[4];
        for (int b = 0; b < 4; b++) {
            double bk = 0, be = 0;
            int bn = 0;
            for (double[] r : rows) {
                if (r[0] < edges[b][0] || r[0] >= edges[b][1]) continue;
                bk += r[0];
                be += r[0] * r[1] / 100;
                bn++;
            }
            per[b] = bk >= 1 ? be / bk * 100 : Double.NaN;
            notes[b] = bn > 0 ? Str.plural(R.plurals.hql_ins_n_trips, bn) : "";
        }
        TextView sub = small(Str.get(R.string.hql_ins_by_length));
        sub.setPadding(0, dp(16), 0, dp(6));
        c.addView(sub);
        PhoneCharts.HBars hb = new PhoneCharts.HBars(act);
        hb.set(labels, per, notes, avg);
        c.addView(hb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        double shortV = per[0];
        double longV = !Double.isNaN(per[2]) ? per[2] : per[3];
        if (!Double.isNaN(shortV) && !Double.isNaN(longV) && shortV > longV * 1.1) {
            TextView tip = note(Str.get(R.string.hql_ins_cold_tip, fmt0((shortV / longV - 1) * 100)));
            tip.setPadding(0, dp(8), 0, 0);
            c.addView(tip);
        }
        // El mejor viaje (de 10 km o más).
        double[] best = null;
        for (double[] r : rows) if (r[0] >= 10 && (best == null || r[1] < best[1])) best = r;
        if (best != null) {
            TextView b = note(Str.get(R.string.hql_ins_best, fmt1(best[1]), fmt0(best[0]), day((long) best[2])));
            b.setTextColor(PhoneCharts.OK);
            b.setPadding(0, dp(10), 0, 0);
            c.addView(b);
        }
        addCard(c);
    }

    private void chargesCard(Data d) {
        LinearLayout c = card(Str.get(R.string.hql_ins_charges));
        // Sin las de un momento (enchufar y desenchufar): menos de 0,5 kWh no es una carga.
        List<ChargeSession> list = new ArrayList<>();
        for (ChargeSession s : d.charges) {
            ChargeSession.Sample l = s.last();
            double e = l == null ? 0 : (l.soc - s.startSoc) * s.capacityKwh / 100;
            if (e >= 0.5) list.add(s);
        }
        list.sort((a, b) -> Long.compare(b.startMs, a.startMs));
        if (list.isEmpty()) {
            c.addView(note(Str.get(R.string.hql_ins_no_charges)));
            addCard(c);
            return;
        }
        int shown = 0;
        for (ChargeSession s : list) {
            if (shown++ >= 8) break;
            if (shown > 1) c.addView(divider());
            ChargeSession.Sample last = s.last();
            double endSoc = last != null ? last.soc : s.startSoc;
            double kwh = Math.max(0, endSoc - s.startSoc) * s.capacityKwh / 100;
            long min = Math.max(0, ((s.endMs > 0 ? s.endMs : (last != null ? last.atMs : s.startMs)) - s.startMs) / 60_000);
            double peak = 0;
            double[] soc = new double[s.samples.size()];
            double[] kw = new double[s.samples.size()];
            for (int i = 0; i < s.samples.size(); i++) {
                ChargeSession.Sample sm = s.samples.get(i);
                soc[i] = sm.soc;
                kw[i] = sm.kw;
                if (!Double.isNaN(sm.kw)) peak = Math.max(peak, sm.kw);
            }
            LinearLayout row = new LinearLayout(act);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(8), 0, dp(8));
            LinearLayout txt = new LinearLayout(act);
            txt.setOrientation(LinearLayout.VERTICAL);
            String where = s.charger != null ? RoutePlanner.chargerName(s.charger) : Str.get(s.dc ? R.string.hql_ins_dc : R.string.hql_ins_ac);
            txt.addView(title(day(s.startMs) + " · " + where));
            txt.addView(small(Str.get(R.string.hql_ins_charge_line, fmt0(s.startSoc), fmt0(endSoc), fmt1(kwh), String.valueOf(min),
                    peak > 0 ? fmt0(peak) : "—")));
            row.addView(txt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            PhoneCharts.Curve curve = new PhoneCharts.Curve(act, true);
            curve.set(soc, kw);
            row.addView(curve, new LinearLayout.LayoutParams(dp(96), dp(44)));
            row.setOnClickListener(v -> openCurve(day(s.startMs) + " · " + where, soc, kw));
            c.addView(row);
        }
        addCard(c);
    }

    /** La curva de una carga en grande. */
    private void openCurve(String head, double[] soc, double[] kw) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), 0);
        box.addView(small(Str.get(R.string.hql_ins_curve_help)));
        PhoneCharts.Curve cv = new PhoneCharts.Curve(act, false);
        cv.set(soc, kw);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220));
        lp.topMargin = dp(10);
        box.addView(cv, lp);
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(act)
                .setTitle(head)
                .setView(box)
                .setPositiveButton(Str.get(R.string.hql_close), null)
                .show();
    }

    private void drivesCard(Data d) {
        LinearLayout c = card(Str.get(R.string.hql_ins_drives));
        List<TripLog.Trip> list = new ArrayList<>();
        for (TripLog.Trip t : d.trips) if (t.track != null && t.track.length >= 2 && t.km >= 1) list.add(t);
        if (list.isEmpty()) {
            c.addView(note(Str.get(R.string.hql_ins_no_drives)));
            addCard(c);
            return;
        }
        int shown = 0;
        for (TripLog.Trip t : list) {
            if (shown++ >= 15) break;
            if (shown > 1) c.addView(divider());
            LinearLayout row = new LinearLayout(act);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(8), 0, dp(8));
            PhoneCharts.TrackThumb thumb = new PhoneCharts.TrackThumb(act);
            thumb.set(t.track);
            row.addView(thumb, new LinearLayout.LayoutParams(dp(84), dp(64)));
            LinearLayout txt = new LinearLayout(act);
            txt.setOrientation(LinearLayout.VERTICAL);
            txt.setPadding(dp(14), 0, 0, 0);
            txt.addView(title(day(t.startMs) + " · " + time(t.startMs)));
            double per100 = TripStats.bestKwhPer100(t, d.history);
            double cost = TripStats.cost(t, d.history, d.price, new Config(act).fuelPrice());
            String line = Str.get(R.string.hql_ins_drive_line, fmt1(t.km), duration(t.minutes),
                    Double.isNaN(per100) ? "—" : fmt1(per100) + " kWh/100");
            if (!Double.isNaN(cost) && cost > 0) line += " · " + Money.format(cost);
            txt.addView(small(line));
            row.addView(txt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            android.widget.ImageView go = new android.widget.ImageView(act);
            go.setImageResource(R.drawable.ic_chevron_right);
            go.setImageTintList(android.content.res.ColorStateList.valueOf(act.getColor(R.color.hql_text_dim)));
            go.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            int gs = Math.round(24 * act.getResources().getDisplayMetrics().density);
            row.addView(go, new LinearLayout.LayoutParams(gs, gs));
            row.setBackgroundResource(selectable());
            row.setOnClickListener(v -> act.startActivity(new Intent(act, TripMapActivity.class)
                    .putExtra(TripMapActivity.EXTRA_START, t.startMs)));
            c.addView(row);
        }
        addCard(c);
    }

    // ------------------------------------------------------------------ piezas

    /**
     * Tarjeta de Material 3 (con contorno, en el color de superficie de la app) con su título; se devuelve el contenido
     * y addCard la pone en la columna.
     */
    private LinearLayout card(String title) {
        com.google.android.material.card.MaterialCardView mc = new com.google.android.material.card.MaterialCardView(act, null,
                com.google.android.material.R.attr.materialCardViewOutlinedStyle);
        mc.setCardBackgroundColor(0xFF111821);
        mc.setStrokeColor(0xFF243140);
        mc.setRadius(dp(22));
        LinearLayout c = new LinearLayout(act);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(18), dp(16), dp(18), dp(16));
        mc.addView(c);
        c.setTag(mc);
        TextView t = new TextView(act);
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
        t.setText(title);
        t.setTextColor(PhoneCharts.ACCENT);
        t.setPadding(0, 0, 0, dp(12));
        c.addView(t);
        return c;
    }

    private void addCard(LinearLayout content) {
        col.addView((View) content.getTag(), cardLp());
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(14);
        return lp;
    }

    private static LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private View fact(String label, String value, String unit) {
        LinearLayout l = new LinearLayout(act);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(0, 0, 0, dp(8));
        l.addView(small(label));
        TextView v = new TextView(act);
        v.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge);
        v.setText(unit.isEmpty() ? value : value + " " + unit);
        v.setTextColor(PhoneCharts.TEXT);
        l.addView(v);
        return l;
    }

    private View stat(String value, String unit) {
        LinearLayout l = new LinearLayout(act);
        l.setOrientation(LinearLayout.VERTICAL);
        TextView v = new TextView(act);
        v.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge);
        v.setText(value);
        v.setTextColor(PhoneCharts.TEXT);
        v.setSingleLine(true);
        l.addView(v);
        TextView u = small(unit);
        u.setSingleLine(true);
        l.addView(u);
        return l;
    }

    private TextView title(String s) {
        TextView t = new TextView(act);
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall);
        t.setText(s);
        t.setTextColor(PhoneCharts.TEXT);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return t;
    }

    private TextView small(String s) {
        TextView t = new TextView(act);
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        t.setText(s);
        t.setTextColor(PhoneCharts.DIM);
        return t;
    }

    private TextView note(String s) {
        TextView t = small(s);
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        t.setTextColor(PhoneCharts.DIM);
        return t;
    }

    private View divider() {
        com.google.android.material.divider.MaterialDivider v = new com.google.android.material.divider.MaterialDivider(act);
        v.setDividerColor(0xFF1E2935);
        return v;
    }

    private int selectable() {
        TypedValue tv = new TypedValue();
        act.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        return tv.resourceId;
    }

    private int dp(float d) {
        return Math.round(d * act.getResources().getDisplayMetrics().density);
    }

    private static String fmt0(double v) {
        return String.format(Locale.getDefault(), "%.0f", v);
    }

    private static String fmt1(double v) {
        return String.format(Locale.getDefault(), "%.1f", v);
    }

    private static String fmtThousands(double v) {
        return String.format(Locale.getDefault(), "%,.0f", v);
    }

    private static String day(long ms) {
        return new SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(new Date(ms));
    }

    private static String time(long ms) {
        return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(ms));
    }

    static String duration(long minutes) {
        if (minutes < 60) return minutes + " min";
        return String.format(Locale.getDefault(), "%d h %02d min", minutes / 60, minutes % 60);
    }

    private static String ago(long ms) {
        long s = Math.max(0, ms / 1000);
        if (s < 90) return s + " s";
        long m = s / 60;
        if (m < 90) return m + " min";
        return String.format(Locale.getDefault(), "%.1f h", m / 60.0);
    }
}
