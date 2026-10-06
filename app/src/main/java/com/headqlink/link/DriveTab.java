package com.headqlink.link;

import com.andrerinas.openheadunit.R;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.widget.LinearLayout;

import com.andrerinas.openheadunit.aap.NavTap;

import java.util.Locale;

/**
 * Pestaña "Conducción": la próxima maniobra de Android Auto en grande (flecha, distancia con una barra que se va
 * vaciando hasta el giro, calle, carriles y la maniobra de después), avisos de radar y de sol de cara, destino y
 * llegada; a la derecha, la velocidad con la señal del límite de la vía, el rumbo en una cinta de brújula, altitud,
 * pendiente y el sol (de la salida a la puesta). Datos: NavTap (o la demostración), CarSensors y RoadInfo.
 */
final class DriveTab implements CarScreen {
    /** Refresco: 4 por segundo basta para la distancia y la velocidad; lo que no cambia no se redibuja. */
    private static final long TICK_MS = 250;

    private CarSensors sensors;
    private RoadInfo road;
    private boolean running;
    private NavTap.Info nav = new NavTap.Info();
    private CarSensors.Snapshot s = new CarSensors.Snapshot();
    private RoadInfo.State rs = new RoadInfo.State();
    private CarKit.Card turnCard;
    private CarKit.Card speedCard;
    private CarKit.Card headingCard;
    private CarKit.Card altCard;
    private CarKit.Card slopeCard;
    private CarKit.Card sunCard;
    private final RectF tmp = new RectF();
    // Distancia al inicio del paso actual (para la barra que se vacía) y el paso que se sigue.
    private int stepStart = -1;
    private int lastStepM = -1;
    private int lastKind = -1;
    private String lastNext;
    private String[] compass;
    private final CarKit.Para hint = new CarKit.Para(26, CarKit.DIM, CarKit.REGULAR);

    @Override
    public View create(Host h) {
        Context c = h.context();
        sensors = CarSensors.start(c);
        road = RoadInfo.start(c);
        compass = Str.get(R.string.hql_compass_short).split(",");

        LinearLayout row = CarKit.row(c);
        turnCard = CarKit.add(row, new CarKit.Card(c, Str.get(R.string.hql_drive_navigation), this::paintTurn), 1.5f, 0);
        LinearLayout right = CarKit.add(row, CarKit.col(c), 1f, 0);
        speedCard = CarKit.add(right, new CarKit.Card(c, Str.get(R.string.hql_speed), this::paintSpeed), 0, 300);
        headingCard = CarKit.add(right, new CarKit.Card(c, null, this::paintHeading), 0, 112);
        LinearLayout pair = CarKit.add(right, CarKit.row(c), 0, 150);
        altCard = CarKit.add(pair, new CarKit.Card(c, Str.get(R.string.hql_altitude), this::paintAltitude), 1f, 0);
        slopeCard = CarKit.add(pair, new CarKit.Card(c, Str.get(R.string.hql_grade), this::paintSlope), 1f, 0);
        sunCard = CarKit.add(right, new CarKit.Card(c, Str.get(R.string.hql_sun), this::paintSun), 1f, 0);
        running = true;
        tick();
        return CarKit.page(c, row);
    }

    private void tick() {
        if (!running) return;
        nav = DemoMode.navInfo();
        s = sensors.snapshot();
        rs = road.state();
        trackStep();
        turnCard.invalidate();
        speedCard.invalidate();
        headingCard.invalidate();
        altCard.invalidate();
        slopeCard.invalidate();
        sunCard.invalidate();
        turnCard.postDelayed(this::tick, TICK_MS);
    }

    /**
     * La barra de la maniobra se vacía desde la distancia a la que empezó el paso. Sin historia (recién abierta la
     * pestaña), se toma el siguiente escalón redondo (300 m, 1 km, 3 km…): sigue vaciándose de forma continua.
     */
    private void trackStep() {
        int m = nav.active ? nav.stepMeters : -1;
        String next = nav.nextRoad;
        boolean changed = m < 0 || lastStepM < 0 || m > lastStepM + 60 || nav.kind != lastKind
                || (next != null && !next.equals(lastNext));
        if (changed) stepStart = m < 0 ? -1 : roundUp(m);
        lastStepM = m;
        lastKind = nav.kind;
        lastNext = next;
    }

