package com.headqlink.link;

import android.content.Context;

import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.zip.GZIPInputStream;

/**
 * Guía de programación de la TV (XMLTV, la de TDTChannels: epg/TV.xml.gz): «qué ponen ahora» en cada canal. La lista dice
 * dónde está su guía (url-tvg en la cabecera de la M3U, «epg» en la JSON) y cada canal su id (tvg-id, epg_id). Se baja
 * como mucho cada 6 horas (se guarda en la caché del móvil) y de ella solo se queda lo que va de una hora antes a las 18
 * siguientes.
 */
final class Epg {
    static final class Prog {
        final long start;
        final long stop;
        final String title;

        Prog(long start, long stop, String title) {
            this.start = start;
            this.stop = stop;
            this.title = title;
        }
    }

    private static final long REFRESH_MS = 6 * 3600_000L;
    private static final long KEEP_BEFORE_MS = 3600_000L;
    private static final long KEEP_AFTER_MS = 18 * 3600_000L;
    /** Si el rato que se guardó se acaba, se vuelve a leer el fichero. */
    private static final long REPARSE_MS = 3 * 3600_000L;
    private static final int MAX_BYTES = 40 << 20;
    private static final int MAX_PROGS = 60_000;

    /** id del canal → sus programas, por hora de empiece. */
    private final Map<String, List<Prog>> byId = new HashMap<>();
    /** Nombre normalizado (del id o del display-name) → id, para los canales sin id en la lista. */
    private final Map<String, String> idByName = new HashMap<>();
    final long parsedAt;

    private Epg(long parsedAt) {
        this.parsedAt = parsedAt;
    }

    int channels() {
        return byId.size();
    }

    /** Lo que se emite a las nowMs en el canal (por su id o, si no lo trae, por su nombre); null si no se sabe. */
    Prog now(String id, String name, long nowMs) {
        List<Prog> l = list(id, name);
        if (l == null) return null;
        for (Prog p : l) {
            if (p.start > nowMs) break;
            if (nowMs < p.stop) return p;
        }
        return null;
    }

    /** El siguiente programa después del de ahora; null si no hay. */
    Prog next(String id, String name, long nowMs) {
        List<Prog> l = list(id, name);
        if (l == null) return null;
        for (Prog p : l) if (p.start > nowMs) return p;
        return null;
    }

    private List<Prog> list(String id, String name) {
        List<Prog> l = id == null || id.isEmpty() ? null : byId.get(id);
        if (l == null && id != null && !id.isEmpty()) {
            String k = idByName.get(norm(id));
            if (k != null) l = byId.get(k);
        }
        if (l == null && name != null) {
            String k = idByName.get(norm(name));
            if (k != null) l = byId.get(k);
        }
        return l;
    }

