package com.headqlink.link;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.andrerinas.openheadunit.R;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Mapa de la ruta del plan (pestaña Ruta › Mapa): solo para mirarla y compararla con la de Google Maps, no para navegar.
 * El trazado que usa el plan (lo ya hecho en gris), las paradas de carga con el % de llegada y de salida y los minutos,
 * el destino y «estás aquí», que se mueve cada pocos segundos. Mapa de OpenStreetMap oscuro, como el de los viajes.
 */
final class RouteMap {
    private RouteMap() {
    }

    /** Ventana a pantalla completa sobre la pestaña; onClose la quita. */
    static View panel(Context ctx, RoutePlanner planner, RoutePlanner.Plan plan, ChargePlanner.Result charge, Runnable onClose) {
        FrameLayout panel = new FrameLayout(ctx);
        panel.setBackgroundColor(CarKit.BG);
        panel.setClickable(true);
        WebView web = web(ctx, html(plan, charge, planner));
        panel.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout bar = CarKit.row(ctx);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(18, 12, 18, 12);
        bar.setBackground(CarStyle.round(0xE60A0E14, 26));
        TextView title = CarStyle.text(ctx, Str.get(R.string.hql_route_map_title), 28, CarKit.TEXT);
        title.setTypeface(CarKit.MEDIUM);
        bar.addView(title);
        TextView km = CarStyle.text(ctx, String.format(Locale.getDefault(), "  ·  %.0f km", plan.totalKm), 24, CarKit.DIM);
        bar.addView(km);
        TextView close = CarKit.pill(ctx, Str.get(R.string.hql_close), true);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = 24;
        bar.addView(close, clp);
        panel.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START));
        ((FrameLayout.LayoutParams) bar.getLayoutParams()).setMargins(20, 18, 0, 0);

        TextView note = CarStyle.text(ctx, Str.get(R.string.hql_route_map_note), 20, CarKit.DIM);
        note.setPadding(18, 10, 18, 10);
        note.setBackground(CarStyle.round(0xD90A0E14, 18));
        note.setMaxWidth(900);
        FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        nlp.setMargins(20, 0, 0, 18);
        panel.addView(note, nlp);

        // «Estás aquí» al día cada 3 s mientras el mapa esté abierto.
        Runnable[] tick = new Runnable[1];
        tick[0] = () -> {
            if (!panel.isAttachedToWindow()) return;
            CarSensors.Snapshot s = planner.sensorSnapshot();
            if (!Double.isNaN(s.lat)) {
                web.evaluateJavascript(String.format(Locale.US, "if(window.here)here(%.6f,%.6f,%d);", s.lat, s.lon,
                        Math.max(0, plan.progress)), null);
            }
            panel.postDelayed(tick[0], 3000);
        };
        panel.postDelayed(tick[0], 1500);
        close.setOnClickListener(v -> {
            panel.removeCallbacks(tick[0]);
            web.destroy();
            onClose.run();
        });
        return panel;
    }

    @SuppressLint("SetJavaScriptEnabled")
    private static WebView web(Context ctx, String html) {
        WebView w = new WebView(ctx);
        w.setBackgroundColor(CarKit.SURFACE_HI);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setAllowFileAccess(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        // Las teselas de OpenStreetMap piden una aplicación identificable (política de uso de tile.openstreetmap.org).
        s.setUserAgentString(s.getUserAgentString() + " " + Http.USER_AGENT);
        w.setWebViewClient(new WebViewClient());
        w.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "utf-8", null);
        return w;
    }

    /** La página con los datos dentro: trazado, paradas, destino y la posición de ahora. */
    static String html(RoutePlanner.Plan p, ChargePlanner.Result charge, RoutePlanner planner) {
        JSONObject d = new JSONObject();
        try {
            JSONArray line = new JSONArray();
            for (int i = 0; i < p.n; i++) line.put(new JSONArray().put(round(p.lat[i])).put(round(p.lon[i])));
            d.put("line", line);
            d.put("progress", Math.max(0, p.progress));
            JSONArray st = new JSONArray();
            if (charge != null) {
                for (ChargePlanner.Stop s : charge.stops) {
                    String label = String.format(Locale.getDefault(), "%.0f→%.0f %% · %.0f min", s.arrivePct, s.departPct, s.minutes);
                    st.put(new JSONObject().put("lat", s.charger.lat).put("lon", s.charger.lon).put("label", label)
                            .put("name", RoutePlanner.chargerName(s.charger)));
                }
            }
            d.put("stops", st);
            CarSensors.Snapshot s = planner.sensorSnapshot();
            if (!Double.isNaN(s.lat)) d.put("here", new JSONArray().put(round(s.lat)).put(round(s.lon)));
            d.put("tiles", !DemoMode.active() || DemoMode.live());
        } catch (JSONException e) {
            L.w("ruta: mapa sin datos: " + e.getClass().getSimpleName());
        }
        return "<!doctype html><html><head><meta charset=\"utf-8\"/>"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no\"/>"
                + "<link rel=\"stylesheet\" href=\"./leaflet/leaflet.css\"/><script src=\"./leaflet/leaflet.js\"></script>"
                + "<style>html,body,#map{height:100%;margin:0;padding:0;background:#17202B}"
                + ".leaflet-container{background:#17202B;font:600 15px sans-serif}"
                + ".leaflet-tile-pane{filter:invert(1) hue-rotate(180deg) brightness(.82) contrast(.9) saturate(.5)}"
                + ".leaflet-control-attribution{background:rgba(10,14,20,.7)!important;color:#8296A9!important;font-size:11px}"
                + ".leaflet-control-attribution a{color:#A3B3C2!important}"
                + ".stop{background:#FFB547;color:#1A1203;border-radius:14px;padding:4px 11px;font:700 17px sans-serif;"
                + "white-space:nowrap;box-shadow:0 2px 6px rgba(0,0,0,.5)}.stop small{font:600 13px sans-serif;display:block;opacity:.8}"
                + "</style></head><body><div id=\"map\"></div><script>"
                + "var D=" + d + ";"
                + "var map=L.map('map',{zoomControl:false,attributionControl:true});"
                + "if(D.tiles){L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,"
                + "attribution:'&copy; OpenStreetMap'}).addTo(map);}"
                // Lo ya hecho en gris y lo que falta en el color de la app.
                + "var done=L.polyline([],{color:'#5E6B78',weight:6,opacity:.9}).addTo(map);"
                + "var rest=L.polyline([],{color:'#00E5C7',weight:7,opacity:.95,lineJoin:'round'}).addTo(map);"
                + "function split(k){k=Math.max(0,Math.min(D.line.length-1,k));done.setLatLngs(D.line.slice(0,k+1));"
                + "rest.setLatLngs(D.line.slice(k));}"
                + "split(D.progress);"
                + "var b=D.line[D.line.length-1];"
                + "L.circleMarker(b,{radius:12,color:'#0A0E14',weight:3,fillColor:'#4CFF9F',fillOpacity:1}).addTo(map);"
                + "function esc(t){return String(t).replace(/[&<>\"]/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;'}[c];});}"
                + "D.stops.forEach(function(s,i){L.marker([s.lat,s.lon],{icon:L.divIcon({className:'',html:"
                + "'<div class=\"stop\">⚡ '+(D.stops.length>1?'P'+(i+1)+' · ':'')+esc(s.label)+'<small>'+esc(s.name)+'</small></div>',"
                + "iconSize:null,iconAnchor:[20,16]})}).addTo(map);});"
                + "var me=null;function here(la,lo,k){if(!me){me=L.circleMarker([la,lo],{radius:10,color:'#0A0E14',weight:3,"
                + "fillColor:'#EAF2F7',fillOpacity:1}).addTo(map);}else{me.setLatLng([la,lo]);}split(k);}"
                + "if(D.here)here(D.here[0],D.here[1],D.progress);"
                + "map.fitBounds(L.polyline(D.line).getBounds(),{padding:[70,70]});"
                + "</script></body></html>";
    }

    private static double round(double v) {
        return Math.round(v * 1e5) / 1e5;
    }
}