    private static int roundUp(int m) {
        int[] steps = {100, 300, 500, 1000, 2000, 3000, 5000, 10000, 20000, 50000};
        for (int st : steps) if (m <= st) return st;
        return m;
    }

    @Override
    public void destroy() {
        running = false;
        CarSensors.stop();
    }

    // ------------------------------------------------------------------ maniobra

    private void paintTurn(Canvas cv, RectF r, Paint p) {
        if (!nav.active) {
            float cy = r.centerY() - 60;
            p.setColor(CarKit.SURFACE_HI);
            cv.drawCircle(r.centerX(), cy, 96, p);
            CarIcons.route(cv, r.centerX(), cy, 110, CarKit.MUTED, p);
            CarKit.text(cv, Str.get(R.string.hql_no_navigation), r.centerX(), cy + 170, 38, CarKit.TEXT, CarKit.MEDIUM, p, Paint.Align.CENTER);
            hint.draw(cv, Str.get(R.string.hql_no_navigation_hint), r.left + 60, cy + 196, (int) r.width() - 120, true);
            return;
        }
        float x0 = r.left;
        float y0 = r.top;
        // Flecha en su baldosa.
        tmp.set(x0, y0, x0 + 236, y0 + 236);
        p.setColor(CarKit.SURFACE_TOP);
        cv.drawRoundRect(tmp, 28, 28, p);
        CarIcons.maneuver(cv, nav.kind, nav.angle, tmp.centerX(), tmp.centerY(), 176, CarKit.ACCENT, p);
        // Distancia, calle y detalle.
        float tx = x0 + 276;
        String[] d = distance(nav.stepMeters, nav.stepDisplay);
        CarKit.number(cv, d[0], d[1], tx, y0 + 104, 112, CarKit.TEXT, CarKit.REGULAR, p, Paint.Align.LEFT);
        String street = nav.nextRoad != null ? nav.nextRoad : nav.road != null ? nav.road : "";
        p.setTypeface(CarKit.MEDIUM);
        p.setTextSize(40);
        CarKit.text(cv, CarKit.ellipsize(street, r.right - tx, p), tx, y0 + 168, 40, CarKit.TEXT, CarKit.MEDIUM, p, Paint.Align.LEFT);
        String detail = "";
        if (nav.kind == NavTap.KIND_ROUNDABOUT && nav.roundaboutExit > 0) detail = Str.get(R.string.hql_roundabout_exit, nav.roundaboutExit);
        if (nav.stepSeconds > 0 && nav.stepSeconds < 600) {
            String in = Str.get(R.string.hql_drive_in_sec, (int) nav.stepSeconds);
            detail = detail.isEmpty() ? in : detail + " · " + in;
        }
        CarKit.text(cv, detail, tx, y0 + 214, 27, CarKit.DIM, CarKit.REGULAR, p, Paint.Align.LEFT);
        // Barra que se vacía hasta la maniobra.
        if (stepStart > 0 && nav.stepMeters >= 0) {
            tmp.set(x0, y0 + 256, r.right, y0 + 272);
            CarKit.bar(cv, tmp, nav.stepMeters / (float) stepStart, CarKit.ACCENT, p);
        }
        float y = y0 + 300;
        // Carriles.
        boolean[] lanes = nav.lanes;
        if (lanes != null && lanes.length > 0) {
            float lw = 62;
            for (int i = 0; i < lanes.length; i++) {
                float lx = x0 + i * (lw + 12);
                tmp.set(lx, y, lx + lw, y + 76);
                p.setColor(lanes[i] ? CarKit.alpha(CarKit.ACCENT, 0.18f) : CarKit.SURFACE_HI);
                cv.drawRoundRect(tmp, 14, 14, p);
                if (lanes[i]) {
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(2);
                    p.setColor(CarKit.ACCENT);
                    cv.drawRoundRect(tmp, 14, 14, p);
                    p.setStyle(Paint.Style.FILL);
                }
                CarIcons.lane(cv, tmp.centerX(), tmp.centerY(), 46, lanes[i] ? CarKit.ACCENT : CarKit.MUTED, p);
            }
            y += 100;
        }
        // La maniobra de después, en una línea.
        if (nav.thenKind >= 0) {
            float lw = CarKit.label(cv, Str.get(R.string.hql_drive_then), x0, y + 33, p);
            tmp.set(x0 + lw + 18, y, x0 + lw + 70, y + 52);
            p.setColor(CarKit.SURFACE_HI);
            cv.drawRoundRect(tmp, 12, 12, p);
            CarIcons.maneuver(cv, nav.thenKind, nav.thenAngle, tmp.centerX(), tmp.centerY(), 40, CarKit.DIM, p);
            if (nav.thenRoad != null) {
                float tx2 = tmp.right + 16;
                p.setTypeface(CarKit.REGULAR);
                p.setTextSize(29);
                CarKit.text(cv, CarKit.ellipsize(nav.thenRoad, r.right - tx2, p), tx2, y + 36, 29, CarKit.DIM, CarKit.REGULAR, p, Paint.Align.LEFT);
            }
        }
        // Avisos y destino abajo.
        float b = r.bottom;
        String alert = null;
        boolean camera = false;
        if (rs.cameraM >= 0) {
            camera = true;
            alert = Str.get(R.string.hql_camera_at, Math.round(rs.cameraM / 10.0) * 10.0) + (rs.cameraLimit > 0 ? " · " + rs.cameraLimit + " km/h" : "");
        } else if (rs.sunGlare) {
            alert = Str.get(R.string.hql_sun_glare);
        }
        if (alert != null) {
            tmp.set(x0, b - 180, r.right, b - 116);
            p.setColor(CarKit.alpha(CarKit.AMBER, 0.13f));
            cv.drawRoundRect(tmp, 18, 18, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2);
            p.setColor(CarKit.alpha(CarKit.AMBER, 0.55f));
            cv.drawRoundRect(tmp, 18, 18, p);
            p.setStyle(Paint.Style.FILL);
            if (camera) CarIcons.camera(cv, x0 + 44, tmp.centerY(), 38, CarKit.AMBER, p);
            else CarIcons.sun(cv, x0 + 44, tmp.centerY(), 40, CarKit.AMBER, p);
            p.setTypeface(CarKit.MEDIUM);
            p.setTextSize(28);
            CarKit.text(cv, CarKit.ellipsize(alert, tmp.width() - 110, p), x0 + 84, tmp.centerY() + 10, 28, CarKit.AMBER, CarKit.MEDIUM, p, Paint.Align.LEFT);
        }
        // Destino: bandera, nombre y lo que falta; la hora de llegada, grande a la derecha.
        p.setColor(CarKit.OUTLINE);
        cv.drawRect(x0, b - 96, r.right, b - 94, p);
        CarIcons.flag(cv, x0 + 22, b - 46, 40, CarKit.ACCENT, p);
        float etaW = 0;
        if (nav.eta != null) {
            CarKit.label(cv, Str.get(R.string.hql_drive_arrival), r.right, b - 62, CarKit.FAINT, p, Paint.Align.RIGHT);
            etaW = CarKit.text(cv, nav.eta, r.right, b - 10, 46, CarKit.TEXT, CarKit.MEDIUM, p, Paint.Align.RIGHT);
        }
        String dest = nav.destination != null ? nav.destination : "";
        p.setTypeface(CarKit.MEDIUM);
        p.setTextSize(30);
        CarKit.text(cv, CarKit.ellipsize(dest, r.right - x0 - 70 - etaW - 30, p), x0 + 58, b - 50, 30, CarKit.TEXT, CarKit.MEDIUM, p, Paint.Align.LEFT);
        String rem = "";
        if (nav.remainingMeters >= 0) {
            rem = String.format(Locale.getDefault(), "%.0f km", nav.remainingMeters / 1000.0);
            if (nav.remainingSeconds >= 0) rem += " · " + duration(nav.remainingSeconds);
        }
        CarKit.text(cv, rem, x0 + 58, b - 12, 25, CarKit.DIM, CarKit.REGULAR, p, Paint.Align.LEFT);
    }

