package com.headqlink.link;

import com.andrerinas.openheadunit.R;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

/**
 * Un viaje en grande (al pulsar su tarjeta en Viajes): el recorrido sobre un mapa de OpenStreetMap (Leaflet, el mismo
 * del selector de mapas) con la salida, la llegada y las paradas (TripStops) con su duración; al lado, las cifras: km,
 * tiempo, velocidades, el consumo (el del coche si lo da su historial, el real o el estimado), la gasolina de un REEV,
 * el coste, el desnivel y la lista de paradas. En las capturas no se piden teselas (sin red): solo el trazado.
 */
final class TripDetail {
    private final Context ctx;
    private final Config cfg;
    private final TripLog.Trip t;
    private final List<CloudHistory.Trip> history;
    private final List<TripStops.Stop> stops;
    private final Runnable onBack;
    private final java.text.DateFormat dayFmt;
    private final java.text.DateFormat timeFmt;
    private WebView web;
    private final CarKit.Para note = new CarKit.Para(21, CarKit.FAINT, CarKit.REGULAR);

    TripDetail(Context ctx, TripLog.Trip t, List<CloudHistory.Trip> history, Runnable onBack) {
        this.ctx = ctx;
        this.cfg = new Config(ctx);
        this.t = t;
        this.history = history;
        this.stops = TripStops.find(t.track);
        this.onBack = onBack;
        Locale loc = Locale.getDefault();
        dayFmt = new java.text.SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(loc, "EEEEdMMMM"), loc);
        timeFmt = android.text.format.DateFormat.getTimeFormat(ctx);
    }

    /** La vista completa (mapa y cifras). */
    View create() {
        LinearLayout row = CarKit.row(ctx);
        FrameLayout mapCard = CarKit.add(row, new CarKit.Card(ctx, null, null), 1.6f, 0);
        mapCard.setPadding(4, 4, 4, 4);
        if (t.track.length >= 2) {
            web = map();
            mapCard.addView(web, CarStyle.match());
        } else {
            mapCard.addView(CarStyle.message(ctx, Str.get(R.string.hql_trip_no_track)), CarStyle.match());
        }
        LinearLayout right = CarKit.add(row, CarKit.col(ctx), 1f, 0);
        CarKit.Card info = CarKit.add(right, new CarKit.Card(ctx, null, this::paintInfo), 1f, 0);
        TextView back = CarKit.pill(ctx, "‹ " + Str.get(R.string.hql_tab_trips), false);
        back.setOnClickListener(v -> close());
        info.addView(back, CarKit.at(Gravity.TOP | Gravity.END, 0, 0, 0, 0));
        return CarKit.page(ctx, row);
    }

    void close() {
        if (web != null) {
            web.stopLoading();
            web.destroy();
            web = null;
        }
        onBack.run();
    }

    // ------------------------------------------------------------------ mapa

    @SuppressLint("SetJavaScriptEnabled")
    private WebView map() {
        WebView w = new WebView(ctx);
        w.setBackgroundColor(CarKit.SURFACE_HI);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setAllowFileAccess(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        // Las teselas de OpenStreetMap piden una aplicación identificable (política de uso de tile.openstreetmap.org).
        s.setUserAgentString(s.getUserAgentString() + " " + Http.USER_AGENT);
        w.setWebViewClient(new WebViewClient());
        w.setVerticalScrollBarEnabled(false);
        w.setHorizontalScrollBarEnabled(false);
        w.loadDataWithBaseURL("file:///android_asset/", html(), "text/html", "utf-8", null);
        return w;
    }

    /** La página del mapa con los datos del viaje dentro (sin puente con Java: no hace falta). */
    private String html() {
        JSONObject d = new JSONObject();
        try {
            JSONArray pts = new JSONArray();
            for (double[] q : t.track) pts.put(new JSONArray().put(q[0]).put(q[1]));
            d.put("track", pts);
            JSONArray st = new JSONArray();
            for (TripStops.Stop s : stops) {
                st.put(new JSONObject().put("lat", s.lat).put("lon", s.lon).put("label", DriveTab.duration((long) s.durationSec)));
            }
            d.put("stops", st);
            // Sin teselas en las capturas (sin red); la vista previa interactiva sí las pide.
            d.put("tiles", !DemoMode.active() || DemoMode.live());
        } catch (JSONException e) {
            L.w("viaje: mapa sin datos: " + e);
        }
        return "<!doctype html><html><head><meta charset=\"utf-8\"/>"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no\"/>"
                + "<link rel=\"stylesheet\" href=\"./leaflet/leaflet.css\"/><script src=\"./leaflet/leaflet.js\"></script>"
                + "<style>html,body,#map{height:100%;margin:0;padding:0;background:#17202B}"
                + ".leaflet-container{background:#17202B;font:600 15px sans-serif}"
                // Mapa oscuro: las teselas normales de OSM invertidas y apagadas, a juego con la pantalla del coche.
                + ".leaflet-tile-pane{filter:invert(1) hue-rotate(180deg) brightness(.82) contrast(.9) saturate(.5)}"
                + ".leaflet-control-attribution{background:rgba(10,14,20,.7)!important;color:#8296A9!important;font-size:11px}"
                + ".leaflet-control-attribution a{color:#A3B3C2!important}"
                + ".stop{background:#FFB547;color:#1A1203;border-radius:14px;padding:3px 10px;font:700 16px sans-serif;"
                + "white-space:nowrap;box-shadow:0 2px 6px rgba(0,0,0,.5)}"
                + "</style></head><body><div id=\"map\"></div><script>"
                + "var D=" + d + ";"
                + "var map=L.map('map',{zoomControl:false,attributionControl:true});"
                + "if(D.tiles){L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,"
                + "attribution:'&copy; OpenStreetMap'}).addTo(map);}"
                + "var line=L.polyline(D.track,{color:'#00E5C7',weight:7,opacity:.95,lineJoin:'round'}).addTo(map);"
                + "L.polyline(D.track,{color:'#00211C',weight:2,opacity:.35}).addTo(map);"
                + "var a=D.track[0],b=D.track[D.track.length-1];"
                + "L.circleMarker(a,{radius:9,color:'#0A0E14',weight:3,fillColor:'#EAF2F7',fillOpacity:1}).addTo(map);"
                + "L.circleMarker(b,{radius:11,color:'#0A0E14',weight:3,fillColor:'#4CFF9F',fillOpacity:1}).addTo(map);"
                + "D.stops.forEach(function(s,i){L.marker([s.lat,s.lon],{icon:L.divIcon({className:'',html:"
                + "'<div class=\"stop\">P'+(D.stops.length>1?(i+1):'')+' · '+s.label+'</div>',iconSize:null,iconAnchor:[20,14]})})"
                + ".addTo(map);});"
                + "map.fitBounds(line.getBounds(),{padding:[48,48]});"
                + "</script></body></html>";
    }

    // ------------------------------------------------------------------ cifras

    private void paintInfo(Canvas cv, RectF r, Paint p) {
        float x = r.left;
        float y = r.top + 20;
        CarKit.label(cv, dayFmt.format(new java.util.Date(t.startMs)), x, y, CarKit.FAINT, p, Paint.Align.LEFT, r.width() - 200);
        String when = timeFmt.format(new java.util.Date(t.startMs)) + " → " + timeFmt.format(new java.util.Date(t.endMs()));
        CarKit.text(cv, when, x, y + 40, 28, CarKit.TEXT, CarKit.MEDIUM, p, Paint.Align.LEFT);
        // Distancia y tiempo, en grande.
        y += 122;
        float w = CarKit.number(cv, String.format(Locale.getDefault(), "%.1f", t.km), "km", x, y, 64, CarKit.TEXT, CarKit.REGULAR, p,
                Paint.Align.LEFT);
        CarKit.text(cv, DriveTab.duration(t.minutes * 60), x + w + 28, y - 4, 32, CarKit.DIM, CarKit.MEDIUM, p, Paint.Align.LEFT);
        // Consumo: el del coche, el real o el estimado, con su fuente.
        y += 70;
        TripStats.Source src = TripStats.source(t, history);
        double per100 = TripStats.bestKwhPer100(t, history);
        int src1 = src == TripStats.Source.CAR ? R.string.hql_trip_source_car : src == TripStats.Source.REAL
                ? R.string.hql_trip_source_real : R.string.hql_trip_source_estimated;
        CarKit.label(cv, Str.get(R.string.hql_trip_consumption), x, y, p);
        float cw = CarKit.number(cv, Double.isNaN(per100) ? "—" : String.format(Locale.getDefault(), "%.1f", per100), "kWh/100 km", x,
                y + 50, 46, colorFor(per100), CarKit.MEDIUM, p, Paint.Align.LEFT);
        CarKit.text(cv, Str.get(src1), x + cw + 20, y + 46, 22, src == TripStats.Source.ESTIMATED ? CarKit.FAINT : CarKit.ACCENT,
                CarKit.MEDIUM, p, Paint.Align.LEFT);
        y += 50;
        // Gasolina (REEV).
        double fuel = TripStats.fuelL(t, history);
        if (!Double.isNaN(fuel)) {
            y += 52;
            CarIcons.fuel(cv, x + 14, y - 12, 28, CarKit.AMBER, p);
            String ft = fuel < 0.05 ? Str.get(R.string.hql_trip_fuel_none)
                    : Str.get(R.string.hql_trip_fuel, fuel, TripStats.litersPer100(t, history));
            CarKit.text(cv, ft, x + 40, y, 27, fuel < 0.05 ? CarKit.DIM : CarKit.TEXT, CarKit.MEDIUM, p, Paint.Align.LEFT);
        }
        // Fila de cifras: velocidad media, máxima, desnivel y coste.
        y += 70;
        double avg = TripStats.avgKmh(t);
        float col = r.width() / 4f;
        stat(cv, x, y, Str.get(R.string.hql_trip_avg), Double.isNaN(avg) ? "—" : String.format(Locale.getDefault(), "%.0f", avg), "km/h", p);
        stat(cv, x + col, y, Str.get(R.string.hql_trip_max), String.format(Locale.getDefault(), "%.0f", t.maxKmh), "km/h", p);
        stat(cv, x + 2 * col, y, Str.get(R.string.hql_trip_climb), "↑" + String.format(Locale.getDefault(), "%.0f", t.climb), "m", p);
        double cost = TripStats.cost(t, history, cfg.electricityPrice(), cfg.fuelPrice());
        stat(cv, x + 3 * col, y, Str.get(R.string.hql_trip_cost_label), String.format(Locale.getDefault(), "%.2f", cost), "€", p);
        // Paradas.
        y += 70;
        p.setColor(CarKit.OUTLINE);
        cv.drawRect(r.left, y, r.right, y + 2, p);
        y += 44;
        if (stops.isEmpty()) {
            boolean timed = t.track.length > 0 && t.track[0].length > 2 && !Double.isNaN(t.track[0][2]);
            note.draw(cv, Str.get(timed ? R.string.hql_trip_no_stops : R.string.hql_trip_stops_old), x, y - 26, (int) r.width(), false);
            return;
        }
        CarKit.label(cv, Str.get(R.string.hql_trip_stops, stops.size(), DriveTab.duration((long) TripStops.totalSec(stops))), x, y, p);
        y += 16;
        for (int i = 0; i < stops.size() && y + 44 < r.bottom; i++) {
            TripStops.Stop s = stops.get(i);
            y += 44;
            p.setColor(CarKit.alpha(CarKit.AMBER, 0.18f));
            cv.drawCircle(x + 18, y - 10, 18, p);
            CarKit.text(cv, "P" + (stops.size() > 1 ? String.valueOf(i + 1) : ""), x + 18, y - 2, 20, CarKit.AMBER, CarKit.MEDIUM, p,
                    Paint.Align.CENTER);
            String at = timeFmt.format(new java.util.Date(t.startMs + Math.round(s.atSec * 1000)));
            CarKit.text(cv, Str.get(R.string.hql_trip_stop_line, DriveTab.duration((long) s.durationSec), at, s.km), x + 48, y, 25,
                    CarKit.TEXT, CarKit.MEDIUM, p, Paint.Align.LEFT);
        }
    }

    private static void stat(Canvas cv, float x, float y, String label, String v, String unit, Paint p) {
        CarKit.label(cv, label, x, y, p);
        CarKit.number(cv, v, unit, x, y + 42, 32, CarKit.TEXT, CarKit.MEDIUM, p, Paint.Align.LEFT);
    }

    private static int colorFor(double v) {
        if (Double.isNaN(v)) return CarKit.MUTED;
        if (v < 15) return CarKit.GREEN;
        if (v < 21) return CarKit.ACCENT;
        if (v < 27) return CarKit.AMBER;
        return CarKit.RED;
    }
}
