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
 * (RoutePlanner), la batería indicada, los viajes guardados (TripLog) y los datos de la nube de Leapmotor (CarCloud:
 * una foto inventada y coherente con el trayecto, y algunos viajes con consumo real).
 *
 * Nunca está activo salvo que lo active la vista previa, y no se puede activar con la sesión del coche en marcha ni
 * con los sensores ya arrancados. Mientras dura no se hace ninguna petición de red ni se guarda nada (ni viajes ni la
 * batería indicada): al desactivarlo no queda rastro. La única excepción es la cuenta de Leapmotor, si el usuario la
 * ha configurado: la vista previa interactiva la lee (solo lectura) para ver sus datos reales sin el coche; sin cuenta,
 * y siempre en las capturas, salen los datos inventados.
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
    /** Estado de la nube en la demostración: "" (datos en marcha), "sin_nube" (sin cuenta) o "cargando". */
    private static volatile String cloudState = "";

    // Nube de la demostración: C10 Life, 84 % al salir, 20,5 kWh/100 km reales y 12 480 km en el cuentakilómetros.
    static final double CLOUD_CAP_KWH = CarCloudStore.KWH_C10_LIFE;
    static final double CLOUD_SOC0 = 84.0;
    static final double CLOUD_KWH_PER_KM = 0.205;
    static final double CLOUD_ODO0 = 12480;

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
        addCloudToTrips(trips);
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
        cloudState = "";
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
     * Estado de la demostración para una captura: "" (con destino, viajes y datos de la nube), "sin_ruta" (sin destino:
     * Ruta y Conducción vacías), "sin_viajes" (la pestaña Viajes sin viajes guardados), "sin_nube" (sin cuenta de
     * Leapmotor: todo estimado y Estado vacío) o "cargando" (el coche cargando en un cargador rápido).
     */
    static void applyState(String state) {
        if (drive == null) return;
        place = "sin_ruta".equals(state) ? null : defaultPlace;
        trips = "sin_viajes".equals(state) ? new ArrayList<>() : defaultTrips;
        cloudState = "sin_nube".equals(state) || "cargando".equals(state) ? state : "";
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

    // ------------------------------------------------------------------ nube de Leapmotor (inventada)

    /**
     * Foto de la nube de la demostración, como si la hubiera leído CarCloud: el % baja con los km del trayecto, el
     * cuentakilómetros sube, una rueda va baja (para ver el aviso) y el dato es de hace 40 s. Pasa por el mismo
     * intérprete que la nube de verdad (LeapStatus), con las señales numéricas.
     */
    static CarCloud.Snapshot cloudSnapshot() {
        DemoDrive d = drive;
        if (d == null || "sin_nube".equals(cloudState)) return CarCloud.Snapshot.of(CarCloud.State.NO_ACCOUNT);
        boolean charging = "cargando".equals(cloudState);
        int k = sample();
        double km = d.distKm[k];
        double speed = charging ? 0 : d.speedKmh[k];
        double soc = charging ? 56.4 : CLOUD_SOC0 - km * CLOUD_KWH_PER_KM / CLOUD_CAP_KWH * 100;
        double volts = charging ? 412.6 : 398.6;
        // Potencia «medida»: la del modelo con la pendiente y la aceleración del trayecto, un 4 % más (otra fuente).
        int k0 = Math.max(0, k - 20);
        double dkm = d.distKm[k] - d.distKm[k0];
        double grade = dkm > 0.01 ? (d.altM[k] - d.altM[k0]) / (dkm * 1000) * 100 : 0;
        double kw = charging ? 85.1 : new EnergyModel().compute(speed, grade, 0, DemoDrive.TEMP_C, d.longG[k]) * 1.04;
        long now = nowMs();
        try {
            org.json.JSONObject sig = new org.json.JSONObject();
            sig.put("1204", (int) soc);
            sig.put("100003", Math.round(soc * 10) / 10.0);
            sig.put("2188", Math.round(soc * 4.6));
            sig.put("1149", charging ? 1 : 0);
            sig.put("47", 0);
            sig.put("1197", charging ? 1 : 0);
            sig.put("3736", 0);
            sig.put("1200", charging ? 24 : 0);
            sig.put("1177", volts);
            sig.put("1178", Math.round(kw * 1000 / volts * 10) / 10.0);
            sig.put("1182", charging ? 31 : 27);
            sig.put("1186", charging ? 1 : 0);
            sig.put("1318", (long) Math.floor(CLOUD_ODO0 + km));
            sig.put("1319", Math.round(speed));
            sig.put("1349", charging ? 24 : 22);
            sig.put("1298", charging ? 0 : 1);
            sig.put("1277", charging ? 1 : 0);
            sig.put("1278", 0);
            sig.put("1279", 0);
            sig.put("1280", 0);
            sig.put("1281", charging ? 1 : 0);
            sig.put("1258", charging ? 0 : 1);
            sig.put("2667", 246);
            sig.put("2653", 247);
            sig.put("2646", 244);
            sig.put("2660", 213);
            sig.put("2641", 0);
            sig.put("2648", 0);
            sig.put("2655", 0);
            sig.put("2662", 0);
            org.json.JSONObject data = new org.json.JSONObject();
            data.put("collectTime", now - 40_000);
            data.put("signal", sig);
            return new CarCloud.Snapshot(CarCloud.State.OK, LeapStatus.parse(data), now - 12_000, 640, CLOUD_CAP_KWH, "C10", 0, true);
        } catch (org.json.JSONException e) {
            return CarCloud.Snapshot.of(CarCloud.State.NO_ACCOUNT);
        }
    }

    /** Inicio del trayecto de la demostración en la nube: el % y los km al salir. */
    static TripLog.CloudMark cloudTripStart() {
        return new TripLog.CloudMark(CLOUD_SOC0, CLOUD_ODO0, DemoDrive.BASE_MS);
    }

    /**
     * Datos del coche (inicio y final) en algunos viajes de la demostración, con un consumo real algo distinto del
     * estimado: así se ven la marca «real» y los totales reales.
     */
    static void addCloudToTrips(List<TripLog.Trip> list) {
        double[] factor = {0.96, 1.05, 0.98, Double.NaN, 1.03};
        double odo = CLOUD_ODO0;
        for (int i = 0; i < list.size(); i++) {
            TripLog.Trip t = list.get(i);
            double odoEnd = odo - 5;
            double odoStart = odoEnd - Math.round(t.km);
            odo = odoStart;
            if (i >= factor.length || Double.isNaN(factor[i])) continue;
            double real = t.kwh / t.km * 100 * factor[i];
            double drop = real * (odoEnd - odoStart) / CLOUD_CAP_KWH;
            t.odoStart = odoStart;
            t.odoEnd = odoEnd;
            t.socEnd = 40 + 6 * i;
            t.socStart = t.socEnd + Math.round(drop * 10) / 10.0;
            t.capKwh = CLOUD_CAP_KWH;
        }
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
