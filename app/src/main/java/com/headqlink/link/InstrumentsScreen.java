package com.headqlink.link;

import com.andrerinas.openheadunit.R;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * Instrumentos con los sensores del móvil: velocímetro de segmentos (GPS) con zonas de color según el límite de la
 * vía y la marca de la máxima, medidor de fuerzas G con la estela de los últimos segundos y los picos del viaje,
 * inclinómetro (cabeceo por la pendiente; balanceo estimado por la fuerza lateral), nota de suavidad (tirones) y el
 * resumen del viaje con el cronómetro 0-50/0-100 automático.
 */
final class InstrumentsScreen implements CarScreen {
    private static final long TICK_MS = 100;
    /** Escala del velocímetro y del medidor G. */
    private static final float MAX_KMH = 180;
    private static final float MAX_G = 0.6f;
    private static final int SEGMENTS = 45;
    /** Balanceo de carrocería estimado de un SUV: unos 5° por g lateral. */
    private static final double ROLL_DEG_PER_G = 5;

    private CarSensors sensors;
    private RoadInfo road;
    private boolean running;
    private int ticks;
    private CarSensors.Snapshot s = new CarSensors.Snapshot();
    private int limit = -1;
    private CarKit.Card speedCard;
    private CarKit.Card gCard;
    private CarKit.Card inclCard;
    private CarKit.Card smoothCard;
    private float speedFrac;
    private final float[] trailLat = new float[CarSensors.TRAIL];
    private final float[] trailLong = new float[CarSensors.TRAIL];
    private final long[] trailMs = new long[CarSensors.TRAIL];
    private int trailN;
    private final RectF oval = new RectF();
    private final RectF tmp = new RectF();
    private Bitmap carSide;
    private Bitmap carFront;
    private final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final CarKit.Para how = new CarKit.Para(21, CarKit.FAINT, CarKit.REGULAR);

    @Override
    public View create(Host h) {
        Context c = h.context();
        sensors = CarSensors.start(c);
        road = RoadInfo.start(c);
        LinearLayout top = CarKit.row(c);
        speedCard = CarKit.add(top, new CarKit.Card(c, Str.get(R.string.hql_speed), this::paintSpeed), 1.15f, 0);
        gCard = CarKit.add(top, new CarKit.Card(c, Str.get(R.string.hql_gforce), this::paintG), 1.15f, 0);
        LinearLayout right = CarKit.add(top, CarKit.col(c), 0.9f, 0);
        inclCard = CarKit.add(right, new CarKit.Card(c, Str.get(R.string.hql_inclination), this::paintIncl), 1f, 0);
        smoothCard = CarKit.add(right, new CarKit.Card(c, Str.get(R.string.hql_smoothness), this::paintSmooth), 0.8f, 0);
        // «Reiniciar viaje», en la fila del título del velocímetro.
        TextView reset = CarKit.pill(c, Str.get(R.string.hql_reset_trip), false);
        speedCard.setClipToPadding(false);
        speedCard.addView(reset, CarKit.at(Gravity.END | Gravity.TOP, 0, -58, 0, 0));
        reset.setOnClickListener(v -> {
            sensors.resetTrip();
            tick();
        });
        running = true;
        tick();
        return CarKit.page(c, top);
    }

    private void tick() {
        if (!running) return;
        s = sensors.snapshot();
        limit = road.state().limitKmh;
        trailN = sensors.gTrail(trailLat, trailLong, trailMs);
        // Sin posiciones al día, la aguja baja a cero (el número dice «—» y el motivo).
        float target = s.gpsLive() ? (float) Math.min(1, s.speedKmh / MAX_KMH) : 0;
        speedFrac += (target - speedFrac) * 0.6f;
        speedCard.invalidate();
        gCard.invalidate();
        if (ticks++ % 5 == 0) {
            inclCard.invalidate();
            smoothCard.invalidate();
        }
        speedCard.removeCallbacks(tickTask);
        speedCard.postDelayed(tickTask, TICK_MS);
    }

    private final Runnable tickTask = this::tick;

    @Override
    public void destroy() {
        running = false;
        CarSensors.stop();
        if (carSide != null) carSide.recycle();
        if (carFront != null) carFront.recycle();
    }

    // ------------------------------------------------------------------ velocímetro

