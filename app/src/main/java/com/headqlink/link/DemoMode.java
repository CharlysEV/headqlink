package com.headqlink.link;

import com.andrerinas.openheadunit.aap.NavTap;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * «Modo demostración»: datos simulados (DemoDrive) para ver los paneles del modo extendido sin coche, en la vista
 * previa (PreviewActivity) y en las capturas PNG. Alimenta los mismos caminos que usan las pantallas: los sensores
 * (CarSensors recibe el trayecto como si fuera el GPS), la navegación de Android Auto, la vía (RoadInfo), la ruta
 * (RoutePlanner), la batería indicada y los viajes guardados (TripLog).
 *
 * Nunca está activo salvo que lo active la vista previa, y no se puede activar con la sesión del coche en marcha ni
 * con los sensores ya arrancados. Mientras dura no se hace ninguna petición de red ni se guarda nada (ni viajes ni la
 * batería indicada): al desactivarlo no queda rastro.
 */
final class DemoMode {
    private static volatile DemoDrive drive;
    private static volatile boolean live;
    private static volatile double t;
    private static volatile RoutePlanner.Plan plan;
    private static volatile List<TripLog.Trip> trips;
    /** Destino de la demostración (null: el usuario lo quitó y se ve la pestaña Ruta sin destino). */
    private static volatile RoutePlanner.Place place;
    /** % de batería «indicado» y la energía de los sensores en ese momento. */
    private static volatile double socSet;
    private static volatile double socKwhAt;
    private static RoutePlanner.Place defaultPlace;
    private static List<TripLog.Trip> defaultTrips;

    private DemoMode() {
    }

    /**
     * Activa la demostración desde el instante startSec del trayecto. live: el reloj avanza solo (vista previa
     * interactiva); si no, queda quieto y lo mueve seek() (capturas reproducibles). false si no se puede.
     */
    static synchronized boolean enable(boolean live, double startSec) {
        if (drive != null) return true;
        if (LinkState.running || CarSensors.isRunning()) {
            L.w("modo demostración: no con la sesión del coche o los sensores en marcha");
            return false;
        }
        DemoDrive d = new DemoDrive(DemoDrive.SEED);
        plan = DemoDrive.plan(DemoDrive.SEED);
        trips = DemoDrive.trips(DemoDrive.SEED);
        RoutePlanner.Place p = new RoutePlanner.Place();
        p.name = DemoDrive.DESTINATION;
        p.detail = DemoDrive.DESTINATION;
        p.lat = plan.destLat;
        p.lon = plan.destLon;
        place = p;
        defaultPlace = p;
        defaultTrips = trips;
        socSet = 88;
        socKwhAt = 0;
        DemoMode.live = live;
        t = Math.max(0, Math.min(d.loopSec, startSec));
        drive = d;
        L.i("modo demostración: activado (" + (live ? "en vivo" : "quieto") + ")");
        return true;
    }

    static synchronized void disable() {
        if (drive == null) return;
        drive = null;
        plan = null;
        trips = null;
        place = null;
        defaultPlace = null;
        defaultTrips = null;
        L.i("modo demostración: desactivado");
    }

    static boolean active() {
        return drive != null;
    }

    /** El trayecto, o null fuera de la demostración. */
    static DemoDrive drive() {
        return drive;
    }

    static boolean live() {
        return live;
    }

    /** Instante del trayecto (s). */
    static double time() {
        return t;
    }

    /** Avanza el reloj (vista previa en vivo); al final del trayecto vuelve a empezar y devuelve true. */
    static boolean advance(double dt) {
        DemoDrive d = drive;
        if (d == null) return false;
        double nt = t + dt;
        if (nt > d.loopSec) {
            t = 0;
            return true;
        }
        t = nt;
        return false;
    }

    /**
     * Estado de la demostración para una captura: "" (con destino y viajes), "sin_ruta" (sin destino: Ruta y
     * Conducción vacías) o "sin_viajes" (la pestaña Viajes sin viajes guardados).
     */
    static void applyState(String state) {
        if (drive == null) return;
        place = "sin_ruta".equals(state) ? null : defaultPlace;
        trips = "sin_viajes".equals(state) ? new ArrayList<>() : defaultTrips;
    }

    /** Fija el instante (capturas): los sensores se recalculan hasta ahí. */
    static void seek(double sec) {
        DemoDrive d = drive;
        if (d == null) return;
        t = Math.max(0, Math.min(d.loopSec, sec));
        CarSensors.demoSeek();
    }

    /** Hora «de pared» de la demostración (fija: las capturas no dependen del día). */
    static long nowMs() {
        return DemoDrive.BASE_MS + Math.round(t * 1000);
    }

