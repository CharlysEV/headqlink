package com.headqlink.link;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.util.Locale;

/**
 * Abrir la navegación en el navegador elegido (Ajuste de la Ruta, «Navegar con»): Google Maps o Waze (si está
 * instalado; si no, Maps). Con Android Auto en marcha, el que se abre sale en la pantalla del coche.
 */
final class NavApps {
    static final String MAPS = "com.google.android.apps.maps";
    static final String WAZE = "com.waze";

    private NavApps() {
    }

    static boolean wazeInstalled(Context ctx) {
        try {
            return ctx.getPackageManager().getLaunchIntentForPackage(WAZE) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Se navega con Waze: elegido y presente. */
    static boolean useWaze(Context ctx) {
        return Config.NAV_WAZE.equals(new Config(ctx).navApp()) && wazeInstalled(ctx);
    }

    /** Navegar a un punto (destino o cargador). */
    static void go(Context ctx, double lat, double lon) {
        if (useWaze(ctx)) {
            start(ctx, String.format(Locale.US, "https://waze.com/ul?ll=%.6f,%.6f&navigate=yes", lat, lon), WAZE, "Waze");
            return;
        }
        // Coche virtual con otra salida (un Sevilla-Barcelona desde casa): la ruta en Maps desde esa salida.
        RoutePlanner.Place o = RoutePlanner.testOrigin();
        String uri = o == null ? String.format(Locale.US, "google.navigation:q=%.6f,%.6f&mode=d", lat, lon)
                : String.format(Locale.US, "https://www.google.com/maps/dir/?api=1&origin=%.6f,%.6f&destination=%.6f,%.6f&travelmode=driving",
                o.lat, o.lon, lat, lon);
        start(ctx, uri, MAPS, "Google Maps");
    }

    /**
     * Navegar con las paradas del plan de carga y las del viaje (paradas intermedias, el destino y, de ida y vuelta, la
     * vuelta), en el orden de la ruta: Maps las lleva como puntos intermedios (hasta 3: con más, va hasta la cuarta y
     * desde allí se vuelve a pulsar); Waze no admite paradas, así que va a la siguiente. cp puede ser null (sin plan de
     * carga: solo las del viaje).
     */
    static void goWithStops(Context ctx, RoutePlanner.Plan p, ChargePlanner.Result cp) {
        double from = cp != null ? cp.fromKm : p.km[Math.min(Math.max(0, p.progress), p.n - 1)];
        java.util.List<double[]> pts = new java.util.ArrayList<>();
        if (cp != null) for (ChargePlanner.Stop st : cp.stops) pts.add(new double[]{st.charger.lat, st.charger.lon, st.km});
        for (int i = 0; i < p.waypoints.size() && i < p.waypointKm.length; i++) {
            RoutePlanner.Place w = p.waypoints.get(i);
            pts.add(new double[]{w.lat, w.lon, p.waypointKm[i]});
        }
        if (p.waypoints.isEmpty()) pts.add(new double[]{p.destLat, p.destLon, p.totalKm});
        java.util.List<double[]> route = ahead(pts, from + ChargePlanner.HERE_KM, MAX_WAYPOINTS);
        if (route.isEmpty()) route.add(new double[]{p.destLat, p.destLon, p.totalKm});
        if (useWaze(ctx)) {
            go(ctx, route.get(0)[0], route.get(0)[1]);
            return;
        }
        double[] dest = route.get(route.size() - 1);
        StringBuilder wp = new StringBuilder();
        for (int i = 0; i < route.size() - 1; i++) {
            if (wp.length() > 0) wp.append('|');
            wp.append(String.format(Locale.US, "%.6f,%.6f", route.get(i)[0], route.get(i)[1]));
        }
        // Coche virtual con otra salida: desde ella (Maps enseña la ruta; navegar solo se puede desde donde se está).
        RoutePlanner.Place o = RoutePlanner.testOrigin();
        String url = String.format(Locale.US, "https://www.google.com/maps/dir/?api=1&destination=%.6f,%.6f&travelmode=driving",
                dest[0], dest[1]) + (o == null ? "&dir_action=navigate" : String.format(Locale.US, "&origin=%.6f,%.6f", o.lat, o.lon))
                + (wp.length() > 0 ? "&waypoints=" + Uri.encode(wp.toString()) : "");
        if (start(ctx, url, MAPS, "Google Maps")) L.i("ruta: Google Maps con " + (route.size() - 1) + " paradas por el camino");
    }

    /** Puntos intermedios que admite Google Maps en el móvil. */
    static final int MAX_WAYPOINTS = 3;

    /**
     * Puro: de los puntos {lat, lon, km}, los que quedan por delante de fromKm en el orden de la ruta, como mucho
     * maxWaypoints + 1 (el último es a donde se navega; los demás, puntos intermedios).
     */
    static java.util.List<double[]> ahead(java.util.List<double[]> pts, double fromKm, int maxWaypoints) {
        java.util.List<double[]> out = new java.util.ArrayList<>();
        for (double[] x : pts) if (x[2] > fromKm) out.add(x);
        java.util.Collections.sort(out, (a, b) -> Double.compare(a[2], b[2]));
        while (out.size() > maxWaypoints + 1) out.remove(out.size() - 1);
        return out;
    }

    private static boolean start(Context ctx, String uri, String pkg, String label) {
        try {
            // NO_USER_ACTION: no es que el usuario salga de la app (el coche virtual vuelve delante: el navegador va en AA).
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(pkg)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
            ctx.getApplicationContext().startActivity(i);
            CarUi.switchToAa();
            return true;
        } catch (RuntimeException e) {
            // Solo el tipo: el mensaje lleva el Intent, con las coordenadas.
            L.w("ruta: no se pudo abrir " + label + ": " + e.getClass().getSimpleName());
            return false;
        }
    }
}
