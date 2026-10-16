package com.headqlink.link;

import com.andrerinas.openheadunit.R;

import android.content.Context;
import android.os.SystemClock;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Incidencias de tráfico de la DGT en la ruta: el Punto de Acceso Nacional (nap.dgt.es) publica en abierto, en DATEX II
 * 3.7, las situaciones activas en las carreteras de España (obras, carriles y calzadas cortados, retenciones, accidentes,
 * vehículos detenidos —las balizas V16 conectadas—, obstáculos, desprendimientos, incendios, limitaciones de velocidad…),
 * con su carretera, punto kilométrico, coordenadas y sentido. Cataluña y el País Vasco publican las suyas aparte.
 * <p>
 * Con una ruta planificada se descargan cada REFRESH_MS, se quedan las que caen sobre el trazado (a menos de ON_ROUTE_M y
 * en nuestro sentido si la DGT lo dice) y, de las que quedan por delante, la pestaña Ruta enseña la lista, Conducción la
 * próxima, y las importantes avisan (tarjeta y voz) a ALERT_KM.
 */
final class DgtTraffic {
    static final String URL_XML = "https://nap.dgt.es/datex2/v3/dgt/SituationPublication/datex2_v37.xml";
    static final long REFRESH_MS = 5 * 60_000L;
    static final long RETRY_MS = 2 * 60_000L;
    /** Distancia máxima al trazado para que una incidencia sea de la ruta (las coordenadas de la DGT van al eje). */
    static final double ON_ROUTE_M = 120;
    /** Las importantes avisan con tarjeta y voz a esta distancia (y una sola vez cada una). */
    static final double ALERT_KM = 2.5;

    /** Tipos, de más a menos importante (con varios en una situación, manda el primero). */
    enum Kind { ACCIDENT, VEHICLE_STOPPED, OBJECT, ROCKFALL, FIRE, FLOOD, HAZARD, ROAD_CLOSED, CONGESTION, ROADWORKS, LANE_CLOSED, SPEED, SURFACE, OTHER }

    static final class Incident {
        String id = "";
        Kind kind = Kind.OTHER;
        String road = "";
        double km = Double.NaN;
        /** Dónde empieza y acaba (o el punto, dos veces). */
        double lat1 = Double.NaN;
        double lon1 = Double.NaN;
        double lat2 = Double.NaN;
        double lon2 = Double.NaN;
        /** Sentido afectado, en grados (0 norte, 90 este), o NaN si la DGT no lo dice. */
        double heading = Double.NaN;
        boolean laneClosed;
        boolean carriagewayClosed;

        /** Las que merecen aviso con tarjeta y voz. */
        boolean serious() {
            switch (kind) {
                case ACCIDENT:
                case VEHICLE_STOPPED:
                case OBJECT:
                case ROCKFALL:
                case FIRE:
                case FLOOD:
                case HAZARD:
                case ROAD_CLOSED:
                case CONGESTION:
                    return true;
                default:
                    return false;
            }
        }
    }

    /** Una incidencia de la ruta y su km por la ruta. */
    static final class OnRoute {
        final Incident inc;
        final double kmAlong;

        OnRoute(Incident inc, double kmAlong) {
            this.inc = inc;
            this.kmAlong = kmAlong;
        }
    }

    /** Una que queda por delante y a cuántos km. */
    static final class Ahead {
        final Incident inc;
        final double km;

        Ahead(Incident inc, double km) {
            this.inc = inc;
            this.km = km;
        }
    }

    private DgtTraffic() {
    }

    // ---------------------------------------------------------------- lectura (pura)

    /** Puro: el sentido de la DGT (tpegDirection: «eastBound»…) en grados, o NaN. */
    static double heading(String tpeg) {
        if (tpeg == null) return Double.NaN;
        switch (tpeg) {
            case "northBound":
                return 0;
            case "northEastBound":
                return 45;
            case "eastBound":
                return 90;
            case "southEastBound":
                return 135;
            case "southBound":
                return 180;
            case "southWestBound":
                return 225;
            case "westBound":
                return 270;
            case "northWestBound":
                return 315;
            default:
                return Double.NaN;
        }
    }