    /** «Ahora» para los paneles: el real o el de la demostración. */
    static long wallClockMs() {
        return active() ? nowMs() : System.currentTimeMillis();
    }

    private static int sample() {
        DemoDrive d = drive;
        return d == null ? 0 : d.index(t);
    }

    /** Km de la ruta larga en que va el coche. */
    private static double routeKm() {
        DemoDrive d = drive;
        return DemoDrive.ROUTE_START_KM + (d == null ? 0 : d.distKm[sample()]);
    }

    // ------------------------------------------------------------------ fuentes para los paneles

    /** Navegación para los paneles: la de Android Auto o, en la demostración, la del guion. */
    static NavTap.Info navInfo() {
        DemoDrive d = drive;
        RoutePlanner.Plan p = plan;
        if (d == null) return NavTap.getInfo();
        NavTap.Info i = new NavTap.Info();
        if (place == null || p == null) return i;
        int k = sample();
        DemoDrive.Nav nv = d.nav(k);
        i.active = true;
        i.destination = place.name;
        double remKm = Math.max(0, p.totalKm - routeKm());
        i.remainingMeters = (int) Math.round(remKm * 1000);
        i.remainingSeconds = Math.round(remKm / 104.0 * 3600);
        i.eta = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(nowMs() + i.remainingSeconds * 1000));
        i.road = nv.road;
        i.kind = nv.kind;
        i.angle = nv.angle;
        i.roundaboutExit = nv.exit;
        i.nextRoad = nv.nextRoad;
        i.stepMeters = nv.stepMeters;
        double v = Math.max(20, d.speedKmh[k]);
        i.stepSeconds = Math.round(nv.stepMeters / (v / 3.6));
        i.lanes = nv.lanes;
        i.thenKind = nv.thenKind;
        i.thenAngle = nv.thenAngle;
        i.thenRoad = nv.thenRoad;
        i.updatedMs = android.os.SystemClock.elapsedRealtime();
        return i;
    }

    /** Datos de la vía del guion: límite, nombre, radar y sol. */
    static RoadInfo.State road() {
        RoadInfo.State st = new RoadInfo.State();
        DemoDrive d = drive;
        if (d == null) return st;
        int k = sample();
        st.limitKmh = d.limitKmh(k);
        st.roadName = d.road(k);
        double ahead = (d.cameraKm - d.distKm[k]) * 1000;
        if (ahead > 0 && ahead < 1500 && d.speedKmh[k] > 5) {
            st.cameraM = ahead;
            st.cameraLimit = DemoDrive.CAMERA_LIMIT;
        }
        long now = nowMs();
        double[] sun = RoadInfo.sunPosition(d.lat[k], d.lon[k], now);
        st.sunElevation = sun[0];
        double diff = Math.abs(((sun[1] - d.headingDeg[k]) + 540) % 360 - 180);
        st.sunGlare = d.speedKmh[k] > 10 && sun[0] > 0 && sun[0] < 20 && diff < 30;
        RoadInfo.fillSun(st, d.lat[k], d.lon[k], now);
        return st;
    }

    /** Ruta de la demostración con la posición actual, o null si el usuario quitó el destino. */
    static RoutePlanner.Plan plan() {
        RoutePlanner.Plan p = plan;
        if (p == null || place == null) return null;
        p.progress = (int) Math.max(0, Math.min(p.n - 1, Math.round(routeKm() / p.totalKm * (p.n - 1))));
        return p;
    }

    static RoutePlanner.Place place() {
        return place;
    }

    /** El usuario elige (o quita, con null) el destino en la demostración: la ruta es siempre la del guion. */
    static void setPlace(RoutePlanner.Place p) {
        if (p != null) {
            RoutePlanner.Place q = new RoutePlanner.Place();
            q.name = p.name;
            q.detail = p.detail;
            q.lat = p.lat;
            q.lon = p.lon;
            RoutePlanner.Plan pl = plan;
            if (pl != null) pl.destination = p.name;
            place = q;
        } else {
            place = null;
        }
    }

    /** % de batería de la demostración: el indicado menos la energía estimada desde entonces. */
    static double soc(double kwhTotal) {
        return Math.max(0, socSet - (kwhTotal - socKwhAt) / EnergyModel.USABLE_KWH * 100);
    }

    static void setSoc(double pct, double kwhTotal) {
        socSet = Math.max(0, Math.min(100, pct));
        socKwhAt = kwhTotal;
    }

    /** Viajes guardados de la demostración (no se leen ni se escriben los del usuario). */
    static List<TripLog.Trip> trips(int max) {
        List<TripLog.Trip> all = trips;
        List<TripLog.Trip> out = new ArrayList<>();
        if (all == null) return out;
        for (int i = 0; i < all.size() && i < max; i++) out.add(all.get(i));
        return out;
    }
}
