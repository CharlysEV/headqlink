package com.headqlink.link;

import com.andrerinas.openheadunit.R;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

import com.andrerinas.openheadunit.aap.NavTap;

import java.util.Locale;

/**
 * Pestaña "Conducción": próxima maniobra de Android Auto (flecha, distancia, calle, carriles),
 * velocidad con el límite de la vía, aviso de radar y de sol de cara (RoadInfo).
 */
final class DriveTab implements CarScreen {
    private Panel view;
    private CarSensors sensors;
    private RoadInfo road;
    private boolean running;

    @Override
    public View create(Host h) {
        Context c = h.context();
        sensors = CarSensors.start(c);
        road = RoadInfo.start(c);
        view = new Panel(c);
        running = true;
        tick();
        return view;
    }

    private void tick() {
        if (!running) return;
        view.nav = NavTap.getInfo();
        view.s = sensors.snapshot();
        view.road = road.state();
        view.invalidate();
        view.postDelayed(this::tick, 250);
    }

    @Override
    public void destroy() {
        running = false;
        CarSensors.stop();
    }

    private static final class Panel extends View {
        NavTap.Info nav = new NavTap.Info();
        CarSensors.Snapshot s = new CarSensors.Snapshot();
        RoadInfo.State road = new RoadInfo.State();
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Panel(Context c) {
            super(c);
        }

        @Override
        protected void onDraw(Canvas cv) {
            int w = getWidth();
            int h = getHeight();
            float pad = 24;
            float split = w * 0.55f;
            RectF left = new RectF(pad, 8, split - 10, h - pad);
            RectF right = new RectF(split + 10, 8, w - pad, h - pad);
            card(cv, left);
            card(cv, right);
            maneuver(cv, left);
            speed(cv, right);
        }

        private void card(Canvas cv, RectF r) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(CarStyle.CARD);
            cv.drawRoundRect(r, 28, 28, p);
        }

        private void maneuver(Canvas cv, RectF r) {
            if (!nav.active) {
                text(cv, Str.get(R.string.hql_no_navigation), r.left + 32, r.top + 60, 30, CarStyle.TEXT, Paint.Align.LEFT);
                text(cv, Str.get(R.string.hql_no_navigation_hint), r.left + 32, r.top + 100, 22,
                        CarStyle.TEXT_DIM, Paint.Align.LEFT);
                return;
            }
            float cx = r.left + 170;
            float cy = r.top + 200;
            arrow(cv, cx, cy, 110);
            String dist = nav.stepDisplay != null ? nav.stepDisplay
                    : nav.stepMeters >= 0 ? (nav.stepMeters >= 1000 ? String.format(Locale.getDefault(), "%.1f km", nav.stepMeters / 1000.0)
                    : (nav.stepMeters / 10 * 10) + " m") : "";
            text(cv, dist, r.left + 320, r.top + 160, 72, CarStyle.TEXT, Paint.Align.LEFT);
            String next = nav.nextRoad != null ? nav.nextRoad : nav.road != null ? nav.road : "";
            text(cv, ellipsize(next, r.width() - 350, 30), r.left + 320, r.top + 215, 30, CarStyle.TEXT, Paint.Align.LEFT);
            if (nav.kind == NavTap.KIND_ROUNDABOUT && nav.roundaboutExit > 0) {
                text(cv, Str.get(R.string.hql_roundabout_exit, nav.roundaboutExit), r.left + 320, r.top + 255, 24, CarStyle.TEXT_DIM, Paint.Align.LEFT);
            }
            // Carriles.
            boolean[] lanes = nav.lanes;
            if (lanes != null && lanes.length > 0) {
                float lw = 46;
                float lx = r.left + 32;
                float ly = r.top + 360;
                for (int i = 0; i < lanes.length; i++) {
                    p.setColor(lanes[i] ? CarStyle.ACCENT : 0xFF5F6368);
                    cv.drawRoundRect(new RectF(lx + i * (lw + 10), ly, lx + i * (lw + 10) + lw, ly + 70), 10, 10, p);
                }
            }
            // Destino.
            String arrive = "";
            if (nav.remainingMeters >= 0) {
                arrive = Str.get(R.string.hql_remaining_km, nav.remainingMeters / 1000.0) + (nav.eta != null ? " · " + Str.get(R.string.hql_arrival, nav.eta) : "");
            }
            text(cv, arrive, r.left + 32, r.bottom - 70, 26, CarStyle.TEXT, Paint.Align.LEFT);
            if (nav.destination != null) {
                text(cv, ellipsize(nav.destination, r.width() - 64, 22), r.left + 32, r.bottom - 34, 22, CarStyle.TEXT_DIM, Paint.Align.LEFT);
            }
        }