    /** Puro: el tipo de una situación por sus causas y detalles (con varios, el más importante). */
    static Kind kind(Set<String> d) {
        if (d.contains("accident")) return Kind.ACCIDENT;
        if (d.contains("vehicleObstruction") || d.contains("vehicleStuck") || d.contains("brokenDownVehicle") || d.contains("vehicleOnFire")
                || d.contains("abandonedVehicle")) return Kind.VEHICLE_STOPPED;
        if (d.contains("objectOnTheRoad") || d.contains("obstructionOnTheRoad") || d.contains("animalsOnTheRoad")
                || d.contains("spillageOnTheRoad") || d.contains("fallenTrees")) return Kind.OBJECT;
        if (d.contains("rockfalls") || d.contains("landslips") || d.contains("mudslide")) return Kind.ROCKFALL;
        if (d.contains("forestFire") || d.contains("fire") || d.contains("grassFire") || d.contains("smokeHazard")) return Kind.FIRE;
        if (d.contains("flooding") || d.contains("flashFloods")) return Kind.FLOOD;
        if (d.contains("environmentalObstruction") || d.contains("poorEnvironment") || d.contains("infrastructureDamageObstruction")) {
            return Kind.HAZARD;
        }
        if (d.contains("roadClosed")) return Kind.ROAD_CLOSED;
        if (d.contains("slowTraffic") || d.contains("stationaryTraffic") || d.contains("queuingTraffic") || d.contains("heavyTraffic")
                || d.contains("abnormalTraffic")) return Kind.CONGESTION;
        if (d.contains("roadworks") || d.contains("roadMaintenance") || d.contains("newRoadworksLayout")) return Kind.ROADWORKS;
        if (laneClosed(d) || d.contains("carriagewayClosures")) return Kind.LANE_CLOSED;
        if (d.contains("speedRestrictionInOperation") || d.contains("speedManagement")) return Kind.SPEED;
        if (d.contains("roadSurfaceInPoorCondition") || d.contains("nonWeatherRelatedRoadConditions")) return Kind.SURFACE;
        return Kind.OTHER;
    }

    static boolean laneClosed(Set<String> d) {
        return d.contains("laneClosures") || d.contains("singleAlternateLineTraffic") || d.contains("narrowLanes")
                || d.contains("lanesDeviated") || d.contains("doNotUseSpecifiedLanesOrCarriageways") || d.contains("intermittentShortTermClosures");
    }