    private void paintSpeed(Canvas cv, RectF r, Paint p) {
        float cx = r.centerX();
        float rad = Math.min(r.width(), r.height()) / 2f - 18;
        float cy = r.top + rad + 18;
        oval.set(cx - rad, cy - rad, cx + rad, cy + rad);
        float stroke = 26;
        float segSweep = 270f / SEGMENTS;
        float lit = speedFrac * SEGMENTS;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(stroke);
        for (int i = 0; i < SEGMENTS; i++) {
            float kmh = (i + 0.5f) * MAX_KMH / SEGMENTS;
            int color;
            if (i < lit) {
                if (limit > 0 && kmh > limit * 1.1f) color = CarKit.RED;
                else if (limit > 0 && kmh > limit) color = CarKit.AMBER;
                else color = CarKit.accentAt(i / (float) SEGMENTS);
                float part = Math.min(1, lit - i);
                if (part < 1) color = CarKit.lerp(CarKit.SURFACE_TOP, color, part);
            } else {
                color = CarKit.SURFACE_TOP;
            }
            p.setColor(color);
            cv.drawArc(oval, 135 + i * segSweep + 0.8f, segSweep - 1.6f, false, p);
        }
        p.setStyle(Paint.Style.FILL);
        // Escala.
        for (int v = 0; v <= MAX_KMH; v += 30) {
            double a = Math.toRadians(135 + 270 * v / MAX_KMH);
            float lr = rad - stroke - 26;
            CarKit.text(cv, String.valueOf(v), cx + (float) Math.cos(a) * lr, cy + (float) Math.sin(a) * lr + 8, 21,
                    CarKit.FAINT, CarKit.MEDIUM, p, Paint.Align.CENTER);
        }
        // Límite de la vía (marca blanca) y máxima del viaje (marca roja), por fuera del arco.
        if (limit > 0) tick(cv, cx, cy, rad + stroke / 2 + 4, limit, CarKit.TEXT, p);
        if (s.maxSpeedKmh > 1) tick(cv, cx, cy, rad + stroke / 2 + 4, (float) s.maxSpeedKmh, CarKit.RED, p);
        String gpsNote = CarSensors.gpsNote(s);
        int color = gpsNote != null ? CarKit.MUTED : limit > 0 && s.speedKmh > limit * 1.1 + 2 ? CarKit.RED
                : limit > 0 && s.speedKmh > limit + 2 ? CarKit.AMBER : CarKit.TEXT;
        CarKit.text(cv, gpsNote != null ? "—" : String.format(Locale.getDefault(), "%.0f", s.speedKmh), cx, cy + 46, 136, color,
                CarKit.REGULAR, p, Paint.Align.CENTER);
        CarKit.text(cv, "km/h", cx, cy + 92, 28, CarKit.DIM, CarKit.MEDIUM, p, Paint.Align.CENTER);
        String[] t = timer(s.timer);
        if (!t[1].equals("—")) {
            CarKit.text(cv, Str.get(R.string.hql_timer_line, t[0], t[1]), cx, cy + rad * 0.71f + 64, 25, CarKit.ACCENT, CarKit.MEDIUM, p, Paint.Align.CENTER);
        }
        if (gpsNote != null) {
            p.setTypeface(CarKit.REGULAR);
            p.setTextSize(22);
            CarKit.text(cv, CarKit.ellipsize(gpsNote, rad * 1.9f, p), cx, cy + rad * 0.86f, 22, CarKit.RED, CarKit.REGULAR, p,
                    Paint.Align.CENTER);
        }
        // Fichas del viaje: distancia (y tiempo), media y máxima.
        double avg = s.tripSec > 60 ? s.tripKm / (s.tripSec / 3600.0) : Double.NaN;
        float first = r.width() * 0.36f;
        float cw = (r.width() - first - 2 * 14) / 2f;
        float top = r.bottom - 92;
        tmp.set(r.left, top, r.left + first, r.bottom);
        CarKit.chip(cv, tmp, Str.get(R.string.hql_trip) + " · " + DriveTab.duration(s.tripSec), String.format(Locale.getDefault(), "%.1f", s.tripKm), "km", CarKit.TEXT, p);
        tmp.set(tmp.right + 14, top, tmp.right + 14 + cw, r.bottom);
        CarKit.chip(cv, tmp, Str.get(R.string.hql_avg), Double.isNaN(avg) ? "—" : String.format(Locale.getDefault(), "%.0f", avg), "km/h", CarKit.TEXT, p);
        tmp.offset(cw + 14, 0);
        CarKit.chip(cv, tmp, Str.get(R.string.hql_max), String.format(Locale.getDefault(), "%.0f", s.maxSpeedKmh), "km/h", CarKit.TEXT, p);
    }

