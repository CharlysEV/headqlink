package com.c10link.link;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import java.util.UUID;

/**
 * Ajustes del prototipo. 0 = usar lo que pida el coche.
 * Se pueden fijar por adb: am start-foreground-service -n <applicationId>/com.c10link.link.LinkService
 *   --ei fps 60 --ei kbps 12000 --ei width 1920 --ei height 882 --es profile high --ez prepend true
 *   --es mode pattern|app|aa --es pkg com.google.android.apps.maps --ei dpi 200
 */
final class Config {
    static final String QDLINK_VERSION = "1.9.7";

    static final String FPS = "fps";
    static final String KBPS = "kbps";
    static final String WIDTH = "width";
    static final String HEIGHT = "height";
    static final String PROFILE = "profile";
    static final String PREPEND = "prepend";
    static final String MODE = "mode";
    static final String PKG = "pkg";
    static final String DPI = "dpi";
    /**
     * Freno a Android Auto: cada frame se le confirma solo cuando ha salido por la radio hacia el
     * coche (activado por defecto; probado en el coche el 2026-10-03, ESTADO.md §10.10).
     */
    static final String AA_BRAKE = "aa_brake";
    /**
     * Modo "último frame" (docs/DISENO_FLUIDEZ.md): decodificar AA en el móvil y recodificar solo el
     * frame más reciente cuando el enlace tiene sitio, en vez de reenviar su H.264.
     */
    static final String AA_REENCODE = "aa_reencode";
    /** Ventana max_unacked de vídeo anunciada a AA con el freno activo. */
    static final String AA_WINDOW = "aa_window";

    static final String MODE_PATTERN = "pattern";
    static final String MODE_APP = "app";
    static final String MODE_AA = "aa";
    /** Android Auto con panel propio y pantallas nuestras (fotos, vídeos). */
    static final String MODE_AA_EXT = "aa_ext";

    /** Modos que usan Android Auto (servidor de AA, accesibilidad, freno…). */
    static boolean isAa(String mode) {
        return MODE_AA.equals(mode) || MODE_AA_EXT.equals(mode);
    }

    private final SharedPreferences sp;

