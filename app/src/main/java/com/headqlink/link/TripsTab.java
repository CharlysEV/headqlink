package com.headqlink.link;

import com.andrerinas.openheadunit.R;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.DateFormatSymbols;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Pestaña "Viajes": el viaje en curso y los guardados (TripLog) como tarjetas con su recorrido dibujado, km, tiempo,
 * velocidad media, consumo estimado, desnivel y coste; a la derecha, los km de cada día de las dos últimas semanas
 * (esta semana frente a la anterior) y los récords (más largo, más eficiente, más desnivel). Sin viajes, un estado
 * vacío que explica cuándo se guardan. Con la cuenta de Leapmotor, el consumo de los viajes con datos del coche al
 * empezar y al terminar es el REAL (marcado «real») y abajo salen sus totales reales.
 */
final class TripsTab implements CarScreen {
    private static final int DAYS = 14;

    private Context ctx;
    private Config cfg;
    private CarSensors sensors;
    private boolean running;
    private List<TripLog.Trip> trips;
    private CarSensors.Snapshot s = new CarSensors.Snapshot();
    private CloudEnergy.Result liveReal = CloudEnergy.NONE;
    private TripStats.Totals allTotals = new TripStats.Totals();
    private View live;
    private int longest = -1;
    private int efficient = -1;
    private int climb = -1;
    private java.text.DateFormat dayFmt;
    private java.text.DateFormat timeFmt;
    private final CarKit.Para para = new CarKit.Para(26, CarKit.DIM, CarKit.REGULAR);
    private final RectF tmp = new RectF();

    @Override
    public View create(Host h) {
        ctx = h.context();
        cfg = new Config(ctx);
        sensors = CarSensors.start(ctx);
        trips = TripLog.recent(ctx, 30);
        longest = TripStats.longest(trips);
        efficient = TripStats.mostEfficient(trips);
        climb = TripStats.mostClimb(trips);
        allTotals = TripStats.totals(trips, Long.MIN_VALUE, Long.MAX_VALUE);
        Locale loc = Locale.getDefault();
        dayFmt = new java.text.SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(loc, "EEEdMMM"), loc);
        timeFmt = android.text.format.DateFormat.getTimeFormat(ctx);