        /** Flecha de maniobra: tallo vertical y giro según el ángulo (negativo a la izquierda). */
        private void arrow(Canvas cv, float cx, float cy, float size) {
            p.setColor(CarStyle.TEXT);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(22);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            float base = cy + size;
            float mid = cy;
            if (nav.kind == NavTap.KIND_ROUNDABOUT) {
                cv.drawCircle(cx, cy - size * 0.2f, size * 0.42f, p);
                cv.drawLine(cx, base, cx, cy + size * 0.22f, p);
                p.setStyle(Paint.Style.FILL);
                return;
            }
            if (nav.kind == NavTap.KIND_DESTINATION) {
                p.setStyle(Paint.Style.FILL);
                cv.drawCircle(cx + nav.angle * 0.5f, cy - size * 0.3f, size * 0.32f, p);
                p.setColor(CarStyle.CARD);
                cv.drawCircle(cx + nav.angle * 0.5f, cy - size * 0.3f, size * 0.13f, p);
                return;
            }
            Path path = new Path();
            float ex;
            float ey;
            double dir;
            if (nav.kind == NavTap.KIND_UTURN) {
                float side = nav.angle < 0 ? -1 : 1;
                path.moveTo(cx - side * size * 0.35f, base);
                path.lineTo(cx - side * size * 0.35f, cy - size * 0.4f);
                path.quadTo(cx, cy - size * 1.05f, cx + side * size * 0.35f, cy - size * 0.4f);
                path.lineTo(cx + side * size * 0.35f, cy + size * 0.1f);
                ex = cx + side * size * 0.35f;
                ey = cy + size * 0.1f;
                dir = Math.toRadians(180);
            } else {
                path.moveTo(cx, base);
                path.lineTo(cx, mid);
                dir = Math.toRadians(nav.angle);
                ex = (float) (cx + Math.sin(dir) * size * 0.85);
                ey = (float) (mid - Math.cos(dir) * size * 0.85);
                path.lineTo(ex, ey);
            }
            cv.drawPath(path, p);
            // Punta.
            p.setStyle(Paint.Style.FILL);
            Path head = new Path();
            double a1 = dir + Math.toRadians(150);
            double a2 = dir - Math.toRadians(150);
            float hs = size * 0.45f;
            head.moveTo((float) (ex + Math.sin(dir) * 14), (float) (ey - Math.cos(dir) * 14));
            head.lineTo((float) (ex + Math.sin(a1) * hs), (float) (ey - Math.cos(a1) * hs));
            head.lineTo((float) (ex + Math.sin(a2) * hs), (float) (ey - Math.cos(a2) * hs));
            head.close();
            cv.drawPath(head, p);
        }

        private void speed(Canvas cv, RectF r) {
            float cx = r.left + r.width() * 0.38f;
            float cy = r.top + 190;
            text(cv, String.format(Locale.getDefault(), "%.0f", s.speedKmh), cx, cy + 40, 140,
                    road.limitKmh > 0 && s.speedKmh > road.limitKmh * 1.05 ? CarStyle.BAD : CarStyle.TEXT, Paint.Align.CENTER);
            text(cv, "km/h", cx, cy + 90, 26, CarStyle.TEXT_DIM, Paint.Align.CENTER);
            // Señal de límite (círculo rojo, como la señal real).
            float sx = r.right - 130;
            float sy = r.top + 150;
            if (road.limitKmh > 0) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xFFFFFFFF);
                cv.drawCircle(sx, sy, 86, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(16);
                p.setColor(0xFFD93025);
                cv.drawCircle(sx, sy, 78, p);
                p.setStyle(Paint.Style.FILL);
                text(cv, String.valueOf(road.limitKmh), sx, sy + 26, 70, 0xFF202124, Paint.Align.CENTER);
                if (road.limitEstimated) text(cv, Str.get(R.string.hql_estimated), sx, sy + 120, 20, CarStyle.TEXT_DIM, Paint.Align.CENTER);
            }
            float y = r.top + 350;
            if (!road.roadName.isEmpty()) {
                text(cv, ellipsize(road.roadName, r.width() - 64, 26), r.left + 32, y, 26, CarStyle.TEXT, Paint.Align.LEFT);
                y += 50;
            }
            if (road.cameraM >= 0) {
                alert(cv, r, y, CarStyle.WARN, String.format(Locale.getDefault(), "%s%s", Str.get(R.string.hql_camera_at, road.cameraM),
                        road.cameraLimit > 0 ? " · " + road.cameraLimit + " km/h" : ""));
                y += 80;
            }
            if (road.sunGlare) {
                alert(cv, r, y, CarStyle.WARN, Str.get(R.string.hql_sun_glare));
                y += 80;
            }
            String sun = road.sunset.isEmpty() ? "" : Str.get(R.string.hql_sunset, road.sunset);
            text(cv, sun, r.left + 32, r.bottom - 30, 22, CarStyle.TEXT_DIM, Paint.Align.LEFT);
            if (!s.gps) text(cv, Str.get(R.string.hql_waiting_phone_gps), r.left + 32, r.bottom - 64, 22, CarStyle.BAD, Paint.Align.LEFT);
        }

        private void alert(Canvas cv, RectF r, float y, int color, String msg) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF3C3420);
            cv.drawRoundRect(new RectF(r.left + 24, y - 40, r.right - 24, y + 22), 20, 20, p);
            text(cv, msg, r.left + 48, y, 26, color, Paint.Align.LEFT);
        }

        private void text(Canvas cv, String s, float x, float y, float size, int color, Paint.Align align) {
            p.setStyle(Paint.Style.FILL);
            p.setTextSize(size);
            p.setColor(color);
            p.setTextAlign(align);
            cv.drawText(s, x, y, p);
        }

        private String ellipsize(String s, float max, float size) {
            p.setTextSize(size);
            if (p.measureText(s) <= max) return s;
            while (s.length() > 3 && p.measureText(s + "…") > max) s = s.substring(0, s.length() - 1);
            return s + "…";
        }
    }
}