    /** Del texto del cronómetro ("0-50 km/h: 8,1 s · 0-100 km/h: 9,9 s"), el último tramo: {nombre, segundos}. */
    static String[] timer(String t) {
        if (t == null || t.isEmpty()) return new String[]{"0-100", "—"};
        String last = t.contains("·") ? t.substring(t.lastIndexOf('·') + 1).trim() : t.trim();
        int colon = last.indexOf(':');
        if (colon < 0) return new String[]{"0-100", "—"};
        String name = last.substring(0, colon).replace(" km/h", "").trim();
        String secs = last.substring(colon + 1).replace("s", "").trim();
        return new String[]{name, secs};
    }

    /** Marca triangular por fuera del arco del velocímetro en kmh. */
    private void tick(Canvas cv, float cx, float cy, float rr, float kmh, int color, Paint p) {
        double a = Math.toRadians(135 + 270 * Math.min(1, kmh / MAX_KMH));
        float x = cx + (float) Math.cos(a) * rr;
        float y = cy + (float) Math.sin(a) * rr;
        float ox = cx + (float) Math.cos(a) * (rr + 18);
        float oy = cy + (float) Math.sin(a) * (rr + 18);
        double perp = a + Math.PI / 2;
        android.graphics.Path path = tri;
        path.rewind();
        path.moveTo(x, y);
        path.lineTo(ox + (float) Math.cos(perp) * 9, oy + (float) Math.sin(perp) * 9);
        path.lineTo(ox - (float) Math.cos(perp) * 9, oy - (float) Math.sin(perp) * 9);
        path.close();
        p.setColor(color);
        cv.drawPath(path, p);
    }

    private final android.graphics.Path tri = new android.graphics.Path();

    // ------------------------------------------------------------------ fuerzas G