    /** Lee la publicación: por cada situación activa, su tipo, carretera, km, coordenadas (la primera y la última) y sentido. */
    static List<Incident> parse(InputStream in) throws Exception {
        XmlPullParser xp = XmlPullParserFactory.newInstance().newPullParser();
        xp.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        xp.setInput(in, "UTF-8");
        List<Incident> out = new ArrayList<>();
        Incident cur = null;
        Set<String> details = new HashSet<>();
        boolean active = true;
        double lat = Double.NaN;
        String text = null;
        for (int ev = xp.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = xp.next()) {
            if (ev == XmlPullParser.START_TAG) {
                String n = xp.getName();
                text = null;
                if (n.endsWith(":situation")) {
                    cur = new Incident();
                    String id = xp.getAttributeValue(null, "id");
                    cur.id = id == null ? "" : id;
                    details.clear();
                    active = true;
                } else if (cur != null && n.endsWith(":situationRecord")) {
                    String t = xp.getAttributeValue(null, "xsi:type");
                    if (t != null && t.indexOf(':') >= 0) details.add(Character.toLowerCase(t.charAt(t.indexOf(':') + 1)) + t.substring(t.indexOf(':') + 2));
                }
            } else if (ev == XmlPullParser.TEXT) {
                text = xp.getText();
            } else if (ev == XmlPullParser.END_TAG && cur != null) {
                String n = xp.getName();
                String t = text == null ? "" : text.trim();
                text = null;
                int c = n.indexOf(':');
                String local = c >= 0 ? n.substring(c + 1) : n;
                switch (local) {
                    case "situation":
                        if (active && !Double.isNaN(cur.lat1) && !Double.isNaN(cur.lon1)) {
                            cur.kind = kind(details);
                            cur.laneClosed = laneClosed(details);
                            cur.carriagewayClosed = details.contains("carriagewayClosures");
                            if (Double.isNaN(cur.lat2)) {
                                cur.lat2 = cur.lat1;
                                cur.lon2 = cur.lon1;
                            }
                            out.add(cur);
                        }
                        cur = null;
                        break;
                    case "validityStatus":
                        if (t.equals("suspended") || t.equals("inactive")) active = false;
                        break;
                    case "causeType":
                    case "roadMaintenanceType":
                    case "vehicleObstructionType":
                    case "obstructionType":
                    case "accidentType":
                    case "environmentalObstructionType":
                    case "abnormalTrafficType":
                    case "roadOrCarriagewayOrLaneManagementType":
                    case "poorEnvironmentType":
                    case "nonWeatherRelatedRoadConditionType":
                    case "speedManagementType":
                    case "infrastructureDamageType":
                        if (!t.isEmpty()) details.add(t);
                        break;
                    case "roadName":
                        if (cur.road.isEmpty()) cur.road = t;
                        break;
                    case "kilometerPoint":
                        if (Double.isNaN(cur.km)) cur.km = num(t);
                        break;
                    case "tpegDirection":
                        if (Double.isNaN(cur.heading)) cur.heading = heading(t);
                        break;
                    case "latitude":
                        lat = num(t);
                        break;
                    case "longitude":
                        double lon = num(t);
                        if (!Double.isNaN(lat) && !Double.isNaN(lon)) {
                            if (Double.isNaN(cur.lat1)) {
                                cur.lat1 = lat;
                                cur.lon1 = lon;
                            } else {
                                cur.lat2 = lat;
                                cur.lon2 = lon;
                            }
                        }
                        lat = Double.NaN;
                        break;
                    default:
                        break;
                }
            }
        }
        return out;
    }

    private static double num(String s) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    // ---------------------------------------------------------------- sobre la ruta (puro)

