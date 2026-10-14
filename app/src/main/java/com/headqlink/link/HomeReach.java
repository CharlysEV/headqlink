package com.headqlink.link;

import com.andrerinas.openheadunit.R;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/**
 * «¿Llego a casa?» para el widget del móvil: con el último % del coche (la nube de Leapmotor) y la distancia a «Casa»
 * (SavedPlaces), «Casa: llegas con ~34 %» o «Casa: necesitas cargar». La distancia es la de la última ruta calculada a
 * Casa desde cerca de aquí (por carretera, con su energía) o, si no la hay, la línea recta × ROAD_FACTOR, marcada con
 * «≈». Sin red: el widget solo hace cuentas con lo que ya hay en el móvil (el último %, la última posición conocida) y
 * guarda el resultado; se rehace solo si cambian el %, la posición (unos cientos de metros), Casa o la ruta.
 *
 * Nada de esto va al registro ni a la copia de ajustes (la ruta a Casa se guarda en Config.HOME_ROUTE, fuera de ella).
 */
final class HomeReach {
    /** La carretera es, de media, un 30 % más larga que la línea recta. */
    static final double ROAD_FACTOR = 1.3;
    /** Llegar con menos de esto es «necesitas cargar» (%). */
    static final double RESERVE_PCT = 10;
    /** Más cerca de esto, ya se está en Casa: no se enseña nada (km). */
    static final double AT_HOME_KM = 0.8;
    /** Consumo supuesto sin el del coche (kWh/100 km). */
    static final double DEFAULT_KWH_100 = 18;
    /** La ruta calculada a Casa vale si se sale de a menos de esto de donde salió (km)… */
    static final double ROUTE_NEAR_KM = 2;
    /** …y no es más vieja que esto. */
    static final long ROUTE_MAX_AGE_MS = 30L * 86_400_000L;
    /** El último % del coche vale para el widget durante esto (aparcado, el % casi no cambia). */
    static final long SOC_MAX_AGE_MS = 3L * 86_400_000L;
    /** La última línea guardada (sin posición a mano) vale esto. */
    static final long LINE_MAX_AGE_MS = 12L * 3600_000L;
    private static final String PREFS = "hql_widget";

    /** Lo que se estima. */
    static final class Estimate {
        final double km;
        /** Distancia en línea recta × ROAD_FACTOR (no por carretera). */
        final boolean approx;
        final double arrivePct;
        final boolean needCharge;

        Estimate(double km, boolean approx, double arrivePct, boolean needCharge) {
            this.km = km;
            this.approx = approx;
            this.arrivePct = arrivePct;
            this.needCharge = needCharge;
        }
    }

    private HomeReach() {
    }

    /**
     * Puro: con qué % se llega a Casa. straightKm: en línea recta; roadKm y roadKwh: los de una ruta calculada (0 o NaN si
     * no hay). null sin % o ya en Casa.
     */
    static Estimate estimate(double soc, double capKwh, double kwhPer100, double straightKm, double roadKm, double roadKwh,
                             double reservePct) {
        if (Double.isNaN(soc) || Double.isNaN(straightKm) || !(capKwh > 0) || straightKm < AT_HOME_KM) return null;
        boolean road = roadKm > 0;
        double km = road ? roadKm : straightKm * ROAD_FACTOR;
        double per100 = kwhPer100 > 0 ? kwhPer100 : DEFAULT_KWH_100;
        double kwh = road && roadKwh > 0 ? roadKwh : km * per100 / 100;
        double arrive = soc - kwh / capKwh * 100;
        return new Estimate(km, !road, arrive, arrive < reservePct);
    }

    /**
     * Puro: ¿vale la ruta guardada (salida, destino, km, kWh, hora) para ir a Casa desde aquí? Si salió de cerca de aquí,
     * fue a Casa (a menos de 150 m) y no es vieja.
     */
    static boolean routeFits(double oLat, double oLon, double dLat, double dLon, long atMs, double lat, double lon, double homeLat,
                             double homeLon, long nowMs) {
        if (nowMs - atMs > ROUTE_MAX_AGE_MS || nowMs < atMs) return false;
        if (RoadInfo.dist(dLat, dLon, homeLat, homeLon) > SavedPlaces.SAME_M + 30) return false;
        return RoadInfo.dist(oLat, oLon, lat, lon) / 1000 <= ROUTE_NEAR_KM;
    }

    /** Puro: la ruta a Casa como JSON para Config.HOME_ROUTE. */
    static String routeJson(double oLat, double oLon, double dLat, double dLon, double km, double kwh, long atMs) {
        try {
            return new JSONObject().put("ola", oLat).put("olo", oLon).put("dla", dLat).put("dlo", dLon).put("km", km).put("kwh", kwh)
                    .put("at", atMs).toString();
        } catch (JSONException e) {
            return "";
        }
    }

    // ------------------------------------------------------------------ en el móvil

    private static volatile double lastLat = Double.NaN;
    private static volatile double lastLon = Double.NaN;
    private static volatile long lastAtMs;
    private static String memoKey = "";
    private static String memoLine = "";

    /** La posición del coche (el planificador la pasa con cada lectura del GPS): para no pedírsela a Android. */
    static void notePosition(double lat, double lon) {
        if (Double.isNaN(lat) || Double.isNaN(lon)) return;
        lastLat = lat;
        lastLon = lon;
        lastAtMs = System.currentTimeMillis();
    }

