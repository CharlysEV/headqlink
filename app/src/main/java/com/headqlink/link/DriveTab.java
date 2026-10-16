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

        turnCard = new CarKit.Card(c, Str.get(R.string.hql_drive_navigation), this::paintTurn);
        speedCard = new CarKit.Card(c, Str.get(R.string.hql_speed), this::paintSpeed);
        headingCard = new CarKit.Card(c, null, this::paintHeading);
        altCard = new CarKit.Card(c, Str.get(R.string.hql_altitude), this::paintAltitude);
        slopeCard = new CarKit.Card(c, Str.get(R.string.hql_grade), this::paintSlope);
        sunCard = new CarKit.Card(c, Str.get(R.string.hql_sun), this::paintSun);
        LinearLayout top;
        if (CarKit.narrow) {
            // Pantalla partida: todo en una pantalla, sin deslizar. Navegación arriba, a lo ancho; Velocidad y Rumbo;
            // Altitud, Pendiente y Sol.
            top = CarKit.keep(CarKit.col(c));
            CarKit.add(top, turnCard, 1f, 0);
            LinearLayout r2 = CarKit.add(top, CarKit.row(c), 0, NARROW_ROW2_H);
            CarKit.add(r2, speedCard, 1f, 0);
            CarKit.add(r2, headingCard, 1f, 0);
            LinearLayout r3 = CarKit.add(top, CarKit.row(c), 0, NARROW_ROW3_H);
            CarKit.add(r3, altCard, 1f, 0);
            CarKit.add(r3, slopeCard, 1f, 0);
            CarKit.add(r3, sunCard, 1.5f, 0);
        } else {
            top = CarKit.row(c);
            CarKit.add(top, turnCard, 1.5f, 0);
            LinearLayout right = CarKit.add(top, CarKit.col(c), 1f, 0);
            CarKit.add(right, speedCard, 0, 300);
            CarKit.add(right, headingCard, 0, 112);
            LinearLayout pair = CarKit.add(right, CarKit.row(c), 0, 150);
            CarKit.add(pair, altCard, 1f, 0);
            CarKit.add(pair, slopeCard, 1f, 0);
            CarKit.add(right, sunCard, 1f, 0);
        }
        running = true;
        tick();
        return CarKit.page(c, top);
    }

    /** La próxima incidencia de la DGT sale en Conducción desde esta distancia. */
    static final double TRAFFIC_SHOW_KM = 10;

    /** Pantalla partida: alto de la fila de Velocidad y Rumbo y de la de Altitud, Pendiente y Sol. */
    static final int NARROW_ROW2_H = 150;
    static final int NARROW_ROW3_H = 140;

    /** Escala de un dibujo hecho para la pestaña ancha (alto o ancho de referencia) en una tarjeta más pequeña. */
    static float fit(float have, float ref) {
        return Math.max(0.45f, Math.min(1f, have / ref));
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
        // A escala en una tarjeta más baja (pantalla partida): todo lo de abajo cabe; 1 en la ancha.
        float k = r.height() >= 600 ? 1f : fit(r.height(), 700);
        if (!nav.active) {
            float cy = r.centerY() - 60 * k;
            p.setColor(CarKit.SURFACE_HI);
            cv.drawCircle(r.centerX(), cy, 96 * k, p);
            CarIcons.route(cv, r.centerX(), cy, 110 * k, CarKit.MUTED, p);
            CarKit.text(cv, Str.get(R.string.hql_no_navigation), r.centerX(), cy + 170 * k, 38 * Math.max(k, 0.75f), CarKit.TEXT, CarKit.MEDIUM, p, Paint.Align.CENTER);
            if (k > 0.7f) hint.draw(cv, Str.get(R.string.hql_no_navigation_hint), r.left + 60, cy + 196, (int) r.width() - 120, true);
            return;
        }
        float x0 = r.left;
        float y0 = r.top;
        // Flecha en su baldosa.
        tmp.set(x0, y0, x0 + 236 * k, y0 + 236 * k);
        p.setColor(CarKit.SURFACE_TOP);
        cv.drawRoundRect(tmp, 28 * k, 28 * k, p);
        CarIcons.maneuver(cv, nav.kind, nav.angle, tmp.centerX(), tmp.centerY(), 176 * k, CarKit.ACCENT, p);
        // Distancia, calle y detalle.
        float tx = x0 + 276 * k;
        String[] d = distance(nav.stepMeters, nav.stepDisplay);
        CarKit.number(cv, d[0], d[1], tx, y0 + 104 * k, 112 * k, CarKit.TEXT, CarKit.REGULAR, p, Paint.Align.LEFT);
        String street = nav.nextRoad != null ? nav.nextRoad : nav.road != null ? nav.road : "";
        p.setTypeface(CarKit.MEDIUM);
        p.setTextSize(40 * k);
        CarKit.text(cv, CarKit.ellipsize(street, r.right - tx, p), tx, y0 + 168 * k, 40 * k, CarKit.TEXT, CarKit.MEDIUM, p, Paint.Align.LEFT);
        String detail = "";
        if (nav.kind == NavTap.KIND_ROUNDABOUT && nav.roundaboutExit > 0) detail = Str.get(R.string.hql_roundabout_exit, nav.roundaboutExit);
        if (nav.stepSeconds > 0 && nav.stepSeconds < 600) {
            String in = Str.get(R.string.hql_drive_in_sec, (int) nav.stepSeconds);
            detail = detail.isEmpty() ? in : detail + " · " + in;
        }
        CarKit.text(cv, detail, tx, y0 + 214 * k, 27 * Math.max(k, 0.7f), CarKit.DIM, CarKit.REGULAR, p, Paint.Align.LEFT);
        // Barra que se vacía hasta la maniobra.
        if (stepStart > 0 && nav.stepMeters >= 0) {
            tmp.set(x0, y0 + 256 * k, r.right, y0 + 272 * k);
            CarKit.bar(cv, tmp, nav.stepMeters / (float) stepStart, CarKit.ACCENT, p);
        }
        float y = y0 + 300 * k;
        // Carriles.
        boolean[] lanes = nav.lanes;
        if (lanes != null && lanes.length > 0) {
            float lw = 62 * k;
            for (int i = 0; i < lanes.length; i++) {
                float lx = x0 + i * (lw + 12 * k);
                tmp.set(lx, y, lx + lw, y + 76 * k);
                p.setColor(lanes[i] ? CarKit.alpha(CarKit.ACCENT, 0.18f) : CarKit.SURFACE_HI);
                cv.drawRoundRect(tmp, 14, 14, p);
                if (lanes[i]) {
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(2);
                    p.setColor(CarKit.ACCENT);
                    cv.drawRoundRect(tmp, 14, 14, p);
                    p.setStyle(Paint.Style.FILL);
                }
                CarIcons.lane(cv, tmp.centerX(), tmp.centerY(), 46 * k, lanes[i] ? CarKit.ACCENT : CarKit.MUTED, p);
            }
            y += 100 * k;
        }
        // La maniobra de después, en una línea.
        if (nav.thenKind >= 0) {
            float lw = CarKit.label(cv, Str.get(R.string.hql_drive_then), x0, y + 33 * k, p);
            tmp.set(x0 + lw + 18, y, x0 + lw + 18 + 52 * k, y + 52 * k);
            p.setColor(CarKit.SURFACE_HI);
            cv.drawRoundRect(tmp, 12, 12, p);
            CarIcons.maneuver(cv, nav.thenKind, nav.thenAngle, tmp.centerX(), tmp.centerY(), 40 * k, CarKit.DIM, p);
            if (nav.thenRoad != null) {
                float tx2 = tmp.right + 16;
                p.setTypeface(CarKit.REGULAR);
                p.setTextSize(29 * Math.max(k, 0.75f));
                CarKit.text(cv, CarKit.ellipsize(nav.thenRoad, r.right - tx2, p), tx2, y + 36 * k, 29 * Math.max(k, 0.75f), CarKit.DIM, CarKit.REGULAR, p, Paint.Align.LEFT);
            }
        }
        // Avisos y destino abajo.
        float b = r.bottom;
        String alert = null;
        boolean camera = false;
        int trafficColor = 0;
        if (rs.cameraM >= 0) {
            camera = true;
            alert = Str.get(R.string.hql_camera_at, Math.round(rs.cameraM / 10.0) * 10.0) + (rs.cameraLimit > 0 ? " · " + rs.cameraLimit + " km/h" : "");
        } else if (!DgtTraffic.ahead().isEmpty() && DgtTraffic.ahead().get(0).km <= TRAFFIC_SHOW_KM) {
            // La próxima incidencia de la DGT en la ruta: «Obras · carril cortado a 3,2 km».
            DgtTraffic.Ahead a = DgtTraffic.ahead().get(0);
            alert = DgtTraffic.title(a.inc) + " " + Str.get(R.string.hql_tr_at, DgtTraffic.kmText(a.km));
            trafficColor = DgtTraffic.color(a.inc);
        } else if (rs.sunGlare) {
            alert = Str.get(R.string.hql_sun_glare);
        }
        if (alert != null) {
            tmp.set(x0, b - 180 * k, r.right, b - 116 * k);
            p.setColor(CarKit.alpha(CarKit.AMBER, 0.13f));
            cv.drawRoundRect(tmp, 18 * k, 18 * k, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2);
            p.setColor(CarKit.alpha(CarKit.AMBER, 0.55f));
            cv.drawRoundRect(tmp, 18 * k, 18 * k, p);
            p.setStyle(Paint.Style.FILL);
            if (camera) CarIcons.camera(cv, x0 + 44 * k, tmp.centerY(), 38 * k, CarKit.AMBER, p);
            else if (trafficColor != 0) CarIcons.warning(cv, x0 + 44 * k, tmp.centerY(), 40 * k, trafficColor, p);
            else CarIcons.sun(cv, x0 + 44 * k, tmp.centerY(), 40 * k, CarKit.AMBER, p);
            p.setTypeface(CarKit.MEDIUM);
            p.setTextSize(28 * Math.max(k, 0.75f));
            CarKit.text(cv, CarKit.ellipsize(alert, tmp.width() - 110 * k, p), x0 + 84 * k, tmp.centerY() + 10 * k, 28 * Math.max(k, 0.75f), CarKit.AMBER, CarKit.MEDIUM, p, Paint.Align.LEFT);
        }
        // Destino: bandera, nombre y lo que falta; la hora de llegada, grande a la derecha.
        p.setColor(CarKit.OUTLINE);
        cv.drawRect(x0, b - 96 * k, r.right, b - 96 * k + 2, p);
        CarIcons.flag(cv, x0 + 22 * k, b - 46 * k, 40 * k, CarKit.ACCENT, p);
        float etaW = 0;
        if (nav.eta != null) {
            CarKit.label(cv, Str.get(R.string.hql_drive_arrival), r.right, b - 62 * k, CarKit.FAINT, p, Paint.Align.RIGHT);
            etaW = CarKit.text(cv, nav.eta, r.right, b - 10 * k, 46 * Math.max(k, 0.7f), CarKit.TEXT, CarKit.MEDIUM, p, Paint.Align.RIGHT);
        }
        String dest = nav.destination != null ? nav.destination : "";
        p.setTypeface(CarKit.MEDIUM);
        p.setTextSize(30 * Math.max(k, 0.75f));
        CarKit.text(cv, CarKit.ellipsize(dest, r.right - x0 - 70 * k - etaW - 30, p), x0 + 58 * k, b - 50 * k, 30 * Math.max(k, 0.75f), CarKit.TEXT, CarKit.MEDIUM, p, Paint.Align.LEFT);
        String rem = "";
        if (nav.remainingMeters >= 0) {
            rem = String.format(Locale.getDefault(), "%.0f km", nav.remainingMeters / 1000.0);
            if (nav.remainingSeconds >= 0) rem += " · " + duration(nav.remainingSeconds);
        }
        CarKit.text(cv, rem, x0 + 58 * k, b - 12 * k, 25 * Math.max(k, 0.75f), CarKit.DIM, CarKit.REGULAR, p, Paint.Align.LEFT);
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
        // Sin posiciones al día (móvil bloqueado, túnel…): «—» y el motivo, nunca la última velocidad congelada.
        String gpsNote = CarSensors.gpsNote(s);
        int color = CarKit.TEXT;
        if (gpsNote != null) color = CarKit.MUTED;
        else if (limit > 0 && v > limit * 1.1 + 2) color = CarKit.RED;
        else if (limit > 0 && v > limit + 2) color = CarKit.AMBER;
        // A escala en una tarjeta pequeña (pantalla partida); 1 en la ancha.
        float k = Math.min(fit(r.height(), 230), fit(r.width(), 520));
        boolean small = k < 0.8f;
        CarKit.number(cv, gpsNote != null ? "—" : String.format(Locale.getDefault(), "%.0f", v), "km/h", r.left,
                r.top + (small ? r.height() * 0.62f : 150), 156 * k, color, CarKit.REGULAR, p, Paint.Align.LEFT);
        if (limit > 0) {
            float rad = small ? Math.min(r.height() / 2f - 2, 80) : 80;
            float cx = r.right - rad - 2;
            float cy = small ? r.top + rad : r.top + 78;
            p.setColor(0xFFFFFFFF);
            cv.drawCircle(cx, cy, rad, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(15 * rad / 80);
            p.setColor(0xFFD93025);
            cv.drawCircle(cx, cy, rad * 0.9f, p);
            p.setStyle(Paint.Style.FILL);
            CarKit.text(cv, String.valueOf(limit), cx, cy + 24 * rad / 80, (limit >= 100 ? 60 : 68) * rad / 80, 0xFF15181C, CarKit.MEDIUM, p,
                    Paint.Align.CENTER);
            if (rs.limitEstimated && !small) CarKit.label(cv, Str.get(R.string.hql_estimated), cx, cy + 112, CarKit.FAINT, p, Paint.Align.CENTER);
        }
        // Pequeña: sin la línea de la vía (no cabe); la velocidad y la señal.
        if (small && gpsNote == null) return;
        String line = gpsNote != null ? gpsNote : rs.roadName;
        p.setTypeface(CarKit.REGULAR);
        p.setTextSize(small ? 20 : 27);
        CarKit.text(cv, CarKit.ellipsize(line, r.width() - (limit > 0 ? 190 * k : 0), p), r.left, r.bottom - 6, small ? 20 : 27,
                gpsNote == null ? CarKit.DIM : CarKit.RED, CarKit.REGULAR, p, Paint.Align.LEFT);
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
        float ax1 = r.left + r.width() * (r.width() < 420 ? 0.3f : 0.42f);
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
        float tx = ax1 + (r.width() < 420 ? 16 : 24);
        // Tarjeta estrecha (pantalla partida): letra más pequeña.
        float fs = r.width() < 420 ? 0.68f : 1f;
        java.text.DateFormat f = android.text.format.DateFormat.getTimeFormat(sunCard.getContext());
        if (day) {
            String in = duration((set - now) / 1000);
            CarKit.text(cv, Str.get(R.string.hql_sunset, rs.sunset), tx, r.top + 30 * fs, 30 * fs, CarKit.TEXT, CarKit.MEDIUM, p, Paint.Align.LEFT);
            CarKit.text(cv, Str.get(R.string.hql_sun_in, in), tx, r.top + 68 * fs, 25 * fs, CarKit.DIM, CarKit.REGULAR, p, Paint.Align.LEFT);
        } else {
            CarKit.text(cv, Str.get(R.string.hql_sunrise_at, f.format(new java.util.Date(rise + (now > set ? 86_400_000L : 0)))),
                    tx, r.top + 30 * fs, 30 * fs, CarKit.TEXT, CarKit.MEDIUM, p, Paint.Align.LEFT);
        }
    }
}