    /** "450", "m" / "1,2", "km": la cifra y la unidad por separado (para dibujar la unidad pequeña). */
    static String[] distance(int meters, String display) {
        if (meters < 0) {
            if (display == null) return new String[]{"", ""};
            int sp = display.lastIndexOf(' ');
            return sp > 0 ? new String[]{display.substring(0, sp), display.substring(sp + 1)} : new String[]{display, ""};
        }
        if (meters >= 10000) return new String[]{String.format(Locale.getDefault(), "%.0f", meters / 1000.0), "km"};
        if (meters >= 1000) return new String[]{String.format(Locale.getDefault(), "%.1f", meters / 1000.0), "km"};
        int rounded = meters >= 300 ? Math.round(meters / 50f) * 50 : Math.round(meters / 10f) * 10;
        return new String[]{String.valueOf(rounded), "m"};
    }

    static String duration(long sec) {
        if (sec < 0) return "";
        long m = Math.max(0, Math.round(sec / 60.0));
        return m >= 60 ? String.format(Locale.getDefault(), "%d h %02d min", m / 60, m % 60) : m + " min";
    }

    // ------------------------------------------------------------------ velocidad

    private void paintSpeed(Canvas cv, RectF r, Paint p) {
        int limit = rs.limitKmh;
        double v = s.speedKmh;
        int color = CarKit.TEXT;
        if (limit > 0 && v > limit * 1.1 + 2) color = CarKit.RED;
        else if (limit > 0 && v > limit + 2) color = CarKit.AMBER;
        CarKit.number(cv, String.format(Locale.getDefault(), "%.0f", v), "km/h", r.left, r.top + 150, 156, color, CarKit.REGULAR, p, Paint.Align.LEFT);
        if (limit > 0) {
            float cx = r.right - 82;
            float cy = r.top + 78;
            p.setColor(0xFFFFFFFF);
            cv.drawCircle(cx, cy, 80, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(15);
            p.setColor(0xFFD93025);
            cv.drawCircle(cx, cy, 72, p);
            p.setStyle(Paint.Style.FILL);
            CarKit.text(cv, String.valueOf(limit), cx, cy + 24, limit >= 100 ? 60 : 68, 0xFF15181C, CarKit.MEDIUM, p, Paint.Align.CENTER);
            if (rs.limitEstimated) CarKit.label(cv, Str.get(R.string.hql_estimated), cx, cy + 112, CarKit.FAINT, p, Paint.Align.CENTER);
        }
        String line = !s.gps ? Str.get(R.string.hql_waiting_phone_gps) : rs.roadName;
        p.setTypeface(CarKit.REGULAR);
        p.setTextSize(27);
        CarKit.text(cv, CarKit.ellipsize(line, r.width() - (limit > 0 ? 190 : 0), p), r.left, r.bottom - 6, 27,
                s.gps ? CarKit.DIM : CarKit.RED, CarKit.REGULAR, p, Paint.Align.LEFT);
    }

    // ------------------------------------------------------------------ rumbo

    private void paintHeading(Canvas cv, RectF r, Paint p) {
        double hdg = ((s.headingDeg % 360) + 360) % 360;
        CarKit.label(cv, Str.get(R.string.hql_heading), r.left, r.top + 14, p);
        String card = compass[(int) Math.round(hdg / 45) % 8];
        CarKit.number(cv, card, String.format(Locale.getDefault(), "%.0f°", hdg), r.left, r.bottom - 2, 40, CarKit.TEXT, CarKit.MEDIUM, p, Paint.Align.LEFT);
        // Cinta de brújula: ±75° a lo ancho, marcas cada 15° y letras cada 45°.
        float tx0 = r.left + 170;
        float tx1 = r.right;
        float mid = (tx0 + tx1) / 2;
        float pxPerDeg = (tx1 - tx0) / 150f;
        cv.save();
        cv.clipRect(tx0, r.top - 10, tx1, r.bottom + 10);
        int first = (int) Math.floor((hdg - 80) / 15) * 15;
        for (int d = first; d <= hdg + 80; d += 15) {
            float x = (float) (mid + (d - hdg) * pxPerDeg);
            int norm = ((d % 360) + 360) % 360;
            boolean major = norm % 45 == 0;
            float fade = 1f - Math.min(1f, Math.abs(x - mid) / ((tx1 - tx0) / 2f)) * 0.75f;
            p.setColor(CarKit.alpha(major ? CarKit.DIM : CarKit.MUTED, fade));
            cv.drawRect(x - 1.5f, r.bottom - (major ? 22 : 12), x + 1.5f, r.bottom, p);
            if (major) {
                CarKit.text(cv, compass[norm / 45], x, r.top + 26, 24, CarKit.alpha(norm == 0 ? CarKit.RED : CarKit.TEXT, fade),
                        CarKit.MEDIUM, p, Paint.Align.CENTER);
            }
        }
        cv.restore();
        // Marca central.
        p.setColor(CarKit.ACCENT);
        cv.drawRect(mid - 2, r.top + 34, mid + 2, r.bottom, p);
    }

    // ------------------------------------------------------------------ altitud, pendiente y sol

    private void paintAltitude(Canvas cv, RectF r, Paint p) {
        CarIcons.mountain(cv, r.left + 22, r.bottom - 22, 44, CarKit.MUTED, p);
        String v = Double.isNaN(s.altitudeM) ? "—" : String.format(Locale.getDefault(), "%.0f", s.altitudeM);
        CarKit.number(cv, v, "m", r.left + 58, r.bottom - 4, 50, CarKit.TEXT, CarKit.REGULAR, p, Paint.Align.LEFT);
    }

    private void paintSlope(Canvas cv, RectF r, Paint p) {
        double g = s.gradePct;
        int color = g > 2.5 ? CarKit.AMBER : g < -2.5 ? CarKit.GREEN : CarKit.TEXT;
        // Cuña con la inclinación (exagerada ×3 para que se vea).
        float wx = r.left + 4;
        float wy = r.bottom - 6;
        float len = 40;
        double a = Math.atan(Math.max(-0.3, Math.min(0.3, g / 100 * 3)));
        p.setColor(CarKit.alpha(color, 0.35f));
        android.graphics.Path path = slopePath;
        path.rewind();
        path.moveTo(wx, wy);
        path.lineTo(wx + len, wy);
        path.lineTo(wx + len, (float) (wy - Math.tan(a) * len));
        path.close();
        cv.drawPath(path, p);
        CarKit.number(cv, String.format(Locale.getDefault(), "%+.1f", g), "%", r.left + 58, r.bottom - 4, 50, color, CarKit.REGULAR, p, Paint.Align.LEFT);
    }

    private final android.graphics.Path slopePath = new android.graphics.Path();

    private void paintSun(Canvas cv, RectF r, Paint p) {
        long now = DemoMode.wallClockMs();
        long rise = rs.sunriseMs;
        long set = rs.sunsetMs;
        if (rise <= 0 || set <= rise) {
            CarKit.text(cv, rs.sunset.isEmpty() ? "—" : Str.get(R.string.hql_sunset, rs.sunset), r.left, r.centerY() + 10, 28, CarKit.DIM, CarKit.REGULAR, p, Paint.Align.LEFT);
            return;
        }
        boolean day = now >= rise && now <= set;
        float frac = day ? (now - rise) / (float) (set - rise) : 0;
        // Arco del día: de la salida (izquierda) a la puesta (derecha).
        float ax0 = r.left + 6;
        float ax1 = r.left + r.width() * 0.42f;
        float ah = r.height() * 0.8f;
        tmp.set(ax0, r.bottom - ah, ax1, r.bottom + ah);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(4);
        p.setColor(CarKit.SURFACE_TOP);
        cv.drawArc(tmp, 180, 180, false, p);
        if (day) {
            p.setColor(CarKit.alpha(CarKit.AMBER, 0.7f));
            cv.drawArc(tmp, 180, 180 * frac, false, p);
        }
        p.setStyle(Paint.Style.FILL);
        p.setColor(CarKit.OUTLINE);
        cv.drawRect(ax0 - 4, r.bottom - 1, ax1 + 4, r.bottom + 1, p);
        if (day) {
            double ang = Math.PI * (1 - frac);
            float sx = (float) (tmp.centerX() + Math.cos(ang) * tmp.width() / 2);
            float sy = (float) (tmp.centerY() - Math.sin(ang) * tmp.height() / 2);
            CarIcons.sun(cv, sx, sy, 34, CarKit.AMBER, p);
        }
        float tx = ax1 + 24;
        java.text.DateFormat f = android.text.format.DateFormat.getTimeFormat(sunCard.getContext());
        if (day) {
            String in = duration((set - now) / 1000);
            CarKit.text(cv, Str.get(R.string.hql_sunset, rs.sunset), tx, r.top + 30, 30, CarKit.TEXT, CarKit.MEDIUM, p, Paint.Align.LEFT);
            CarKit.text(cv, Str.get(R.string.hql_sun_in, in), tx, r.top + 68, 25, CarKit.DIM, CarKit.REGULAR, p, Paint.Align.LEFT);
        } else {
            CarKit.text(cv, Str.get(R.string.hql_sunrise_at, f.format(new java.util.Date(rise + (now > set ? 86_400_000L : 0)))),
                    tx, r.top + 30, 30, CarKit.TEXT, CarKit.MEDIUM, p, Paint.Align.LEFT);
        }
    }
}
