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
        /** Potencia para el plan y la pantalla: la de OpenStreetMap o, si falta, la estimada (kwSource). 0: desconocida. */
        double maxKw;
        /** De dónde sale maxKw: ChargerFilter.KW_TAGGED, KW_NETWORK, KW_SOCKETS o KW_UNKNOWN. */
        int kwSource = ChargerFilter.KW_TAGGED;
        /** Solo enchufes de alterna (Tipo 2, Schuko…): lento seguro. */
        boolean acOnly;
        /** Red (ChargerFilter: «tesla», «zunder»… u OTHER). */
        String network = ChargerFilter.OTHER;
        /** Voltaje máximo en continua (V): 920, 1000 en los de alto voltaje; 400–500 en los de antes. 0: no se sabe. */
        double maxVolts;
        /** De dónde sale: OpenStreetMap o el registro oficial de la DGT (SOURCE_*). */
        int source = SOURCE_OSM;
        static final int SOURCE_OSM = 0;
        static final int SOURCE_DGT = 1;
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
        /** Gasto extra del coche que lleva la previsión: kW por hora y kWh fijos al salir. */
        double overheadKw;
        double overheadTripKwh;
        double totalKm;
        long totalSeconds;
        double destTemp = Double.NaN;
        double destWind = Double.NaN;
        double destRain = Double.NaN;
        int destCode = -1;
        /** Cargadores junto a la ruta, por km. Se sustituye entera cuando llegan más (chargerVersion sube). */
        volatile List<Charger> chargers = new ArrayList<>();
        /** Tramos [km desde, km hasta] sin datos de cargadores (OpenStreetMap no respondió): se reintentan. */
        volatile List<double[]> chargerGaps = new ArrayList<>();
        /** Cuadrículas de la caché de cargadores que cubren la ruta, y las que aún faltan. */
        List<String> routeTiles = new ArrayList<>();
        volatile List<String> missingTiles = new ArrayList<>();
        volatile int chargerVersion;
        /** Versión de los puntos de la DGT con la que se hizo la lista (DgtChargers.version). */
        int dgtVersion = -1;
        /** Cuántos cargadores de la lista salen de la DGT. */
        volatile int dgtCount;
        /** Cuadrículas con datos de la DGT a las que aún les faltan los de OpenStreetMap (se piden en segundo plano). */
        volatile List<String> osmPending = new ArrayList<>();
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
                followChargeSession(p);
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
        if (p != null && !demo && !p.routeTiles.isEmpty() && p.dgtVersion != DgtChargers.version) {
            // Han llegado (o se han renovado) los puntos de la DGT: la lista de la ruta, con ellos.
            rebuildChargers(p);
            L.i("ruta: cargadores con los datos de la DGT: " + p.chargers.size() + " junto a la ruta (" + p.dgtCount + " de la DGT)");
        }
        if (p != null && !p.osmPending.isEmpty() && SystemClock.elapsedRealtime() - lastOsmExtraMs > OSM_EXTRA_RETRY_MS) {
            fetchOsmInBackground(p);
        }
        if (p != null && !p.missingTiles.isEmpty() && SystemClock.elapsedRealtime() - lastChargerRetryMs > CHARGER_RETRY_MS) {
            lastChargerRetryMs = SystemClock.elapsedRealtime();
            L.i("ruta: reintento los cargadores que faltan (" + p.missingTiles.size() + " zonas)");
            refreshChargers(p, false);
        }
        followChargeSession(p);
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
            L.i(String.format(Locale.US, "ruta: %.1f km · %.1f kWh estimados (%.1f por las cuestas; extra del coche %.1f kW y %.1f kWh al salir; "
                    + "corrección ×%.2f) · %d cargadores", np.totalKm, np.kwhCum[np.n - 1], np.gravCum[np.n - 1], np.overheadKw,
                    np.overheadTripKwh, np.factor, np.chargers.size()));
        } catch (Exception e) {
            failedDestination = dest;
            status = Str.get(R.string.hql_route_failed, e.getMessage());
            L.w("ruta: " + Http.safeError(e));
        }
    }

    // ------------------------------------------------------------------ plan de carga vivo

    /** Avisos del plan de carga: el panel del coche se apunta mientras está en pantalla. */
    /** Tipos de aviso: el plan ha cambiado o la carga es lenta (ámbar); ya puedes seguir (verde). */
    static final int ALERT_WARN = 0;
    static final int ALERT_READY = 1;

    interface ChargeAlertListener {
        void onChargeAlert(String title, String text, int kind);
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
            String settings = minKw + "/" + nets + "/" + c.chargerIncludeUnknown() + "/" + c.planArrivePct() + "/" + c.planMaxPct() + "/" + Math.round(capKwh * 10)
                    + (realSoc ? "/real" : "/indicado") + "/c" + pl.chargerVersion;
            int prog = Math.min(pl.progress, pl.n - 1);
            double f = demo ? DemoMode.planTrend() : trend.factor();
            String key = settings + "/" + prog + "/" + Math.round(soc * 2) + "/" + Math.round(f * 100) + "/" + charging;
            if (key.equals(chargeKey)) return charge;
            chargeKey = key;
            ChargePlanner.Settings s = planSettings(c, capKwh);
            lastCarVolts = s.carVolts;
            List<Charger> list = wantedChargers(pl, c);
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

    /** Los márgenes del plan y lo que se sabe del coche. */
    private static ChargePlanner.Settings planSettings(Config c, double capKwh) {
        ChargePlanner.Settings s = new ChargePlanner.Settings();
        s.arriveMinPct = c.planArrivePct();
        s.maxChargePct = c.planMaxPct();
        s.capacityKwh = capKwh;
        s.carPeakKw = carPeakKw(capKwh);
        // La de 81,9 kWh es de 800 V: en los cargadores de 400–500 V (Supercharger, los de 50 kW de antes) carga a la mitad.
        s.carVolts = carVolts(capKwh);
        return s;
    }

    /** Los cargadores de la ruta que pasan el filtro. */
    private static List<Charger> wantedChargers(Plan pl, Config c) {
        java.util.Set<String> want = ChargerFilter.parseNetworks(c.chargerNetworks());
        List<Charger> list = new ArrayList<>();
        for (Charger ch : pl.chargers) {
            if (ChargerFilter.accepts(ch, c.chargerMinKw(), want, c.chargerIncludeUnknown())) list.add(ch);
        }
        return list;
    }

    /**
     * Pico de carga rápida del coche (kW): la batería grande (81,9 kWh) carga más rápido que la de 69,9 (84 kW), ~130 kW
     * de pico, lo que da los 28 min del 19 al 81 % de ABRP en un cargador de 400 kW.
     */
    static double carPeakKw(double capKwh) {
        return capKwh >= 80 ? 130 : 84;
    }

    // ------------------------------------------------------------------ carga en directo

    /** El plan de justo antes de enchufar (para saber cuántas paradas quedaban después de esta). */
    private ChargePlanner.Result beforePlug;
    private String sessionKey = "";

    /**
     * La carga en directo: al ver que el coche carga empieza una (ChargeWatch), con el % para seguir según el plan, y
     * le pasa cada lectura de la nube. p puede ser null (cargando sin ruta: sin objetivo).
     */
    private void followChargeSession(Plan p) {
        CarCloud.Snapshot cs = CarCloud.snapshot();
        if (cs == null || !cs.hasData()) return;
        boolean charging = cs.status.charging();
        ChargeSession s = ChargeWatch.session();
        if (s == null && !charging) {
            ChargePlanner.Result r = lastChargePlan();
            synchronized (chargeLock) {
                beforePlug = r;
            }
        }
        if (s == null && charging && !Double.isNaN(cs.status.socBest())) {
            double cap = cs.capacityKwh > 0 ? cs.capacityKwh : EnergyModel.USABLE_KWH;
            Charger at = p == null ? null : chargerHere(p);
            s = new ChargeSession(cs.dataTimeMs(), cs.status.socBest(), cs.status.dcPlugged(), at, cap, carPeakKw(cap), carVolts(cap));
            sessionKey = "";
            if (cs.demo) {
                // Demostración: la carga desde el principio (en las capturas, para que se vea la curva).
                List<double[]> hist = DemoMode.chargeHistory();
                long t0 = hist.isEmpty() ? cs.dataTimeMs() : (long) hist.get(0)[0];
                s = new ChargeSession(t0, DemoMode.CHARGE_SOC0, true, at, cap, carPeakKw(cap), carVolts(cap));
                for (double[] x : hist) s.observe((long) x[0], x[1], x[2], true, false, Double.NaN, (long) x[0]);
                s.demo = true;
            }
            // Fuera del coche solo en un viaje o en carga rápida (en casa, en alterna y sin ruta, no hace falta vigilar
            // horas); en las capturas de la demostración, nunca.
            ChargeWatch.begin(ctx, s, (p != null || s.dc) && (!cs.demo || DemoMode.live()));
        }
        if (s != null && p != null && p.n >= 2) sessionTarget(s, p);
        ChargeWatch.tick(ctx, cs);
    }

    /** Capturas de la demostración (reloj quieto, sin bucle): la carga en directo al pintar. */
    void demoChargeTick() {
        if (demo && !DemoMode.live()) followChargeSession(plan());
    }

    /** El cargador en el que se carga: la parada del plan que está aquí o, si no, el más cercano al móvil (300 m). */
    private Charger chargerHere(Plan p) {
        double here = p.km[Math.min(p.progress, p.n - 1)];
        ChargePlanner.Result before;
        synchronized (chargeLock) {
            before = beforePlug;
        }
        if (before != null) {
            for (ChargePlanner.Stop st : before.stops) if (Math.abs(st.km - here) <= ChargePlanner.HERE_KM) return st.charger;
        }
        CarSensors.Snapshot g = sensors.snapshot();
        if (Double.isNaN(g.lat)) return null;
        Charger best = null;
        double bestKm = 0.3;
        for (Charger c : p.chargers) {
            double km = segmentKm(c.lat, c.lon, c.lat, c.lon, g.lat, g.lon);
            if (km < bestKm) {
                bestKm = km;
                best = c;
            }
        }
        return best;
    }

    /**
     * El objetivo de la carga: el % mínimo (más un margen) para que el resto del viaje no necesite más paradas de las
     * que quedaban, a qué parada (o al destino) se llega y con cuánto, y el % que ahorra la siguiente parada. Se rehace
     * si cambia el plan, el filtro, la tendencia o el avance.
     */
    private void sessionTarget(ChargeSession s, Plan p) {
        ChargePlanner.Result before;
        synchronized (chargeLock) {
            before = beforePlug != null ? beforePlug : charge;
        }
        Config c = new Config(ctx);
        int prog = Math.min(p.progress, p.n - 1);
        double here = p.km[prog];
        double f = demo ? DemoMode.planTrend() : trend.factor();
        int ahead = 0;
        if (before != null) for (ChargePlanner.Stop st : before.stops) if (st.km > here + ChargePlanner.HERE_KM) ahead++;
        String key = System.identityHashCode(p) + "/" + p.chargerVersion + "/" + c.chargerMinKw() + "/" + c.chargerNetworks() + "/"
                + c.chargerIncludeUnknown() + "/" + c.planArrivePct() + "/" + c.planMaxPct() + "/" + Math.round(f * 100) + "/" + prog + "/"
                + ahead;
        if (key.equals(sessionKey)) return;
        sessionKey = key;
        ChargePlanner.Settings set = planSettings(c, s.capacityKwh);
        List<Charger> all = wantedChargers(p, c);
        List<Charger> list = new ArrayList<>();
        for (Charger ch : all) if (Math.abs(ch.kmAlong - here) > ChargePlanner.HERE_KM) list.add(ch);
        double[] kwh = ChargePlanner.scaled(p.km, p.kwhCum, here, f);
        double ready = ChargePlanner.readyPct(p.km, kwh, here, 0, list, set, ahead);
        if (Double.isNaN(ready)) {
            s.setTarget(Double.NaN, Double.NaN, "");
            s.setSkip(Double.NaN, "", Double.NaN, Double.NaN);
            L.i("carga en directo: con el plan no hay % que valga (no se llega ni al 100 %)");
            return;
        }
        double target = Math.min(100, ready + ChargeSession.READY_MARGIN_PCT);
        ChargePlanner.Result after = ChargePlanner.plan(p.km, kwh, here, target, list, set);
        String name = p.destination == null ? "" : p.destination;
        double arrive = after.arrivalPct;
        if (!after.stops.isEmpty()) {
            name = after.stops.get(0).charger.name;
            arrive = after.stops.get(0).arrivePct;
        }
        s.setTarget(ready, arrive, name);
        double skipPct = Double.NaN;
        if (ahead >= 1 && !after.stops.isEmpty()) {
            double skip = ChargePlanner.readyPct(p.km, kwh, here, 0, list, set, ahead - 1);
            if (!Double.isNaN(skip)) skipPct = Math.min(100, skip + ChargeSession.READY_MARGIN_PCT);
        }
        if (!Double.isNaN(skipPct) && skipPct > target + 1) {
            ChargePlanner.Stop next = after.stops.get(0);
            double extra = s.minutesTo(skipPct) - s.minutesTo(target);
            s.setSkip(skipPct, next.charger.name, extra, next.minutes);
        } else {
            s.setSkip(Double.NaN, "", Double.NaN, Double.NaN);
        }
        L.i(String.format(Locale.US, "carga en directo: seguir con %.1f %% (%d paradas después; llegas a %s con %.0f %%)%s", target,
                ahead, name, arrive, Double.isNaN(s.skipPct) ? "" : String.format(Locale.US, "; con %.0f %% te ahorras %s", s.skipPct, s.skipName)));
    }

    /** Voltaje de la batería según su capacidad: la de 81,9 kWh del C10 es de 800 V; la de 69,9, de 400. */
    static double carVolts(double capKwh) {
        return capKwh >= 80 ? 800 : 400;
    }

    private volatile double lastCarVolts = 400;

    /** Voltaje de la batería del coche con el que se hizo el último plan (400 si aún no hay). */
    double carVolts() {
        return lastCarVolts;
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
        if (l != null) l.onChargeAlert(title, text, ALERT_WARN);
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
        NavApps.goWithStops(ctx, p, cp);
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
            note = String.format(Locale.US, "; la corrección pasa a ×%.2f (%d rutas)", RouteCalibration.residual(all), all.size());
        }
        L.i(String.format(Locale.US, "ruta: llegada: previsto %.2f kWh, real %s (nube Leapmotor, %.0f km)%s", predicted,
                generator ? "sin medir (el generador del REEV cargó)" : String.format(Locale.US, "%.2f kWh", real), km, note));
    }

    /**
     * Factor de ajuste a la conducción real: parte del historial del coche (su consumo de los últimos viajes, según la
     * nube) y lo afinan las llegadas medidas. 1 sin nada de eso.
     */
    /** Gasto extra del coche (por viaje y por hora) según su historial de la nube; NONE sin él. */
    private RouteCalibration.Overhead overhead() {
        if (demo) return RouteCalibration.Overhead.NONE;
        return RouteCalibration.overhead(CarCloud.history(), 15);
    }

    /** Corrección que queda según las llegadas medidas con el modelo de ahora (1 sin ellas). */
    private double residualFactor() {
        if (demo) return 1;
        return RouteCalibration.residual(RouteCalibration.fromJson(new Config(ctx).routeCalibration()));
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
        // La conducción, con la corrección que queda; aparte, el gasto extra del coche (fijo al salir y por hora).
        double f = residualFactor();
        RouteCalibration.Overhead oh = overhead();
        p.factor = f;
        p.overheadKw = oh.kw;
        p.overheadTripKwh = oh.perTripKwh;
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
            p.kwhCum[i] = p.kwhCum[i - 1] + drive * f + oh.kw * h + hills + (i == 1 ? oh.perTripKwh : 0);
            p.gravCum[i] = p.gravCum[i - 1] + hills;
        }

        // 6. Cargadores a menos de 2,5 km de la ruta (OpenStreetMap).
        findChargers(p);
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

    /**
     * Cargadores junto a la ruta: recuadros pequeños a lo largo de ella (ROUTE_BOX_KM, con CHARGER_RADIUS_KM de margen),
     * varios por consulta, y luego solo los que están de verdad a menos de CHARGER_RADIUS_KM de la carretera. La
     * consulta «alrededor de la línea» (around con la polilínea) es de las más pesadas de Overpass: en rutas largas
     * los servidores públicos la cortaban (504/500) y se perdían tramos enteros de cargadores.
     */
    static final double ROUTE_BOX_KM = 20;
    static final double CHARGER_RADIUS_KM = 2.5;
    /** Recuadros por consulta (unos 240 km de ruta). */
    static final int BOXES_PER_QUERY = 12;

    /** Cuadrículas por consulta a Overpass. */
    static final int TILES_PER_QUERY = 10;
    /** Con cuadrículas sin datos, se reintentan cada tanto mientras dure la ruta. */
    static final long CHARGER_RETRY_MS = 45_000;
    private long lastChargerRetryMs;

    private void findChargers(Plan p) {
        p.routeTiles = ChargerCache.tilesFor(routeBoxes(p.lat, p.lon, p.km, ROUTE_BOX_KM, CHARGER_RADIUS_KM));
        // En España, los del registro oficial (con potencia y voltaje); si aún no están en el móvil o son viejos, se
        // descargan aparte y, mientras, valen los de OpenStreetMap.
        if (!demo && DgtChargers.nearSpain(p.lat, p.lon)) {
            // La primera vez se esperan (unos segundos: ~3 MB): mejor que pedir toda la ruta a Overpass, que se satura.
            if (DgtChargers.sites(ctx) == null) {
                if (DgtChargers.stale(ctx)) DgtChargers.refresh(ctx);
            } else {
                DgtChargers.refreshIfStale(ctx);
            }
        }
        refreshChargers(p, true);
    }

    /**
     * Pide a Overpass las cuadrículas de la ruta sin ningún dato (ni de la DGT ni en la caché; all: todas las tandas; si
     * no, hasta el primer fallo) y rehace la lista de cargadores de la ruta. Las que ya tienen los de la DGT se completan
     * con OpenStreetMap en segundo plano (sin esperar a Overpass, que se satura a ratos).
     */
    private void refreshChargers(Plan p, boolean all) {
        long now = System.currentTimeMillis();
        List<String> missing = new ArrayList<>();
        for (String t : p.routeTiles) if (DgtChargers.inTile(t) == null && ChargerCache.get(ctx, t, now) == null) missing.add(t);
        int cached = p.routeTiles.size() - missing.size();
        int fetched = 0;
        for (int i = 0; i < missing.size(); i += TILES_PER_QUERY) {
            List<String> part = missing.subList(i, Math.min(missing.size(), i + TILES_PER_QUERY));
            try {
                fetchTiles(part, now);
                fetched += part.size();
            } catch (Exception e) {
                L.w("ruta: cargadores: " + part.size() + " zonas sin respuesta de OpenStreetMap (" + Http.safeError(e) + ")");
                if (!all) break;
            }
        }
        rebuildChargers(p);
        L.i(String.format(Locale.US, "ruta: cargadores: %d zonas con datos, %d pedidas ahora, %d sin datos · %d junto a la ruta "
                + "(%d de la DGT; %d zonas por completar con OpenStreetMap)", cached, fetched, p.missingTiles.size(), p.chargers.size(),
                p.dgtCount, p.osmPending.size()));
        if (!p.osmPending.isEmpty()) fetchOsmInBackground(p);
    }

    /** Si falló, se vuelven a pedir los de OpenStreetMap que completan los de la DGT pasado esto. */
    static final long OSM_EXTRA_RETRY_MS = 120_000;
    private volatile boolean osmBusy;
    private volatile long lastOsmExtraMs;

    /** Los cargadores de OpenStreetMap de las cuadrículas que ya tienen los de la DGT, en otro hilo; luego, la lista. */
    private void fetchOsmInBackground(Plan p) {
        if (osmBusy || demo) return;
        List<String> tiles = new ArrayList<>(p.osmPending);
        if (tiles.isEmpty()) return;
        osmBusy = true;
        lastOsmExtraMs = SystemClock.elapsedRealtime();
        Thread t = new Thread(() -> {
            long now = System.currentTimeMillis();
            int ok = 0;
            try {
                for (int i = 0; i < tiles.size(); i += TILES_PER_QUERY) {
                    List<String> part = tiles.subList(i, Math.min(tiles.size(), i + TILES_PER_QUERY));
                    try {
                        fetchTiles(part, now);
                        ok += part.size();
                    } catch (Exception e) {
                        L.w("ruta: cargadores de OpenStreetMap (además de la DGT): " + part.size() + " zonas sin respuesta ("
                                + Http.safeError(e) + ")");
                    }
                }
                if (ok > 0) {
                    synchronized (RoutePlanner.this) {
                        if (plan == p) rebuildChargers(p);
                    }
                    L.i(String.format(Locale.US, "ruta: cargadores con OpenStreetMap: %d zonas · %d junto a la ruta (%d de la DGT)", ok,
                            p.chargers.size(), p.dgtCount));
                }
            } finally {
                osmBusy = false;
            }
        }, "hql-osm");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /** Un cargador de OpenStreetMap a menos de esto de uno de la DGT de la misma red es el mismo (km). */
    static final double SAME_SITE_NETWORK_KM = 0.4;
    /** …y a menos de esto, aunque no se sepa su red (km). */
    static final double SAME_SITE_KM = 0.12;

    /**
     * Junta los de la DGT (mandan: potencia y voltaje oficiales) con los de OpenStreetMap de las mismas zonas: de estos,
     * solo los que no están ya (el mismo sitio, también entre ellos) y dicen algo útil (su potencia o su red); los demás son el ruido de
     * «potencia sin confirmar». Hay operadores que no han dado de alta todos sus puntos en el registro (un hub de Zunder
     * de 250 kW en la A-2 no está) y OpenStreetMap sí los tiene.
     */
    static List<Charger> mergeSources(List<Charger> official, List<Charger> osm) {
        List<Charger> out = new ArrayList<>(official);
        for (Charger o : osm) {
            boolean useful = (o.kwSource == ChargerFilter.KW_TAGGED && o.maxKw > 0) || o.kwSource == ChargerFilter.KW_NETWORK
                    || !ChargerFilter.OTHER.equals(o.network);
            if (!useful) continue;
            boolean dup = false;
            // Contra los de la DGT y contra los ya añadidos (OpenStreetMap repite a veces una estación: nodo y área).
            for (Charger d : out) {
                if (Math.abs(d.lat - o.lat) > 0.01 || Math.abs(d.lon - o.lon) > 0.015) continue;
                double km = segmentKm(d.lat, d.lon, d.lat, d.lon, o.lat, o.lon);
                boolean sameNet = !ChargerFilter.OTHER.equals(o.network) && o.network.equals(d.network);
                if (km < SAME_SITE_KM || (sameNet && km < SAME_SITE_NETWORK_KM)) {
                    dup = true;
                    break;
                }
            }
            if (!dup) out.add(o);
        }
        return out;
    }

    /**
     * La lista de cargadores de la ruta (a menos de CHARGER_RADIUS_KM) y los huecos: en cada cuadrícula, los de la DGT si
     * tiene alguno (España), completados con los de OpenStreetMap que falten (mergeSources); si no, los de OpenStreetMap.
     */
    private void rebuildChargers(Plan p) {
        long now = System.currentTimeMillis();
        List<Charger> list = new ArrayList<>();
        List<Charger> official = new ArrayList<>();
        List<Charger> extra = new ArrayList<>();
        java.util.Set<String> missing = new java.util.LinkedHashSet<>();
        List<String> osmPending = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        int dgtVersion = DgtChargers.version;
        for (String t : p.routeTiles) {
            List<DgtChargers.Site> dgt = DgtChargers.inTile(t);
            if (dgt != null) {
                for (DgtChargers.Site s : dgt) {
                    int idx = nearest(p, s.lat, s.lon, -1);
                    if (distToRouteKm(p.lat, p.lon, idx, s.lat, s.lon) > CHARGER_RADIUS_KM) continue;
                    Charger c = s.toCharger();
                    c.kmAlong = p.km[idx];
                    official.add(c);
                }
            }
            List<ChargerCache.Item> items = ChargerCache.get(ctx, t, now);
            if (items == null) {
                if (dgt == null) missing.add(t);
                else osmPending.add(t);
                continue;
            }
            for (ChargerCache.Item it : items) {
                if (!it.id.isEmpty() && !seen.add(it.id)) continue;
                int idx = nearest(p, it.lat, it.lon, -1);
                if (distToRouteKm(p.lat, p.lon, idx, it.lat, it.lon) > CHARGER_RADIUS_KM) continue;
                Charger c = it.toCharger();
                c.kmAlong = p.km[idx];
                (dgt != null ? extra : list).add(c);
            }
        }
        list.addAll(mergeSources(official, extra));
        java.util.Collections.sort(list, (a, b) -> Double.compare(a.kmAlong, b.kmAlong));
        p.chargers = list;
        p.dgtVersion = dgtVersion;
        p.dgtCount = official.size();
        p.osmPending = osmPending;
        p.missingTiles = new ArrayList<>(missing);
        p.chargerGaps = ChargerCache.gaps(p.lat, p.lon, p.km, missing);
        p.chargerVersion++;
    }

    /** Recuadros [sur, oeste, norte, este, km desde, km hasta] que cubren la ruta a tramos de segKm, con padKm de margen. */
    static List<double[]> routeBoxes(double[] lat, double[] lon, double[] km, double segKm, double padKm) {
        List<double[]> out = new ArrayList<>();
        int n = lat.length;
        int start = 0;
        while (start < n - 1) {
            double s = lat[start], w = lon[start], no = lat[start], e = lon[start];
            int i = start;
            while (i < n - 1 && km[i + 1] - km[start] <= segKm) {
                i++;
                s = Math.min(s, lat[i]);
                no = Math.max(no, lat[i]);
                w = Math.min(w, lon[i]);
                e = Math.max(e, lon[i]);
            }
            if (i == start) i++; // un tramo más largo que segKm: al menos hasta el punto siguiente
            s = Math.min(s, lat[i]);
            no = Math.max(no, lat[i]);
            w = Math.min(w, lon[i]);
            e = Math.max(e, lon[i]);
            double dLat = padKm / 111.32;
            double dLon = padKm / (111.32 * Math.max(0.2, Math.cos(Math.toRadians((s + no) / 2))));
            out.add(new double[]{s - dLat, w - dLon, no + dLat, e + dLon, km[start], km[i]});
            start = i;
        }
        return out;
    }

    /** Distancia (km) de un punto a la ruta junto al punto i (los tramos i-1..i e i..i+1). */
    static double distToRouteKm(double[] lat, double[] lon, int i, double la, double lo) {
        double best = Double.MAX_VALUE;
        for (int k = Math.max(0, i - 1); k <= Math.min(lat.length - 2, i); k++) {
            best = Math.min(best, segmentKm(lat[k], lon[k], lat[k + 1], lon[k + 1], la, lo));
        }
        if (lat.length == 1) best = segmentKm(lat[0], lon[0], lat[0], lon[0], la, lo);
        return best;
    }

    /** Distancia (km) del punto (la, lo) al tramo a-b, en plano local (vale para unos pocos km). */
    static double segmentKm(double laA, double loA, double laB, double loB, double la, double lo) {
        double cos = Math.cos(Math.toRadians(la));
        double ax = (loA - lo) * cos * 111.32, ay = (laA - la) * 111.32;
        double bx = (loB - lo) * cos * 111.32, by = (laB - la) * 111.32;
        double dx = bx - ax, dy = by - ay;
        double len2 = dx * dx + dy * dy;
        double t = len2 <= 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / len2));
        double px = ax + t * dx, py = ay + t * dy;
        return Math.sqrt(px * px + py * py);
    }

    /** Una tanda de cuadrículas a Overpass; se guardan todas (también las que no tienen cargadores). */
    private void fetchTiles(List<String> keys, long nowMs) throws Exception {
        StringBuilder q = new StringBuilder("[out:json][timeout:50];(");
        java.util.Map<String, List<ChargerCache.Item>> got = new java.util.HashMap<>();
        for (String k : keys) {
            double[] b = ChargerCache.tileBox(k);
            q.append(String.format(Locale.US, "nwr[\"amenity\"=\"charging_station\"](%.4f,%.4f,%.4f,%.4f);", b[0], b[1], b[2], b[3]));
            got.put(k, new ArrayList<>());
        }
        // Nodos y también áreas (algunas estaciones están dibujadas como superficie): de esas, su centro.
        q.append(");out center tags;");
        JSONArray els = new JSONObject(Http.overpass(q.toString())).getJSONArray("elements");
        for (int i = 0; i < els.length(); i++) {
            ChargerCache.Item it = parseItem(els.getJSONObject(i));
            if (it == null) continue;
            List<ChargerCache.Item> l = got.get(ChargerCache.tileOf(it.lat, it.lon));
            if (l != null) l.add(it);
        }
        ChargerCache.put(ctx, got, nowMs);
    }

    /** Un elemento de OpenStreetMap (amenity=charging_station) como cargador guardado, o null si no sirve. */
    static ChargerCache.Item parseItem(JSONObject e) {
        JSONObject tags = e.optJSONObject("tags");
        if (tags == null) return null;
        ChargerCache.Item c = new ChargerCache.Item();
        c.id = e.optString("type") + e.optLong("id");
        JSONObject center = e.optJSONObject("center");
        c.lat = center != null ? center.optDouble("lat") : e.optDouble("lat");
        c.lon = center != null ? center.optDouble("lon") : e.optDouble("lon");
        if (Double.isNaN(c.lat) || Double.isNaN(c.lon)) return null;
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
            if (key.endsWith(":output") || key.equals("maxpower")) c.kw = Math.max(c.kw, parseKw(val));
            if (key.matches("socket:(type2_combo|chademo|type1_combo|tesla_supercharger|tesla_supercharger_ccs|nacs):voltage")) {
                c.volts = Math.max(c.volts, parseVolts(val));
            }
            if (key.matches("socket:[a-z0-9_]+") && !val.equals("no")) c.sockets |= ChargerFilter.socketKind(key.substring(7));
        }
        String op = tags.optString("operator");
        c.detail = sockets + (op.isEmpty() || op.equals(c.name) ? "" : (sockets.length() > 0 ? " · " : "") + op);
        return c;
    }

    private static double parseVolts(String v) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("([0-9]+(?:[.,][0-9]+)?)").matcher(v);
        double best = 0;
        while (m.find()) best = Math.max(best, Double.parseDouble(m.group(1).replace(',', '.')));
        return best > 1500 ? 0 : best;
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