    /** Puro: «La1.TV», «La 1» y «LA 1 HD» dan lo mismo (sin acentos, mayúsculas, espacios ni «.TV»/«HD» al final). */
    static String norm(String s) {
        String t = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).trim();
        t = t.replaceFirst("\\.tv$", "").replaceFirst("\\s+(hd|tv)$", "");
        return t.replaceAll("[^a-z0-9]", "");
    }

    /** Puro: hora XMLTV («20261013140000 +0200»; sin zona, UTC) → ms; -1 si no se entiende. */
    static long parseTime(String s) {
        if (s == null) return -1;
        s = s.trim();
        if (s.length() < 12) return -1;
        try {
            Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT);
            cal.clear();
            int sec = s.length() >= 14 && Character.isDigit(s.charAt(13)) ? Integer.parseInt(s.substring(12, 14)) : 0;
            cal.set(Integer.parseInt(s.substring(0, 4)), Integer.parseInt(s.substring(4, 6)) - 1,
                    Integer.parseInt(s.substring(6, 8)), Integer.parseInt(s.substring(8, 10)),
                    Integer.parseInt(s.substring(10, 12)), sec);
            long ms = cal.getTimeInMillis();
            int sp = s.indexOf(' ');
            if (sp > 0) {
                String z = s.substring(sp + 1).trim();
                if (z.length() == 5 && (z.charAt(0) == '+' || z.charAt(0) == '-')) {
                    int off = Integer.parseInt(z.substring(1, 3)) * 60 + Integer.parseInt(z.substring(3, 5));
                    ms -= (z.charAt(0) == '-' ? -off : off) * 60_000L;
                }
            }
            return ms;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** XMLTV (gzip o no) → guía, solo con los programas que tocan [nowMs − 1 h, nowMs + 18 h]. */
    static Epg parse(InputStream raw, long nowMs) throws Exception {
        InputStream in = new BufferedInputStream(raw);
        in.mark(2);
        int b0 = in.read();
        int b1 = in.read();
        in.reset();
        if (b0 == 0x1f && b1 == 0x8b) in = new GZIPInputStream(in);
        Epg e = new Epg(nowMs);
        long from = nowMs - KEEP_BEFORE_MS;
        long to = nowMs + KEEP_AFTER_MS;
        XmlPullParser p = android.util.Xml.newPullParser();
        p.setInput(in, null);
        String chan = null;
        String progChan = null;
        long start = 0;
        long stop = 0;
        String title = null;
        int total = 0;
        for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
            if (ev == XmlPullParser.START_TAG) {
                String tag = p.getName();
                if ("channel".equals(tag)) {
                    chan = p.getAttributeValue(null, "id");
                    if (chan != null) e.idByName.put(norm(chan), chan);
                } else if ("display-name".equals(tag) && chan != null) {
                    String n = p.nextText();
                    if (n != null && !n.trim().isEmpty()) e.idByName.putIfAbsent(norm(n), chan);
                } else if ("programme".equals(tag)) {
                    progChan = p.getAttributeValue(null, "channel");
                    start = parseTime(p.getAttributeValue(null, "start"));
                    stop = parseTime(p.getAttributeValue(null, "stop"));
                    title = null;
                } else if ("title".equals(tag) && progChan != null && title == null) {
                    title = p.nextText();
                }
            } else if (ev == XmlPullParser.END_TAG) {
                String tag = p.getName();
                if ("channel".equals(tag)) {
                    chan = null;
                } else if ("programme".equals(tag)) {
                    if (progChan != null && title != null && start > 0 && stop > start && stop > from && start < to
                            && total < MAX_PROGS) {
                        List<Prog> l = e.byId.get(progChan);
                        if (l == null) e.byId.put(progChan, l = new ArrayList<>());
                        l.add(new Prog(start, stop, title.trim()));
                        total++;
                    }
                    progChan = null;
                }
            }
        }
        for (List<Prog> l : e.byId.values()) l.sort((a, b) -> Long.compare(a.start, b.start));
        return e;
    }

    // ---- Caché: la guía de cada URL en memoria y en un fichero ----

    private static String memUrl;
    private static Epg mem;

    /** La guía de esa URL (en segundo plano: puede bajarla); null si no hay manera. */
    static Epg load(Context c, String url) {
        if (url == null || !url.startsWith("http")) return null;
        long now = System.currentTimeMillis();
        synchronized (Epg.class) {
            if (url.equals(memUrl) && mem != null && now - mem.parsedAt < REPARSE_MS) return mem;
        }
        File dir = new File(c.getCacheDir(), "epg");
        File f = new File(dir, Integer.toHexString(url.hashCode()) + ".xml");
        if (!f.exists() || now - f.lastModified() > REFRESH_MS) {
            try {
                download(url, dir, f);
                L.i("TV: guía de programación bajada (" + (f.length() / 1024) + " KB)");
            } catch (Exception ex) {
                L.w("TV: no se pudo bajar la guía de programación: " + Http.safeError(ex));
            }
        }
        if (!f.exists()) return null;
        try (InputStream in = new FileInputStream(f)) {
            Epg e = parse(in, now);
            L.i("TV: guía de programación con " + e.channels() + " canales");
            synchronized (Epg.class) {
                memUrl = url;
                mem = e;
            }
            return e;
        } catch (Exception ex) {
            L.w("TV: guía de programación ilegible: " + ex);
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            return null;
        }
    }

    private static void download(String url, File dir, File f) throws Exception {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("sin caché");
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(15_000);
        con.setReadTimeout(30_000);
        con.setInstanceFollowRedirects(true);
        con.setRequestProperty("User-Agent", Http.USER_AGENT);
        try {
            if (con.getResponseCode() >= 400) throw new IllegalStateException("HTTP " + con.getResponseCode());
            File tmp = new File(dir, f.getName() + ".part");
            long n = 0;
            try (InputStream in = con.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                for (int r; (r = in.read(buf)) > 0; ) {
                    n += r;
                    if (n > MAX_BYTES) throw new IllegalStateException("guía demasiado grande");
                    out.write(buf, 0, r);
                }
            }
            if (!tmp.renameTo(f)) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                throw new IllegalStateException("no se pudo guardar");
            }
        } finally {
            con.disconnect();
        }
    }

    /** Puro: «Telediario 1 · hasta 15:30» (con la hora del móvil). */
    static String label(Prog p, java.text.DateFormat time) {
        return p.title + " · " + Str.get(com.andrerinas.openheadunit.R.string.hql_epg_until, time.format(new java.util.Date(p.stop)));
    }
}
