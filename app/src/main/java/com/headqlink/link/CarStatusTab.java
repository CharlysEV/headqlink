package com.headqlink.link;

import com.andrerinas.openheadunit.R;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pestaña «Estado» de la sección Coche: los datos REALES del coche leídos de la nube de Leapmotor (CarCloud), siempre
 * con la edad del dato. Batería en un anillo con la autonomía, la carga (CA/CC, potencia y lo que falta) o la potencia
 * que sale o entra, las temperaturas; las cuatro presiones sobre el coche visto desde arriba (en ámbar la rueda baja o
 * con aviso) con las puertas y el maletero abiertos y el cierre; el cuentakilómetros y de cuándo es cada dato. Sin
 * cuenta (o caducada, desactivada…), un estado vacío que dice qué hacer en el móvil. Solo lectura.
 */
final class CarStatusTab implements CarScreen {
    private static final long TICK_MS = 1000;
    /** Dato viejo: más de 15 min (el coche duerme y la nube da lo último que supo). */
    private static final long OLD_MS = 15 * 60_000L;

    private Context ctx;
    private boolean running;
    private LinearLayout dataView;
    private CarKit.Card emptyCard;
    private final List<View> cards = new ArrayList<>();
    private CarCloud.Snapshot snap = CarCloud.Snapshot.of(CarCloud.State.NO_ACCOUNT);
    private long now;
    private final RectF tmp = new RectF();
    private final RectF car = new RectF();
    private final CarKit.Para body = new CarKit.Para(27, CarKit.DIM, CarKit.REGULAR);
    private final CarKit.Para note = new CarKit.Para(21, CarKit.FAINT, CarKit.REGULAR);
    private final CarKit.Para alert = new CarKit.Para(22, CarKit.AMBER, CarKit.MEDIUM);
    private Bitmap carTop;
    private Bitmap carFront;
    private final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);

    @Override
    public View create(Host h) {
        ctx = h.context();
        FrameLayout frame = new FrameLayout(ctx);
        dataView = CarKit.row(ctx);
        LinearLayout c1 = CarKit.add(dataView, CarKit.col(ctx), 1.02f, 0);
        LinearLayout c2 = CarKit.add(dataView, CarKit.col(ctx), 1.1f, 0);
        LinearLayout c3 = CarKit.add(dataView, CarKit.col(ctx), 0.88f, 0);
        cards.add(CarKit.add(c1, new CarKit.Card(ctx, Str.get(R.string.hql_cloud_battery), this::paintBattery), 1f, 0));
        cards.add(CarKit.add(c2, new CarKit.Card(ctx, Str.get(R.string.hql_cloud_tyres_doors), this::paintTyres), 1f, 0));
        cards.add(CarKit.add(c3, new CarKit.Card(ctx, Str.get(R.string.hql_car), this::paintCar), 1.12f, 0));
        cards.add(CarKit.add(c3, new CarKit.Card(ctx, Str.get(R.string.hql_cloud_data), this::paintData), 1f, 0));
        frame.addView(dataView, CarStyle.match());
        emptyCard = new CarKit.Card(ctx, null, this::paintEmpty);
        frame.addView(emptyCard, CarStyle.match());
        running = true;
        tick();
        return CarKit.page(ctx, frame);
    }

    private void tick() {
        if (!running) return;
        snap = CarCloud.snapshot();
        now = DemoMode.wallClockMs();
        boolean data = snap.hasData();
        dataView.setVisibility(data ? View.VISIBLE : View.GONE);
        emptyCard.setVisibility(data ? View.GONE : View.VISIBLE);
        if (data) {
            for (View v : cards) v.invalidate();
        } else {
            emptyCard.invalidate();
        }
        emptyCard.postDelayed(this::tick, TICK_MS);
    }

    @Override
    public void destroy() {
        running = false;
        if (carTop != null) carTop.recycle();
        if (carFront != null) carFront.recycle();
        carTop = null;
        carFront = null;
    }

    // ------------------------------------------------------------------ formatos

    private static String num(double v, int decimals) {
        if (Double.isNaN(v)) return "—";
        return String.format(Locale.getDefault(), decimals == 0 ? "%.0f" : decimals == 1 ? "%.1f" : "%.2f", v);
    }

    private static int levelColor(double pct) {
        return pct >= 20 ? CarKit.ACCENT : pct >= 8 ? CarKit.AMBER : CarKit.RED;
    }

    /** «1 h 05 min» o «25 min». */
    static String minutes(double min) {
        if (Double.isNaN(min) || min <= 0) return "";
        long m = Math.round(min);
        return m >= 60 ? String.format(Locale.getDefault(), "%d h %02d min", m / 60, m % 60)
                : String.format(Locale.getDefault(), "%d min", m);
    }

    private int ageColor() {
        return snap.ageMs(now) > OLD_MS ? CarKit.AMBER : CarKit.ACCENT;
    }

    // ------------------------------------------------------------------ batería

    private void paintBattery(Canvas cv, RectF r, Paint p) {
        LeapStatus s = snap.status;
        double soc = s.socBest();
        int col = Double.isNaN(soc) ? CarKit.MUTED : levelColor(soc);
        float rad = Math.min(r.width() * 0.34f, 146);
        float cx = r.centerX();
        float cy = r.top + rad + 8;
        tmp.set(cx - rad, cy - rad, cx + rad, cy + rad);
        CarKit.ring(cv, tmp, 135, 270, Double.isNaN(soc) ? 0 : (float) soc / 100f, 26, CarKit.SURFACE_TOP, col, p);
        CarKit.number(cv, num(soc, 0), "%", cx, cy + 22, 100, CarKit.TEXT, CarKit.LIGHT, p, Paint.Align.CENTER);
        CarKit.number(cv, num(s.rangeKm, 0), "km", cx, cy + 82, 40, CarKit.DIM, CarKit.MEDIUM, p, Paint.Align.CENTER);
        CarKit.text(cv, Str.get(R.string.hql_cloud_range), cx, cy + rad + 2, 20, CarKit.FAINT, CarKit.MEDIUM, p, Paint.Align.CENTER);
        float y = cy + rad + 44;
        CarKit.text(cv, CarCloud.realLabel(snap, now), cx, y, 23, ageColor(), CarKit.MEDIUM, p, Paint.Align.CENTER);
        y += 26;
        p.setColor(CarKit.OUTLINE);
        cv.drawRect(r.left, y, r.right, y + 2, p);
        y += 50;
        paintCharge(cv, r, y, s, p);
        // La energía que queda: el % por la capacidad de la variante elegida.
        if (!Double.isNaN(soc) && snap.capacityKwh > 0) {
            float ky = y + 120;
            CarKit.label(cv, Str.get(R.string.hql_cloud_in_battery), r.left, ky, p);
            CarKit.text(cv, Str.get(R.string.hql_cloud_kwh_of, num(soc / 100 * snap.capacityKwh, 1), num(snap.capacityKwh, 1)), r.left,
                    ky + 38, 30, CarKit.TEXT, CarKit.MEDIUM, p, Paint.Align.LEFT);
        }
        // Temperatura de la batería (la del habitáculo está en la tarjeta Coche) y el aviso de batería fría.
        StringBuilder t = new StringBuilder();
        if (!Double.isNaN(s.minBatteryTempC)) {
            t.append(Str.get(R.string.hql_cloud_batt_temp, num(s.minBatteryTempC, 0) + " °C"));
            if (s.batteryThermalRequest != null && s.batteryThermalRequest == 1) t.append(" · ").append(Str.get(R.string.hql_cloud_thermal_on));
        }
        float ty = r.bottom - (s.coldBattery() ? 64 : 4);
        if (t.length() > 0) {
            CarIcons.thermo(cv, r.left + 12, ty - 10, 28, CarKit.DIM, p);
            p.setTypeface(CarKit.MEDIUM);
            p.setTextSize(23);
            CarKit.text(cv, CarKit.ellipsize(t.toString(), r.width() - 36, p), r.left + 34, ty, 23, CarKit.DIM, CarKit.MEDIUM, p, Paint.Align.LEFT);
        }
        if (s.coldBattery()) alert.draw(cv, Str.get(R.string.hql_cloud_cold_battery), r.left, r.bottom - 52, (int) r.width(), false);
    }

    /** Carga: CA o CC con su potencia y lo que falta; sin enchufar, la potencia que sale o entra. */
    private void paintCharge(Canvas cv, RectF r, float y, LeapStatus s, Paint p) {
        double kw = s.powerKw();
        String title;
        int tc;
        String big = null;
        String sub = null;
        if (s.charging()) {
            title = Str.get(s.dcPlugged() ? R.string.hql_cloud_charging_dc : R.string.hql_cloud_charging_ac);
            tc = CarKit.GREEN;
            if (!Double.isNaN(kw)) big = num(Math.abs(kw), 1);
            String left = minutes(s.chargeRemainMin);
            if (!left.isEmpty()) sub = Str.get(R.string.hql_cloud_full_in, left);
        } else if (Boolean.TRUE.equals(s.chargeCompleted) && s.pluggedIn()) {
            title = Str.get(R.string.hql_cloud_charge_done);
            tc = CarKit.GREEN;
        } else if (s.pluggedIn()) {
            title = Str.get(R.string.hql_cloud_plugged);
            tc = CarKit.ACCENT;
        } else {
            title = Str.get(R.string.hql_cloud_unplugged);
            tc = CarKit.DIM;
            if (!Double.isNaN(kw)) {
                boolean moving = !Double.isNaN(s.speedKmh) && s.speedKmh > 1;
                big = num(Math.abs(kw), 1);
                if (kw < -0.2 && moving) {
                    sub = Str.get(R.string.hql_cloud_power_in);
                } else if (moving) {
                    sub = Str.get(R.string.hql_cloud_power_out);
                } else {
                    sub = Str.get(R.string.hql_cloud_power_parked);
                }
            }
        }
        CarIcons.bolt(cv, r.left + 16, y - 10, 34, tc, p);
        p.setTypeface(CarKit.MEDIUM);
        p.setTextSize(28);
        CarKit.text(cv, CarKit.ellipsize(title, r.width() - 44, p), r.left + 42, y, 28, tc, CarKit.MEDIUM, p, Paint.Align.LEFT);
        if (big != null) {
            float w = CarKit.number(cv, big, "kW", r.left, y + 76, 64, CarKit.TEXT, CarKit.REGULAR, p, Paint.Align.LEFT);
            if (sub != null) {
                p.setTypeface(CarKit.MEDIUM);
                p.setTextSize(24);
                CarKit.text(cv, CarKit.ellipsize(sub, r.width() - w - 24, p), r.left + w + 20, y + 72, 24, CarKit.DIM, CarKit.MEDIUM, p,
                        Paint.Align.LEFT);
            }
        } else if (sub != null) {
            CarKit.text(cv, sub, r.left, y + 56, 26, CarKit.DIM, CarKit.MEDIUM, p, Paint.Align.LEFT);
        }
    }

    // ------------------------------------------------------------------ neumáticos y puertas

    private Bitmap carTop(int w, int h) {
        if (carTop == null || carTop.getWidth() != w || carTop.getHeight() != h) {
            if (carTop != null) carTop.recycle();
            carTop = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(carTop);
            float pad = w * 0.1f;
            CarArt.top(c, new RectF(pad, 4, w - pad, h - 4), new Paint(Paint.ANTI_ALIAS_FLAG));
        }
        return carTop;
    }

    private void paintTyres(Canvas cv, RectF r, Paint p) {
        LeapStatus s = snap.status;
        float ch = Math.min(r.height() - 196, 420);
        float cw = ch * 0.46f;
        float cx = r.centerX();
        float top = r.top + 8;
        // El dibujo lleva un margen del 10 % a cada lado para los retrovisores y las ruedas.
        int bw = Math.round(cw / 0.8f);
        Bitmap b = carTop(bw, Math.round(ch));
        tmp.set(cx - bw / 2f, top, cx + bw / 2f, top + ch);
        cv.drawBitmap(b, null, tmp, bmp);
        car.set(cx - cw / 2, top + 4, cx + cw / 2, top + ch - 4);
        // Ruedas (las mismas posiciones que CarArt.top): un marco del color de su estado y la presión al lado.
        float tw = 0.10f * car.width();
        float th = 0.16f * car.height();
        float[] ys = {car.top + 0.15f * car.height(), car.top + 0.70f * car.height()};
        for (int i = 0; i < 4; i++) {
            boolean left = i == LeapStatus.FL || i == LeapStatus.RL;
            float ty = ys[i < 2 ? 0 : 1];
            float tx0 = left ? car.left - tw * 0.2f : car.right - tw * 0.8f;
            double bar = s.tyreBar(i);
            boolean warn = s.tyreWarning(i);
            int c = Double.isNaN(bar) ? CarKit.MUTED : warn ? CarKit.AMBER : CarKit.GREEN;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(4);
            p.setColor(c);
            tmp.set(tx0 - 6, ty - 6, tx0 + tw + 6, ty + th + 6);
            cv.drawRoundRect(tmp, 10, 10, p);
            p.setStyle(Paint.Style.FILL);
            float lx = left ? car.left - 44 : car.right + 44;
            Paint.Align al = left ? Paint.Align.RIGHT : Paint.Align.LEFT;
            CarKit.number(cv, num(bar, 2), "bar", lx, ty + th / 2 + 14, 46, warn ? CarKit.AMBER : CarKit.TEXT, CarKit.REGULAR, p, al);
            if (s.tyreStateAlert(i)) {
                CarKit.text(cv, Str.get(R.string.hql_cloud_tyre_tpms_short), lx, ty + th / 2 + 48, 20, CarKit.AMBER, CarKit.MEDIUM, p, al);
            }
        }
        // Puertas y maletero abiertos: en ámbar, como abiertos de verdad.
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(7);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(CarKit.AMBER);
        float[][] doorY = {{0.36f, 0.55f}, {0.56f, 0.74f}};
        for (int i = 0; i < 4; i++) {
            if (!Boolean.TRUE.equals(s.doorOpen(i))) continue;
            boolean left = i == LeapStatus.FL || i == LeapStatus.RL;
            float[] dy = doorY[i < 2 ? 0 : 1];
            float hx = left ? car.left + 0.04f * car.width() : car.right - 0.04f * car.width();
            float hy = car.top + dy[0] * car.height();
            float len = (dy[1] - dy[0]) * car.height();
            float ex = hx + (left ? -1 : 1) * len * 0.57f;
            float ey = hy + len * 0.82f;
            cv.drawLine(hx, hy, ex, ey, p);
        }
        if (Boolean.TRUE.equals(s.bootOpen)) {
            cv.drawLine(car.left + 0.16f * car.width(), car.bottom + 14, car.right - 0.16f * car.width(), car.bottom + 14, p);
        }
        p.setStrokeCap(Paint.Cap.BUTT);
        p.setStyle(Paint.Style.FILL);
        // Debajo: cierre, puertas y presiones.
        float y = top + ch + 50;
        Boolean locked = s.locked;
        int lc = locked == null ? CarKit.MUTED : locked ? CarKit.GREEN : CarKit.AMBER;
        lock(cv, r.left + 14, y - 10, 30, lc, Boolean.TRUE.equals(locked), p);
        String lt = locked == null ? "—" : Str.get(locked ? R.string.hql_cloud_locked : R.string.hql_cloud_unlocked);
        CarKit.text(cv, lt, r.left + 42, y, 27, lc, CarKit.MEDIUM, p, Paint.Align.LEFT);
        String open = openList(s);
        String doors = open == null ? Str.get(R.string.hql_cloud_doors_closed) : Str.get(R.string.hql_cloud_open_list, open);
        p.setTypeface(CarKit.MEDIUM);
        p.setTextSize(23);
        y += 38;
        CarKit.text(cv, CarKit.ellipsize(doors, r.width(), p), r.left, y, 23, open == null ? CarKit.DIM : CarKit.AMBER, CarKit.MEDIUM, p,
                Paint.Align.LEFT);
        String tyres = tyreSummary(s);
        y += 34;
        CarKit.text(cv, CarKit.ellipsize(tyres, r.width(), p), r.left, y, 23, tyres.equals(Str.get(R.string.hql_cloud_tyres_ok))
                ? CarKit.DIM : CarKit.AMBER, CarKit.MEDIUM, p, Paint.Align.LEFT);
        // La nota, pegada abajo: en una o dos líneas según quepa.
        String nt = Str.get(R.string.hql_cloud_tyres_note);
        p.setTypeface(CarKit.REGULAR);
        p.setTextSize(21);
        note.draw(cv, nt, r.left, r.bottom - (p.measureText(nt) > r.width() ? 50 : 26), (int) r.width(), false);
    }

    private static final int[] PLACES = {R.string.hql_cloud_pos_fl, R.string.hql_cloud_pos_fr, R.string.hql_cloud_pos_rl,
            R.string.hql_cloud_pos_rr};

    /** «delantera izquierda, maletero», o null si todo está cerrado (o no se sabe). */
    private static String openList(LeapStatus s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            if (Boolean.TRUE.equals(s.doorOpen(i))) b.append(b.length() > 0 ? ", " : "").append(Str.get(PLACES[i]));
        }
        if (Boolean.TRUE.equals(s.bootOpen)) b.append(b.length() > 0 ? ", " : "").append(Str.get(R.string.hql_cloud_pos_boot));
        return b.length() == 0 ? null : b.toString();
    }

    /** «Presiones parejas» o la rueda con su aviso. */
    private static String tyreSummary(LeapStatus s) {
        for (int i = 0; i < 4; i++) {
            if (s.tyreStateAlert(i)) return Str.get(R.string.hql_cloud_tyre_tpms, Str.get(PLACES[i]));
        }
        for (int i = 0; i < 4; i++) {
            if (s.tyreLow(i)) return Str.get(R.string.hql_cloud_tyre_low, Str.get(PLACES[i]), num(s.tyreBar(i), 2));
        }
        boolean any = false;
        for (int i = 0; i < 4; i++) any |= !Double.isNaN(s.tyreBar(i));
        return any ? Str.get(R.string.hql_cloud_tyres_ok) : "—";
    }

    /** Candado: cerrado (arco abajo) o abierto (arco levantado). */
    private void lock(Canvas cv, float cx, float cy, float sz, int color, boolean closed, Paint p) {
        p.setColor(color);
        p.setStyle(Paint.Style.FILL);
        tmp.set(cx - sz * 0.36f, cy - sz * 0.05f, cx + sz * 0.36f, cy + sz * 0.45f);
        cv.drawRoundRect(tmp, sz * 0.08f, sz * 0.08f, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(2, sz * 0.1f));
        float lift = closed ? 0 : sz * 0.16f;
        tmp.set(cx - sz * 0.22f, cy - sz * 0.42f - lift, cx + sz * 0.22f, cy + sz * 0.02f - lift);
        cv.drawArc(tmp, 180, 180, false, p);
        if (closed) {
            cv.drawLine(cx - sz * 0.22f, cy - sz * 0.2f, cx - sz * 0.22f, cy - sz * 0.03f, p);
        } else {
            cv.drawLine(cx - sz * 0.22f, cy - sz * 0.2f - lift, cx - sz * 0.22f, cy - sz * 0.12f - lift, p);
        }
        cv.drawLine(cx + sz * 0.22f, cy - sz * 0.2f - lift, cx + sz * 0.22f, cy - sz * 0.03f - (closed ? 0 : sz * 0.09f), p);
        p.setStyle(Paint.Style.FILL);
    }

    // ------------------------------------------------------------------ coche y datos

    private void row(Canvas cv, float x, float y, String label, String value, int color, Paint p, float maxW) {
        CarKit.label(cv, label, x, y, CarKit.FAINT, p, Paint.Align.LEFT, maxW);
        p.setTypeface(CarKit.MEDIUM);
        p.setTextSize(30);
        CarKit.text(cv, CarKit.ellipsize(value, maxW, p), x, y + 38, 30, color, CarKit.MEDIUM, p, Paint.Align.LEFT);
    }

    private void paintCar(Canvas cv, RectF r, Paint p) {
        LeapStatus s = snap.status;
        CarKit.label(cv, Str.get(R.string.hql_cloud_odometer), r.left, r.top + 18, p);
        CarKit.number(cv, odometer(s.odometerKm), "km", r.left, r.top + 82, 58, CarKit.TEXT, CarKit.REGULAR, p, Paint.Align.LEFT);
        String state;
        int sc = CarKit.TEXT;
        boolean moving = !Double.isNaN(s.speedKmh) && s.speedKmh > 1;
        if (moving && snap.ageMs(now) < OLD_MS) {
            state = Str.get(R.string.hql_cloud_driving, num(s.speedKmh, 0));
            sc = CarKit.ACCENT;
        } else if (s.powerOn == null) {
            state = "—";
        } else {
            state = Str.get(s.powerOn ? R.string.hql_cloud_on : R.string.hql_cloud_off);
        }
        float step = Math.min(92, (r.height() - 110) / 3f);
        float y = r.top + 116;
        row(cv, r.left, y, Str.get(R.string.hql_now), state, sc, p, r.width());
        y += step;
        row(cv, r.left, y, Str.get(R.string.hql_cloud_cabin), Double.isNaN(s.interiorTempC) ? "—" : num(s.interiorTempC, 0) + " °C",
                CarKit.TEXT, p, r.width());
        if (Boolean.TRUE.equals(s.speedLimitActive) && !Double.isNaN(s.speedLimitKmh) && s.speedLimitKmh > 0) {
            y += step;
            row(cv, r.left, y, Str.get(R.string.hql_cloud_speed_limit), num(s.speedLimitKmh, 0) + " km/h", CarKit.AMBER, p, r.width());
        }
    }

    /** «12 480» (miles separados). */
    static String odometer(double km) {
        if (Double.isNaN(km)) return "—";
        return String.format(Locale.getDefault(), "%,d", Math.round(km));
    }

    private void paintData(Canvas cv, RectF r, Paint p) {
        float step = Math.min(84, (r.height() - 60) / 3.2f);
        float y = r.top + 18;
        row(cv, r.left, y, Str.get(R.string.hql_cloud_car_data), CarCloud.ago(snap.ageMs(now)), ageColor(), p, r.width());
        y += step;
        String read = snap.fetchedAtMs > 0 ? CarCloud.ago(now - snap.fetchedAtMs) + (snap.latencyMs > 0 ? " · " + snap.latencyMs + " ms" : "") : "—";
        row(cv, r.left, y, Str.get(R.string.hql_cloud_read), read, CarKit.TEXT, p, r.width());
        y += step;
        String type = snap.carType.isEmpty() ? "" : snap.carType.toUpperCase(Locale.ROOT);
        row(cv, r.left, y, Str.get(R.string.hql_cloud_source), Str.get(R.string.hql_cloud_source_value, type).trim(), CarKit.TEXT, p, r.width());
        String warnText = problem(snap, now);
        if (warnText != null) {
            alert.draw(cv, warnText, r.left, r.bottom - 54, (int) r.width(), false);
        } else {
            note.draw(cv, Str.get(R.string.hql_cloud_readonly_note), r.left, r.bottom - 50, (int) r.width(), false);
        }
    }

    /** Aviso con datos de antes: sin conexión (y cuándo se reintenta), sesión caducada o clave del servidor nueva. */
    static String problem(CarCloud.Snapshot s, long nowMs) {
        switch (s.state) {
            case ERROR:
                long min = Math.max(1, Math.round((s.nextTryMs - nowMs) / 60_000.0));
                return Str.get(R.string.hql_cloud_retry_in, min);
            case EXPIRED:
                return Str.get(R.string.hql_cloud_expired_short);
            case SERVER_KEY:
                return Str.get(R.string.hql_cloud_server_key_short);
            default:
                return null;
        }
    }

    // ------------------------------------------------------------------ sin datos

    private Bitmap carFront(int sz) {
        if (carFront == null || carFront.getWidth() != sz) {
            if (carFront != null) carFront.recycle();
            carFront = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
            CarArt.front(new Canvas(carFront), new RectF(0, 0, sz, sz), new Paint(Paint.ANTI_ALIAS_FLAG));
        }
        return carFront;
    }

    private void paintEmpty(Canvas cv, RectF r, Paint p) {
        int title;
        String text;
        switch (snap.state) {
            case OFF:
                title = R.string.hql_cloud_off_title;
                text = Str.get(R.string.hql_cloud_off_body);
                break;
            case UNSUPPORTED:
                title = R.string.hql_cloud_unsupported_title;
                text = Str.get(R.string.hql_cloud_unsupported_body);
                break;
            case WAITING:
                title = R.string.hql_cloud_waiting_title;
                text = Str.get(R.string.hql_cloud_waiting_body);
                break;
            case EXPIRED:
                title = R.string.hql_cloud_expired_title;
                text = Str.get(R.string.hql_cloud_expired_body);
                break;
            case SERVER_KEY:
                title = R.string.hql_cloud_server_key_title;
                text = Str.get(R.string.hql_cloud_server_key_body_car);
                break;
            case ERROR:
                title = R.string.hql_cloud_error_title;
                text = Str.get(R.string.hql_cloud_error_body, Math.max(1, Math.round((snap.nextTryMs - now) / 60_000.0)));
                break;
            default:
                title = R.string.hql_cloud_none_title;
                text = Str.get(R.string.hql_cloud_none_body);
        }
        float cx = r.centerX();
        float cy = r.top + r.height() * 0.27f;
        p.setColor(CarKit.SURFACE_HI);
        cv.drawCircle(cx, cy, 118, p);
        Bitmap b = carFront(196);
        bmp.setAlpha(snap.state == CarCloud.State.WAITING ? 255 : 150);
        tmp.set(cx - 98, cy - 98, cx + 98, cy + 98);
        cv.drawBitmap(b, null, tmp, bmp);
        bmp.setAlpha(255);
        // Una nube pequeña arriba a la derecha: los datos vienen de la nube de Leapmotor.
        p.setColor(CarKit.SURFACE);
        cv.drawCircle(cx + 92, cy - 82, 44, p);
        CarIcons.cloud(cv, cx + 92, cy - 84, 58, snap.state == CarCloud.State.WAITING ? CarKit.ACCENT : CarKit.DIM, p);
        CarKit.text(cv, Str.get(title), cx, cy + 192, 40, CarKit.TEXT, CarKit.MEDIUM, p, Paint.Align.CENTER);
        body.draw(cv, text, r.left + 160, cy + 222, (int) r.width() - 320, true);
    }
}
