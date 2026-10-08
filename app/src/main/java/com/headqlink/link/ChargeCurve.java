package com.headqlink.link;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * La curva de la carga en directo: kW que entran frente a %, lo que va dando de verdad (línea llena) y lo que se espera
 * del C10 en este cargador (discontinua: la curva del coche limitada por el cargador y, en uno de 400–500 V, a la
 * mitad), con una marca en el % para seguir (verde) y en el que ahorra la siguiente parada.
 */
final class ChargeCurve extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final DashPathEffect dash = new DashPathEffect(new float[]{12, 10}, 0);
    private ChargeSession session;

    ChargeCurve(Context c) {
        super(c);
    }

    void setSession(ChargeSession s) {
        session = s;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas cv) {
        ChargeSession s = session;
        if (s == null) return;
        float left = 64;
        float right = getWidth() - 12;
        float top = 12;
        float bottom = getHeight() - 34;
        if (right - left < 60 || bottom - top < 40) return;
        List<ChargeSession.Sample> pts;
        synchronized (s) {
            pts = new ArrayList<>(s.samples);
        }
        // Ejes: % desde un poco antes del inicio hasta el 100; kW hasta lo más alto que se espera o se ha visto.
        double x0 = Math.max(0, Math.floor((s.startSoc - 5) / 10) * 10);
        double x1 = 100;
        double maxKw = s.expectedKw(Math.max(x0, 1));
        for (ChargeSession.Sample q : pts) if (!Double.isNaN(q.kw)) maxKw = Math.max(maxKw, q.kw);
        maxKw = Math.max(10, Math.ceil(maxKw * 1.15 / 10) * 10);
        p.setStrokeWidth(1);
        p.setStyle(Paint.Style.STROKE);
        p.setColor(CarKit.OUTLINE);
        for (int k = 0; k <= 4; k++) {
            float y = (float) (bottom - (bottom - top) * k / 4.0);
            cv.drawLine(left, y, right, y, p);
            String v = String.format(Locale.getDefault(), k == 4 ? "%.0f kW" : "%.0f", maxKw * k / 4);
            CarKit.text(cv, v, k == 4 ? left + 8 : left - 10, y + (k == 4 ? 22 : 7), 18, CarKit.FAINT, CarKit.REGULAR, p,
                    k == 4 ? Paint.Align.LEFT : Paint.Align.RIGHT);
            p.setStyle(Paint.Style.STROKE);
            p.setColor(CarKit.OUTLINE);
        }
        for (double pct = Math.ceil(x0 / 20) * 20; pct <= x1; pct += 20) {
            float x = xOf(pct, x0, x1, left, right);
            // El último, pegado al borde: alineado a la derecha para que no se corte.
            CarKit.text(cv, String.format(Locale.getDefault(), "%.0f %%", pct), x, getHeight() - 6, 18, CarKit.FAINT, CarKit.REGULAR, p,
                    pct >= x1 ? Paint.Align.RIGHT : Paint.Align.CENTER);
        }
        // Marcas: seguir (verde) y ahorrar parada (tenue).
        marker(cv, s.skipPct, x0, x1, left, right, top, bottom, CarKit.alpha(CarKit.DIM, 0.6f));
        marker(cv, s.targetPct, x0, x1, left, right, top, bottom, CarKit.GREEN);
        // Lo esperado (discontinua).
        path.reset();
        boolean first = true;
        for (double pct = x0; pct <= x1; pct += 1) {
            float x = xOf(pct, x0, x1, left, right);
            float y = yOf(s.expectedKw(pct), maxKw, top, bottom);
            if (first) path.moveTo(x, y);
            else path.lineTo(x, y);
            first = false;
        }
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3);
        p.setColor(CarKit.DIM);
        p.setPathEffect(dash);
        cv.drawPath(path, p);
        p.setPathEffect(null);
        // Lo real.
        path.reset();
        first = true;
        for (ChargeSession.Sample q : pts) {
            if (Double.isNaN(q.kw)) continue;
            float x = xOf(q.soc, x0, x1, left, right);
            float y = yOf(q.kw, maxKw, top, bottom);
            if (first) path.moveTo(x, y);
            else path.lineTo(x, y);
            first = false;
        }
        p.setStrokeWidth(5);
        p.setColor(s.slowFired ? CarKit.AMBER : CarKit.ACCENT);
        cv.drawPath(path, p);
        ChargeSession.Sample l = pts.isEmpty() ? null : pts.get(pts.size() - 1);
        if (l != null && !Double.isNaN(l.kw)) {
            p.setStyle(Paint.Style.FILL);
            cv.drawCircle(xOf(l.soc, x0, x1, left, right), yOf(l.kw, maxKw, top, bottom), 8, p);
        }
    }

    private void marker(Canvas cv, double pct, double x0, double x1, float left, float right, float top, float bottom, int color) {
        if (Double.isNaN(pct) || pct < x0 || pct > x1) return;
        float x = xOf(pct, x0, x1, left, right);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3);
        p.setColor(color);
        cv.drawLine(x, top, x, bottom, p);
    }

    private static float xOf(double pct, double x0, double x1, float left, float right) {
        return (float) (left + (right - left) * (Math.max(x0, Math.min(x1, pct)) - x0) / (x1 - x0));
    }

    private static float yOf(double kw, double maxKw, float top, float bottom) {
        return (float) (bottom - (bottom - top) * Math.max(0, Math.min(1, kw / maxKw)));
    }
}
