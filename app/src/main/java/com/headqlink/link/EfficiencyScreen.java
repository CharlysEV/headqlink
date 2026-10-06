package com.headqlink.link;

import com.andrerinas.openheadunit.R;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;
import android.widget.LinearLayout;

import java.util.List;
import java.util.Locale;

/**
 * Eficiencia ESTIMADA con los sensores del móvil, el tiempo (Open-Meteo) y un modelo físico del coche (EnergyModel),
 * sin datos del coche, y así se indica: potencia ahora con la gráfica de los últimos 2 minutos (en verde lo que se
 * recupera), consumo ahora / del viaje / medio de los viajes, en qué se va la energía del viaje (aire, rodadura,
 * subidas, aceleraciones, climatización, electrónica) y cuánto se ha recuperado, las condiciones (viento respecto al
 * coche, temperatura, pendiente, altitud), el coste del viaje (€/kWh de Ajustes) frente a la gasolina, el CO₂ que no
 * ha salido por un tubo de escape y un consejo con su cifra.
 */
final class EfficiencyScreen implements CarScreen {
    private static final long TICK_MS = 500;
    /** CO₂ por litro de gasolina quemado (combustión; sin contar la fabricación del combustible). */
    static final double CO2_KG_PER_L = 2.31;
    private static final int[] PART_COLORS = {CarKit.BLUE, CarKit.VIOLET, CarKit.AMBER, CarKit.RED, CarKit.ACCENT, CarKit.MUTED};
    private static final int[] PART_NAMES = {R.string.hql_air, R.string.hql_rolling, R.string.hql_climbs, R.string.hql_acceleration,
            R.string.hql_hvac, R.string.hql_electronics};

