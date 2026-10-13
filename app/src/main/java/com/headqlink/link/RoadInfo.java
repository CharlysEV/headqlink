package com.headqlink.link;

import android.content.Context;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Datos de la vía para la pestaña "Conducción", con OpenStreetMap (Overpass) y cálculos locales:
 * - Límite de velocidad de la vía por la que se circula (etiqueta maxspeed, o el genérico español
 *   según el tipo de vía, marcado como estimado).
 * - Radares fijos cercanos (highway=speed_camera) delante, en la vía por la que se va y en el sentido de la marcha.
 * Dos hilos: uno pregunta a OpenStreetMap (puede tardar o fallar) y otro, cada segundo, mira los radares con la
 * posición de ese momento. Antes iban en el mismo y, si OpenStreetMap tardaba, el aviso salía con la posición de hacía
 * un rato (hasta un kilómetro después del radar).
 * - Sol de cara: posición del sol (NOAA) frente al rumbo.
 */
final class RoadInfo {
    static final class State {
        int limitKmh = -1;
        boolean limitEstimated;
        String roadName = "";
        double cameraM = -1;
        int cameraLimit = -1;
        boolean sunGlare;
        double sunElevation = Double.NaN;
        String sunset = "";
        /** Salida y puesta de sol de hoy (ms; -1 si no hay). */
        long sunriseMs = -1;
        long sunsetMs = -1;
    }

    private static RoadInfo instance;
    private final android.content.Context ctx;
    private final RadarVoice radarVoice = new RadarVoice();
    private final CarSensors sensors;
    private volatile boolean running;
    private volatile State state = new State();
    private final List<double[]> cameras = new ArrayList<>(); // lat, lon, límite, sentido (grados; NaN si ambos)
    private double camLat = Double.NaN;
    private double camLon = Double.NaN;
    private long camMs;
    /** La última carga de radares falló (sin red, Overpass saturado): se reintenta pronto, no a los 8 km. */
    private boolean camFailed;
    private double limitLat = Double.NaN;
    private double limitLon = Double.NaN;
    private long limitMs;
    private int limit = -1;
    private boolean limitEst;
    private String road = "";
    /** La vía por la que se va es de sentido único (una calzada de autovía): el radar sin sentido conocido a la izquierda es de la de enfrente. */
    private volatile boolean roadOneway;

    /** Modo demostración: la vía es la del guion (DemoMode), sin consultas a OpenStreetMap. */
    private final boolean demo;

    private RoadInfo(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        sensors = CarSensors.start(ctx);
        demo = DemoMode.active();
    }

    static synchronized RoadInfo start(Context ctx) {
        if (instance == null) {
            instance = new RoadInfo(ctx);
            instance.running = true;
            if (!instance.demo) {
                new Thread(instance::loop, "road-info").start();
                new Thread(instance::netLoop, "road-net").start();
            }
        }
        return instance;
    }

    static synchronized void stop() {
        if (instance == null) return;
        instance.running = false;
        CarSensors.stop();
        instance = null;
    }

    static RoadInfo get() {
        return instance;
    }

    State state() {
        return demo ? DemoMode.road() : state;
    }

    /** Cada segundo, con la posición de ese momento: radares, sol y lo último que se sepa de la vía. */
    private void loop() {
        while (running) {
            try {
                step();
            } catch (Exception e) {
                L.w("vía: " + Http.safeError(e));
            }
            SystemClock.sleep(1000);
        }
    }

    /** Las consultas a OpenStreetMap, en su hilo (si tarda o falla, los avisos siguen al día). */
    private void netLoop() {
        while (running) {
            try {
                netStep();
            } catch (Http.OverpassPausedException ignored) {
                // Servidores saturados o sin red: se vuelve a probar cuando acabe la pausa (ya está en el log).
            } catch (Exception e) {
                L.w("vía: " + Http.safeError(e));
            }
            SystemClock.sleep(2000);
        }
    }

