package com.headqlink.link;

import android.content.Context;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Puntos de recarga de España según la DGT (Punto de Acceso Nacional, nap.dgt.es: los datos que los operadores mandan
 * por ley al Ministerio para la Transición Ecológica; DATEX II v3, licencia CC BY 4.0): todos con su potencia por
 * conector y casi todos con su voltaje, lo que OpenStreetMap casi nunca tiene (en la zona de Madrid, potencia en el 12 % y
 * voltaje en el 1 %). Se descarga comprimido (~3 MB; ~80 MB de XML) como mucho una vez cada MAX_AGE_MS y se guarda en el
 * móvil en forma compacta (~1,5 MB). Fuera de España, o sin ellos, los cargadores siguen saliendo de OpenStreetMap.
 */
final class DgtChargers {
    static final String URL_XML = "https://infocar.dgt.es/datex2/v3/miterd/EnergyInfrastructureTablePublication/electrolineras.xml";
    static final long MAX_AGE_MS = 7L * 86_400_000L;
    private static final String FILE = "dgt-chargers.tsv";
    /** España (península, Baleares, Canarias, Ceuta y Melilla), por lo ancho. */
    static final double LAT_MIN = 27.5;
    static final double LAT_MAX = 44.0;
    static final double LON_MIN = -18.3;
    static final double LON_MAX = 4.5;

    /** Un punto de recarga (un sitio, con todos sus conectores resumidos). */
    static final class Site {
        String id = "";
        double lat;
        double lon;
        String name = "";
        String operator = "";
        /** Potencia máxima en continua y en alterna (kW) y voltaje máximo en continua (V); 0 si no hay o no se sabe. */
        double dcKw;
        double acKw;
        double dcVolts;
        /** Enchufes: ChargerFilter.SOCKET_DC | SOCKET_AC. */
        int sockets;
        boolean ccs;
        boolean chademo;
        boolean type2;
        /** Puntos de recarga (conectores que cargan a la vez). */
        int points;

        /** Como cargador de la ruta (sin el km). */
        RoutePlanner.Charger toCharger() {
            RoutePlanner.Charger c = new RoutePlanner.Charger();
            c.lat = lat;
            c.lon = lon;
            c.name = name.isEmpty() ? operator : name;
            // La red, por el operador oficial (el nombre engaña: hay «superchargers» que no son de Tesla).
            c.network = ChargerFilter.classify("", "", operator, "");
            boolean dc = (sockets & ChargerFilter.SOCKET_DC) != 0;
            c.acOnly = !dc;
            c.maxKw = dc ? dcKw : acKw;
            c.kwSource = c.maxKw > 0 ? ChargerFilter.KW_TAGGED : ChargerFilter.KW_UNKNOWN;
            // Sin voltaje declarado, el típico de su red (los Supercharger, 500 V).
            c.maxVolts = !dc ? 0 : dcVolts > 0 ? dcVolts : ChargerFilter.typicalVolts(c.network, c.name, ChargerFilter.SOCKET_DC);
            StringBuilder d = new StringBuilder();
            if (ccs) d.append("CCS");
            if (chademo) d.append(d.length() > 0 ? " · " : "").append("CHAdeMO");
            if (type2) d.append(d.length() > 0 ? " · " : "").append(Str.get(com.andrerinas.openheadunit.R.string.hql_type2));
            if (points > 1) d.append(d.length() > 0 ? " · " : "").append(Str.get(com.andrerinas.openheadunit.R.string.hql_charger_points, points));
            if (!operator.isEmpty() && !operator.equalsIgnoreCase(c.name)) d.append(d.length() > 0 ? " · " : "").append(operator);
            c.detail = d.toString();
            c.source = RoutePlanner.Charger.SOURCE_DGT;
            return c;
        }
    }

    private static List<Site> sites;
    private static long loadedAtMs;
    /** Índice por cuadrículas de ChargerCache. */
    private static Map<String, List<Site>> grid;
    private static volatile boolean downloading;
    private static boolean triedLoad;
    /** Sube cada vez que cambian los puntos (para rehacer la lista de cargadores de la ruta). */
    static volatile int version;

    private DgtChargers() {
    }

    /** ¿Pasa la ruta cerca de España (algún punto dentro del recuadro)? Entonces merecen la pena estos datos. */
    static boolean nearSpain(double[] lat, double[] lon) {
        for (int i = 0; i < lat.length; i++) {
            if (lat[i] >= LAT_MIN && lat[i] <= LAT_MAX && lon[i] >= LON_MIN && lon[i] <= LON_MAX) return true;
        }
        return false;
    }

