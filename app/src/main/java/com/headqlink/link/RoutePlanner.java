package com.headqlink.link;

import com.andrerinas.openheadunit.R;

import android.content.Context;
import android.net.Uri;
import android.os.SystemClock;

import com.andrerinas.openheadunit.aap.NavTap;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Plan de la ruta que navega Android Auto, para la pestaña "Ruta" de la sección Coche.
 *
 * AA solo da el destino (texto), la distancia y el tiempo restantes; el trazado se reconstruye con
 * servicios abiertos: Nominatim (destino → coordenadas), OSRM (ruta), Open-Meteo (elevación y
 * tiempo previsto en cada tramo a la hora de paso) y Overpass/OSM (cargadores junto a la ruta).
 * Con EnergyModel se estima la energía de cada tramo y el % de batería al llegar. La ruta de OSRM
 * puede no coincidir exactamente con la de Google Maps: es una aproximación y así se indica.
 *
 * La energía de cada tramo tiene dos partes: la de las cuestas (física: subir cuesta, bajar recupera) y la de la
 * conducción (aire, rodadura, arrancar y frenar en ciudad, climatización). La segunda se ajusta con lo que gastó el
 * coche de verdad en las últimas rutas (RouteCalibration): al llegar, se compara lo previsto con la bajada real del %
 * de la nube de Leapmotor.
 */
final class RoutePlanner {
    static final class Charger {
        String name;
        String detail;
        double lat;
        double lon;
        double kmAlong;
        double maxKw;
        /** Red (ChargerFilter: «tesla», «zunder»… u OTHER). */
        String network = ChargerFilter.OTHER;
    }

    static final class Plan {
        String destination;
        double destLat;
        double destLon;
        int n;
        double[] lat;
        double[] lon;
        double[] km;
        double[] elev;
        double[] headwind;
        double[] temp;
        double[] kwhCum;
        /** La parte de kwhCum que se debe a las cuestas (kWh acumulados; negativa si se recupera bajando). */
        double[] gravCum;
        /** Factor de ajuste a la conducción real con el que se calculó (RouteCalibration). */
        double factor = 1.0;
        double totalKm;
        long totalSeconds;
        double destTemp = Double.NaN;
        double destWind = Double.NaN;
        double destRain = Double.NaN;
        int destCode = -1;
        final List<Charger> chargers = new ArrayList<>();
        /** Índice del punto de la ruta más cercano a la posición actual. */
        volatile int progress;
        /** Hora (de pared) a la que se calculó. */
        long builtAtMs;
        // Para comparar al llegar: la primera lectura de la nube tras calcularla (NaN: aún no) y el punto de la ruta.
        double startSoc = Double.NaN;
        double startOdo = Double.NaN;
        double startCap = Double.NaN;
        double startFuel = Double.NaN;
        int startProgress;
        /** Hora (de pared) de la llegada, o 0. */
        long arrivedAtMs;
        /** Lo previsto y lo real al llegar (kWh); NaN hasta saberlo. */
        volatile double arrivalPredictedKwh = Double.NaN;
        volatile double arrivalRealKwh = Double.NaN;
        boolean arrivalDone;
    }

    private static RoutePlanner instance;

    // Modo prueba (coche virtual, sin coche): rutas reales desde el GPS o desde la salida elegida, con un % de prueba
    // que se cambia a mano, sin guardar nada del viaje ni ajustar la previsión.
    private static volatile boolean testMode;
    /** Salida elegida en el modo prueba, o null: el GPS. */
    private static volatile Place testOrigin;
    private static volatile double testSoc = Double.NaN;

    static boolean testMode() {
        return testMode;
    }

    static Place testOrigin() {
        return testMode ? testOrigin : null;
    }

    /** Entra o sale del modo prueba (al salir se olvidan la salida, el % y el destino elegidos). */
    static void setTestMode(boolean on) {
        testMode = on;
        testOrigin = null;
        testSoc = Double.NaN;
        if (!on) setManualDestination(null);
        L.i("ruta: modo prueba " + (on ? "activado (coche virtual)" : "desactivado"));
    }

    /** Modo prueba: la salida (null: el GPS); la ruta se rehace enseguida desde ahí. */
    static void setTestOrigin(Place pl) {
        if (!testMode) return;
        testOrigin = pl;
        L.i("ruta: salida de prueba " + (pl == null ? "el GPS" : "elegida a mano"));
        RoutePlanner r = instance;
        if (r == null || r.demo) return;
        r.plan = null;
        r.lastAttemptMs = 0;
        r.failedDestination = null;
        new Thread(() -> {
            try {
                r.step();
            } catch (Exception e) {
                L.w("ruta: " + Http.safeError(e));
            }
        }, "route-now").start();
    }

    private final Context ctx;
    private final CarSensors sensors;
    private volatile boolean running;
    private volatile Plan plan;
    private volatile String status = noRoute();
    private static String noRoute() {
        return Str.get(R.string.hql_route_none);
    }
    /**
     * Destino elegido en la pestaña Ruta. Hace falta porque Android Auto solo nos manda la próxima
     * maniobra: con el cuadro de instrumentos que anuncia Open Headunit (ImageCodesOnly) no llegan
     * ni el destino ni la distancia total.
     */
    private static volatile Place manual;

    /** Un lugar buscado (Nominatim). */
    static final class Place {
        String name;
        String detail;
        double lat;
        double lon;
    }

    static Place manualDestination() {
        if (DemoMode.active()) return DemoMode.place();
        return manual;
    }