    private void netStep() throws Exception {
        CarSensors.Snapshot s = sensors.snapshot();
        if (Double.isNaN(s.lat)) return;
        long now = SystemClock.elapsedRealtime();
        // Los radares primero y por su lado: si falla la consulta del límite, no se quedan sin cargar.
        Exception camError = null;
        if (shouldLoadCameras(camLat, camLon, s.lat, s.lon, now - camMs, camFailed)) {
            camMs = now;
            try {
                loadCameras(s.lat, s.lon);
                // Solo una carga buena cuenta como hecha aquí; si falla, se reintenta a los CAM_RETRY_MS.
                camLat = s.lat;
                camLon = s.lon;
                camFailed = false;
            } catch (Exception e) {
                camFailed = true;
                camError = e;
                if (!(e instanceof Http.OverpassPausedException)) L.w("vía: radares sin cargar (" + Http.safeError(e) + "); reintento en "
                        + CAM_RETRY_MS / 1000 + " s");
            }
        }
        // Overpass pide moderación: como mucho una consulta cada 8 s y cada 250 m.
        if ((Double.isNaN(limitLat) || dist(s.lat, s.lon, limitLat, limitLon) > 250) && now - limitMs > 8000) {
            limitMs = now;
            limitLat = s.lat;
            limitLon = s.lon;
            updateLimit(s);
        }
        if (camError != null) throw camError;
    }

    /** Radares: se cargan 15 km a la redonda y se vuelven a cargar a los RELOAD_M (como mucho, cada RELOAD_MS). */
    static final double CAM_RELOAD_M = 8000;
    static final long CAM_RELOAD_MS = 60_000;
    /** Tras una carga fallida, se reintenta a los 20 s (antes no se reintentaba hasta recorrer otros 8 km). */
    static final long CAM_RETRY_MS = 20_000;

    /**
     * Puro: ¿toca cargar los radares? Sin carga buena aún (o lejos de la última, más de CAM_RELOAD_M) y pasado el tiempo
     * de espera desde el último intento: CAM_RELOAD_MS, o CAM_RETRY_MS si el último falló.
     */
    static boolean shouldLoadCameras(double camLat, double camLon, double lat, double lon, long sinceLastMs, boolean failed) {
        boolean far = Double.isNaN(camLat) || dist(lat, lon, camLat, camLon) > CAM_RELOAD_M;
        return far && sinceLastMs > (failed ? CAM_RETRY_MS : CAM_RELOAD_MS);
    }

    private void step() {
        CarSensors.Snapshot s = sensors.snapshot();
        if (Double.isNaN(s.lat)) return;
        State st = new State();
        st.limitKmh = limit;
        st.limitEstimated = limitEst;
        st.roadName = road;
        // Radar más cercano delante, en la vía por la que se va (cerca de la línea de la marcha) y de nuestro sentido.
        double[] nearest = null;
        if (s.speedKmh > 5) {
            synchronized (cameras) {
                for (double[] c : cameras) {
                    double ahead = aheadOnRoad(s.lat, s.lon, s.headingDeg, c[0], c[1], c[3], roadOneway);
                    if (ahead >= 0 && (st.cameraM < 0 || ahead < st.cameraM)) {
                        st.cameraM = ahead;
                        st.cameraLimit = (int) c[2];
                        nearest = c;
                    }
                }
            }
        }
        // Aviso por voz (una vez por radar, y una vez si se va por encima del límite cerca de él).
        RadarVoice.Alert alert = radarVoice.onTick(st.cameraM, st.cameraLimit, nearest != null ? nearest[0] : 0,
                nearest != null ? nearest[1] : 0, s.speedKmh);
        if (alert != null && new Config(ctx).radarVoice()) {
            String text = Str.get(alert.text, alert.args);
            L.i("vía: aviso por voz: " + text);
            VoiceAlert.say(ctx, text);
        }
        double[] sun = sunPosition(s.lat, s.lon, System.currentTimeMillis());
        st.sunElevation = sun[0];
        double diff = Math.abs(((sun[1] - s.headingDeg) + 540) % 360 - 180);
        st.sunGlare = s.speedKmh > 10 && sun[0] > 0 && sun[0] < 20 && diff < 30;
        fillSun(st, s.lat, s.lon, System.currentTimeMillis());
        state = st;
    }

