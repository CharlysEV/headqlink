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
     * Navegar con las paradas del plan de carga: Maps las lleva como puntos intermedios (hasta 3: con más, se va a las 3
     * primeras y se replanifica allí); Waze no admite paradas, así que va a la siguiente (y desde allí, a la otra).
     */
    static void goWithStops(Context ctx, RoutePlanner.Plan p, ChargePlanner.Result cp) {
        ChargePlanner.Stop next = cp.next();
        if (useWaze(ctx)) {
            if (next != null) go(ctx, next.charger.lat, next.charger.lon);
            else go(ctx, p.destLat, p.destLon);
            return;
        }
        StringBuilder wp = new StringBuilder();
        int n = 0;
        for (ChargePlanner.Stop st : cp.stops) {
            if (st.km <= cp.fromKm + ChargePlanner.HERE_KM) continue;
            if (n++ >= 3) break;
            if (wp.length() > 0) wp.append('|');
            wp.append(String.format(Locale.US, "%.6f,%.6f", st.charger.lat, st.charger.lon));
        }
        // Coche virtual con otra salida: desde ella (Maps enseña la ruta; navegar solo se puede desde donde se está).
        RoutePlanner.Place o = RoutePlanner.testOrigin();
        String url = String.format(Locale.US, "https://www.google.com/maps/dir/?api=1&destination=%.6f,%.6f&travelmode=driving",
                p.destLat, p.destLon) + (o == null ? "&dir_action=navigate" : String.format(Locale.US, "&origin=%.6f,%.6f", o.lat, o.lon))
                + (wp.length() > 0 ? "&waypoints=" + Uri.encode(wp.toString()) : "");
        if (start(ctx, url, MAPS, "Google Maps")) L.i("ruta: Google Maps con " + Math.min(3, n) + " paradas de carga");
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
