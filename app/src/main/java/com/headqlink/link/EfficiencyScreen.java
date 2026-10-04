package com.headqlink.link;

import com.andrerinas.openheadunit.R;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import java.util.Locale;

/**
 * Eficiencia con los sensores del móvil y el tiempo (Open-Meteo): altitud, pendiente, desnivel,
 * viento de cara/cola y temperatura, y un reparto ESTIMADO de la potencia que pide el coche
 * (aerodinámica con el viento, rodadura, pendiente, aceleración) con un modelo físico del coche.
 * No hay datos del coche: es una estimación y así se indica en pantalla.
 */
final class EfficiencyScreen implements CarScreen {
    private CarSensors sensors;
    private Panel view;
    private boolean running;
    // Media de consumo estimado (energía y distancia acumuladas).
    private double whSum;
    private double kmSum;
    private long lastNs;

    @Override
    public View create(Host h) {
        Context c = h.context();
        sensors = CarSensors.start(c);
        view = new Panel(c);
        running = true;
        tick();
        return view;
    }

    private void tick() {
        if (!running) return;
        CarSensors.Snapshot s = sensors.snapshot();
        EnergyModel m = view.m;
        m.compute(s.speedKmh, s.gradePct, s.headwindKmh, s.tempC, s.longG);
        long now = System.nanoTime();
        if (lastNs != 0 && s.speedKmh > 3) {
            double dtH = (now - lastNs) / 3.6e12;
            whSum += m.batteryKw * 1000 * dtH;
            kmSum += s.speedKmh * dtH;
        }
        lastNs = now;
        view.s = s;
        view.avgWhKm = kmSum > 0.2 ? whSum / kmSum : Double.NaN;
        view.invalidate();
        view.postDelayed(this::tick, 200);
    }

    @Override
    public void destroy() {
        running = false;
        CarSensors.stop();
    }

    private static final class Panel extends View {
        CarSensors.Snapshot s = new CarSensors.Snapshot();
        final EnergyModel m = new EnergyModel();
        double avgWhKm = Double.NaN;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Panel(Context c) {
            super(c);
        }

        @Override
        protected void onDraw(Canvas cv) {
            int w = getWidth();
            float col1 = 40;
            float col2 = w * 0.5f + 20;
            // Columna 1: terreno y aire.
            float y = 70;
            y = big(cv, col1, y, Str.get(R.string.hql_altitude), Double.isNaN(s.altitudeM) ? "—" : String.format(Locale.getDefault(), "%.0f m", s.altitudeM),
                    s.baro ? Str.get(R.string.hql_barometer) : "GPS");
            y = big(cv, col1, y, Str.get(R.string.hql_grade), String.format(Locale.getDefault(), "%+.1f %%", s.gradePct),
                    Str.get(R.string.hql_climb_descent, s.climbM, s.descentM));
            String wind;
            String windSub;
            if (Double.isNaN(s.headwindKmh)) {
                wind = "—";
                windSub = Str.get(R.string.hql_no_data_yet);
            } else {
                wind = String.format(Locale.getDefault(), "%.0f km/h %s", Math.abs(s.headwindKmh), s.headwindKmh >= 0 ? Str.get(R.string.hql_headwind) : Str.get(R.string.hql_tailwind));
                windSub = Str.get(R.string.hql_wind_from, s.windKmh, compass(s.windFromDeg), s.tempC);
            }
            big(cv, col1, y, Str.get(R.string.hql_wind), wind, windSub);

            // Columna 2: potencia estimada.
            y = 70;
            p.setTextAlign(Paint.Align.LEFT);
            p.setColor(CarStyle.TEXT_DIM);
            p.setTextSize(24);
            cv.drawText(Str.get(R.string.hql_power_estimated), col2, y, p);
            p.setColor(CarStyle.TEXT);
            p.setTextSize(64);
            cv.drawText(String.format(Locale.getDefault(), "%.1f kW", m.batteryKw), col2, y + 70, p);
            p.setTextSize(26);
            p.setColor(CarStyle.TEXT_DIM);
            String perKm = s.speedKmh > 5 ? Str.get(R.string.hql_whkm_now, m.batteryKw * 1000 / s.speedKmh) : Str.get(R.string.hql_stopped);
            String avg = Double.isNaN(avgWhKm) ? "" : " · " + Str.get(R.string.hql_whkm_avg, avgWhKm);
            cv.drawText(perKm + avg, col2, y + 110, p);
            y += 160;
            float barW = w - col2 - 40;
            y = bar(cv, col2, y, barW, Str.get(R.string.hql_air), m.aeroKw, 0xFF8AB4F8);
            y = bar(cv, col2, y, barW, "  " + Str.get(R.string.hql_from_wind), m.windKw, 0xFF5E97F6);
            y = bar(cv, col2, y, barW, Str.get(R.string.hql_rolling), m.rollKw, 0xFFA8C7FA);
            y = bar(cv, col2, y, barW, Str.get(R.string.hql_grade), m.gradeKw, 0xFFFDD663);
            y = bar(cv, col2, y, barW, Str.get(R.string.hql_acceleration), m.accelKw, 0xFFF28B82);
            y = bar(cv, col2, y, barW, Str.get(R.string.hql_hvac), m.hvacKw, 0xFF81C995);
            p.setTextSize(20);
            p.setColor(CarStyle.TEXT_DIM);
            cv.drawText(Str.get(R.string.hql_model_note), col2, y + 20, p);
        }

        private float big(Canvas cv, float x, float y, String label, String value, String sub) {
            p.setTextAlign(Paint.Align.LEFT);
            p.setColor(CarStyle.TEXT_DIM);
            p.setTextSize(24);
            cv.drawText(label, x, y, p);
            p.setColor(CarStyle.TEXT);
            p.setTextSize(56);
            cv.drawText(value, x, y + 62, p);
            p.setColor(CarStyle.TEXT_DIM);
            p.setTextSize(22);
            cv.drawText(sub, x, y + 96, p);
            return y + 170;
        }

        /** Barra centrada en cero: a la derecha consume, a la izquierda recupera. Escala ±40 kW. */
        private float bar(Canvas cv, float x, float y, float width, String label, double kw, int color) {
            p.setTextAlign(Paint.Align.LEFT);
            p.setColor(CarStyle.TEXT);
            p.setTextSize(24);
            cv.drawText(label, x, y + 26, p);
            float bx = x + 190;
            float bw = width - 190 - 110;
            float mid = bx + bw / 2;
            p.setColor(0xFF2E3238);
            cv.drawRoundRect(new RectF(bx, y + 6, bx + bw, y + 30), 8, 8, p);
            float len = (float) Math.max(-1, Math.min(1, kw / 40)) * bw / 2;
            p.setColor(color);
            cv.drawRect(Math.min(mid, mid + len), y + 6, Math.max(mid, mid + len), y + 30, p);
            p.setColor(CarStyle.TEXT_DIM);
            p.setTextAlign(Paint.Align.RIGHT);
            cv.drawText(String.format(Locale.getDefault(), "%+.1f kW", kw), x + width, y + 26, p);
            return y + 46;
        }

        private static String compass(double deg) {
            String[] d = Str.get(R.string.hql_compass).split(",");
            return d[(int) Math.round(((deg % 360) + 360) % 360 / 45) % 8];
        }
    }
}