    private void paintG(Canvas cv, RectF r, Paint p) {
        float cx = r.centerX();
        float rad = Math.min(r.width() / 2f - 14, (r.height() - 130) / 2f - 34);
        float cy = r.top + rad + 30;
        float scale = rad / MAX_G;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2);
        for (float g = 0.2f; g <= MAX_G + 0.01f; g += 0.2f) {
            p.setColor(g >= MAX_G - 0.01f ? CarKit.EDGE : CarKit.OUTLINE);
            cv.drawCircle(cx, cy, g * scale, p);
        }
        p.setColor(CarKit.OUTLINE);
        cv.drawLine(cx - rad, cy, cx + rad, cy, p);
        cv.drawLine(cx, cy - rad, cx, cy + rad, p);
        p.setStyle(Paint.Style.FILL);
        for (float g = 0.2f; g <= MAX_G + 0.01f; g += 0.2f) {
            CarKit.text(cv, String.format(Locale.getDefault(), "%.1f", g), cx + g * scale * 0.71f + 6, cy - g * scale * 0.71f - 6, 17,
                    CarKit.MUTED, CarKit.MEDIUM, p, Paint.Align.LEFT);
        }
        // Ejes: arriba frenada (el cuerpo va hacia delante), abajo aceleración, a los lados la curva.
        CarKit.label(cv, Str.get(R.string.hql_braking), cx, cy - rad - 10, CarKit.FAINT, p, Paint.Align.CENTER);
        CarKit.label(cv, Str.get(R.string.hql_acceleration), cx, cy + rad + 26, CarKit.FAINT, p, Paint.Align.CENTER);
        CarKit.label(cv, Str.get(R.string.hql_left_short), cx - rad + 10, cy - 12, CarKit.FAINT, p, Paint.Align.LEFT);
        CarKit.label(cv, Str.get(R.string.hql_right_short), cx + rad - 10, cy - 12, CarKit.FAINT, p, Paint.Align.RIGHT);
        // Picos del viaje: marcas en los ejes con su valor.
        peak(cv, cx, cy - (float) clamp(s.maxBrakeG) * scale, s.maxBrakeG, 0, p);
        peak(cv, cx, cy + (float) clamp(s.maxAccelG) * scale, s.maxAccelG, 2, p);
        peak(cv, cx - (float) clamp(s.maxLeftG) * scale, cy, s.maxLeftG, 3, p);
        peak(cv, cx + (float) clamp(s.maxRightG) * scale, cy, s.maxRightG, 1, p);
        // Estela de los últimos segundos, que se apaga con la edad.
        long now = s.clockMs;
        float px = 0;
        float py = 0;
        boolean have = false;
        for (int i = 0; i < trailN; i++) {
            long age = now - trailMs[i];
            if (age > CarSensors.TRAIL_MS || age < 0) continue;
            float f = 1f - age / (float) CarSensors.TRAIL_MS;
            float x = cx - (float) clamp(trailLat[i]) * scale;
            float y = cy + (float) clamp(trailLong[i]) * scale;
            if (have) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(3 + 4 * f);
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setColor(CarKit.alpha(CarKit.ACCENT, 0.08f + 0.5f * f * f));
                cv.drawLine(px, py, x, y, p);
                p.setStyle(Paint.Style.FILL);
                p.setStrokeCap(Paint.Cap.BUTT);
            }
            px = x;
            py = y;
            have = true;
        }
        float dx = cx - (float) clamp(s.latG) * scale;
        float dy = cy + (float) clamp(s.longG) * scale;
        p.setColor(CarKit.alpha(CarKit.ACCENT, 0.22f));
        cv.drawCircle(dx, dy, 30, p);
        p.setColor(CarKit.ACCENT);
        cv.drawCircle(dx, dy, 16, p);
        double total = Math.hypot(s.latG, s.longG);
        double peak = Math.max(Math.max(s.maxBrakeG, s.maxAccelG), Math.max(s.maxLeftG, s.maxRightG));
        float cw = (r.width() - 14) / 2f;
        tmp.set(r.left, r.bottom - 92, r.left + cw, r.bottom);
        CarKit.chip(cv, tmp, Str.get(R.string.hql_now), String.format(Locale.getDefault(), "%.2f", total), "g", CarKit.ACCENT, p);
        tmp.offset(cw + 14, 0);
        CarKit.chip(cv, tmp, Str.get(R.string.hql_peak), String.format(Locale.getDefault(), "%.2f", peak), "g", CarKit.AMBER, p);
    }

    /** Pico en un eje (side: 0 arriba, 1 derecha, 2 abajo, 3 izquierda) con su valor al lado. */
    private void peak(Canvas cv, float x, float y, double g, int side, Paint p) {
        if (g < 0.03) return;
        android.graphics.Path path = tri;
        path.rewind();
        float a = 11;
        switch (side) {
            case 0:
            case 2:
                path.moveTo(x - a, y);
                path.lineTo(x + a, y);
                path.lineTo(x, y + (side == 0 ? -a : a));
                break;
            default:
                path.moveTo(x, y - a);
                path.lineTo(x, y + a);
                path.lineTo(x + (side == 1 ? a : -a), y);
        }
        path.close();
        p.setColor(CarKit.AMBER);
        cv.drawPath(path, p);
        String v = String.format(Locale.getDefault(), "%.2f", g);
        switch (side) {
            case 0:
                CarKit.text(cv, v, x + 16, y - 2, 22, CarKit.AMBER, CarKit.MEDIUM, p, Paint.Align.LEFT);
                break;
            case 2:
                CarKit.text(cv, v, x + 16, y + 20, 22, CarKit.AMBER, CarKit.MEDIUM, p, Paint.Align.LEFT);
                break;
            case 1:
                CarKit.text(cv, v, x + 4, y + 34, 22, CarKit.AMBER, CarKit.MEDIUM, p, Paint.Align.CENTER);
                break;
            default:
                CarKit.text(cv, v, x - 4, y + 34, 22, CarKit.AMBER, CarKit.MEDIUM, p, Paint.Align.CENTER);
        }
    }

    private static double clamp(double g) {
        return Math.max(-MAX_G, Math.min(MAX_G, g));
    }

    // ------------------------------------------------------------------ inclinómetro

    private void paintIncl(Canvas cv, RectF r, Paint p) {
        double pitch = Math.toDegrees(Math.atan(s.gradePct / 100));
        double roll = s.latG * ROLL_DEG_PER_G;
        float w = r.width() / 2;
        dial(cv, r.left + w / 2, r.top, w, (float) pitch, true, p);
        dial(cv, r.left + w * 1.5f, r.top, w, (float) roll, false, p);
    }

    private void dial(Canvas cv, float cx, float top, float w, float deg, boolean side, Paint p) {
        float rad = Math.min(w / 2 - 12, Math.min(84, (inclCard.getHeight() - 205) / 2f));
        float cy = top + rad + 4;
        p.setColor(CarKit.SURFACE_HI);
        cv.drawCircle(cx, cy, rad, p);
        // Marcas cada 5° (±15°) en la parte de arriba.
        p.setColor(CarKit.MUTED);
        for (int d = -15; d <= 15; d += 5) {
            double a = Math.toRadians(-90 + d * 3);
            float r0 = rad - (d == 0 ? 14 : 8);
            cv.drawLine(cx + (float) Math.cos(a) * r0, cy + (float) Math.sin(a) * r0,
                    cx + (float) Math.cos(a) * rad, cy + (float) Math.sin(a) * rad, p);
        }
        Bitmap b = side ? carSide() : carFront();
        float bw = rad * 1.45f;
        float bh = bw * b.getHeight() / b.getWidth();
        cv.save();
        // Exagerado ×2 para que se vea (la cifra es la real).
        cv.rotate(side ? -deg * 2 : deg * 2, cx, cy);
        p.setColor(CarKit.alpha(CarKit.ACCENT, 0.5f));
        cv.drawRect(cx - rad + 8, cy + bh / 2 - 1, cx + rad - 8, cy + bh / 2 + 2, p);
        tmp.set(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2);
        cv.drawBitmap(b, null, tmp, bmp);
        cv.restore();
        CarKit.label(cv, Str.get(side ? R.string.hql_pitch : R.string.hql_roll), cx, cy + rad + 30, CarKit.FAINT, p, Paint.Align.CENTER);
        CarKit.number(cv, String.format(Locale.getDefault(), "%+.1f", deg), "°", cx, cy + rad + 72, 36, CarKit.TEXT, CarKit.MEDIUM, p, Paint.Align.CENTER);
        String sub = side ? String.format(Locale.getDefault(), "%+.1f %%", s.gradePct) : Str.get(R.string.hql_estimated);
        CarKit.text(cv, sub, cx, cy + rad + 100, 21, CarKit.FAINT, CarKit.REGULAR, p, Paint.Align.CENTER);
    }

    /** El coche de perfil y de frente, dibujados una vez (CarArt) y reutilizados girados. */
    private Bitmap carSide() {
        if (carSide == null) {
            carSide = Bitmap.createBitmap(240, 110, Bitmap.Config.ARGB_8888);
            CarArt.side(new Canvas(carSide), new RectF(4, 4, 236, 106), new Paint(Paint.ANTI_ALIAS_FLAG));
        }
        return carSide;
    }

    private Bitmap carFront() {
        if (carFront == null) {
            carFront = Bitmap.createBitmap(200, 180, Bitmap.Config.ARGB_8888);
            CarArt.front(new Canvas(carFront), new RectF(0, 0, 200, 180), new Paint(Paint.ANTI_ALIAS_FLAG));
        }
        return carFront;
    }

    // ------------------------------------------------------------------ suavidad y viaje

    private void paintSmooth(Canvas cv, RectF r, Paint p) {
        int score = s.smoothScore;
        float rad = Math.min(r.height() / 2f - 8, 80);
        float cx = r.left + rad + 6;
        float cy = r.centerY();
        oval.set(cx - rad, cy - rad, cx + rad, cy + rad);
        int color = score >= 85 ? CarKit.GREEN : score >= 70 ? CarKit.ACCENT : score >= 50 ? CarKit.AMBER : CarKit.RED;
        CarKit.ring(cv, oval, 135, 270, score < 0 ? 0 : score / 100f, 16, CarKit.SURFACE_TOP, color, p);
        CarKit.text(cv, score < 0 ? "—" : String.valueOf(score), cx, cy + 22, 62, score < 0 ? CarKit.MUTED : CarKit.TEXT, CarKit.REGULAR, p, Paint.Align.CENTER);
        float tx = cx + rad + 26;
        int tw = (int) (r.right - tx);
        if (score < 0) {
            how.draw(cv, Str.get(R.string.hql_smooth_wait), tx, cy - 30, tw, false);
            return;
        }
        int word = score >= 85 ? R.string.hql_smooth_excellent : score >= 70 ? R.string.hql_smooth_good
                : score >= 50 ? R.string.hql_smooth_fair : R.string.hql_smooth_rough;
        CarKit.text(cv, Str.get(word), tx, r.top + 40, 32, color, CarKit.MEDIUM, p, Paint.Align.LEFT);
        if (s.smoothRecent >= 0) {
            CarKit.text(cv, Str.get(R.string.hql_smooth_recent, s.smoothRecent), tx, r.top + 78, 23, CarKit.DIM, CarKit.REGULAR, p, Paint.Align.LEFT);
        }
        how.draw(cv, Str.get(R.string.hql_smooth_how), tx, r.top + 98, tw, false);
    }

}