    private void updateLimit(CarSensors.Snapshot s) throws Exception {
        String q = String.format(Locale.US, "[out:json][timeout:10];way(around:25,%.6f,%.6f)[\"highway\"];out tags geom;", s.lat, s.lon);
        JSONArray els = new JSONObject(Http.overpass(q, true))
                .getJSONArray("elements");
        JSONObject best = null;
        double bestScore = Double.MAX_VALUE;
        for (int i = 0; i < els.length(); i++) {
            JSONObject w = els.getJSONObject(i);
            JSONArray g = w.optJSONArray("geometry");
            if (g == null || g.length() < 2) continue;
            String hw = w.getJSONObject("tags").optString("highway");
            if (hw.matches("footway|cycleway|path|steps|pedestrian|track|service|bridleway|corridor")) continue;
            // Tramo más cercano y su rumbo: preferimos la vía alineada con nuestra marcha.
            double score = Double.MAX_VALUE;
            for (int k = 1; k < g.length(); k++) {
                JSONObject a = g.getJSONObject(k - 1);
                JSONObject b = g.getJSONObject(k);
                double d = dist(s.lat, s.lon, (a.getDouble("lat") + b.getDouble("lat")) / 2, (a.getDouble("lon") + b.getDouble("lon")) / 2);
                double brg = bearing(a.getDouble("lat"), a.getDouble("lon"), b.getDouble("lat"), b.getDouble("lon"));
                double diff = Math.abs(((brg - s.headingDeg) + 540) % 360 - 180);
                diff = Math.min(diff, 180 - diff); // la vía vale en los dos sentidos
                score = Math.min(score, d + (s.speedKmh > 5 ? diff * 2 : 0));
            }
            if (score < bestScore) {
                bestScore = score;
                best = w;
            }
        }
        if (best == null) {
            limit = -1;
            road = "";
            roadOneway = false;
            return;
        }
        JSONObject tags = best.getJSONObject("tags");
        roadOneway = oneway(tags) != 0;
        road = firstNonEmpty(tags.optString("name"), tags.optString("ref"));
        int l = parseLimit(tags.optString("maxspeed"));
        if (l > 0) {
            limit = l;
            limitEst = false;
        } else {
            limit = defaultLimit(tags.optString("highway"), tags.optString("lanes"));
            limitEst = true;
        }
    }

