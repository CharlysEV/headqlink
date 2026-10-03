package com.c10link.link;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * Instrumentos con los sensores del móvil: velocímetro (GPS), medidor de fuerzas G con picos de
 * aceleración, frenada y curva, y cronómetro 0-50/0-100 automático.
 */
final class InstrumentsScreen implements CarScreen {
    private CarSensors sensors;
    private Gauges view;
    private boolean running;

    @Override
    public View create(Host h) {
        Context c = h.context();
        sensors = CarSensors.start(c);
        FrameLayout f = new FrameLayout(c);
        view = new Gauges(c);
        f.addView(view, CarStyle.match());
        TextView reset = CarStyle.pill(c, "Reiniciar viaje");
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        lp.setMargins(0, 0, 24, 24);
        f.addView(reset, lp);
        reset.setOnClickListener(v -> sensors.resetTrip());
        running = true;
        tick();
        return f;
    }

    private void tick() {
        if (!running) return;
        view.s = sensors.snapshot();
        view.invalidate();
        view.postDelayed(this::tick, 100);
    }

    @Override
    public void destroy() {
        running = false;
        CarSensors.stop();
    }

    private static final class Gauges extends View {
        CarSensors.Snapshot s = new CarSensors.Snapshot();
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Gauges(Context c) {
            super(c);
        }

        @Override
        protected void onDraw(Canvas cv) {
            int w = getWidth();
            int h = getHeight();
            float r = Math.min(w / 4f, h / 2f) - 50;
            float cx1 = w * 0.27f;
            float cx2 = w * 0.73f;
            float cy = h * 0.47f;
            speedometer(cv, cx1, cy, r);
            gMeter(cv, cx2, cy, r);
            p.setTextAlign(Paint.Align.LEFT);
            p.setColor(CarStyle.TEXT_DIM);
            p.setTextSize(24);
            String trip = String.format(Locale.getDefault(), "Viaje %.1f km · %d min · máx %.0f km/h",
                    s.tripKm, s.tripSec / 60, s.maxSpeedKmh);
            cv.drawText(trip, 30, h - 36, p);
            if (!s.timer.isEmpty()) {
                p.setColor(CarStyle.ACCENT);
                p.setTextSize(30);
                p.setTextAlign(Paint.Align.CENTER);
                cv.drawText(s.timer, w / 2f, 50, p);
            }
            if (!s.gps) {
                p.setColor(0xFFF28B82);
                p.setTextSize(24);
                p.setTextAlign(Paint.Align.CENTER);
                cv.drawText("Esperando al GPS del móvil (y su permiso de ubicación)…", w / 2f, 90, p);
            }
        }

        private void speedometer(Canvas cv, float cx, float cy, float r) {
            RectF arc = new RectF(cx - r, cy - r, cx + r, cy + r);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(26);
            p.setColor(0xFF2E3238);
            cv.drawArc(arc, 135, 270, false, p);
            float frac = (float) Math.min(1, s.speedKmh / 180);
            p.setColor(CarStyle.ACCENT);
            cv.drawArc(arc, 135, 270 * frac, false, p);
            p.setStrokeWidth(4);
            p.setColor(0xFFF28B82);
            float mf = (float) Math.min(1, s.maxSpeedKmh / 180);
            double ma = Math.toRadians(135 + 270 * mf);
            cv.drawLine(cx + (float) Math.cos(ma) * (r - 22), cy + (float) Math.sin(ma) * (r - 22),
                    cx + (float) Math.cos(ma) * (r + 22), cy + (float) Math.sin(ma) * (r + 22), p);
            p.setStyle(Paint.Style.FILL);
            p.setTextAlign(Paint.Align.CENTER);
            p.setColor(CarStyle.TEXT);
            p.setTextSize(r * 0.55f);
            cv.drawText(String.format(Locale.getDefault(), "%.0f", s.speedKmh), cx, cy + r * 0.18f, p);
            p.setTextSize(28);
            p.setColor(CarStyle.TEXT_DIM);
            cv.drawText("km/h", cx, cy + r * 0.42f, p);
        }

        private void gMeter(Canvas cv, float cx, float cy, float r) {
            float scale = r / 0.8f; // 0,8 g en el borde
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(3);
            p.setColor(0xFF3A3F46);
            for (float g = 0.2f; g <= 0.81f; g += 0.2f) cv.drawCircle(cx, cy, g * scale, p);
            cv.drawLine(cx - r, cy, cx + r, cy, p);
            cv.drawLine(cx, cy - r, cx, cy + r, p);
            p.setStyle(Paint.Style.FILL);
            // Arriba: frenada (el cuerpo va hacia delante); abajo: aceleración; izquierda/derecha: curva.
            float px = cx - (float) clamp(s.latG) * scale;
            float py = cy + (float) clamp(s.longG) * scale;
            p.setColor(CarStyle.ACCENT);
            cv.drawCircle(px, py, 16, p);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(24);
            p.setColor(CarStyle.TEXT_DIM);
            cv.drawText(String.format(Locale.getDefault(), "frenada máx %.2f g", s.maxBrakeG), cx, cy - r - 16, p);
            cv.drawText(String.format(Locale.getDefault(), "aceleración máx %.2f g", s.maxAccelG), cx, cy + r + 36, p);
            p.setTextAlign(Paint.Align.RIGHT);
            cv.drawText(String.format(Locale.getDefault(), "izq. %.2f g", s.maxLeftG), cx - r - 12, cy + 8, p);
            p.setTextAlign(Paint.Align.LEFT);
            cv.drawText(String.format(Locale.getDefault(), "dcha. %.2f g", s.maxRightG), cx + r + 12, cy + 8, p);
            p.setTextAlign(Paint.Align.CENTER);
            p.setColor(CarStyle.TEXT);
            p.setTextSize(30);
            double total = Math.hypot(s.latG, s.longG);
            cv.drawText(String.format(Locale.getDefault(), "%.2f g", total), cx, cy + r * 0.62f, p);
        }

        private static double clamp(double g) {
            return Math.max(-0.8, Math.min(0.8, g));
        }
    }
}