        LinearLayout row = CarKit.row(ctx);
        LinearLayout list = CarKit.col(ctx);
        ScrollView sv = new ScrollView(ctx);
        sv.setVerticalScrollBarEnabled(false);
        sv.setFillViewport(true);
        sv.addView(list);
        CarKit.add(row, sv, 2f, 0);
        live = new CarKit.Card(ctx, Str.get(R.string.hql_trip_live), this::paintLive);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 168);
        llp.bottomMargin = CarKit.GAP;
        list.addView(live, llp);
        if (trips.isEmpty()) {
            CarKit.Card empty = new CarKit.Card(ctx, null, this::paintEmpty);
            list.addView(empty, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        }
        for (int i = 0; i < trips.size(); i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 196);
            if (i > 0) lp.topMargin = CarKit.GAP;
            list.addView(new TripCard(ctx, trips.get(i), i), lp);
        }
        if (!trips.isEmpty()) {
            TextView note = CarStyle.text(ctx, Str.get(R.string.hql_trips_note, cfg.electricityPrice(), cfg.fuelLitersPer100(), cfg.fuelPrice()), 20, CarKit.FAINT);
            note.setPadding(8, 16, 8, 8);
            list.addView(note);
        }
        LinearLayout right = CarKit.add(row, CarKit.col(ctx), 1f, 0);
        CarKit.add(right, new CarKit.Card(ctx, Str.get(R.string.hql_last_14_days), this::paintDays), 1f, 0);
        CarKit.add(right, new CarKit.Card(ctx, Str.get(R.string.hql_records), this::paintRecords), 1f, 0);
        running = true;
        tick();
        return CarKit.page(ctx, row);
    }

    private void tick() {
        if (!running) return;
        s = sensors.snapshot();
        liveReal = TripLog.liveReal(CarCloud.snapshot());
        // El viaje en curso, solo si ya se ha movido el coche.
        live.setVisibility(s.tripKm >= 0.1 || s.speedKmh > 3 ? View.VISIBLE : View.GONE);
        live.invalidate();
        live.postDelayed(this::tick, 1000);
    }

    @Override
    public void destroy() {
        running = false;
        CarSensors.stop();
    }

    private String day(long ms) {
        return dayFmt.format(new java.util.Date(ms));
    }

    private static int useColor(double v) {
        if (Double.isNaN(v)) return CarKit.MUTED;
        if (v < 15) return CarKit.GREEN;
        if (v < 21) return CarKit.ACCENT;
        if (v < 27) return CarKit.AMBER;
        return CarKit.RED;
    }

    // ------------------------------------------------------------------ viaje en curso y vacío

    private void paintLive(Canvas cv, RectF r, Paint p) {
        p.setColor(CarKit.GREEN);
        cv.drawCircle(r.right - 8, r.top - 30, 7, p);
        p.setColor(CarKit.alpha(CarKit.GREEN, 0.25f));
        cv.drawCircle(r.right - 8, r.top - 30, 14, p);
        double avg = s.tripSec > 60 ? s.tripKm / (s.tripSec / 3600.0) : Double.NaN;
        boolean real = liveReal.ok();
        double per100 = real ? liveReal.kwhPer100 : s.tripKmEnergy > 0.3 ? s.tripKwh / s.tripKmEnergy * 100 : Double.NaN;
        float w = CarKit.number(cv, String.format(Locale.getDefault(), "%.1f", s.tripKm), "km", r.left, r.bottom - 8, 56, CarKit.TEXT,
                CarKit.REGULAR, p, Paint.Align.LEFT);
        String sub = DriveTab.duration(s.tripSec) + (Double.isNaN(avg) ? "" : " · " + Str.get(R.string.hql_avg_kmh, avg));
        CarKit.text(cv, sub, r.left + w + 30, r.bottom - 12, 27, CarKit.DIM, CarKit.MEDIUM, p, Paint.Align.LEFT);
        stat(cv, r.right - 200, r.bottom - 12, Double.isNaN(per100) ? "—" : String.format(Locale.getDefault(), "%.1f", per100), "kWh/100",
                useColor(per100), p);
        if (real) realTag(cv, r.right - 214, r.bottom - 16, Paint.Align.RIGHT, p);
    }

    /** Marca «REAL» (consumo con los datos del coche) con su base en y; devuelve su ancho. */
    static float realTag(Canvas cv, float x, float y, Paint.Align align, Paint p) {
        String t = Str.get(R.string.hql_cloud_real_tag).toUpperCase(Locale.getDefault());
        p.setTypeface(CarKit.MEDIUM);
        p.setTextSize(17);
        p.setLetterSpacing(0.1f);
        float tw = p.measureText(t);
        p.setLetterSpacing(0);
        float w = tw + 22;
        float x0 = align == Paint.Align.RIGHT ? x - w : x;
        RectF b = new RectF(x0, y - 22, x0 + w, y + 6);
        p.setStyle(Paint.Style.FILL);
        p.setColor(CarKit.alpha(CarKit.ACCENT, 0.16f));
        cv.drawRoundRect(b, 14, 14, p);
        p.setColor(CarKit.ACCENT);
        p.setLetterSpacing(0.1f);
        p.setTextAlign(Paint.Align.LEFT);
        cv.drawText(t, x0 + 11, y - 2, p);
        p.setLetterSpacing(0);
        return w;
    }

    /** Cifra mediana con su unidad, alineada a la izquierda en x. */
    private static void stat(Canvas cv, float x, float y, String v, String unit, int color, Paint p) {
        CarKit.number(cv, v, unit, x, y, 34, color, CarKit.MEDIUM, p, Paint.Align.LEFT);
    }

    private void paintEmpty(Canvas cv, RectF r, Paint p) {
        float cx = r.centerX();
        float cy = r.centerY() - 120;
        // Un camino de puntos del coche a la bandera.
        p.setColor(CarKit.MUTED);
        for (int i = 0; i < 9; i++) {
            float t = i / 8f;
            float x = cx - 200 + 400 * t;
            float y = cy + 40 - (float) Math.sin(t * Math.PI) * 70;
            cv.drawCircle(x, y, 6, p);
        }
        p.setColor(CarKit.SURFACE_HI);
        cv.drawCircle(cx - 230, cy + 40, 46, p);
        CarIcons.route(cv, cx - 230, cy + 40, 50, CarKit.DIM, p);
        p.setColor(CarKit.alpha(CarKit.ACCENT, 0.16f));
        cv.drawCircle(cx + 230, cy + 40, 46, p);
        CarIcons.flag(cv, cx + 232, cy + 40, 52, CarKit.ACCENT, p);
        CarKit.text(cv, Str.get(R.string.hql_trips_empty_title), cx, cy + 160, 40, CarKit.TEXT, CarKit.MEDIUM, p, Paint.Align.CENTER);
        para.draw(cv, Str.get(R.string.hql_trips_none), r.left + 80, cy + 190, (int) r.width() - 160, true);
    }

    // ------------------------------------------------------------------ tarjeta de viaje

    private final class TripCard extends View {
        private final TripLog.Trip t;
        private final int index;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path track = new Path();
        private final RectF r = new RectF();
        private final RectF box = new RectF();
        private float trackW = -1;
        private float startX;
        private float startY;
        private float endX;
        private float endY;

        TripCard(Context c, TripLog.Trip t, int index) {
            super(c);
            this.t = t;
            this.index = index;
        }

        /** El recorrido escalado a la caja, calculado una vez por tamaño. */
        private void buildTrack(RectF b) {
            if (trackW == b.width()) return;
            trackW = b.width();
            track.rewind();
            double[][] pts = t.track;
            if (pts.length < 2) return;
            double minLa = 90;
            double maxLa = -90;
            double minLo = 180;
            double maxLo = -180;
            for (double[] q : pts) {
                minLa = Math.min(minLa, q[0]);
                maxLa = Math.max(maxLa, q[0]);
                minLo = Math.min(minLo, q[1]);
                maxLo = Math.max(maxLo, q[1]);
            }
            double cos = Math.cos(Math.toRadians((minLa + maxLa) / 2));
            double spanX = Math.max(1e-6, (maxLo - minLo) * cos);
            double spanY = Math.max(1e-6, maxLa - minLa);
            float pad = 20;
            double scale = Math.min((b.width() - 2 * pad) / spanX, (b.height() - 2 * pad) / spanY);
            float offX = (float) (b.left + (b.width() - spanX * scale) / 2);
            float offY = (float) (b.top + (b.height() - spanY * scale) / 2);
            for (int i = 0; i < pts.length; i++) {
                float x = (float) (offX + (pts[i][1] - minLo) * cos * scale);
                float y = (float) (offY + spanY * scale - (pts[i][0] - minLa) * scale);
                if (i == 0) {
                    track.moveTo(x, y);
                    startX = x;
                    startY = y;
                } else {
                    track.lineTo(x, y);
                }
                endX = x;
                endY = y;
            }
        }

        @Override
        protected void onDraw(Canvas cv) {
            r.set(1, 1, getWidth() - 1, getHeight() - 1);
            CarKit.card(cv, r, p);
            float pad = CarKit.PAD;
            box.set(pad, pad, pad + 230, getHeight() - pad);
            p.setColor(CarKit.SURFACE_HI);
            cv.drawRoundRect(box, 16, 16, p);
            buildTrack(box);
            if (t.track.length >= 2) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(5);
                p.setStrokeJoin(Paint.Join.ROUND);
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setColor(CarKit.ACCENT);
                cv.drawPath(track, p);
                p.setStyle(Paint.Style.FILL);
                p.setStrokeCap(Paint.Cap.BUTT);
                p.setColor(CarKit.TEXT);
                cv.drawCircle(startX, startY, 7, p);
                p.setColor(CarKit.GREEN);
                cv.drawCircle(endX, endY, 8, p);
            }
            float x = box.right + 30;
            float top = pad;
            CarKit.label(cv, day(t.startMs) + " · " + timeFmt.format(new java.util.Date(t.startMs)), x, top + 22, p);
            float w = CarKit.number(cv, String.format(Locale.getDefault(), "%.1f", t.km), "km", x, top + 86, 54, CarKit.TEXT, CarKit.REGULAR, p, Paint.Align.LEFT);
            double avg = TripStats.avgKmh(t);
            String sub = DriveTab.duration(t.minutes * 60) + (Double.isNaN(avg) ? "" : " · " + Str.get(R.string.hql_avg_kmh, avg));
            CarKit.text(cv, sub, x + w + 26, top + 84, 26, CarKit.DIM, CarKit.MEDIUM, p, Paint.Align.LEFT);
            // Fichas: consumo (el real si el viaje tiene datos del coche, con su marca), desnivel y coste.
            double real = TripStats.realKwhPer100(t);
            double per100 = Double.isNaN(real) ? TripStats.kwhPer100(t) : real;
            float y = getHeight() - pad - 4;
            float cx = x;
            CarIcons.leaf(cv, cx + 12, y - 12, 26, useColor(per100), p);
            cx += 32;
            cx += CarKit.number(cv, Double.isNaN(per100) ? "—" : String.format(Locale.getDefault(), "%.1f", per100), "kWh/100", cx, y, 32,
                    useColor(per100), CarKit.MEDIUM, p, Paint.Align.LEFT) + 14;
            if (!Double.isNaN(real)) cx += realTag(cv, cx, y - 2, Paint.Align.LEFT, p);
            cx += 26;
            CarIcons.mountain(cv, cx + 14, y - 12, 28, CarKit.DIM, p);
            cx += 36;
            cx += CarKit.number(cv, String.format(Locale.getDefault(), "%.0f", t.climb), "m", cx, y, 32, CarKit.TEXT, CarKit.MEDIUM, p, Paint.Align.LEFT) + 40;
            double cost = t.kwh * cfg.electricityPrice();
            CarKit.number(cv, String.format(Locale.getDefault(), "%.2f", cost), "€", cx, y, 32, CarKit.TEXT, CarKit.MEDIUM, p, Paint.Align.LEFT);
            // Distintivo si es un récord.
            String badge = null;
            int bc = 0;
            if (index == longest) {
                badge = Str.get(R.string.hql_record_longest);
                bc = CarKit.AMBER;
            } else if (index == efficient) {
                badge = Str.get(R.string.hql_record_efficient);
                bc = CarKit.GREEN;
            } else if (index == climb) {
                badge = Str.get(R.string.hql_record_climb);
                bc = CarKit.BLUE;
            }
            if (badge != null) {
                p.setTypeface(CarKit.MEDIUM);
                p.setTextSize(20);
                float bw = p.measureText(badge) + 64;
                RectF b = tmp;
                b.set(getWidth() - pad - bw, pad, getWidth() - pad, pad + 40);
                p.setColor(CarKit.alpha(bc, 0.14f));
                cv.drawRoundRect(b, 20, 20, p);
                if (bc == CarKit.AMBER) CarIcons.trophy(cv, b.left + 24, b.centerY(), 24, bc, p);
                else if (bc == CarKit.GREEN) CarIcons.leaf(cv, b.left + 24, b.centerY(), 22, bc, p);
                else CarIcons.mountain(cv, b.left + 24, b.centerY(), 24, bc, p);
                CarKit.text(cv, badge, b.left + 44, b.centerY() + 7, 20, bc, CarKit.MEDIUM, p, Paint.Align.LEFT);
            }
        }
    }

    // ------------------------------------------------------------------ columna derecha

    private void paintDays(Canvas cv, RectF r, Paint p) {
        long now = DemoMode.wallClockMs();
        TimeZone tz = TimeZone.getDefault();
        double[] km = TripStats.dailyKm(trips, now, DAYS, tz);
        long today = TripStats.startOfDay(now, tz);
        TripStats.Totals week = TripStats.totals(trips, today - 6 * 86_400_000L, today + 86_400_000L);
        TripStats.Totals prev = TripStats.totals(trips, today - 13 * 86_400_000L, today - 6 * 86_400_000L);
        float w = CarKit.number(cv, String.format(Locale.getDefault(), "%.0f", week.km), "km", r.left, r.top + 50, 56, CarKit.TEXT, CarKit.REGULAR, p, Paint.Align.LEFT);
        CarKit.text(cv, Str.get(R.string.hql_this_week), r.left + w + 18, r.top + 48, 24, CarKit.DIM, CarKit.MEDIUM, p, Paint.Align.LEFT);
        if (prev.km > 1) {
            double d = (week.km - prev.km) / prev.km * 100;
            CarKit.text(cv, Str.get(R.string.hql_vs_last_week, d), r.left, r.top + 84, 22, CarKit.FAINT, CarKit.MEDIUM, p, Paint.Align.LEFT);
        }
        double max = 10;
        for (double v : km) max = Math.max(max, v);
        float top = r.top + 112;
        float bottom = r.bottom - 30;
        float bw = r.width() / DAYS;
        String[] names = new DateFormatSymbols(Locale.getDefault()).getShortWeekdays();
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(today);
        c.add(Calendar.DAY_OF_YEAR, -(DAYS - 1));
        for (int i = 0; i < DAYS; i++) {
            float x = r.left + i * bw + 4;
            float h = (float) (km[i] / max * (bottom - top - 26));
            boolean thisWeek = i >= DAYS - 7;
            p.setColor(thisWeek ? CarKit.accentAt(i / (float) DAYS) : CarKit.MUTED);
            if (km[i] <= 0) p.setColor(CarKit.SURFACE_TOP);
            tmp.set(x, bottom - Math.max(6, h), x + bw - 8, bottom);
            cv.drawRoundRect(tmp, 6, 6, p);
            if (km[i] >= max * 0.99 && km[i] > 0) {
                CarKit.text(cv, String.format(Locale.getDefault(), "%.0f", km[i]), tmp.centerX(), tmp.top - 8, 18, CarKit.DIM, CarKit.MEDIUM, p, Paint.Align.CENTER);
            }
            String n = names[c.get(Calendar.DAY_OF_WEEK)];
            String letter = n.isEmpty() ? "" : n.substring(0, 1).toUpperCase(Locale.getDefault());
            CarKit.text(cv, letter, tmp.centerX(), r.bottom, 18, i == DAYS - 1 ? CarKit.TEXT : CarKit.FAINT, CarKit.MEDIUM, p, Paint.Align.CENTER);
            c.add(Calendar.DAY_OF_YEAR, 1);
        }
    }

    private void paintRecords(Canvas cv, RectF r, Paint p) {
        if (trips.isEmpty()) {
            CarKit.text(cv, Str.get(R.string.hql_no_records), r.left, r.top + 30, 25, CarKit.DIM, CarKit.REGULAR, p, Paint.Align.LEFT);
            return;
        }
        boolean realLine = allTotals.realTrips > 0;
        float step = (r.height() - (realLine ? 72 : 40)) / 3f;
        float y = r.top;
        if (longest >= 0) {
            TripLog.Trip t = trips.get(longest);
            record(cv, r, y, 0, Str.get(R.string.hql_record_longest), String.format(Locale.getDefault(), "%.1f", t.km), "km", day(t.startMs), p);
        }
        if (efficient >= 0) {
            TripLog.Trip t = trips.get(efficient);
            record(cv, r, y + step, 1, Str.get(R.string.hql_record_efficient), String.format(Locale.getDefault(), "%.1f", TripStats.bestKwhPer100(t)),
                    "kWh/100 km", day(t.startMs), p);
        }
        if (climb >= 0) {
            TripLog.Trip t = trips.get(climb);
            record(cv, r, y + 2 * step, 2, Str.get(R.string.hql_record_climb), "↑" + String.format(Locale.getDefault(), "%.0f", t.climb), "m", day(t.startMs), p);
        }
        double km = 0;
        double kwh = 0;
        for (TripLog.Trip t : trips) {
            km += t.km;
            kwh += t.kwh;
        }
        CarKit.text(cv, Str.get(R.string.hql_trips_total, trips.size(), km, kwh * cfg.electricityPrice()), r.left, r.bottom, 21, CarKit.FAINT,
                CarKit.MEDIUM, p, Paint.Align.LEFT);
        // Los totales reales: solo los viajes con datos del coche al empezar y al terminar.
        if (realLine) {
            String rt = Str.get(R.string.hql_cloud_trips_real_total, allTotals.realTrips, allTotals.realKm, allTotals.realKwhPer100());
            p.setTypeface(CarKit.MEDIUM);
            p.setTextSize(21);
            CarKit.text(cv, CarKit.ellipsize(rt, r.width(), p), r.left, r.bottom - 30, 21, CarKit.ACCENT, CarKit.MEDIUM, p, Paint.Align.LEFT);
        }
    }

    private void record(Canvas cv, RectF r, float y, int kind, String title, String value, String unit, String when, Paint p) {
        int color = kind == 0 ? CarKit.AMBER : kind == 1 ? CarKit.GREEN : CarKit.BLUE;
        float cy = y + 40;
        p.setColor(CarKit.alpha(color, 0.14f));
        cv.drawCircle(r.left + 32, cy, 32, p);
        if (kind == 0) CarIcons.trophy(cv, r.left + 32, cy, 36, color, p);
        else if (kind == 1) CarIcons.leaf(cv, r.left + 32, cy, 34, color, p);
        else CarIcons.mountain(cv, r.left + 32, cy, 36, color, p);
        float x = r.left + 84;
        CarKit.label(cv, title, x, y + 20, p);
        float w = CarKit.number(cv, value, unit, x, y + 62, 36, CarKit.TEXT, CarKit.MEDIUM, p, Paint.Align.LEFT);
        CarKit.text(cv, when, Math.max(x + w + 18, r.right - 0), y + 60, 21, CarKit.FAINT, CarKit.MEDIUM, p, Paint.Align.RIGHT);
    }
}
