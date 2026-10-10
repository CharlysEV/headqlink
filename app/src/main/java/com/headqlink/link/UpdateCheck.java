package com.headqlink.link;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.util.function.Consumer;

/**
 * Aviso de versión nueva, con las versiones de HeadQLink (el comprobador de Open Headunit mira las de open-headunit).
 * Como mucho una consulta cada 6 h a GitHub: la última release del repositorio, con sus notas. También «Novedades»:
 * la primera vez que arranca una versión recién instalada, las notas de esa versión.
 */
final class UpdateCheck {
    static final String REPO = "CharlysEV/headqlink";
    static final String RELEASES_WEB = "https://github.com/" + REPO + "/releases";
    private static final String API = "https://api.github.com/repos/" + REPO + "/releases";
    private static final long EVERY_MS = 6 * 3600_000L;

    /** Una release: versión limpia («0.2.34»), enlace y notas. */
    static final class Release {
        final String version;
        final String url;
        final String notes;

        Release(String version, String url, String notes) {
            this.version = version;
            this.url = url;
            this.notes = notes;
        }
    }

    private UpdateCheck() {
    }

    /** Puro: «v0.2.33-qdauto» → {0, 2, 33}; null si no empieza por números. */
    static int[] parse(String tag) {
        if (tag == null) return null;
        String s = tag.trim();
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
        int dash = s.indexOf('-');
        if (dash >= 0) s = s.substring(0, dash);
        String[] parts = s.split("\\.");
        int[] v = new int[3];
        for (int i = 0; i < 3; i++) {
            if (i >= parts.length) break;
            try {
                v[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                return i == 0 ? null : v;
            }
        }
        return v;
    }

    /** Puro: ¿latest es más nueva que current? Compara número a número (0.2.30 > 0.2.9). */
    static boolean newer(String latest, String current) {
        int[] a = parse(latest);
        int[] b = parse(current);
        if (a == null || b == null) return false;
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) return a[i] > b[i];
        }
        return false;
    }

    /** Puro: «v0.2.33-qdauto» → «0.2.33». */
    static String clean(String tag) {
        int[] v = parse(tag);
        if (v == null) return tag == null ? "" : tag;
        return v[0] + "." + v[1] + "." + v[2];
    }

    /**
     * Puro: las notas de GitHub (Markdown) en texto llano para un diálogo: títulos sin «#», listas con «•», sin
     * negritas ni código, y sin la sección «Aviso» (licencias, que ya están en la app) ni el SHA.
     */
    static String plainNotes(String md) {
        if (md == null) return "";
        StringBuilder out = new StringBuilder();
        boolean skip = false;
        for (String raw : md.replace("\r", "").split("\n")) {
            String l = raw.trim();
            if (l.startsWith("## ")) {
                skip = l.toLowerCase().contains("aviso");
                if (skip) continue;
                // El título general («0.2.33: …») ya va en el título del diálogo.
                if (out.length() == 0) continue;
                l = l.substring(3);
            }
            if (skip) continue;
            if (l.startsWith("### ")) l = l.substring(4).toUpperCase();
            if (l.startsWith("SHA-256")) continue;
            if (raw.startsWith("  - ")) l = "    – " + l.substring(2);
            else if (l.startsWith("- ")) l = "• " + l.substring(2);
            l = l.replace("**", "").replace("`", "");
            // Sin líneas en blanco seguidas ni al principio.
            if (l.isEmpty() && (out.length() == 0 || out.toString().endsWith("\n\n"))) continue;
            out.append(l).append('\n');
        }
        return out.toString().trim();
    }

    /**
     * Comprueba si hay versión nueva (como mucho cada 6 h; antes, lo recordado) y avisa en el hilo principal con la
     * release si es más nueva que la instalada, o null.
     */
    static void check(Context ctx, Consumer<Release> cb) {
        Context app = ctx.getApplicationContext();
        Config cfg = new Config(app);
        String installed = installedVersion(app);
        long last = cfg.updateCheckedAt();
        Handler main = new Handler(Looper.getMainLooper());
        if (System.currentTimeMillis() - last < EVERY_MS) {
            Release r = cfg.updateRelease();
            cb.accept(r != null && newer(r.version, installed) ? r : null);
            return;
        }
        new Thread(() -> {
            Release r = null;
            try {
                JSONObject o = new JSONObject(Http.get(API + "/latest"));
                r = new Release(clean(o.optString("tag_name")), o.optString("html_url", RELEASES_WEB), o.optString("body", ""));
                cfg.setUpdateRelease(r);
                L.i("versiones: la última publicada es " + r.version + " · instalada " + installed
                        + (newer(r.version, installed) ? " · hay versión nueva" : ""));
            } catch (Exception e) {
                L.w("versiones: no se pudo consultar GitHub: " + Http.safeError(e));
                r = cfg.updateRelease();
            }
            cfg.setUpdateCheckedAt(System.currentTimeMillis());
            Release fin = r;
            main.post(() -> cb.accept(fin != null && newer(fin.version, installed) ? fin : null));
        }, "hql-update-check").start();
    }

    /**
     * «Novedades»: si esta versión arranca por primera vez (versionCode distinto del último visto), busca sus notas y
     * las da en el hilo principal (null si no hay red o no hay release). Se marca como vista solo cuando hay notas,
     * para volver a intentarlo si no hubo red.
     */
    static void whatsNew(Context ctx, Consumer<Release> cb) {
        Context app = ctx.getApplicationContext();
        Config cfg = new Config(app);
        long code = installedCode(app);
        if (code <= 0 || cfg.seenVersionCode() == code) return;
        if (cfg.seenVersionCode() == 0) {
            // Primera instalación: sin novedades que contar.
            cfg.setSeenVersionCode(code);
            return;
        }
        String installed = installedVersion(app);
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            Release r = null;
            try {
                int dash = installed.indexOf('-');
                String tag = "v" + installed + (dash < 0 ? "-qdauto" : "");
                JSONObject o = new JSONObject(Http.get(API + "/tags/" + tag));
                r = new Release(clean(o.optString("tag_name")), o.optString("html_url", RELEASES_WEB), o.optString("body", ""));
                cfg.setSeenVersionCode(code);
                L.i("versiones: novedades de la " + r.version);
            } catch (Exception e) {
                L.w("versiones: sin novedades de la " + installed + ": " + Http.safeError(e));
            }
            Release fin = r;
            main.post(() -> cb.accept(fin));
        }, "hql-whats-new").start();
    }

    static String installedVersion(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private static long installedCode(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).getLongVersionCode();
        } catch (Exception e) {
            return 0;
        }
    }
}