    /**
     * Radares en 15 km, con las vías en las que están: de la vía sale su sentido (en una autovía, cada calzada es de un
     * sentido; el radar de la de enfrente no es para nosotros). Y sus relaciones de control (type=enforcement): el punto
     * «from» (donde empieza lo vigilado) da el sentido de los radares que no lo llevan ni están en una vía de un sentido,
     * que son la mayoría.
     */
    private void loadCameras(double lat, double lon) throws Exception {
        String q = String.format(Locale.US, "[out:json][timeout:25];node[\"highway\"=\"speed_camera\"](around:15000,%.5f,%.5f)->.c;"
                + ".c out body;way(bn.c)[\"highway\"];out tags geom;"
                + "rel(bn.c)[\"type\"=\"enforcement\"]->.r;.r out body;(node(r.r);- node.c;);out skel;", lat, lon);
        JSONArray els = new JSONObject(Http.overpass(q))
                .getJSONArray("elements");
        List<JSONObject> nodes = new ArrayList<>();
        List<JSONObject> ways = new ArrayList<>();
        List<JSONObject> rels = new ArrayList<>();
        java.util.Map<Long, double[]> points = new java.util.HashMap<>();
        for (int i = 0; i < els.length(); i++) {
            JSONObject e = els.getJSONObject(i);
            String type = e.optString("type");
            if ("node".equals(type)) {
                JSONObject t = e.optJSONObject("tags");
                if (t != null && "speed_camera".equals(t.optString("highway"))) nodes.add(e);
                points.put(e.optLong("id"), new double[]{e.optDouble("lat"), e.optDouble("lon")});
            } else if ("way".equals(type)) {
                ways.add(e);
            } else if ("relation".equals(type)) {
                rels.add(e);
            }
        }
        int fromRel = 0;
        List<double[]> list = new ArrayList<>();
        int withDir = 0;
        for (JSONObject e : nodes) {
            double la = e.getDouble("lat");
            double lo = e.getDouble("lon");
            JSONObject t = e.optJSONObject("tags");
            String dirTag = t != null ? t.optString("direction") : "";
            // La vía del radar (la que pasa por ese punto) y su rumbo allí.
            double wayBrg = Double.NaN;
            int oneway = 0;
            for (JSONObject w : ways) {
                JSONArray g = w.optJSONArray("geometry");
                if (g == null) continue;
                for (int k = 0; k < g.length(); k++) {
                    JSONObject pt = g.getJSONObject(k);
                    if (Math.abs(pt.getDouble("lat") - la) > 1e-7 || Math.abs(pt.getDouble("lon") - lo) > 1e-7) continue;
                    JSONObject a = g.getJSONObject(k > 0 ? k - 1 : k);
                    JSONObject b = g.getJSONObject(k < g.length() - 1 ? k + 1 : k);
                    if (a != b) wayBrg = bearing(a.getDouble("lat"), a.getDouble("lon"), b.getDouble("lat"), b.getDouble("lon"));
                    oneway = oneway(w.optJSONObject("tags"));
                    break;
                }
                if (!Double.isNaN(wayBrg)) break;
            }
            double dir = cameraDirection(dirTag, wayBrg, oneway);
            if (Double.isNaN(dir)) {
                dir = relationDirection(e.optLong("id"), la, lo, rels, points);
                if (!Double.isNaN(dir)) fromRel++;
            }
            if (!Double.isNaN(dir)) withDir++;
            list.add(new double[]{la, lo, t != null ? parseLimit(t.optString("maxspeed")) : -1, dir});
        }
        synchronized (cameras) {
            cameras.clear();
            cameras.addAll(list);
        }
        L.i("vía: " + list.size() + " radares en 15 km (" + withDir + " con sentido conocido, " + fromRel + " por su relación de control)");
    }

    /**
     * Puro: sentido de un radar por su relación de control (type=enforcement): de «from» (donde empieza lo vigilado) al
     * radar o, si no hay «from», del radar a «to». NaN si no está en ninguna o los puntos están encima del radar.
     */
    static double relationDirection(long camId, double la, double lo, List<JSONObject> rels, java.util.Map<Long, double[]> points) {
        for (JSONObject r : rels) {
            JSONArray ms = r.optJSONArray("members");
            if (ms == null) continue;
            boolean device = false;
            double[] from = null;
            double[] to = null;
            for (int i = 0; i < ms.length(); i++) {
                JSONObject m = ms.optJSONObject(i);
                if (m == null || !"node".equals(m.optString("type"))) continue;
                String role = m.optString("role");
                long ref = m.optLong("ref");
                if ("device".equals(role) && ref == camId) device = true;
                else if ("from".equals(role) && from == null) from = points.get(ref);
                else if ("to".equals(role) && to == null) to = points.get(ref);
            }
            if (!device) continue;
            if (from != null && dist(from[0], from[1], la, lo) > 10) return bearing(from[0], from[1], la, lo);
            if (to != null && dist(la, lo, to[0], to[1]) > 10) return bearing(la, lo, to[0], to[1]);
        }
        return Double.NaN;
    }

    /** Puro: 1 si la vía es de sentido único en el de su dibujo, -1 si al revés, 0 si de doble sentido. */
    static int oneway(JSONObject tags) {
        if (tags == null) return 0;
        String ow = tags.optString("oneway");
        if ("-1".equals(ow) || "reverse".equals(ow)) return -1;
        if ("yes".equals(ow) || "true".equals(ow) || "1".equals(ow)) return 1;
        if ("no".equals(ow)) return 0;
        String hw = tags.optString("highway");
        if ("motorway".equals(hw) || "roundabout".equals(tags.optString("junction"))) return 1;
        return 0;
    }