    private CarSensors sensors;
    private boolean running;
    private Config cfg;
    private CarSensors.Snapshot s = new CarSensors.Snapshot();
    private final EnergyBreakdown trip = new EnergyBreakdown();
    private final float[] power = new float[CarSensors.POWER_SECS];
    private int powerN;
    private double avgAllTrips = Double.NaN;
    private CarKit.Card powerCard;
    private CarKit.Card condCard;
    private CarKit.Card useCard;
    private CarKit.Card splitCard;
    private CarKit.Card costCard;
    private CarKit.Card co2Card;
    private CarKit.Card tipCard;
    private final Path line = new Path();
    private final Path area = new Path();
    private final RectF tmp = new RectF();
    private final CarKit.Para tipText = new CarKit.Para(28, CarKit.TEXT, CarKit.REGULAR);
    private final CarKit.Para small = new CarKit.Para(20, CarKit.FAINT, CarKit.REGULAR);
    private Bitmap carTop;
    private final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);

    @Override
    public View create(Host h) {
        Context c = h.context();
        sensors = CarSensors.start(c);
        cfg = new Config(c);
        avgAllTrips = TripStats.avgKwhPer100(TripLog.recent(c, 60));
        LinearLayout row = CarKit.row(c);
        LinearLayout c1 = CarKit.add(row, CarKit.col(c), 1f, 0);
        LinearLayout c2 = CarKit.add(row, CarKit.col(c), 1f, 0);
        LinearLayout c3 = CarKit.add(row, CarKit.col(c), 1f, 0);
        powerCard = CarKit.add(c1, new CarKit.Card(c, Str.get(R.string.hql_power_now), this::paintPower), 1.15f, 0);
        condCard = CarKit.add(c1, new CarKit.Card(c, Str.get(R.string.hql_conditions), this::paintConditions), 1f, 0);
        useCard = CarKit.add(c2, new CarKit.Card(c, Str.get(R.string.hql_consumption), this::paintUse), 0.62f, 0);
        splitCard = CarKit.add(c2, new CarKit.Card(c, Str.get(R.string.hql_where_energy_goes), this::paintSplit), 1.38f, 0);
        costCard = CarKit.add(c3, new CarKit.Card(c, Str.get(R.string.hql_trip_cost_title), this::paintCost), 0.8f, 0);
        co2Card = CarKit.add(c3, new CarKit.Card(c, Str.get(R.string.hql_co2_avoided), this::paintCo2), 0.75f, 0);
        tipCard = CarKit.add(c3, new CarKit.Card(c, Str.get(R.string.hql_tip), this::paintTip), 0.85f, 0);
        running = true;
        tick();
        return CarKit.page(c, row);
    }

    private void tick() {
        if (!running) return;
        s = sensors.snapshot();
        sensors.tripBreakdown(trip);
        powerN = sensors.powerHistory(power);
        powerCard.invalidate();
        condCard.invalidate();
        useCard.invalidate();
        splitCard.invalidate();
        costCard.invalidate();
        co2Card.invalidate();
        tipCard.invalidate();
        powerCard.postDelayed(this::tick, TICK_MS);
    }

    @Override
    public void destroy() {
        running = false;
        CarSensors.stop();
        if (carTop != null) carTop.recycle();
    }

    private static String num(double v, int decimals) {
        return String.format(Locale.getDefault(), decimals == 0 ? "%.0f" : decimals == 1 ? "%.1f" : "%.2f", v);
    }

    /** Color de un consumo (kWh/100 km): verde si es bajo, ámbar o rojo si es alto para este coche. */
    private static int useColor(double v) {
        if (Double.isNaN(v)) return CarKit.MUTED;
        if (v < 15) return CarKit.GREEN;
        if (v < 21) return CarKit.ACCENT;
        if (v < 27) return CarKit.AMBER;
        return CarKit.RED;
    }

    // ------------------------------------------------------------------ potencia

    private void paintPower(Canvas cv, RectF r, Paint p) {
        boolean moving = s.speedKmh > 2;
        double kw = s.powerKw;
        int color = kw < -0.3 ? CarKit.GREEN : kw > 45 ? CarKit.AMBER : CarKit.TEXT;
        CarKit.number(cv, String.format(Locale.getDefault(), "%+.1f", kw).replace("+", ""), "kW", r.left, r.top + 66, 80, color, CarKit.REGULAR, p, Paint.Align.LEFT);
        int state = !moving ? R.string.hql_stopped_cap : kw < -0.3 ? R.string.hql_regenerating : R.string.hql_consuming;
        CarKit.text(cv, Str.get(state), r.right, r.top + 30, 25, kw < -0.3 && moving ? CarKit.GREEN : CarKit.DIM, CarKit.MEDIUM, p, Paint.Align.RIGHT);
        // Gráfica de los últimos 2 min: por encima de cero consume, por debajo recupera.
        RectF g = tmp;
        g.set(r.left + 46, r.top + 104, r.right, r.bottom - 26);
        float hi = 10;
        float lo = 0;
        for (int i = 0; i < powerN; i++) {
            hi = Math.max(hi, power[i]);
            lo = Math.min(lo, power[i]);
        }
        hi = (float) Math.ceil(hi / 10) * 10;
        lo = lo < 0 ? (float) Math.floor(lo / 10) * 10 : 0;
        float zero = g.top + hi / (hi - lo) * g.height();
        p.setColor(CarKit.SURFACE_HI);
        cv.drawRoundRect(g, 12, 12, p);
        p.setColor(CarKit.OUTLINE);
        cv.drawRect(g.left, zero - 1, g.right, zero + 1, p);
        CarKit.text(cv, num(hi, 0), g.left - 10, g.top + 18, 19, CarKit.FAINT, CarKit.MEDIUM, p, Paint.Align.RIGHT);
        boolean roomBelow = g.bottom - zero > 26;
        if (zero - g.top > 30 && (roomBelow || lo == 0)) {
            CarKit.text(cv, "0", g.left - 10, zero + 7, 19, CarKit.FAINT, CarKit.MEDIUM, p, Paint.Align.RIGHT);
        }
        if (lo < 0) CarKit.text(cv, num(lo, 0), g.left - 10, g.bottom - 2, 19, CarKit.GREEN, CarKit.MEDIUM, p, Paint.Align.RIGHT);
        CarKit.text(cv, "−2 min", g.left, r.bottom, 19, CarKit.FAINT, CarKit.MEDIUM, p, Paint.Align.LEFT);
        CarKit.text(cv, Str.get(R.string.hql_now_lower), g.right, r.bottom, 19, CarKit.FAINT, CarKit.MEDIUM, p, Paint.Align.RIGHT);
        if (powerN < 2) return;
        float dx = g.width() / (CarSensors.POWER_SECS - 1);
        float x0 = g.right - (powerN - 1) * dx;
        line.rewind();
        area.rewind();
        area.moveTo(x0, zero);
        for (int i = 0; i < powerN; i++) {
            float x = x0 + i * dx;
            float y = g.top + (hi - power[i]) / (hi - lo) * g.height();
            if (i == 0) line.moveTo(x, y);
            else line.lineTo(x, y);
            area.lineTo(x, y);
        }
        area.lineTo(g.right, zero);
        area.close();
        // Relleno: cian por encima del cero, verde por debajo.
        cv.save();
        cv.clipRect(g.left, g.top, g.right, zero);
        p.setColor(CarKit.alpha(CarKit.ACCENT, 0.22f));
        cv.drawPath(area, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3.5f);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setColor(CarKit.ACCENT);
        cv.drawPath(line, p);
        p.setStyle(Paint.Style.FILL);
        cv.restore();
        cv.save();
        cv.clipRect(g.left, zero, g.right, g.bottom);
        p.setColor(CarKit.alpha(CarKit.GREEN, 0.3f));
        cv.drawPath(area, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3.5f);
        p.setColor(CarKit.GREEN);
        cv.drawPath(line, p);
        p.setStyle(Paint.Style.FILL);
        cv.restore();
    }

    // ------------------------------------------------------------------ condiciones

    private void paintConditions(Canvas cv, RectF r, Paint p) {
        // Viento respecto al coche: el coche visto desde arriba, morro arriba, y una flecha desde donde sopla.
        float rad = Math.min(r.height() / 2f - 6, 92);
        float cx = r.left + rad;
        float cy = r.top + rad + 2;
        p.setColor(CarKit.SURFACE_HI);
        cv.drawCircle(cx, cy, rad, p);
        Bitmap b = carTop();
        float bh = rad * 0.9f;
        float bw = bh * b.getWidth() / b.getHeight();
        tmp.set(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2);
        cv.drawBitmap(b, null, tmp, bmp);
        boolean wind = !Double.isNaN(s.windKmh) && !Double.isNaN(s.headwindKmh);
        if (wind && s.windKmh >= 1) {
            double rel = Math.toRadians(s.windFromDeg - s.headingDeg);
            float ax = cx + (float) Math.sin(rel) * rad * 0.74f;
            float ay = cy - (float) Math.cos(rel) * rad * 0.74f;
            // La flecha apunta hacia donde va el aire (desde su origen hacia el coche).
            CarIcons.windArrow(cv, ax, ay, rad * 0.42f, (float) Math.toDegrees(rel) + 180, s.headwindKmh > 3 ? CarKit.AMBER : CarKit.BLUE, p);
        }
        float tx = cx + rad + 26;
        float y = r.top + 22;
        float step = (r.height() - 22) / 4f;
        row(cv, tx, y, Str.get(R.string.hql_wind), wind ? String.format(Locale.getDefault(), "%.0f km/h %s", Math.abs(s.headwindKmh),
                s.headwindKmh >= 0 ? Str.get(R.string.hql_headwind) : Str.get(R.string.hql_tailwind)) : "—", wind && s.headwindKmh > 3 ? CarKit.AMBER : CarKit.TEXT, p);
        row(cv, tx, y + step, Str.get(R.string.hql_temperature), Double.isNaN(s.tempC) ? "—" : String.format(Locale.getDefault(), "%.0f °C", s.tempC), CarKit.TEXT, p);
        int gc = s.gradePct > 2.5 ? CarKit.AMBER : s.gradePct < -2.5 ? CarKit.GREEN : CarKit.TEXT;
        row(cv, tx, y + 2 * step, Str.get(R.string.hql_grade), String.format(Locale.getDefault(), "%+.1f %%", s.gradePct), gc, p);
        row(cv, tx, y + 3 * step, Str.get(R.string.hql_altitude), Double.isNaN(s.altitudeM) ? "—"
                : String.format(Locale.getDefault(), "%.0f m · ↑%.0f ↓%.0f", s.altitudeM, s.climbM, s.descentM), CarKit.TEXT, p);
    }

    private void row(Canvas cv, float x, float y, String label, String value, int color, Paint p) {
        CarKit.label(cv, label, x, y, p);
        CarKit.text(cv, value, x, y + 34, 29, color, CarKit.MEDIUM, p, Paint.Align.LEFT);
    }

    private Bitmap carTop() {
        if (carTop == null) {
            carTop = Bitmap.createBitmap(90, 180, Bitmap.Config.ARGB_8888);
            CarArt.top(new Canvas(carTop), new RectF(10, 6, 80, 174), new Paint(Paint.ANTI_ALIAS_FLAG));
        }
        return carTop;
    }

    // ------------------------------------------------------------------ consumo y reparto

    private void paintUse(Canvas cv, RectF r, Paint p) {
        double trip100 = s.tripKmEnergy > 0.3 ? s.tripKwh / s.tripKmEnergy * 100 : Double.NaN;
        double[] v = {s.kwh100Now, trip100, avgAllTrips};
        int[] names = {R.string.hql_now, R.string.hql_trip, R.string.hql_avg_trips};
        float w = r.width() / 3f;
        for (int i = 0; i < 3; i++) {
            float x = r.left + i * w;
            CarKit.label(cv, Str.get(names[i]), x, r.top + 18, p);
            String val = Double.isNaN(v[i]) ? "—" : num(v[i], 1);
            CarKit.text(cv, val, x, r.top + 74, 50, i == 0 && v[i] < 0 ? CarKit.GREEN : useColor(v[i]), CarKit.REGULAR, p, Paint.Align.LEFT);
            if (i > 0) {
                p.setColor(CarKit.OUTLINE);
                cv.drawRect(x - 14, r.top, x - 12, r.top + 82, p);
            }
        }
        CarKit.text(cv, Str.get(R.string.hql_kwh100_unit), r.left, r.bottom, 21, CarKit.FAINT, CarKit.MEDIUM, p, Paint.Align.LEFT);
    }

    private void paintSplit(Canvas cv, RectF r, Paint p) {
        double consumed = trip.consumed();
        if (consumed < 0.05) {
            small.draw(cv, Str.get(R.string.hql_split_wait), r.left, r.top + 10, (int) r.width(), false);
            return;
        }
        // Barra apilada con el reparto (huecos de 3 px entre partes; extremos redondeados).
        float bh = 34;
        float x = r.left;
        float total = r.width();
        float rad = bh / 2;
        int last = -1;
        for (int i = 0; i < EnergyBreakdown.PARTS; i++) if (trip.share(i) * total >= 2) last = i;
        boolean first = true;
        for (int i = 0; i < EnergyBreakdown.PARTS; i++) {
            float w = (float) (trip.share(i) * total);
            if (w < 2) continue;
            float x1 = x + w - (i == last ? 0 : 3);
            p.setColor(PART_COLORS[i]);
            if (first || i == last) {
                tmp.set(x, r.top, x1, r.top + bh);
                cv.drawRoundRect(tmp, rad, rad, p);
                if (first && i != last && x1 - x > rad) cv.drawRect(x + rad, r.top, x1, r.top + bh, p);
                if (!first && i == last && x1 - x > rad) cv.drawRect(x, r.top, x1 - rad, r.top + bh, p);
            } else {
                cv.drawRect(x, r.top, x1, r.top + bh, p);
            }
            first = false;
            x += w;
        }
        // Leyenda: color, nombre, kWh y %.
        float y = r.top + bh + 46;
        float step = Math.min(42, (r.bottom - y - 70) / 5.6f);
        for (int i = 0; i < EnergyBreakdown.PARTS; i++) {
            float cy = y + i * step;
            p.setColor(PART_COLORS[i]);
            tmp.set(r.left, cy - 18, r.left + 18, cy);
            cv.drawRoundRect(tmp, 5, 5, p);
            CarKit.text(cv, Str.get(PART_NAMES[i]), r.left + 32, cy, 25, CarKit.TEXT, CarKit.REGULAR, p, Paint.Align.LEFT);
            CarKit.text(cv, num(trip.part(i), 2) + " kWh", r.right - 92, cy, 23, CarKit.DIM, CarKit.REGULAR, p, Paint.Align.RIGHT);
            CarKit.text(cv, num(trip.share(i) * 100, 0) + " %", r.right, cy, 25, CarKit.TEXT, CarKit.MEDIUM, p, Paint.Align.RIGHT);
        }
        // Recuperado y neto.
        float ry = r.bottom - 40;
        p.setColor(CarKit.OUTLINE);
        cv.drawRect(r.left, ry - 44, r.right, ry - 42, p);
        CarIcons.leaf(cv, r.left + 10, ry - 9, 24, CarKit.GREEN, p);
        CarKit.text(cv, Str.get(R.string.hql_recovered), r.left + 32, ry, 25, CarKit.GREEN, CarKit.MEDIUM, p, Paint.Align.LEFT);
        CarKit.text(cv, "−" + num(trip.recovered(), 2) + " kWh", r.right, ry, 25, CarKit.GREEN, CarKit.MEDIUM, p, Paint.Align.RIGHT);
        CarKit.text(cv, Str.get(R.string.hql_estimated_model) + " · " + Str.get(R.string.hql_net_kwh, trip.net()), r.left, r.bottom, 21,
                CarKit.FAINT, CarKit.MEDIUM, p, Paint.Align.LEFT);
    }

    // ------------------------------------------------------------------ coste, CO₂ y consejo

    private void paintCost(Canvas cv, RectF r, Paint p) {
        double price = cfg.electricityPrice();
        double cost = Math.max(0, s.tripKwh) * price;
        double km = Math.max(0, s.tripKmEnergy);
        double petrol = km * cfg.fuelLitersPer100() / 100 * cfg.fuelPrice();
        CarKit.number(cv, num(cost, 2), "€", r.left, r.top + 64, 72, CarKit.TEXT, CarKit.REGULAR, p, Paint.Align.LEFT);
        CarKit.text(cv, Str.get(R.string.hql_cost_rate, price, Math.max(0, s.tripKwh)), r.left, r.top + 108, 23, CarKit.DIM, CarKit.REGULAR, p, Paint.Align.LEFT);
        if (km > 0.2) {
            CarKit.text(cv, Str.get(R.string.hql_cost_petrol, petrol), r.left, r.bottom - 2, 25, CarKit.GREEN, CarKit.MEDIUM, p, Paint.Align.LEFT);
        }
    }

    private void paintCo2(Canvas cv, RectF r, Paint p) {
        double km = Math.max(0, s.tripKmEnergy);
        double kg = km * cfg.fuelLitersPer100() / 100 * CO2_KG_PER_L;
        CarIcons.leaf(cv, r.left + 26, r.top + 40, 52, CarKit.GREEN, p);
        CarKit.number(cv, num(kg, 1), "kg", r.left + 68, r.top + 62, 64, CarKit.TEXT, CarKit.REGULAR, p, Paint.Align.LEFT);
        small.draw(cv, Str.get(R.string.hql_co2_note, cfg.fuelLitersPer100(), CO2_KG_PER_L), r.left, r.top + 84, (int) r.width(), false);
    }

    private void paintTip(Canvas cv, RectF r, Paint p) {
        EcoTip tip = EcoTip.pick(s.speedKmh, s.headwindKmh, s.tempC, trip);
        String t;
        switch (tip.kind) {
            case EcoTip.SLOWER:
                t = Str.get(R.string.hql_tip_slower, tip.value);
                break;
            case EcoTip.GENTLER:
                t = Str.get(R.string.hql_tip_gentler, tip.value);
                break;
            case EcoTip.CLIMATE:
                t = Str.get(R.string.hql_tip_climate, tip.value);
                break;
            case EcoTip.REGEN:
                t = Str.get(R.string.hql_tip_regen, tip.value);
                break;
            default:
                t = Str.get(R.string.hql_tip_steady);
        }
        CarIcons.bulb(cv, r.left + 22, r.top + 26, 46, CarKit.AMBER, p);
        float h = tipText.draw(cv, t, r.left + 60, r.top, (int) (r.width() - 60), false);
        if (tip.kind == EcoTip.SLOWER) {
            small.draw(cv, Str.get(R.string.hql_tip_slower_sub, s.speedKmh), r.left + 60, r.top + h + 12, (int) (r.width() - 60), false);
        }
    }
}