    /**
     * Puro: las incidencias que caen sobre el trazado (lat, lon, km de sus n puntos): a menos de ON_ROUTE_M de algún tramo,
     * y en nuestro sentido si la DGT lo dice (la de la calzada de enfrente de una autovía, no). Ordenadas por su km.
     */
    static List<OnRoute> onRoute(List<Incident> all, double[] lat, double[] lon, double[] km, int n) {
        List<OnRoute> out = new ArrayList<>();
        if (all == null || n < 2) return out;
        double minLat = 90, maxLat = -90, minLon = 180, maxLon = -180;
        for (int i = 0; i < n; i++) {
            minLat = Math.min(minLat, lat[i]);
            maxLat = Math.max(maxLat, lat[i]);
            minLon = Math.min(minLon, lon[i]);
            maxLon = Math.max(maxLon, lon[i]);
        }
        double pad = 0.01;
        for (Incident inc : all) {
            double best = Double.MAX_VALUE;
            double bestKm = Double.NaN;
            double bestBearing = Double.NaN;
            for (int k = 0; k < 2; k++) {
                double plat = k == 0 ? inc.lat1 : inc.lat2;
                double plon = k == 0 ? inc.lon1 : inc.lon2;
                if (plat < minLat - pad || plat > maxLat + pad || plon < minLon - pad || plon > maxLon + pad) continue;
                double coslat = Math.cos(Math.toRadians(plat));
                for (int i = 1; i < n; i++) {
                    // Plano local en metros alrededor del punto.
                    double ax = (lon[i - 1] - plon) * coslat * 111_320;
                    double ay = (lat[i - 1] - plat) * 110_540;
                    double bx = (lon[i] - plon) * coslat * 111_320;
                    double by = (lat[i] - plat) * 110_540;
                    double dx = bx - ax;
                    double dy = by - ay;
                    double len2 = dx * dx + dy * dy;
                    double tt = len2 <= 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / len2));
                    double px = ax + tt * dx;
                    double py = ay + tt * dy;
                    double d = Math.sqrt(px * px + py * py);
                    if (d < best) {
                        best = d;
                        bestKm = km[i - 1] + tt * (km[i] - km[i - 1]);
                        bestBearing = Math.toDegrees(Math.atan2(dx, dy));
                    }
                }
                if (k == 0 && inc.lat2 == inc.lat1 && inc.lon2 == inc.lon1) break;
            }
            if (best > ON_ROUTE_M) continue;
            if (!Double.isNaN(inc.heading) && !Double.isNaN(bestBearing)) {
                double diff = Math.abs(((bestBearing - inc.heading) % 360 + 540) % 360 - 180);
                if (diff > 100) continue;
            }
            out.add(new OnRoute(inc, bestKm));
        }
        Collections.sort(out, (a, b) -> Double.compare(a.kmAlong, b.kmAlong));
        return out;
    }

    /** Puro: de las de la ruta, las que quedan por delante de hereKm (con las de los últimos 200 m: aún se ven), a cuántos km. */
    static List<Ahead> ahead(List<OnRoute> onRoute, double hereKm) {
        List<Ahead> out = new ArrayList<>();
        if (onRoute == null) return out;
        for (OnRoute r : onRoute) {
            double d = r.kmAlong - hereKm;
            if (d >= -0.2) out.add(new Ahead(r.inc, Math.max(0, d)));
        }
        return out;
    }

    // ---------------------------------------------------------------- textos

    static String label(Kind k) {
        switch (k) {
            case ACCIDENT:
                return Str.get(R.string.hql_tr_accident);
            case VEHICLE_STOPPED:
                return Str.get(R.string.hql_tr_vehicle);
            case OBJECT:
                return Str.get(R.string.hql_tr_object);
            case ROCKFALL:
                return Str.get(R.string.hql_tr_rockfall);
            case FIRE:
                return Str.get(R.string.hql_tr_fire);
            case FLOOD:
                return Str.get(R.string.hql_tr_flood);
            case HAZARD:
                return Str.get(R.string.hql_tr_hazard);
            case ROAD_CLOSED:
                return Str.get(R.string.hql_tr_closed);
            case CONGESTION:
                return Str.get(R.string.hql_tr_congestion);
            case ROADWORKS:
                return Str.get(R.string.hql_tr_roadworks);
            case LANE_CLOSED:
                return Str.get(R.string.hql_tr_lane_closed);
            case SPEED:
                return Str.get(R.string.hql_tr_speed);
            case SURFACE:
                return Str.get(R.string.hql_tr_surface);
            default:
                return Str.get(R.string.hql_tr_other);
        }
    }

    /** «Obras · carril cortado» (el tipo y, si no lo dice ya, si corta carril o calzada). */
    static String title(Incident i) {
        String t = label(i.kind);
        if (i.carriagewayClosed && i.kind != Kind.ROAD_CLOSED) t += " · " + Str.get(R.string.hql_tr_carriageway);
        else if (i.laneClosed && i.kind != Kind.LANE_CLOSED) t += " · " + Str.get(R.string.hql_tr_lane);
        return t;
    }

    /** «A-4 km 243» (lo que se sepa). */
    static String where(Incident i) {
        String r = i.road == null ? "" : i.road;
        if (!Double.isNaN(i.km)) r = (r.isEmpty() ? "" : r + " ") + String.format(Locale.getDefault(), "km %.0f", i.km);
        return r;
    }

    /** Color: rojo lo que para o corta, ámbar el resto de lo importante, gris lo demás. */
    static int color(Incident i) {
        switch (i.kind) {
            case ACCIDENT:
            case ROAD_CLOSED:
            case VEHICLE_STOPPED:
            case OBJECT:
            case ROCKFALL:
            case FIRE:
            case FLOOD:
                return CarKit.RED;
            case CONGESTION:
            case HAZARD:
            case LANE_CLOSED:
            case ROADWORKS:
                return CarKit.AMBER;
            default:
                return CarKit.DIM;
        }
    }

    static String kmText(double km) {
        return km < 10 ? String.format(Locale.getDefault(), "%.1f", km) : String.format(Locale.getDefault(), "%.0f", km);
    }

    // ---------------------------------------------------------------- estado (descarga y la ruta en curso)

    private static volatile List<Incident> incidents;
    private static volatile long loadedAtMs;
    private static volatile long lastTryMs;
    private static volatile boolean downloading;
    private static volatile int version;
    /** La ruta para la que se calcularon y su resultado. */
    private static Object routeFor;
    private static int routeVersion = -1;
    private static volatile List<OnRoute> onRoute = new ArrayList<>();
    private static volatile List<Ahead> ahead = new ArrayList<>();
    private static final Set<String> announced = new HashSet<>();

    /** Las que quedan por delante en la ruta actual (de la más cercana a la más lejana). */
    static List<Ahead> ahead() {
        return ahead;
    }

    /**
     * Desde RoutePlanner, cada pocos segundos: con una ruta, se descargan si hace falta (en otro hilo), se sitúan sobre
     * ella y, de las que quedan por delante, las importantes a ALERT_KM avisan (una vez cada una). Sin ruta, nada.
     */
    static void update(Context ctx, RoutePlanner.Plan plan, double hereKm, Alert alert) {
        if (!new Config(ctx).dgtTraffic() || plan == null || plan.n < 2) {
            ahead = new ArrayList<>();
            return;
        }
        long now = System.currentTimeMillis();
        if (!downloading && now - loadedAtMs > REFRESH_MS && now - lastTryMs > RETRY_MS) fetch(ctx);
        List<Incident> all = incidents;
        if (all == null) return;
        if (plan != routeFor || version != routeVersion) {
            routeFor = plan;
            routeVersion = version;
            onRoute = onRoute(all, plan.lat, plan.lon, plan.km, plan.n);
            int serious = 0;
            for (OnRoute r : onRoute) if (r.inc.serious()) serious++;
            L.i("tráfico DGT: " + onRoute.size() + " incidencias en la ruta (" + serious + " importantes)"); // sin sitios
        }
        List<Ahead> a = ahead(onRoute, hereKm);
        ahead = a;
        for (Ahead x : a) {
            if (!x.inc.serious() || x.km > ALERT_KM) continue;
            if (!announced.add(x.inc.id)) continue;
            String km = kmText(x.km);
            String title = Str.get(R.string.hql_tr_title);
            String text = title(x.inc) + " " + Str.get(R.string.hql_tr_at, km) + (where(x.inc).isEmpty() ? "" : " · " + where(x.inc));
            L.i("tráfico DGT: aviso de " + x.inc.kind.name().toLowerCase(Locale.ROOT) + " a " + km + " km");
            if (alert != null) alert.onTraffic(title, text, Str.get(R.string.hql_tr_voice, label(x.inc.kind), km));
        }
    }

    /** Quién avisa (RoutePlanner: tarjeta y, si está activada, la voz). */
    interface Alert {
        void onTraffic(String title, String text, String voice);
    }

    private static void fetch(Context ctx) {
        downloading = true;
        Thread t = new Thread(() -> {
            long t0 = SystemClock.elapsedRealtime();
            try {
                HttpURLConnection con = (HttpURLConnection) new URL(URL_XML).openConnection();
                con.setConnectTimeout(15_000);
                con.setReadTimeout(60_000);
                con.setRequestProperty("User-Agent", Http.USER_AGENT);
                int code = con.getResponseCode();
                if (code != 200) throw new IOException("HTTP " + code);
                List<Incident> out;
                try (InputStream in = new java.io.BufferedInputStream(con.getInputStream())) {
                    out = parse(in);
                }
                incidents = out;
                loadedAtMs = System.currentTimeMillis();
                version++;
                L.i(String.format(Locale.US, "tráfico DGT: %d incidencias activas en %.1f s", out.size(),
                        (SystemClock.elapsedRealtime() - t0) / 1000.0));
            } catch (Exception e) {
                L.w("tráfico DGT: no se pudo descargar (" + Http.safeError(e) + ")");
            } finally {
                lastTryMs = System.currentTimeMillis();
                downloading = false;
            }
        }, "hql-dgt-trafico");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /** Para las pruebas y la demostración: unas incidencias como si fueran las descargadas. */
    static void setForTest(List<Incident> all) {
        incidents = all;
        loadedAtMs = System.currentTimeMillis();
        version++;
    }
}