    /**
     * Puro: sentido (grados) para el que vale un radar, o NaN si se desconoce (vale para los dos). direction de OSM en
     * grados o puntos cardinales manda; forward/backward es respecto al dibujo de su vía; sin eso, el de la vía si es de
     * sentido único.
     */
    static double cameraDirection(String dirTag, double wayBearing, int oneway) {
        if (dirTag != null && !dirTag.isEmpty()) {
            String d = dirTag.trim().toUpperCase(Locale.ROOT);
            if (d.matches("[0-9]+(\\.[0-9]+)?")) return Double.parseDouble(d) % 360;
            String[] card = {"N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"};
            for (int i = 0; i < card.length; i++) if (card[i].equals(d)) return i * 22.5;
            if (!Double.isNaN(wayBearing)) {
                if ("FORWARD".equals(d)) return wayBearing;
                if ("BACKWARD".equals(d)) return (wayBearing + 180) % 360;
            }
        }
        if (!Double.isNaN(wayBearing) && oneway != 0) return oneway > 0 ? wayBearing : (wayBearing + 180) % 360;
        return Double.NaN;
    }

    /** Hasta dónde se mira y cuánto puede apartarse un radar de la línea de la marcha (la vía hace curvas). */
    static final double AHEAD_M = 1500;

    /**
     * Puro: metros hasta el radar si está delante, en nuestra vía y para nuestro sentido; -1 si no. «En nuestra vía»: a
     * poca distancia de la línea de la marcha (25 m más un 8 % de lo que falta, por las curvas). Antes valía cualquier
     * radar a ±25° del rumbo, que a 1 km son 400 m a cada lado: los de otras carreteras y calles cercanas.
     */
    static double aheadOnRoad(double lat, double lon, double heading, double camLat, double camLon, double camDir) {
        return aheadOnRoad(lat, lon, heading, camLat, camLon, camDir, false);
    }

    /**
     * Igual, sabiendo si se va por una vía de un solo sentido (una calzada de autovía o autopista). Ahí, un radar sin
     * sentido conocido que queda claramente a la izquierda (más de 12 m, y algo más de lejos) está en la calzada de
     * enfrente (se circula por la derecha): no es para nosotros.
     */
    static double aheadOnRoad(double lat, double lon, double heading, double camLat, double camLon, double camDir, boolean onewayRoad) {
        double d = dist(lat, lon, camLat, camLon);
        if (d > AHEAD_M) return -1;
        double diff = Math.toRadians(((bearing(lat, lon, camLat, camLon) - heading) + 540) % 360 - 180);
        double along = d * Math.cos(diff);
        double side = d * Math.sin(diff); // + a la derecha, − a la izquierda
        double cross = Math.abs(side);
        if (along <= 0 || cross > 25 + 0.08 * along) return -1;
        if (!Double.isNaN(camDir) && Math.abs(((camDir - heading) + 540) % 360 - 180) > 60) return -1;
        if (Double.isNaN(camDir) && onewayRoad && -side > 12 + 0.02 * along) return -1;
        return along;
    }

    /** "50", "50 mph", "ES:urban", "ES:rural", "ES:motorway"… */
    static int parseLimit(String v) {
        if (v == null || v.isEmpty()) return -1;
        if (v.matches("[0-9]+")) return Integer.parseInt(v);
        if (v.endsWith("mph")) return (int) Math.round(Integer.parseInt(v.replaceAll("[^0-9]", "")) * 1.609);
        switch (v) {
            case "ES:urban":
                return 50;
            case "ES:zone30":
                return 30;
            case "ES:rural":
                return 90;
            case "ES:trunk":
            case "ES:motorway":
                return 120;
            default:
                return -1;
        }
    }

