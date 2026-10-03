package com.c10link.link;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import java.util.Locale;

/**
 * Eficiencia con los sensores del móvil y el tiempo (Open-Meteo): altitud, pendiente, desnivel,
 * viento de cara/cola y temperatura, y un reparto ESTIMADO de la potencia que pide el coche
 * (aerodinámica con el viento, rodadura, pendiente, aceleración) con un modelo físico del C10.
 * No hay datos del coche: es una estimación y así se indica en pantalla.
 */
final class EfficiencyScreen implements CarScreen {
    /** Modelo físico aproximado del Leapmotor C10 (valores públicos y estimados). */
    static final class Model {
        double massKg = 1985 + 90;   // en orden de marcha + ocupantes
        double cda = 0.27 * 2.65;    // Cd estimado × área frontal (m²)
        double crr = 0.009;          // neumáticos de baja resistencia
        double driveEff = 0.88;      // batería → rueda
        double regenEff = 0.65;      // rueda → batería
        double auxKw = 0.4;          // electrónica (sin climatización)

        double aeroKw;
        double rollKw;
        double gradeKw;
        double accelKw;
        double windKw;               // parte de la aerodinámica debida al viento
        double batteryKw;

        void compute(CarSensors.Snapshot s) {
            double v = s.speedKmh / 3.6;
            double vw = Double.isNaN(s.headwindKmh) ? 0 : s.headwindKmh / 3.6;
            double t = Double.isNaN(s.tempC) ? 15 : s.tempC;
            double rho = 101325 / (287.05 * (273.15 + t));
            double theta = Math.atan(s.gradePct / 100);
            double air = v + vw;
            aeroKw = 0.5 * rho * cda * air * Math.abs(air) * v / 1000;
            double still = 0.5 * rho * cda * v * v * v / 1000;
            windKw = aeroKw - still;
            rollKw = v > 0.3 ? crr * massKg * 9.81 * Math.cos(theta) * v / 1000 : 0;
            gradeKw = massKg * 9.81 * Math.sin(theta) * v / 1000;
            accelKw = massKg * s.longG * 9.81 * v / 1000;
            double wheel = aeroKw + rollKw + gradeKw + accelKw;
            batteryKw = (wheel >= 0 ? wheel / driveEff : wheel * regenEff) + auxKw;
        }
    }

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
        Model m = view.m;
        m.compute(s);
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
        final Model m = new Model();
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
            y = big(cv, col1, y, "Altitud", Double.isNaN(s.altitudeM) ? "—" : String.format(Locale.getDefault(), "%.0f m", s.altitudeM),
                    s.baro ? "barómetro" : "GPS");
            y = big(cv, col1, y, "Pendiente", String.format(Locale.getDefault(), "%+.1f %%", s.gradePct),
                    String.format(Locale.getDefault(), "subidos %.0f m · bajados %.0f m", s.climbM, s.descentM));
            String wind;
            String windSub;
            if (Double.isNaN(s.headwindKmh)) {
                wind = "—";
                windSub = "sin datos todavía";
            } else {
                wind = String.format(Locale.getDefault(), "%.0f km/h %s", Math.abs(s.headwindKmh), s.headwindKmh >= 0 ? "de cara" : "de cola");
                windSub = String.format(Locale.getDefault(), "viento %.0f km/h desde %s · %.0f °C", s.windKmh, compass(s.windFromDeg), s.tempC);
            }
            big(cv, col1, y, "Viento", wind, windSub);

            // Columna 2: potencia estimada.
            y = 70;
            p.setTextAlign(Paint.Align.LEFT);
            p.setColor(CarStyle.TEXT_DIM);
            p.setTextSize(24);
            cv.drawText("Potencia estimada (sin datos del coche)", col2, y, p);
            p.setColor(CarStyle.TEXT);
            p.setTextSize(64);
            cv.drawText(String.format(Locale.getDefault(), "%.1f kW", m.batteryKw), col2, y + 70, p);
            p.setTextSize(26);
            p.setColor(CarStyle.TEXT_DIM);
            String perKm = s.speedKmh > 5 ? String.format(Locale.getDefault(), "%.0f Wh/km ahora", m.batteryKw * 1000 / s.speedKmh) : "parado";
            String avg = Double.isNaN(avgWhKm) ? "" : String.format(Locale.getDefault(), " · media %.0f Wh/km", avgWhKm);
            cv.drawText(perKm + avg, col2, y + 110, p);
            y += 160;
            float barW = w - col2 - 40;
            y = bar(cv, col2, y, barW, "Aire", m.aeroKw, 0xFF8AB4F8);
            y = bar(cv, col2, y, barW, "  del viento", m.windKw, 0xFF5E97F6);
            y = bar(cv, col2, y, barW, "Rodadura", m.rollKw, 0xFFA8C7FA);
            y = bar(cv, col2, y, barW, "Pendiente", m.gradeKw, 0xFFFDD663);
            y = bar(cv, col2, y, barW, "Aceleración", m.accelKw, 0xFFF28B82);
            p.setTextSize(20);
            p.setColor(CarStyle.TEXT_DIM);
            cv.drawText("Modelo C10: 2.075 kg, CdA 0,72 m², Crr 0,009. Negativo = recuperas energía.", col2, y + 20, p);
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
            String[] d = {"el N", "el NE", "el E", "el SE", "el S", "el SO", "el O", "el NO"};
            return d[(int) Math.round(((deg % 360) + 360) % 360 / 45) % 8];
        }
    }
}
