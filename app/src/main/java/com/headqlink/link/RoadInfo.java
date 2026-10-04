package com.headqlink.link;

import android.content.Context;
import android.net.Uri;
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
 * - Radares fijos cercanos (highway=speed_camera) delante y en el sentido de la marcha.
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
    }

    private static RoadInfo instance;
    private final CarSensors sensors;
    private volatile boolean running;
    private volatile State state = new State();
    private final List<double[]> cameras = new ArrayList<>(); // lat, lon, límite
    private double camLat = Double.NaN;
    private double camLon = Double.NaN;
    private long camMs;
    private double limitLat = Double.NaN;
    private double limitLon = Double.NaN;
    private long limitMs;
    private int limit = -1;
    private boolean limitEst;
    private String road = "";

    private RoadInfo(Context ctx) {
        sensors = CarSensors.start(ctx);
    }

    static synchronized RoadInfo start(Context ctx) {
        if (instance == null) {
            instance = new RoadInfo(ctx);
            instance.running = true;
            new Thread(instance::loop, "road-info").start();
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
        return state;
    }

    private void loop() {
        while (running) {
            try {
                step();
            } catch (Exception e) {
                L.w("vía: " + e.getMessage());
            }
            SystemClock.sleep(3000);
        }
    }

    private void step() throws Exception {
        CarSensors.Snapshot s = sensors.snapshot();
        if (Double.isNaN(s.lat)) return;
        long now = SystemClock.elapsedRealtime();
        // Overpass pide moderación: como mucho una consulta cada 8 s y cada 250 m.
        if ((Double.isNaN(limitLat) || dist(s.lat, s.lon, limitLat, limitLon) > 250) && now - limitMs > 8000) {
            limitMs = now;
            limitLat = s.lat;
            limitLon = s.lon;
            updateLimit(s);
        }
        if ((Double.isNaN(camLat) || dist(s.lat, s.lon, camLat, camLon) > 8000) && now - camMs > 60_000) {
            camMs = now;
            camLat = s.lat;
            camLon = s.lon;
            loadCameras(s.lat, s.lon);
        }
        State st = new State();
        st.limitKmh = limit;
        st.limitEstimated = limitEst;
        st.roadName = road;
        // Radar más cercano delante (±25° del rumbo) a menos de 1,5 km.
        if (s.speedKmh > 5) {
            for (double[] c : cameras) {
                double d = dist(s.lat, s.lon, c[0], c[1]);
                if (d > 1500) continue;
                double brg = bearing(s.lat, s.lon, c[0], c[1]);
                double diff = Math.abs(((brg - s.headingDeg) + 540) % 360 - 180);
                if (diff < 25 && (st.cameraM < 0 || d < st.cameraM)) {
                    st.cameraM = d;
                    st.cameraLimit = (int) c[2];
                }
            }
        }
        double[] sun = sunPosition(s.lat, s.lon, System.currentTimeMillis());
        st.sunElevation = sun[0];
        double diff = Math.abs(((sun[1] - s.headingDeg) + 540) % 360 - 180);
        st.sunGlare = s.speedKmh > 10 && sun[0] > 0 && sun[0] < 20 && diff < 30;
        st.sunset = sunset(s.lat, s.lon);
        state = st;
    }

    private void updateLimit(CarSensors.Snapshot s) throws Exception {
        String q = String.format(Locale.US, "[out:json][timeout:10];way(around:25,%.6f,%.6f)[\"highway\"];out tags geom;", s.lat, s.lon);
        JSONArray els = new JSONObject(Http.post("https://overpass-api.de/api/interpreter", "data=" + Uri.encode(q)))
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
            return;
        }
        JSONObject tags = best.getJSONObject("tags");
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

    private void loadCameras(double lat, double lon) throws Exception {
        String q = String.format(Locale.US, "[out:json][timeout:20];node[\"highway\"=\"speed_camera\"](around:15000,%.5f,%.5f);out body;", lat, lon);
        JSONArray els = new JSONObject(Http.post("https://overpass-api.de/api/interpreter", "data=" + Uri.encode(q)))
                .getJSONArray("elements");
        List<double[]> list = new ArrayList<>();
        for (int i = 0; i < els.length(); i++) {
            JSONObject e = els.getJSONObject(i);
            JSONObject t = e.optJSONObject("tags");
            list.add(new double[]{e.getDouble("lat"), e.getDouble("lon"), t != null ? parseLimit(t.optString("maxspeed")) : -1});
        }
        synchronized (cameras) {
            cameras.clear();
            cameras.addAll(list);
        }
        L.i("vía: " + list.size() + " radares en 15 km");
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

    /** Hora local de la puesta de sol de hoy (búsqueda por minutos), o "" si no hay. */
    private static String sunset(double lat, double lon) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 12);
        c.set(Calendar.MINUTE, 0);
        long t = c.getTimeInMillis();
        for (int m = 0; m < 12 * 60; m += 2) {
            if (sunPosition(lat, lon, t + m * 60_000L)[0] < -0.83) {
                Calendar s = Calendar.getInstance(TimeZone.getDefault());
                s.setTimeInMillis(t + m * 60_000L);
                return String.format(Locale.getDefault(), "%02d:%02d", s.get(Calendar.HOUR_OF_DAY), s.get(Calendar.MINUTE));
            }
        }
        return "";
    }
}