    /** Límites genéricos en España por tipo de vía (estimados cuando OSM no tiene maxspeed). */
    private static int defaultLimit(String hw, String lanes) {
        switch (hw) {
            case "motorway":
            case "motorway_link":
                return hw.endsWith("link") ? 60 : 120;
            case "trunk":
                return 100;
            case "primary":
            case "secondary":
            case "tertiary":
                return 90;
            case "residential":
            case "living_street":
                return "1".equals(lanes) || lanes.isEmpty() ? 30 : 50;
            default:
                return 50;
        }
    }

    private static String firstNonEmpty(String... s) {
        for (String x : s) if (x != null && !x.isEmpty()) return x;
        return "";
    }

    static double dist(double la1, double lo1, double la2, double lo2) {
        double dy = Math.toRadians(la2 - la1);
        double dx = Math.toRadians(lo2 - lo1) * Math.cos(Math.toRadians((la1 + la2) / 2));
        return Math.sqrt(dx * dx + dy * dy) * 6371000;
    }

    static double bearing(double la1, double lo1, double la2, double lo2) {
        double a = Math.toRadians(la1);
        double b = Math.toRadians(la2);
        double dl = Math.toRadians(lo2 - lo1);
        double y = Math.sin(dl) * Math.cos(b);
        double x = Math.cos(a) * Math.sin(b) - Math.sin(a) * Math.cos(b) * Math.cos(dl);
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }

    /** Posición del sol (NOAA simplificado): {elevación, azimut} en grados. */
    static double[] sunPosition(double lat, double lon, long utcMs) {
        double jd = utcMs / 86400000.0 + 2440587.5;
        double n = jd - 2451545.0;
        double l = (280.460 + 0.9856474 * n) % 360;
        double g = Math.toRadians((357.528 + 0.9856003 * n) % 360);
        double lambda = Math.toRadians(l + 1.915 * Math.sin(g) + 0.020 * Math.sin(2 * g));
        double eps = Math.toRadians(23.439 - 0.0000004 * n);
        double ra = Math.atan2(Math.cos(eps) * Math.sin(lambda), Math.cos(lambda));
        double dec = Math.asin(Math.sin(eps) * Math.sin(lambda));
        double gmst = (18.697374558 + 24.06570982441908 * n) % 24;
        double lst = Math.toRadians(((gmst * 15 + lon) % 360 + 360) % 360);
        double ha = lst - ra;
        double phi = Math.toRadians(lat);
        double el = Math.asin(Math.sin(phi) * Math.sin(dec) + Math.cos(phi) * Math.cos(dec) * Math.cos(ha));
        double az = Math.atan2(-Math.sin(ha), Math.tan(dec) * Math.cos(phi) - Math.sin(phi) * Math.cos(ha));
        return new double[]{Math.toDegrees(el), (Math.toDegrees(az) + 360) % 360};
    }

    /** Salida y puesta de sol del día de nowMs (y la hora local de la puesta en texto). */
    static void fillSun(State st, double lat, double lon, long nowMs) {
        long[] t = sunTimes(lat, lon, nowMs, TimeZone.getDefault());
        st.sunriseMs = t[0];
        st.sunsetMs = t[1];
        if (t[1] > 0) {
            Calendar s = Calendar.getInstance(TimeZone.getDefault());
            s.setTimeInMillis(t[1]);
            st.sunset = String.format(Locale.getDefault(), "%02d:%02d", s.get(Calendar.HOUR_OF_DAY), s.get(Calendar.MINUTE));
        }
    }

    /** Salida y puesta de sol del día local de dayMs (búsqueda por minutos desde el mediodía): {salida, puesta}, -1 si no hay. */
    static long[] sunTimes(double lat, double lon, long dayMs, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(dayMs);
        c.set(Calendar.HOUR_OF_DAY, 12);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        long noon = c.getTimeInMillis();
        long rise = -1;
        long set = -1;
        for (int m = 0; m < 12 * 60; m += 2) {
            if (set < 0 && sunPosition(lat, lon, noon + m * 60_000L)[0] < -0.83) set = noon + m * 60_000L;
            if (rise < 0 && sunPosition(lat, lon, noon - m * 60_000L)[0] < -0.83) rise = noon - m * 60_000L;
        }
        return new long[]{rise, set};
    }
}