    Config(Context ctx) {
        sp = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE);
    }

    void applyExtras(Intent i) {
        if (i == null || i.getExtras() == null) return;
        SharedPreferences.Editor e = sp.edit();
        for (String k : new String[]{FPS, KBPS, WIDTH, HEIGHT, DPI, "aa_dpi"}) {
            if (i.hasExtra(k)) e.putInt(k, i.getIntExtra(k, 0));
        }
        if (i.hasExtra(PROFILE)) e.putString(PROFILE, i.getStringExtra(PROFILE));
        if (i.hasExtra(PREPEND)) e.putBoolean(PREPEND, i.getBooleanExtra(PREPEND, false));
        if (i.hasExtra(AA_REENCODE)) e.putBoolean(AA_REENCODE, i.getBooleanExtra(AA_REENCODE, true));
        if (i.hasExtra(AA_BRAKE)) e.putBoolean(AA_BRAKE, i.getBooleanExtra(AA_BRAKE, true));
        if (i.hasExtra(AA_WINDOW)) e.putInt(AA_WINDOW, i.getIntExtra(AA_WINDOW, 0));
        if (i.hasExtra(MODE)) e.putString(MODE, i.getStringExtra(MODE));
        if (i.hasExtra(PKG)) e.putString(PKG, i.getStringExtra(PKG));
        if (i.hasExtra("force_legacy_launch")) e.putBoolean("force_legacy_launch", i.getBooleanExtra("force_legacy_launch", false));
        e.apply();
    }

    boolean getBool(String k) {
        return sp.getBoolean(k, false);
    }

    void putBool(String k, boolean v) {
        sp.edit().putBoolean(k, v).apply();
    }

    /** Activado por defecto: lo más fluido medido en el coche (2026-10-03, ESTADO.md §10.12). */
    static final String PANEL_GRAY = "panel_gray";
    private static final String IPTV_SOURCE = "iptv_source";
    private static final String PDF_DOCS = "pdf_docs";

    /** Lista de TV (M3U): URL http(s) o content:// de un archivo elegido en el móvil. */
    String iptvSource() {
        return sp.getString(IPTV_SOURCE, "");
    }

    void setIptvSource(String s) {
        sp.edit().putString(IPTV_SOURCE, s == null ? "" : s.trim()).apply();
    }

    /** Documentos PDF elegidos en el móvil: pares {uri, nombre}, en el orden en que se añadieron. */
    java.util.List<String[]> pdfDocuments() {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        try {
            org.json.JSONArray a = new org.json.JSONArray(sp.getString(PDF_DOCS, "[]"));
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONObject o = a.getJSONObject(i);
                out.add(new String[]{o.getString("uri"), o.optString("name", "Documento")});
            }
        } catch (org.json.JSONException ignored) {
        }
        return out;
    }

    void setPdfDocuments(java.util.List<String[]> docs) {
        org.json.JSONArray a = new org.json.JSONArray();
        try {
            for (String[] d : docs) a.put(new org.json.JSONObject().put("uri", d[0]).put("name", d[1]));
        } catch (org.json.JSONException ignored) {
        }
        sp.edit().putString(PDF_DOCS, a.toString()).apply();
    }

    /** Accesos directos de la web en el coche: {nombre, url}. */
    String[][] webShortcuts() {
        return WebScreen.DEFAULT_SHORTCUTS;
    }

    /** Gris del panel propio (0-80), ajustable para que se funda con las barras del coche. */
    int panelGray() {
        return sp.getInt(PANEL_GRAY, 30);
    }

    int panelColor() {
        int g = panelGray();
        return 0xFF000000 | (g << 16) | (g << 8) | Math.min(255, g + 2);
    }

    boolean aaReencode() {
        return sp.getBoolean(AA_REENCODE, true);
    }

    /**
     * fps de Android Auto: ajuste manual o, en modo "último frame", 60 (la puerta descarta lo que
     * el enlace no admite); en reenvío directo, lo que pide el coche (a 60 acumulaba frames).
     */
    int aaFps(int carFps) {
        int v = sp.getInt(FPS, 0);
        if (v > 0) return v;
        return aaReencode() || MODE_AA_EXT.equals(mode()) ? 60 : (carFps > 0 ? carFps : 30);
    }

    boolean aaBrake() {
        return sp.getBoolean(AA_BRAKE, true);
    }

    /** Ventana de vídeo para AA con el freno activo (por defecto 2 frames). */
    int aaWindow() {
        int v = sp.getInt(AA_WINDOW, 0);
        return v > 0 ? v : 2;
    }

    int getInt(String k) {
        return sp.getInt(k, 0);
    }

    void putInt(String k, int v) {
        sp.edit().putInt(k, v).apply();
    }

    int fps(int carFps) {
        int v = sp.getInt(FPS, 0);
        if (v > 0) return v;
        return carFps > 0 ? carFps : 30;
    }

    int bitrate(int carBps) {
        int kbps = sp.getInt(KBPS, 0);
        if (kbps > 0) return kbps * 1000;
        return carBps > 0 ? carBps : 4_000_000;
    }

    /** Resolución de vídeo: ajuste manual > VIDEO_ARGS > CAR_INFO > 1920x882. */
    int[] videoSize(int carW, int carH, int argsW, int argsH) {
        int w = sp.getInt(WIDTH, 0);
        int h = sp.getInt(HEIGHT, 0);
        if (w <= 0 || h <= 0) {
            if (argsW > 0 && argsH > 0) {
                w = argsW;
                h = argsH;
            } else if (carW > 0 && carH > 0) {
                w = carW;
                h = carH;
            } else {
                w = 1920;
                h = 882;
            }
        }
        return new int[]{w & ~1, h & ~1};
    }

    String profile() {
        return sp.getString(PROFILE, "baseline");
    }

    void setProfile(String p) {
        sp.edit().putString(PROFILE, p).apply();
    }

    boolean prependSpsPps() {
        return sp.getBoolean(PREPEND, false);
    }

    boolean setupDone() {
        return sp.getBoolean("setup_done", false);
    }

    void setSetupDone(boolean done) {
        sp.edit().putBoolean("setup_done", done).apply();
    }

    String mode() {
        return sp.getString(MODE, MODE_PATTERN);
    }

    void setMode(String m) {
        sp.edit().putString(MODE, m).apply();
    }

    String targetPackage() {
        return sp.getString(PKG, "");
    }

    void setTargetPackage(String p) {
        sp.edit().putString(PKG, p).apply();
    }

    /** Densidad del VirtualDisplay en modo app; 200 da una UI legible en la pantalla de 14,6". */
    int dpi() {
        int v = sp.getInt(DPI, 0);
        return v > 0 ? v : 200;
    }

    /** Densidad anunciada a Android Auto para la pantalla del coche (ajustable con el extra aa_dpi). */
    int aaDpi() {
        int v = sp.getInt("aa_dpi", 0);
        return v > 0 ? v : 200;
    }

    String deviceName() {
        return Build.MODEL;
    }

    String deviceUuid() {
        String u = sp.getString("uuid", null);
        if (u == null) {
            u = UUID.randomUUID().toString();
            sp.edit().putString("uuid", u).apply();
        }
        return u;
    }

    String summary() {
        return "fps=" + sp.getInt(FPS, 0) + " kbps=" + sp.getInt(KBPS, 0) + " size=" + sp.getInt(WIDTH, 0) + "x"
                + sp.getInt(HEIGHT, 0) + " profile=" + profile() + " prepend=" + prependSpsPps()
                + " mode=" + mode() + (MODE_APP.equals(mode()) ? " pkg=" + targetPackage() + " dpi=" + dpi() : "")
                + " (0 = lo que pida el coche)";
    }
}
