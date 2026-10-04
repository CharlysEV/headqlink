package com.headqlink.link;

import com.andrerinas.openheadunit.R;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Pestaña "Viajes": los últimos viajes (TripLog) con su recorrido, km, tiempo, desnivel, energía
 * estimada, consumo y coste frente a un coche de gasolina (precios en Config).
 */
final class TripsTab implements CarScreen {
    @Override
    public View create(Host h) {
        Context c = h.context();
        List<TripLog.Trip> trips = TripLog.recent(c, 30);
        if (trips.isEmpty()) {
            return CarStyle.message(c, Str.get(R.string.hql_trips_none));
        }
        Config cfg = new Config(c);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(24, 8, 24, 24);
        SimpleDateFormat fmt = new SimpleDateFormat("EEEE d MMM · HH:mm", Locale.getDefault());
        for (TripLog.Trip t : trips) {
            LinearLayout card = CarStyle.card(c);
            card.setOrientation(LinearLayout.HORIZONTAL);
            Track track = new Track(c, t.track);
            card.addView(track, new LinearLayout.LayoutParams(260, 180));
            LinearLayout info = new LinearLayout(c);
            info.setOrientation(LinearLayout.VERTICAL);
            info.setPadding(28, 0, 0, 0);
            info.addView(CarStyle.label(c, fmt.format(new Date(t.startMs))));
            TextView main = CarStyle.text(c, Str.get(R.string.hql_trip_line, t.km, t.minutes, t.maxKmh), 32, CarStyle.TEXT);
            info.addView(main);
            double whKm = t.km > 0 ? t.kwh * 1000 / t.km : 0;
            double cost = t.kwh * cfg.electricityPrice();
            double fuel = t.km * cfg.fuelLitersPer100() / 100 * cfg.fuelPrice();
            info.addView(CarStyle.text(c, String.format(Locale.getDefault(),
                    "~%.1f kWh · %.0f Wh/km · ↑%.0f m ↓%.0f m", t.kwh, whKm, t.climb, t.descent), 24, CarStyle.TEXT_DIM));
            info.addView(CarStyle.text(c, Str.get(R.string.hql_trip_cost, cost, fuel), 24, CarStyle.GOOD));
            card.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = 16;
            col.addView(card, lp);
        }
        TextView note = CarStyle.text(c, Str.get(R.string.hql_trips_note, cfg.electricityPrice(), cfg.fuelLitersPer100(), cfg.fuelPrice()), 20, CarStyle.TEXT_DIM);
        col.addView(note);
        ScrollView sv = new ScrollView(c);
        sv.addView(col);
        return sv;
    }

    /** Recorrido dibujado sin mapa de fondo, ajustado a la caja. */
    private static final class Track extends View {
        private final double[][] pts;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Track(Context c, double[][] pts) {
            super(c);
            this.pts = pts;
        }

        @Override
        protected void onDraw(Canvas cv) {
            p.setColor(0xFF1E1F22);
            cv.drawRoundRect(0, 0, getWidth(), getHeight(), 20, 20, p);
            if (pts.length < 2) return;
            double minLa = 90, maxLa = -90, minLo = 180, maxLo = -180;
            for (double[] q : pts) {
                minLa = Math.min(minLa, q[0]);
                maxLa = Math.max(maxLa, q[0]);
                minLo = Math.min(minLo, q[1]);
                maxLo = Math.max(maxLo, q[1]);
            }
            double cos = Math.cos(Math.toRadians((minLa + maxLa) / 2));
            double spanX = Math.max(1e-6, (maxLo - minLo) * cos);
            double spanY = Math.max(1e-6, maxLa - minLa);
            float pad = 18;
            double scale = Math.min((getWidth() - 2 * pad) / spanX, (getHeight() - 2 * pad) / spanY);
            float offX = (float) ((getWidth() - spanX * scale) / 2);
            float offY = (float) ((getHeight() - spanY * scale) / 2);
            Path path = new Path();
            for (int i = 0; i < pts.length; i++) {
                float x = (float) (offX + (pts[i][1] - minLo) * cos * scale);
                float y = (float) (getHeight() - offY - (pts[i][0] - minLa) * scale);
                if (i == 0) path.moveTo(x, y);
                else path.lineTo(x, y);
            }
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(5);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setColor(CarStyle.ACCENT);
            cv.drawPath(path, p);
            p.setStyle(Paint.Style.FILL);
        }
    }
}