    /** Fija (o quita, con null) el destino elegido en el coche; la ruta se calcula enseguida. */
    static void setManualDestination(Place pl) {
        if (DemoMode.active()) {
            DemoMode.setPlace(pl);
            return;
        }
        manual = pl;
        RoutePlanner r = instance;
        if (r != null) {
            r.plan = null;
            r.lastAttemptMs = 0;
            r.failedDestination = null;
            r.status = pl != null ? Str.get(R.string.hql_route_calculating, pl.name) : noRoute();
            new Thread(() -> {
                try {
                    r.step();
                } catch (Exception e) {
                    L.w("ruta: " + Http.safeError(e));
                }
            }, "route-now").start();
        }
    }

    /** Busca lugares por texto, primero cerca de lat/lon (llamar fuera del hilo principal). */
    static java.util.List<Place> search(String q, double lat, double lon) throws Exception {
        String url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=6&q=" + Uri.encode(q);
        if (!Double.isNaN(lat)) {
            url += String.format(Locale.US, "&viewbox=%.3f,%.3f,%.3f,%.3f", lon - 2, lat + 2, lon + 2, lat - 2);
        }
        JSONArray a = new JSONArray(Http.get(url));
        java.util.List<Place> out = new java.util.ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            Place p = new Place();
            String full = o.optString("display_name");
            String n = o.optString("name");
            p.name = n.isEmpty() ? full.split(",")[0] : n;
            p.detail = full;
            p.lat = o.getDouble("lat");
            p.lon = o.getDouble("lon");
            out.add(p);
        }
        return out;
    }

    /** Los sensores que mueven la ruta (para saber si el GPS está al día). */
    CarSensors.Snapshot sensorSnapshot() {
        return sensors.snapshot();
    }

    /** Última posición conocida del móvil (NaN si no hay). */
    double[] position() {
        Place o = testOrigin();
        if (o != null) return new double[]{o.lat, o.lon};
        CarSensors.Snapshot s = sensors.snapshot();
        return new double[]{s.lat, s.lon};
    }
    private long lastAttemptMs;
    private String failedDestination;
    // Batería: % fijado por el usuario menos la energía estimada desde entonces.
    private double lastKwhTotal;
    /** Modo demostración: la ruta y la batería son las de DemoMode (sin red y sin tocar los ajustes). */
    private final boolean demo;

    private RoutePlanner(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.sensors = CarSensors.start(ctx);
        demo = DemoMode.active();
    }

    static synchronized RoutePlanner start(Context ctx) {
        if (instance == null) {
            instance = new RoutePlanner(ctx);
            instance.running = true;
            if (!instance.demo) new Thread(instance::loop, "route-planner").start();
            // Demostración en vivo: el plan de carga también se vigila con la pestaña Ruta cerrada (avisos sobre AA).
            else if (DemoMode.live()) new Thread(instance::demoLoop, "route-planner-demo").start();
        }
        return instance;
    }

    static synchronized void stop() {
        if (instance == null) return;
        instance.running = false;
        CarSensors.stop();
        VoiceAlert.shutdown();
        instance = null;
    }

    static RoutePlanner get() {
        return instance;
    }

    Plan plan() {
        return demo ? DemoMode.plan() : plan;
    }

    String status() {
        if (demo) return DemoMode.plan() == null ? noRoute() : "";
        return status;
    }

    /** % de batería estimado ahora, o NaN si el usuario no lo ha indicado. */
    double socNow() {
        if (demo) return DemoMode.soc(sensors.snapshot().kwhTotal);
        if (testMode) {
            // Empieza con el % real del coche (si hay cuenta) o un 80 %; luego, el que se elija con ±5.
            if (Double.isNaN(testSoc)) {
                CarCloud.Snapshot cs = CarCloud.snapshot();
                double real = cs == null ? Double.NaN : cs.soc(System.currentTimeMillis(), CarCloud.SOC_MAX_AGE_MS);
                testSoc = Double.isNaN(real) ? 80 : Math.round(real);
            }
            return testSoc;
        }
        Config c = new Config(ctx);
        double set = c.socPct();
        if (Double.isNaN(set)) return Double.NaN;
        return Math.max(0, set - c.socUsedKwh() / EnergyModel.USABLE_KWH * 100);
    }

    /** El usuario indica el % de batería actual. */
    void setSoc(double pct) {
        if (testMode) {
            testSoc = Math.max(0, Math.min(100, pct));
            return;
        }
        if (demo) {
            DemoMode.setSoc(pct, sensors.snapshot().kwhTotal);
            return;
        }
        Config c = new Config(ctx);
        c.setSoc(Math.max(0, Math.min(100, pct)));
    }

    /** kWh estimados desde la posición actual hasta el destino. */
    static double remainingKwh(Plan p) {
        if (p == null || p.n == 0) return Double.NaN;
        int i = Math.min(p.progress, p.n - 1);
        return p.kwhCum[p.n - 1] - p.kwhCum[i];
    }

    // ------------------------------------------------------------------ bucle

    private void loop() {
        while (running) {
            try {
                step();
            } catch (Exception e) {
                L.w("ruta: " + Http.safeError(e));
            }
            SystemClock.sleep(5000);
        }
    }

    private void demoLoop() {
        while (running) {
            try {
                Plan p = plan();
                if (p != null) followCharge(p);
            } catch (RuntimeException e) {
                L.w("ruta: " + e.getClass().getSimpleName());
            }
            SystemClock.sleep(2000);
        }
    }

    private synchronized void step() {
        CarSensors.Snapshot s = sensors.snapshot();
        if (!testMode) trackEnergy(s);
        Place origin = testOrigin();
        NavTap.Info nav = NavTap.getInfo();
        Plan p = plan;
        if (p != null && origin == null && !Double.isNaN(s.lat)) p.progress = nearest(p, s.lat, s.lon, p.progress);
        if (p != null) compareOnArrival(p, s);
        if (p != null) followCharge(p);
        // Destino: el de AA si lo manda; si no, el elegido aquí.
        Place m = manual;
        String dest = nav.active && nav.destination != null ? nav.destination : m != null ? m.name : null;
        boolean useManual = dest != null && m != null && dest.equals(m.name);
        if (dest == null) {
            if (p == null) status = noRoute();
            return;
        }
        if (p != null && dest.equals(p.destination)) return;
        double oLat = origin != null ? origin.lat : s.lat;
        double oLon = origin != null ? origin.lon : s.lon;
        if (Double.isNaN(oLat)) {
            status = Str.get(R.string.hql_waiting_gps);
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (dest.equals(failedDestination) && now - lastAttemptMs < 120_000) return;
        if (lastAttemptMs > 0 && now - lastAttemptMs < 20_000) return;
        lastAttemptMs = now;
        status = Str.get(R.string.hql_route_calculating, dest);
        try {
            Plan np = useManual ? build(dest, oLat, oLon, m.lat, m.lon) : build(dest, oLat, oLon, Double.NaN, Double.NaN);
            np.builtAtMs = System.currentTimeMillis();
            plan = np;
            failedDestination = null;
            status = "";
            // Sin el nombre del destino: el log se exporta y no lleva ubicaciones (qdauto §7.5).
            L.i(String.format(Locale.US, "ruta: %.1f km · %.1f kWh estimados (%.1f por las cuestas, ajuste a la conducción ×%.2f) · "
                    + "%d cargadores", np.totalKm, np.kwhCum[np.n - 1], np.gravCum[np.n - 1], np.factor, np.chargers.size()));
        } catch (Exception e) {
            failedDestination = dest;
            status = Str.get(R.string.hql_route_failed, e.getMessage());
            L.w("ruta: " + Http.safeError(e));
        }
    }

    // ------------------------------------------------------------------ plan de carga vivo

    /** Avisos del plan de carga: el panel del coche se apunta mientras está en pantalla. */
    interface ChargeAlertListener {
        void onChargeAlert(String title, String text);
    }

    static volatile ChargeAlertListener chargeAlerts;
    /** Entre dos avisos por voz (los cambios seguidos solo actualizan el aviso escrito), salvo si ya no se llega. */
    static final long VOICE_GAP_MS = 90_000;

    private final Object chargeLock = new Object();
    private final ChargePlanner.Trend trend = new ChargePlanner.Trend();
    private ChargePlanner.Result charge;
    private String chargeKey = "";
    private String chargeSettings = "";
    private Plan chargeFor;
    private int chargeLogged = -1;
    private long lastVoiceMs = Long.MIN_VALUE / 2;
    /** El último aviso del plan de esta ruta (la pestaña Ruta lo enseña) y su hora (de pared); null si ninguno. */
    volatile String lastAlert;
    volatile long lastAlertAtMs;

    /**
     * En el bucle (aunque no se vea la pestaña Ruta): cada lectura nueva de la nube alimenta la tendencia del viaje y el
     * plan se rehace con el % de ahora; si cambia, avisa.
     */
    private void followCharge(Plan p) {
        CarCloud.Snapshot cs = CarCloud.snapshot();
        boolean data = cs != null && cs.hasData();
        boolean charging = data && (cs.status.charging() || cs.status.pluggedIn());
        if (data && !cs.demo && !testMode) {
            synchronized (chargeLock) {
                trend.observe(p, p.km, p.kwhCum, p.km[Math.min(p.progress, p.n - 1)], cs.status.socBest(), cs.status.odometerKm,
                        cs.capacityKwh, charging, cs.dataTimeMs());
            }
        }
        double[] b = battery(cs);
        chargePlan(b[0], b[1], b[2] > 0, data && cs.status.reev(), charging);
    }

    /**
     * % de batería y capacidad (kWh) para la ruta, y 1 si son los reales del coche (nube, al día) o 0 si es el %
     * indicado.
     */
    double[] battery(CarCloud.Snapshot cs) {
        if (testMode) return new double[]{socNow(), cs != null && cs.hasData() ? cs.capacityKwh : EnergyModel.USABLE_KWH, 0};
        double real = cs == null ? Double.NaN : cs.soc(DemoMode.wallClockMs(), CarCloud.SOC_MAX_AGE_MS);
        if (!Double.isNaN(real)) return new double[]{real, cs.capacityKwh, 1};
        return new double[]{socNow(), EnergyModel.USABLE_KWH, 0};
    }

    /**
     * El plan de carga con el % de ahora (se rehace si cambia algo: el avance, el %, la tendencia del viaje, el filtro o
     * los márgenes; si no, el mismo). null sin ruta, sin % o en un REEV (el generador pone lo que falte). realSoc: el %
     * es el del coche (pasar del indicado al real no es un cambio del que avisar).
     */
    ChargePlanner.Result chargePlan(double soc, double capKwh, boolean realSoc, boolean reev, boolean charging) {
        Plan pl = plan();
        synchronized (chargeLock) {
            if (pl == null || pl.n < 2 || Double.isNaN(soc) || reev) {
                charge = null;
                chargeKey = "";
                return null;
            }
            if (pl != chargeFor) {
                chargeFor = pl;
                charge = null;
                chargeSettings = "";
                lastAlert = null;
            }
            Config c = new Config(ctx);
            int minKw = c.chargerMinKw();
            String nets = c.chargerNetworks();
            String settings = minKw + "/" + nets + "/" + c.planArrivePct() + "/" + c.planMaxPct() + "/" + Math.round(capKwh * 10)
                    + (realSoc ? "/real" : "/indicado");
            int prog = Math.min(pl.progress, pl.n - 1);
            double f = demo ? DemoMode.planTrend() : trend.factor();
            String key = settings + "/" + prog + "/" + Math.round(soc * 2) + "/" + Math.round(f * 100) + "/" + charging;
            if (key.equals(chargeKey)) return charge;
            chargeKey = key;
            ChargePlanner.Settings s = new ChargePlanner.Settings();
            s.arriveMinPct = c.planArrivePct();
            s.maxChargePct = c.planMaxPct();
            s.capacityKwh = capKwh;
            java.util.Set<String> want = ChargerFilter.parseNetworks(nets);
            List<Charger> list = new ArrayList<>();
            for (Charger ch : pl.chargers) if (ChargerFilter.accepts(ch.maxKw, ch.network, minKw, want)) list.add(ch);
            // Con los mismos ajustes, las paradas elegidas se mantienen mientras se lleguen (que el plan no baile).
            boolean same = settings.equals(chargeSettings) && charge != null;
            List<Charger> keep = new ArrayList<>();
            if (same) for (ChargePlanner.Stop st : charge.stops) keep.add(st.charger);
            double from = pl.km[prog];
            ChargePlanner.Result r = ChargePlanner.plan(pl.km, ChargePlanner.scaled(pl.km, pl.kwhCum, from, f), from, soc, list, s, keep);
            r.trend = f;
            // Cargando (en una parada del plan o en otra), el plan cambia sin avisar: lo ha decidido el conductor.
            ChargePlanner.Change change = same && !charging ? ChargePlanner.change(charge, r) : ChargePlanner.Change.NONE;
            ChargePlanner.Result before = charge;
            charge = r;
            chargeSettings = settings;
            int sig = r.outcome.ordinal() * 100 + r.stops.size();
            if (sig != chargeLogged || change != ChargePlanner.Change.NONE) {
                chargeLogged = sig;
                L.i(String.format(Locale.US, "ruta: plan de carga%s: %s, %d paradas, %.0f min cargando, llegada %.0f %%, gasto ×%.2f",
                        change == ChargePlanner.Change.NONE ? "" : " recalculado (" + change.name().toLowerCase(Locale.ROOT) + ")",
                        r.outcome.name().toLowerCase(Locale.ROOT), r.stops.size(), r.chargeMinutes, r.arrivalPct, f));
            }
            if (change != ChargePlanner.Change.NONE) alert(change, before, r);
            return r;
        }
    }

    /** El plan de carga ya calculado (sin rehacerlo), o null. */
    ChargePlanner.Result lastChargePlan() {
        synchronized (chargeLock) {
            return charge;
        }
    }

    /** El plan ha cambiado durante el viaje: aviso escrito (panel del coche y pestaña Ruta) y por voz. */
    private void alert(ChargePlanner.Change change, ChargePlanner.Result before, ChargePlanner.Result now) {
        String title = Str.get(change == ChargePlanner.Change.NO_CHARGER ? R.string.hql_plan_alert_fail_title : R.string.hql_plan_alert_title);
        String text = alertText(change, before, now);
        lastAlert = text;
        lastAlertAtMs = DemoMode.wallClockMs();
        ChargeAlertListener l = chargeAlerts;
        if (l != null) l.onChargeAlert(title, text);
        long t = SystemClock.elapsedRealtime();
        boolean voice = new Config(ctx).planVoice() && (!demo || DemoMode.live());
        if (voice && (change == ChargePlanner.Change.NO_CHARGER || t - lastVoiceMs >= VOICE_GAP_MS)) {
            lastVoiceMs = t;
            VoiceAlert.say(ctx, title + ". " + text);
        }
    }

    /** «Gastas un 12 % más de lo previsto. Nueva parada: Zunder Écija, en 45 km (llegas con 14 %).» */
    static String alertText(ChargePlanner.Change change, ChargePlanner.Result before, ChargePlanner.Result now) {
        StringBuilder b = new StringBuilder();
        double pct = (now.trend - 1) * 100;
        if (pct >= 5) b.append(Str.get(R.string.hql_plan_alert_more, pct)).append(' ');
        else if (pct <= -5) b.append(Str.get(R.string.hql_plan_alert_less, -pct)).append(' ');
        ChargePlanner.Stop next = now.next();
        if (change == ChargePlanner.Change.NO_CHARGER) {
            b.append(Str.get(R.string.hql_plan_alert_fail));
        } else if (change == ChargePlanner.Change.FEWER) {
            ChargePlanner.Stop d = ChargePlanner.dropped(before, now);
            if (next == null) b.append(Str.get(R.string.hql_plan_alert_none, now.arrivalPct));
            else if (d != null) b.append(Str.get(R.string.hql_plan_alert_fewer, chargerName(d.charger)));
        } else {
            ChargePlanner.Stop a = ChargePlanner.added(before, now);
            if (a == null) a = next;
            if (a != null) b.append(Str.get(R.string.hql_plan_alert_stop, chargerName(a.charger), a.km - now.fromKm, a.arrivePct));
        }
        return b.toString().trim();
    }

    /** Nombre de un cargador para los avisos: el de OpenStreetMap o, si no tiene, el de su red. */
    static String chargerName(Charger c) {
        if (c.name != null && !c.name.trim().isEmpty()) return c.name.trim();
        return c.network == null || c.network.equals(ChargerFilter.OTHER) ? Str.get(R.string.hql_plan_charger) : ChargerFilter.label(c.network);
    }

    /**
     * Google Maps con las paradas del plan como puntos intermedios (Maps admite hasta 3 en el móvil: con más, se va a
     * las 3 primeras y se replanifica allí).
     */
    static void navigateWithStops(Context ctx, Plan p, ChargePlanner.Result cp) {
        StringBuilder wp = new StringBuilder();
        int n = 0;
        for (ChargePlanner.Stop st : cp.stops) {
            if (st.km <= cp.fromKm + ChargePlanner.HERE_KM) continue;
            if (n++ >= 3) break;
            if (wp.length() > 0) wp.append('|');
            wp.append(String.format(Locale.US, "%.6f,%.6f", st.charger.lat, st.charger.lon));
        }
        String url = String.format(Locale.US, "https://www.google.com/maps/dir/?api=1&destination=%.6f,%.6f&travelmode=driving"
                + "&dir_action=navigate", p.destLat, p.destLon) + (wp.length() > 0 ? "&waypoints=" + Uri.encode(wp.toString()) : "");
        try {
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url))
                    .setPackage("com.google.android.apps.maps").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.getApplicationContext().startActivity(i);
            L.i("ruta: Google Maps con " + Math.min(3, n) + " paradas de carga");
            CarUi.switchToAa();
        } catch (RuntimeException e) {
            L.w("ruta: no se pudo abrir Google Maps con las paradas: " + e.getClass().getSimpleName());
        }
    }

    /** Distancia al destino por debajo de la cual se da por llegado (km). */
    static final double ARRIVED_KM = 0.3;

    /**
     * Lo previsto frente a lo real: la primera lectura de la nube tras calcular la ruta marca el inicio; al llegar, la
     * primera lectura de después da lo real. Con una llegada que sirva, se ajusta la previsión de las siguientes rutas.
     */
    private void compareOnArrival(Plan p, CarSensors.Snapshot s) {
        if (p.arrivalDone || demo || testMode) return;
        CarCloud.Snapshot cs = CarCloud.snapshot();
        if (cs == null || !cs.hasData() || cs.demo) return;
        LeapStatus st = cs.status;
        if (Double.isNaN(p.startSoc)) {
            if (cs.fetchedAtMs <= p.builtAtMs || Double.isNaN(st.socBest()) || Double.isNaN(st.odometerKm)) return;
            p.startSoc = st.socBest();
            p.startOdo = st.odometerKm;
            p.startCap = cs.capacityKwh;
            p.startFuel = st.fuelLiters;
            p.startProgress = p.progress;
            return;
        }
        if (st.charging() || st.pluggedIn() || st.socBest() > p.startSoc + 0.5) {
            p.arrivalDone = true; // cargó por el camino: no se compara
            return;
        }
        double toGo = p.totalKm - p.km[Math.min(p.progress, p.n - 1)];
        if (p.arrivedAtMs == 0) {
            if (toGo > ARRIVED_KM || Double.isNaN(s.lat)) return;
            p.arrivedAtMs = System.currentTimeMillis();
            return;
        }
        // La lectura de después de llegar (el coche sube el dato cada poco; se espera a una nueva).
        if (cs.fetchedAtMs < p.arrivedAtMs + 20_000) return;
        p.arrivalDone = true;
        double predicted = p.kwhCum[p.n - 1] - p.kwhCum[Math.min(p.startProgress, p.n - 1)];
        double real = (p.startSoc - st.socBest()) / 100.0 * p.startCap;
        double km = st.odometerKm - p.startOdo;
        // REEV con el generador en marcha: la bajada del % no es lo gastado.
        boolean generator = !Double.isNaN(p.startFuel) && !Double.isNaN(st.fuelLiters) && p.startFuel - st.fuelLiters > 0.05;
        p.arrivalPredictedKwh = predicted;
        p.arrivalRealKwh = generator ? Double.NaN : real;
        boolean usable = !generator && RouteCalibration.usable(predicted, real, km);
        Config c = new Config(ctx);
        String note = "";
        if (usable) {
            java.util.List<RouteCalibration.Sample> all = RouteCalibration.add(RouteCalibration.fromJson(c.routeCalibration()),
                    new RouteCalibration.Sample(predicted, real, km, System.currentTimeMillis()));
            c.setRouteCalibration(RouteCalibration.toJson(all));
            note = String.format(Locale.US, "; el ajuste a la conducción pasa a ×%.2f (%d rutas)", RouteCalibration.factor(all), all.size());
        }
        L.i(String.format(Locale.US, "ruta: llegada: previsto %.2f kWh, real %s (nube Leapmotor, %.0f km)%s", predicted,
                generator ? "sin medir (el generador del REEV cargó)" : String.format(Locale.US, "%.2f kWh", real), km, note));
    }

    /**
     * Factor de ajuste a la conducción real: parte del historial del coche (su consumo de los últimos viajes, según la
     * nube) y lo afinan las llegadas medidas. 1 sin nada de eso.
     */
    private double calibrationFactor() {
        if (demo) return 1.0;
        double prior = RouteCalibration.historyFactor(CarCloud.history(), 15);
        return RouteCalibration.factor(RouteCalibration.fromJson(new Config(ctx).routeCalibration()), prior);
    }

    /**
     * Energía de arrancar y frenar en ciudad que no ve un modelo a velocidad constante (kWh en dkm): paradas por km según
     * la velocidad media del tramo, cada una con la energía cinética que no se recupera al frenar.
     */
    static double stopAndGoKwh(double speedKmh, double dkm) {
        double perKm = speedKmh < 35 ? 1.5 : speedKmh < 50 ? 1.0 : speedKmh < 70 ? 0.4 : 0;
        if (perKm == 0) return 0;
        double v = speedKmh / 3.6;
        double lost = 0.5 * EnergyModel.MASS_KG * v * v * (1 / EnergyModel.DRIVE_EFF - EnergyModel.REGEN_EFF);
        return perKm * dkm * lost / 3.6e6;
    }

    private void trackEnergy(CarSensors.Snapshot s) {
        double k = s.kwhTotal;
        if (k < lastKwhTotal) lastKwhTotal = 0; // los sensores se reiniciaron
        double d = k - lastKwhTotal;
        lastKwhTotal = k;
        if (d > 0) new Config(ctx).addSocUsedKwh(d);
    }

    // ------------------------------------------------------------------ construcción

    private Plan build(String destination, double lat0, double lon0, double dLat, double dLon) throws Exception {
        Plan p = new Plan();
        p.destination = destination;

        // 1. Destino → coordenadas (con preferencia por la zona actual), salvo que ya las tengamos.
        if (!Double.isNaN(dLat)) {
            p.destLat = dLat;
            p.destLon = dLon;
        } else {
            String geo = Http.get("https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&q=" + Uri.encode(destination)
                    + String.format(Locale.US, "&viewbox=%.3f,%.3f,%.3f,%.3f", lon0 - 2, lat0 + 2, lon0 + 2, lat0 - 2));
            JSONArray ga = new JSONArray(geo);
            if (ga.length() == 0) throw new IllegalStateException("destino no encontrado");
            p.destLat = ga.getJSONObject(0).getDouble("lat");
            p.destLon = ga.getJSONObject(0).getDouble("lon");
        }

        // 2. Ruta (OSRM): geometría y duración por tramo.
        String r = Http.get(String.format(Locale.US, "https://router.project-osrm.org/route/v1/driving/%.6f,%.6f;%.6f,%.6f"
                + "?overview=full&geometries=geojson&annotations=distance,duration", lon0, lat0, p.destLon, p.destLat));
        JSONObject route = new JSONObject(r).getJSONArray("routes").getJSONObject(0);
        JSONArray coords = route.getJSONObject("geometry").getJSONArray("coordinates");
        JSONObject ann = route.getJSONArray("legs").getJSONObject(0).getJSONObject("annotation");
        JSONArray dist = ann.getJSONArray("distance");
        JSONArray dur = ann.getJSONArray("duration");
        double totalM = route.getDouble("distance");
        p.totalSeconds = Math.round(route.getDouble("duration"));
        double stepM = Math.max(200, totalM / 400);

        List<double[]> pts = new ArrayList<>(); // lat, lon, km, duración del tramo (s)
        JSONArray c0 = coords.getJSONArray(0);
        pts.add(new double[]{c0.getDouble(1), c0.getDouble(0), 0, 0});
        double accM = 0;
        double accS = 0;
        double km = 0;
        for (int i = 1; i < coords.length(); i++) {
            accM += dist.optDouble(i - 1, 0);
            accS += dur.optDouble(i - 1, 0);
            if (accM >= stepM || i == coords.length() - 1) {
                km += accM / 1000;
                JSONArray ci = coords.getJSONArray(i);
                pts.add(new double[]{ci.getDouble(1), ci.getDouble(0), km, accS});
                accM = 0;
                accS = 0;
            }
        }
        int n = pts.size();
        p.n = n;
        p.lat = new double[n];
        p.lon = new double[n];
        p.km = new double[n];
        double[] segS = new double[n];
        for (int i = 0; i < n; i++) {
            double[] q = pts.get(i);
            p.lat[i] = q[0];
            p.lon[i] = q[1];
            p.km[i] = q[2];
            segS[i] = q[3];
        }
        p.totalKm = p.km[n - 1];

        // 3. Elevación (Open-Meteo, 100 puntos por petición), suavizada.
        p.elev = new double[n];
        for (int from = 0; from < n; from += 100) {
            int to = Math.min(n, from + 100);
            StringBuilder la = new StringBuilder();
            StringBuilder lo = new StringBuilder();
            for (int i = from; i < to; i++) {
                if (i > from) {
                    la.append(',');
                    lo.append(',');
                }
                la.append(String.format(Locale.US, "%.5f", p.lat[i]));
                lo.append(String.format(Locale.US, "%.5f", p.lon[i]));
            }
            JSONArray e = new JSONObject(Http.get("https://api.open-meteo.com/v1/elevation?latitude=" + la + "&longitude=" + lo))
                    .getJSONArray("elevation");
            for (int i = from; i < to; i++) p.elev[i] = e.optDouble(i - from, 0);
        }
        double[] sm = new double[n];
        for (int i = 0; i < n; i++) {
            double a = 0;
            int c = 0;
            for (int k = Math.max(0, i - 2); k <= Math.min(n - 1, i + 2); k++) {
                a += p.elev[k];
                c++;
            }
            sm[i] = a / c;
        }
        p.elev = sm;

        // 4. Tiempo previsto a lo largo de la ruta, a la hora de paso por cada punto.
        double[] etaS = new double[n];
        for (int i = 1; i < n; i++) etaS[i] = etaS[i - 1] + segS[i];
        int k = Math.min(10, n);
        int[] sampleIdx = new int[k];
        for (int j = 0; j < k; j++) sampleIdx[j] = k == 1 ? n - 1 : Math.round(j * (n - 1) / (float) (k - 1));
        double[][] wx = weatherAt(p, sampleIdx, etaS); // por muestra: viento km/h, desde, temp, lluvia, código
        p.headwind = new double[n];
        p.temp = new double[n];
        for (int i = 0; i < n; i++) {
            int j = Math.min(k - 1, Math.round(i * (k - 1) / (float) Math.max(1, n - 1)));
            double[] w = wx[j];
            double brg = i == 0 ? bearing(p, 0, Math.min(1, n - 1)) : bearing(p, i - 1, i);
            p.headwind[i] = w[0] * Math.cos(Math.toRadians(w[1] - brg));
            p.temp[i] = w[2];
        }
        double[] last = wx[k - 1];
        p.destWind = last[0];
        p.destTemp = last[2];
        p.destRain = last[3];
        p.destCode = (int) last[4];

        // 5. Energía estimada por tramo: la de la conducción (ajustada a lo real) y la de las cuestas (física).
        EnergyModel m = new EnergyModel();
        double f = calibrationFactor();
        p.factor = f;
        p.kwhCum = new double[n];
        p.gravCum = new double[n];
        for (int i = 1; i < n; i++) {
            double dkm = p.km[i] - p.km[i - 1];
            double s = Math.max(1, segS[i]);
            double v = Math.max(5, Math.min(140, dkm / s * 3600));
            double grade = dkm > 0 ? Math.max(-15, Math.min(15, (p.elev[i] - p.elev[i - 1]) / (dkm * 1000) * 100)) : 0;
            double h = s / 3600;
            double kw = m.compute(v, grade, p.headwind[i], p.temp[i], 0);
            double flat = m.compute(v, 0, p.headwind[i], p.temp[i], 0);
            double drive = flat * h + stopAndGoKwh(v, dkm);
            double hills = (kw - flat) * h;
            p.kwhCum[i] = p.kwhCum[i - 1] + drive * f + hills;
            p.gravCum[i] = p.gravCum[i - 1] + hills;
        }

        // 6. Cargadores a menos de 2,5 km de la ruta (OpenStreetMap).
        try {
            findChargers(p);
        } catch (Exception e) {
            L.w("ruta: sin cargadores: " + Http.safeError(e));
        }
        p.progress = nearest(p, lat0, lon0, -1);
        return p;
    }

    private double[][] weatherAt(Plan p, int[] idx, double[] etaS) {
        double[][] out = new double[idx.length][];
        for (int j = 0; j < idx.length; j++) out[j] = new double[]{0, 0, 15, 0, -1};
        try {
            StringBuilder la = new StringBuilder();
            StringBuilder lo = new StringBuilder();
            for (int j = 0; j < idx.length; j++) {
                if (j > 0) {
                    la.append(',');
                    lo.append(',');
                }
                la.append(String.format(Locale.US, "%.4f", p.lat[idx[j]]));
                lo.append(String.format(Locale.US, "%.4f", p.lon[idx[j]]));
            }
            String body = Http.get("https://api.open-meteo.com/v1/forecast?latitude=" + la + "&longitude=" + lo
                    + "&hourly=temperature_2m,wind_speed_10m,wind_direction_10m,precipitation,weather_code"
                    + "&wind_speed_unit=kmh&forecast_days=2&timeformat=unixtime");
            JSONArray arr = body.trim().startsWith("[") ? new JSONArray(body) : new JSONArray().put(new JSONObject(body));
            long now = System.currentTimeMillis() / 1000;
            for (int j = 0; j < idx.length && j < arr.length(); j++) {
                JSONObject h = arr.getJSONObject(j).getJSONObject("hourly");
                JSONArray t = h.getJSONArray("time");
                long when = now + Math.round(etaS[idx[j]]);
                int best = 0;
                for (int q = 1; q < t.length(); q++) {
                    if (Math.abs(t.getLong(q) - when) < Math.abs(t.getLong(best) - when)) best = q;
                }
                // A la altura del coche el viento es ~0,7 del medido a 10 m.
                out[j] = new double[]{
                        h.getJSONArray("wind_speed_10m").optDouble(best, 0) * 0.7,
                        h.getJSONArray("wind_direction_10m").optDouble(best, 0),
                        h.getJSONArray("temperature_2m").optDouble(best, 15),
                        h.getJSONArray("precipitation").optDouble(best, 0),
                        h.getJSONArray("weather_code").optDouble(best, -1)};
            }
        } catch (Exception e) {
            L.w("ruta: sin tiempo previsto: " + Http.safeError(e));
        }
        return out;
    }

    /** Tramo de ruta por consulta a Overpass (km): en rutas largas, varias consultas en vez de una enorme. */
    static final double CHARGER_CHUNK_KM = 150;

    private void findChargers(Plan p) throws Exception {
        java.util.Set<String> seen = new java.util.HashSet<>();
        Exception last = null;
        int done = 0;
        for (double from = 0; from < p.totalKm; from += CHARGER_CHUNK_KM) {
            try {
                findChargers(p, from, Math.min(p.totalKm, from + CHARGER_CHUNK_KM), seen);
                done++;
            } catch (Exception e) {
                last = e;
                L.w(String.format(Locale.US, "ruta: cargadores del km %.0f al %.0f: %s", from, from + CHARGER_CHUNK_KM, Http.safeError(e)));
            }
        }
        if (done == 0 && last != null) throw last;
        java.util.Collections.sort(p.chargers, (a, b) -> Double.compare(a.kmAlong, b.kmAlong));
    }

    private void findChargers(Plan p, double fromKm, double toKm, java.util.Set<String> seen) throws Exception {
        StringBuilder poly = new StringBuilder();
        double lastKm = -10;
        int count = 0;
        for (int i = 0; i < p.n; i++) {
            if (p.km[i] < fromKm - 1 || p.km[i] > toKm + 1) continue;
            if (p.km[i] - lastKm < 3 && i != p.n - 1) continue;
            if (count++ > 0) poly.append(',');
            poly.append(String.format(Locale.US, "%.4f,%.4f", p.lat[i], p.lon[i]));
            lastKm = p.km[i];
        }
        if (count < 2) return;
        // Nodos y también áreas (algunas estaciones están dibujadas como superficie): de esas, su centro.
        String q = "[out:json][timeout:40];nwr[\"amenity\"=\"charging_station\"](around:2500," + poly + ");out center body 600;";
        JSONArray els = new JSONObject(Http.post("https://overpass-api.de/api/interpreter", "data=" + Uri.encode(q)))
                .getJSONArray("elements");
        for (int i = 0; i < els.length(); i++) {
            JSONObject e = els.getJSONObject(i);
            if (!seen.add(e.optString("type") + e.optLong("id"))) continue;
            JSONObject tags = e.optJSONObject("tags");
            if (tags == null) continue;
            Charger c = new Charger();
            JSONObject center = e.optJSONObject("center");
            c.lat = center != null ? center.optDouble("lat") : e.optDouble("lat");
            c.lon = center != null ? center.optDouble("lon") : e.optDouble("lon");
            if (Double.isNaN(c.lat) || Double.isNaN(c.lon)) continue;
            c.network = ChargerFilter.classify(tags.optString("brand"), tags.optString("network"), tags.optString("operator"),
                    tags.optString("name"));
            c.name = firstNonEmpty(tags.optString("name"), tags.optString("operator"), tags.optString("brand"), Str.get(R.string.hql_charge_point));
            StringBuilder sockets = new StringBuilder();
            java.util.Iterator<String> it = tags.keys();
            while (it.hasNext()) {
                String key = it.next();
                String val = tags.optString(key);
                if (key.matches("socket:[a-z0-9_]+") && !val.equals("no")) {
                    String name = key.substring(7).replace("type2_combo", "CCS").replace("type2", Str.get(R.string.hql_type2))
                            .replace("chademo", "CHAdeMO").replace("schuko", "Schuko");
                    if (sockets.indexOf(name) < 0) sockets.append(sockets.length() > 0 ? " · " : "").append(name);
                }
                if (key.endsWith(":output") || key.equals("maxpower")) c.maxKw = Math.max(c.maxKw, parseKw(val));
            }
            String op = tags.optString("operator");
            c.detail = sockets + (op.isEmpty() || op.equals(c.name) ? "" : (sockets.length() > 0 ? " · " : "") + op);
            int idx = nearest(p, c.lat, c.lon, -1);
            c.kmAlong = p.km[idx];
            p.chargers.add(c);
        }
    }

    private static double parseKw(String v) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("([0-9]+(?:[.,][0-9]+)?)\\s*(kW|kw|KW|W)?").matcher(v);
        double best = 0;
        while (m.find()) {
            double x = Double.parseDouble(m.group(1).replace(',', '.'));
            if ("W".equals(m.group(2))) x /= 1000;
            best = Math.max(best, x);
        }
        return best > 1000 ? 0 : best;
    }

    private static String firstNonEmpty(String... s) {
        for (String x : s) if (x != null && !x.isEmpty()) return x;
        return "";
    }

    // ------------------------------------------------------------------ geometría

    static int nearest(Plan p, double lat, double lon, int hint) {
        int from = hint < 0 ? 0 : Math.max(0, hint - 20);
        int to = hint < 0 ? p.n : Math.min(p.n, hint + 300);
        int best = from;
        double bd = Double.MAX_VALUE;
        double cos = Math.cos(Math.toRadians(lat));
        for (int i = from; i < to; i++) {
            double dy = p.lat[i] - lat;
            double dx = (p.lon[i] - lon) * cos;
            double d = dx * dx + dy * dy;
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        return best;
    }

    private static double bearing(Plan p, int a, int b) {
        double la1 = Math.toRadians(p.lat[a]);
        double la2 = Math.toRadians(p.lat[b]);
        double dl = Math.toRadians(p.lon[b] - p.lon[a]);
        double y = Math.sin(dl) * Math.cos(la2);
        double x = Math.cos(la1) * Math.sin(la2) - Math.sin(la1) * Math.cos(la2) * Math.cos(dl);
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }
}