    /**
     * Al terminar de calcular una ruta: si va a Casa (un solo destino), se guarda su distancia y su energía por carretera
     * para el widget.
     */
    static void rememberRoute(Context c, double oLat, double oLon, double dLat, double dLon, double km, double kwh) {
        Config cfg = new Config(c);
        SavedPlaces.Saved home = SavedPlaces.tagged(SavedPlaces.parse(cfg.savedPlaces()), SavedPlaces.HOME);
        if (home == null || Double.isNaN(oLat) || RoadInfo.dist(dLat, dLon, home.lat, home.lon) > SavedPlaces.SAME_M + 30) return;
        cfg.setHomeRoute(routeJson(oLat, oLon, dLat, dLon, km, kwh, System.currentTimeMillis()));
        L.i(String.format(Locale.US, "ruta: guardada la distancia a Casa para el widget (%.0f km)", km));
    }

    /** Lo que enseña el widget («» si nada: sin Casa, sin % o ya en Casa). Se rehace solo si cambia algo. */
    static synchronized String line(Context c) {
        try {
            return lineOrThrow(c);
        } catch (RuntimeException e) {
            L.w("widget: «¿llego a Casa?»: " + e.getClass().getSimpleName());
            return "";
        }
    }

    private static String lineOrThrow(Context c) {
        Config cfg = new Config(c);
        SavedPlaces.Saved home = SavedPlaces.tagged(SavedPlaces.parse(cfg.savedPlaces()), SavedPlaces.HOME);
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (home == null) {
            if (!sp.getString("home_line", "").isEmpty()) sp.edit().remove("home_line").remove("home_line_at").apply();
            memoKey = "";
            memoLine = "";
            return "";
        }
        long now = System.currentTimeMillis();
        // El % del coche: el de la nube si hay (se guarda), o el último guardado.
        CarCloud.Snapshot cs = CarCloud.snapshot();
        if (cs != null && cs.hasData() && !cs.demo && !Double.isNaN(cs.status.socBest())) {
            long at = cs.dataTimeMs();
            if (at != sp.getLong("home_soc_at", 0)) {
                sp.edit().putFloat("home_soc", (float) cs.status.socBest()).putFloat("home_cap", (float) cs.capacityKwh)
                        .putLong("home_soc_at", at).apply();
            }
        }
        double soc = now - sp.getLong("home_soc_at", 0) <= SOC_MAX_AGE_MS ? sp.getFloat("home_soc", Float.NaN) : Double.NaN;
        double cap = sp.getFloat("home_cap", (float) EnergyModel.usableKwh());
        double[] pos = position(c);
        if (Double.isNaN(pos[0])) {
            // Sin posición: la última línea, si es reciente.
            return now - sp.getLong("home_line_at", 0) <= LINE_MAX_AGE_MS ? sp.getString("home_line", "") : "";
        }
        String route = cfg.homeRoute();
        String key = Math.round(soc) + "|" + Math.round(pos[0] * 300) + "|" + Math.round(pos[1] * 300) + "|" + Math.round(home.lat * 1e4)
                + "|" + Math.round(home.lon * 1e4) + "|" + route.hashCode() + "|" + Str.locale();
        if (key.equals(memoKey)) return memoLine;
        double roadKm = Double.NaN;
        double roadKwh = Double.NaN;
        if (!route.isEmpty()) {
            try {
                JSONObject o = new JSONObject(route);
                if (routeFits(o.getDouble("ola"), o.getDouble("olo"), o.getDouble("dla"), o.getDouble("dlo"), o.getLong("at"), pos[0], pos[1],
                        home.lat, home.lon, now)) {
                    roadKm = o.getDouble("km");
                    roadKwh = o.optDouble("kwh", Double.NaN);
                }
            } catch (JSONException ignored) {
                // Rota: como si no hubiera.
            }
        }
        CloudHistory.Weekly w = CarCloud.weekly();
        double per100 = w == null ? Double.NaN : w.avgKwhPer100;
        double straight = RoadInfo.dist(pos[0], pos[1], home.lat, home.lon) / 1000;
        Estimate e = estimate(soc, cap, per100, straight, roadKm, roadKwh, RESERVE_PCT);
        String label = Str.get(R.string.hql_pl_home);
        String line = e == null ? "" : e.needCharge ? Str.get(R.string.hql_pl_w_home_charge, label)
                : Str.get(R.string.hql_pl_w_home_arrive, label, e.approx ? "≈" : "~", Math.max(0, e.arrivePct));
        memoKey = key;
        if (!line.equals(memoLine)) {
            memoLine = line;
            sp.edit().putString("home_line", line).putLong("home_line_at", now).apply();
        }
        return line;
    }

    /** La posición: la del planificador si es de ahora, o la última que conoce Android (sin pedir otra). */
    private static double[] position(Context c) {
        if (!Double.isNaN(lastLat) && System.currentTimeMillis() - lastAtMs < 10 * 60_000L) return new double[]{lastLat, lastLon};
        if (!LocationAccess.fine(c)) return new double[]{Double.NaN, Double.NaN};
        try {
            LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
            Location best = null;
            if (lm != null) {
                for (String p : lm.getProviders(false)) {
                    Location l = lm.getLastKnownLocation(p);
                    if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
                }
            }
            if (best != null) return new double[]{best.getLatitude(), best.getLongitude()};
        } catch (SecurityException | IllegalArgumentException e) {
            // Sin permiso ahora (en segundo plano): sin posición.
        }
        return new double[]{Double.NaN, Double.NaN};
    }
}
