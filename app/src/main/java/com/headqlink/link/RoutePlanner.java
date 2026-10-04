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
 */
final class RoutePlanner {
    static final class Charger {
        String name;
        String detail;
        double lat;
        double lon;
        double kmAlong;
        double maxKw;
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
        double totalKm;
        long totalSeconds;
        double destTemp = Double.NaN;
        double destWind = Double.NaN;
        double destRain = Double.NaN;
        int destCode = -1;
        final List<Charger> chargers = new ArrayList<>();
        /** Índice del punto de la ruta más cercano a la posición actual. */
        volatile int progress;
    }

    private static RoutePlanner instance;

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
        return manual;
    }

    /** Fija (o quita, con null) el destino elegido en el coche; la ruta se calcula enseguida. */
    static void setManualDestination(Place pl) {
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
                    L.w("ruta: " + e);
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

    /** Última posición conocida del móvil (NaN si no hay). */
    double[] position() {
        CarSensors.Snapshot s = sensors.snapshot();
        return new double[]{s.lat, s.lon};
    }
    private long lastAttemptMs;
    private String failedDestination;
    // Batería: % fijado por el usuario menos la energía estimada desde entonces.
    private double lastKwhTotal;

    private RoutePlanner(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.sensors = CarSensors.start(ctx);
    }

    static synchronized RoutePlanner start(Context ctx) {
        if (instance == null) {
            instance = new RoutePlanner(ctx);
            instance.running = true;
            new Thread(instance::loop, "route-planner").start();
        }
        return instance;
    }

    static synchronized void stop() {
        if (instance == null) return;
        instance.running = false;
        CarSensors.stop();
        instance = null;
    }

    static RoutePlanner get() {
        return instance;
    }

    Plan plan() {
        return plan;
    }

    String status() {
        return status;
    }

    /** % de batería estimado ahora, o NaN si el usuario no lo ha indicado. */
    double socNow() {
        Config c = new Config(ctx);
        double set = c.socPct();
        if (Double.isNaN(set)) return Double.NaN;
        return Math.max(0, set - c.socUsedKwh() / EnergyModel.USABLE_KWH * 100);
    }

    /** El usuario indica el % de batería actual. */
    void setSoc(double pct) {
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
                L.w("ruta: " + e);
            }
            SystemClock.sleep(5000);
        }
    }

    private synchronized void step() {
        CarSensors.Snapshot s = sensors.snapshot();
        trackEnergy(s);
        NavTap.Info nav = NavTap.getInfo();
        Plan p = plan;
        if (p != null && !Double.isNaN(s.lat)) p.progress = nearest(p, s.lat, s.lon, p.progress);
        // Destino: el de AA si lo manda; si no, el elegido aquí.
        Place m = manual;
        String dest = nav.active && nav.destination != null ? nav.destination : m != null ? m.name : null;
        boolean useManual = dest != null && m != null && dest.equals(m.name);
        if (dest == null) {
            if (p == null) status = noRoute();
            return;
        }
        if (p != null && dest.equals(p.destination)) return;
        if (Double.isNaN(s.lat)) {
            status = Str.get(R.string.hql_waiting_gps);
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (dest.equals(failedDestination) && now - lastAttemptMs < 120_000) return;
        if (lastAttemptMs > 0 && now - lastAttemptMs < 20_000) return;
        lastAttemptMs = now;
        status = Str.get(R.string.hql_route_calculating, dest);
        try {
            Plan np = useManual ? build(dest, s.lat, s.lon, m.lat, m.lon) : build(dest, s.lat, s.lon, Double.NaN, Double.NaN);
            plan = np;
            failedDestination = null;
            status = "";
            L.i(String.format(Locale.US, "ruta: %s · %.1f km · %.1f kWh estimados · %d cargadores",
                    np.destination, np.totalKm, np.kwhCum[np.n - 1], np.chargers.size()));
        } catch (Exception e) {
            failedDestination = dest;
            status = Str.get(R.string.hql_route_failed, e.getMessage());
            L.w("ruta: " + e);
        }
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

        // 5. Energía estimada por tramo.
        EnergyModel m = new EnergyModel();
        p.kwhCum = new double[n];
        for (int i = 1; i < n; i++) {
            double dkm = p.km[i] - p.km[i - 1];
            double s = Math.max(1, segS[i]);
            double v = Math.max(5, Math.min(140, dkm / s * 3600));
            double grade = dkm > 0 ? Math.max(-15, Math.min(15, (p.elev[i] - p.elev[i - 1]) / (dkm * 1000) * 100)) : 0;
            double kw = m.compute(v, grade, p.headwind[i], p.temp[i], 0);
            p.kwhCum[i] = p.kwhCum[i - 1] + kw * s / 3600;
        }

        // 6. Cargadores a menos de 2,5 km de la ruta (OpenStreetMap).
        try {
            findChargers(p);
        } catch (Exception e) {
            L.w("ruta: sin cargadores: " + e.getMessage());
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
            L.w("ruta: sin tiempo previsto: " + e.getMessage());
        }
        return out;
    }

    private void findChargers(Plan p) throws Exception {
        StringBuilder poly = new StringBuilder();
        double lastKm = -10;
        int count = 0;
        for (int i = 0; i < p.n; i++) {
            if (p.km[i] - lastKm < 3 && i != p.n - 1) continue;
            if (count++ > 0) poly.append(',');
            poly.append(String.format(Locale.US, "%.4f,%.4f", p.lat[i], p.lon[i]));
            lastKm = p.km[i];
        }
        String q = "[out:json][timeout:25];node[\"amenity\"=\"charging_station\"](around:2500," + poly + ");out body 300;";
        JSONArray els = new JSONObject(Http.post("https://overpass-api.de/api/interpreter", "data=" + Uri.encode(q)))
                .getJSONArray("elements");
        for (int i = 0; i < els.length(); i++) {
            JSONObject e = els.getJSONObject(i);
            JSONObject tags = e.optJSONObject("tags");
            if (tags == null) continue;
            Charger c = new Charger();
            c.lat = e.getDouble("lat");
            c.lon = e.getDouble("lon");
            c.name = firstNonEmpty(tags.optString("name"), tags.optString("operator"), tags.optString("brand"), "Punto de carga");
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
        p.chargers.sort((a, b) -> Double.compare(a.kmAlong, b.kmAlong));
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