    /** Los puntos guardados (cargándolos del móvil la primera vez); null si no hay. */
    static synchronized List<Site> sites(Context ctx) {
        if (sites != null || triedLoad) return sites;
        triedLoad = true;
        File f = new File(ctx.getFilesDir(), FILE);
        if (!f.exists()) return null;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String head = r.readLine();
            long at = head == null ? 0 : Long.parseLong(head.trim());
            List<Site> out = new ArrayList<>();
            String line;
            while ((line = r.readLine()) != null) {
                Site s = fromLine(line);
                if (s != null) out.add(s);
            }
            setSites(out, at);
            L.i("cargadores DGT: " + out.size() + " puntos del móvil (de hace " + (System.currentTimeMillis() - at) / 86_400_000L + " días)");
            return sites;
        } catch (IOException | RuntimeException e) {
            L.w("cargadores DGT: no se pudieron leer los guardados (" + e.getClass().getSimpleName() + ")");
            return null;
        }
    }

    /** Tras un fallo, no se vuelve a intentar hasta pasado esto. */
    static final long RETRY_MS = 30L * 60_000L;
    private static volatile long lastTryMs;

    /** ¿Hay que descargarlos (no hay, o tienen más de MAX_AGE_MS; y no falló hace poco)? */
    static boolean stale(Context ctx) {
        List<Site> s = sites(ctx);
        long now = System.currentTimeMillis();
        if (lastTryMs > 0 && now - lastTryMs < RETRY_MS) return false;
        synchronized (DgtChargers.class) {
            return s == null || now - loadedAtMs > MAX_AGE_MS;
        }
    }

    /** Si no hay puntos o son viejos, los descarga en otro hilo (al terminar sube version). */
    static void refreshIfStale(Context ctx) {
        if (downloading || !stale(ctx)) return;
        Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        Thread t = new Thread(() -> refresh(app), "hql-dgt");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /** Descarga, lee y guarda (llamar fuera del hilo principal). true si quedaron al día. */
    static boolean refresh(Context ctx) {
        synchronized (DgtChargers.class) {
            if (downloading) return false;
            downloading = true;
        }
        long t0 = System.currentTimeMillis();
        try {
            HttpURLConnection con = (HttpURLConnection) new URL(URL_XML).openConnection();
            con.setConnectTimeout(15_000);
            con.setReadTimeout(90_000);
            con.setRequestProperty("User-Agent", Http.USER_AGENT);
            int code = con.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            List<Site> out;
            try (InputStream in = con.getInputStream()) {
                out = parse(in);
            }
            if (out.size() < 1000) throw new IOException("solo " + out.size() + " puntos");
            long now = System.currentTimeMillis();
            lastTryMs = 0;
            save(ctx, out, now);
            synchronized (DgtChargers.class) {
                setSites(out, now);
            }
            L.i(String.format(Locale.US, "cargadores DGT: %d puntos al día en %.1f s", out.size(), (now - t0) / 1000.0));
            return true;
        } catch (Exception e) {
            L.w("cargadores DGT: no se pudieron descargar (" + Http.safeError(e) + ")");
            lastTryMs = System.currentTimeMillis();
            return false;
        } finally {
            downloading = false;
        }
    }

    private static void setSites(List<Site> out, long at) {
        sites = out;
        loadedAtMs = at;
        Map<String, List<Site>> g = new HashMap<>();
        for (Site s : out) {
            String k = ChargerCache.tileOf(s.lat, s.lon);
            List<Site> l = g.get(k);
            if (l == null) g.put(k, l = new ArrayList<>());
            l.add(s);
        }
        grid = g;
        version++;
    }

    /**
     * Los puntos de una cuadrícula (ChargerCache.tileOf), o null si la DGT no tiene ninguno en ella: fuera de España (o
     * en una cuadrícula española sin cargadores), donde siguen valiendo los de OpenStreetMap.
     */
    static synchronized List<Site> inTile(String tile) {
        return grid == null ? null : grid.get(tile);
    }

    /** Para las pruebas: unos puntos como si fueran los descargados. */
    static synchronized void setForTest(List<Site> out) {
        triedLoad = true;
        setSites(out, System.currentTimeMillis());
    }

    // ------------------------------------------------------------------ DATEX II (sin red: se prueba en el PC)

    /**
     * Lee la publicación de la DGT: por cada energyInfrastructureSite, su nombre, coordenadas, operador y, de sus
     * conectores, la potencia (W) y el voltaje en continua (mode4DC) y en alterna, y los tipos de enchufe.
     */
    static List<Site> parse(InputStream in) throws Exception {
        XmlPullParser xp = XmlPullParserFactory.newInstance().newPullParser();
        xp.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        xp.setInput(in, "UTF-8");
        List<Site> out = new ArrayList<>();
        Site cur = null;
        // Contexto: dentro del nombre del sitio (no del de un punto), del operador, de un conector.
        int depth = 0;
        int siteDepth = -1;
        int siteNameDepth = -1;
        int operatorDepth = -1;
        boolean inPoint = false;
        boolean inConnector = false;
        String connType = "";
        String connMode = "";
        double connW = 0;
        double connV = 0;
        String text = null;
        for (int ev = xp.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = xp.next()) {
            if (ev == XmlPullParser.START_TAG) {
                depth++;
                String n = xp.getName();
                text = null;
                if (n.endsWith("energyInfrastructureSite")) {
                    cur = new Site();
                    String id = xp.getAttributeValue(null, "id");
                    cur.id = id == null ? "" : id;
                    siteDepth = depth;
                } else if (cur != null) {
                    if (n.endsWith(":name") && depth == siteDepth + 1) siteNameDepth = depth;
                    else if (n.endsWith(":operator")) operatorDepth = depth;
                    else if (n.endsWith("refillPoint")) {
                        inPoint = true;
                        cur.points++;
                    } else if (n.endsWith(":connector") && inPoint) {
                        inConnector = true;
                        connType = "";
                        connMode = "";
                        connW = 0;
                        connV = 0;
                    }
                }
            } else if (ev == XmlPullParser.TEXT) {
                text = xp.getText();
            } else if (ev == XmlPullParser.END_TAG) {
                String n = xp.getName();
                String t = text == null ? "" : text.trim();
                if (cur != null) {
                    if (n.endsWith(":value") && siteNameDepth > 0 && cur.name.isEmpty()) cur.name = t;
                    else if (n.endsWith(":value") && operatorDepth > 0 && cur.operator.isEmpty()) cur.operator = t;
                    else if (n.endsWith(":latitude")) cur.lat = num(t);
                    else if (n.endsWith(":longitude")) cur.lon = num(t);
                    else if (inConnector && n.endsWith(":connectorType")) connType = t;
                    else if (inConnector && n.endsWith(":chargingMode")) connMode = t;
                    else if (inConnector && n.endsWith(":maxPowerAtSocket")) connW = num(t);
                    else if (inConnector && n.endsWith(":voltage")) connV = num(t);
                    if (depth == siteNameDepth && n.endsWith(":name")) siteNameDepth = -1;
                    if (depth == operatorDepth && n.endsWith(":operator")) operatorDepth = -1;
                    if (inConnector && n.endsWith(":connector")) {
                        inConnector = false;
                        boolean dc = connMode.toUpperCase(Locale.ROOT).contains("DC") || connType.toLowerCase(Locale.ROOT).contains("combo")
                                || connType.toLowerCase(Locale.ROOT).contains("chademo");
                        double kw = connW / 1000.0;
                        if (kw > 2000) kw = 0; // datos imposibles
                        if (dc) {
                            cur.sockets |= ChargerFilter.SOCKET_DC;
                            cur.dcKw = Math.max(cur.dcKw, kw);
                            if (connV > 0 && connV <= 1500) cur.dcVolts = Math.max(cur.dcVolts, connV);
                        } else {
                            cur.sockets |= ChargerFilter.SOCKET_AC;
                            cur.acKw = Math.max(cur.acKw, kw);
                        }
                        String ty = connType.toLowerCase(Locale.ROOT);
                        if (ty.contains("combo")) cur.ccs = true;
                        else if (ty.contains("chademo")) cur.chademo = true;
                        else if (ty.contains("t2") || ty.contains("type2")) cur.type2 = true;
                    }
                    if (n.endsWith("refillPoint")) inPoint = false;
                    if (depth == siteDepth && n.endsWith("energyInfrastructureSite")) {
                        if (!Double.isNaN(cur.lat) && !Double.isNaN(cur.lon) && (cur.lat != 0 || cur.lon != 0)) out.add(cur);
                        cur = null;
                        siteDepth = -1;
                    }
                }
                text = null;
                depth--;
            }
        }
        return out;
    }

    private static double num(String s) {
        try {
            return Double.parseDouble(s.replace(',', '.'));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    // ------------------------------------------------------------------ guardado compacto (una línea por punto)

    static String toLine(Site s) {
        return String.format(Locale.US, "%s\t%.6f\t%.6f\t%s\t%s\t%.1f\t%.1f\t%.0f\t%d\t%d\t%d", clean(s.id), s.lat, s.lon, clean(s.name),
                clean(s.operator), s.dcKw, s.acKw, s.dcVolts, s.sockets, (s.ccs ? 1 : 0) | (s.chademo ? 2 : 0) | (s.type2 ? 4 : 0), s.points);
    }

    static Site fromLine(String line) {
        String[] p = line.split("\t", -1);
        if (p.length < 11) return null;
        try {
            Site s = new Site();
            s.id = p[0];
            s.lat = Double.parseDouble(p[1]);
            s.lon = Double.parseDouble(p[2]);
            s.name = p[3];
            s.operator = p[4];
            s.dcKw = Double.parseDouble(p[5]);
            s.acKw = Double.parseDouble(p[6]);
            s.dcVolts = Double.parseDouble(p[7]);
            s.sockets = Integer.parseInt(p[8]);
            int k = Integer.parseInt(p[9]);
            s.ccs = (k & 1) != 0;
            s.chademo = (k & 2) != 0;
            s.type2 = (k & 4) != 0;
            s.points = Integer.parseInt(p[10]);
            return s;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static void save(Context ctx, List<Site> out, long at) throws IOException {
        File f = new File(ctx.getFilesDir(), FILE);
        File tmp = new File(ctx.getFilesDir(), FILE + ".tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            w.write(Long.toString(at));
            w.write('\n');
            for (Site s : out) {
                w.write(toLine(s));
                w.write('\n');
            }
        }
        if (!tmp.renameTo(f)) throw new IOException("no se pudo guardar");
    }
}
