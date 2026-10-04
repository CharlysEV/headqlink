package com.headqlink.link;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import java.util.UUID;

/**
 * Ajustes del prototipo. 0 = usar lo que pida el coche.
 * Se pueden fijar por adb: am start-foreground-service -n <applicationId>/com.headqlink.link.LinkService
 *   --ei fps 60 --ei kbps 12000 --ei width 1920 --ei height 882 --es profile high --ez prepend true
 *   --es mode pattern|app|aa --es pkg com.google.android.apps.maps --ei dpi 200
 */
final class Config {
    /** Versión del protocolo que se anuncia al coche en el saludo. */
    static final String LINK_PROTOCOL_VERSION = "1.9.7";

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
     * coche (activado por defecto).
     */
    static final String AA_BRAKE = "aa_brake";
    /**
     * Modo "último frame": decodificar AA en el móvil y recodificar solo el
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

    private final Context app;

    Config(Context ctx) {
        app = ctx.getApplicationContext();
        sp = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE);
    }

    static final String VIDEO_PROFILE = "video_profile";

    /** Perfil elegido por el usuario, o "" si sigue el recomendado. */
    String videoProfileChoice() {
        return sp.getString(VIDEO_PROFILE, "");
    }

    /**
     * Perfil en uso: el elegido o el recomendado para este móvil. En el modo ampliado no hay reenvío
     * directo (el panel exige recodificar), así que Básico se comporta como Medio.
     */
    VideoProfile videoProfile() {
        String id = videoProfileChoice();
        if (id.isEmpty()) id = VideoProfile.recommended(app);
        VideoProfile p = VideoProfile.of(id);
        if (MODE_AA_EXT.equals(mode()) && !p.reencode) p = VideoProfile.of(VideoProfile.MEDIUM);
        return p;
    }

    /** Elige un perfil ("" = recomendado) y quita los ajustes manuales que lo contradirían. */
    void setVideoProfile(String id) {
        sp.edit().putString(VIDEO_PROFILE, id == null ? "" : id)
                .remove(FPS).remove(WIDTH).remove(HEIGHT).remove(AA_REENCODE)
                .remove(LOW_LATENCY).remove("enc_max_clocks").apply();
    }

    void applyExtras(Intent i) {
        if (i == null || i.getExtras() == null) return;
        SharedPreferences.Editor e = sp.edit();
        for (String k : new String[]{FPS, KBPS, WIDTH, HEIGHT, DPI, "aa_dpi"}) {
            if (i.hasExtra(k)) e.putInt(k, i.getIntExtra(k, 0));
        }
        if (i.hasExtra(PROFILE)) e.putString(PROFILE, i.getStringExtra(PROFILE));
        if (i.hasExtra(PREPEND)) e.putBoolean(PREPEND, i.getBooleanExtra(PREPEND, false));
        if (i.hasExtra(LOW_LATENCY)) e.putBoolean(LOW_LATENCY, i.getBooleanExtra(LOW_LATENCY, false));
        if (i.hasExtra("stop_aa_server")) e.putBoolean("stop_aa_server", i.getBooleanExtra("stop_aa_server", false));
        // Variantes de prueba del encoder (ver VideoEncoder.Params).
        for (String k : new String[]{"enc_no_repeat", "enc_max_clocks", "enc_cbr", "enc_no_ir"}) {
            if (i.hasExtra(k)) e.putBoolean(k, i.getBooleanExtra(k, false));
        }
        if (i.hasExtra(PANEL_AUTOHIDE)) e.putBoolean(PANEL_AUTOHIDE, i.getBooleanExtra(PANEL_AUTOHIDE, true));
        if (i.hasExtra(SEND_WHITELIST)) e.putBoolean(SEND_WHITELIST, i.getBooleanExtra(SEND_WHITELIST, true));
        if (i.hasExtra(AA_REENCODE)) e.putBoolean(AA_REENCODE, i.getBooleanExtra(AA_REENCODE, true));
        if (i.hasExtra(VIDEO_PROFILE)) e.putString(VIDEO_PROFILE, i.getStringExtra(VIDEO_PROFILE));
        if (i.hasExtra("clear_manual")) e.remove(FPS).remove(WIDTH).remove(HEIGHT).remove(AA_REENCODE);
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

    static final String PANEL_GRAY = "panel_gray";
    /** "Optimizaciones de latencia" (LowLatency); apagado = comportamiento anterior. */
    static final String LOW_LATENCY = "low_latency";
    /** Enviar Mirror/WhitelistAppOn cada segundo (qué se muestra, para la restricción en marcha del coche). */
    static final String SEND_WHITELIST = "send_whitelist";

    /**
     * Relojes del encoder al máximo (KEY_OPERATING_RATE). Por defecto, con las optimizaciones de
     * latencia: baja la latencia de codificación (ver EncBench), sobre todo con frames irregulares,
     * cuando los relojes bajarían.
     */
    boolean encMaxClocks() {
        return sp.getBoolean("enc_max_clocks", lowLatency());
    }

    /** Optimizaciones de latencia: las del perfil (Alto y Medio sí; Ahorro y Básico no), salvo ajuste manual. */
    boolean lowLatency() {
        if (sp.contains(LOW_LATENCY)) return sp.getBoolean(LOW_LATENCY, false);
        return videoProfile().boost;
    }

    boolean sendWhitelist() {
        return sp.getBoolean(SEND_WHITELIST, true);
    }

    /** Tema elegido en el menú: 0 según el sistema, 1 claro, 2 oscuro (lo aplica UiModeManager). */
    static final String THEME = "theme";

    /** Conexión automática al detectar el Bluetooth del coche (CarBtReceiver). */
    static final String BT_AUTO = "bt_auto";
    static final String BT_AUTO_NAME = "bt_auto_name";

    boolean btAutoConnect() {
        return sp.getBoolean(BT_AUTO, false);
    }

    /** Texto que debe contener el nombre Bluetooth del coche (sin distinguir mayúsculas). */
    String btAutoName() {
        return sp.getString(BT_AUTO_NAME, "Leapmotor_BT");
    }

    void setBtAutoName(String s) {
        sp.edit().putString(BT_AUTO_NAME, s == null ? "" : s.trim()).apply();
    }

    /** Panel propio que se oculta solo con AA en pantalla (vuelve al tocar el borde izquierdo). */
    static final String PANEL_AUTOHIDE = "panel_autohide";

    boolean panelAutoHide() {
        return sp.getBoolean(PANEL_AUTOHIDE, true);
    }

    /**
     * Apagar el servidor de head unit de AA al terminar (por defecto, sí). Escucha en todas las
     * interfaces (también en la red WiFi), así que encendido es una puerta abierta: cualquier aparato
     * de la misma red podría conectarse como head unit. Si el móvil está bloqueado, se apaga al
     * desbloquearlo, tras la capa (los ajustes de AA no se ven).
     */
    boolean stopAaServerOnExit() {
        return sp.getBoolean("stop_aa_server", true);
    }

    private static final String RADIO_SOURCE = "radio_source";

    /** Lista de radio (M3U): URL http(s) o content:// de un archivo; vacía = solo las populares. */
    String radioSource() {
        return sp.getString(RADIO_SOURCE, "");
    }

    void setRadioSource(String s) {
        sp.edit().putString(RADIO_SOURCE, s == null ? "" : s.trim()).apply();
    }

    private static final String IPTV_SOURCE = "iptv_source";

    /** Lista de TV (M3U): URL http(s) o content:// de un archivo elegido en el móvil. */
    String iptvSource() {
        return sp.getString(IPTV_SOURCE, "");
    }

    void setIptvSource(String s) {
        sp.edit().putString(IPTV_SOURCE, s == null ? "" : s.trim()).apply();
    }

    // Batería indicada por el usuario (hasta tener datos del coche) y energía estimada desde entonces.
    double socPct() {
        float v = sp.getFloat("soc_pct", -1f);
        return v < 0 ? Double.NaN : v;
    }

    void setSoc(double pct) {
        sp.edit().putFloat("soc_pct", (float) pct).putFloat("soc_used_kwh", 0f).apply();
    }

    double socUsedKwh() {
        return sp.getFloat("soc_used_kwh", 0f);
    }

    void addSocUsedKwh(double kwh) {
        sp.edit().putFloat("soc_used_kwh", (float) (socUsedKwh() + kwh)).apply();
    }

    /** Precios para el coste de los viajes. */
    double electricityPrice() {
        return sp.getFloat("price_kwh", 0.20f);
    }

    double fuelPrice() {
        return sp.getFloat("price_fuel", 1.60f);
    }

    double fuelLitersPer100() {
        return sp.getFloat("fuel_l100", 6.5f);
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

    /** "Último frame" (recodificar): lo decide el perfil, salvo ajuste manual por adb (aa_reencode). */
    boolean aaReencode() {
        if (sp.contains(AA_REENCODE)) return sp.getBoolean(AA_REENCODE, true);
        return videoProfile().reencode;
    }

    /** fps de Android Auto: ajuste manual o los del perfil. */
    int aaFps(int carFps) {
        int v = sp.getInt(FPS, 0);
        if (v > 0) return v;
        return videoProfile().fps;
    }

    /** Cadencia fija del relay: la del perfil, salvo fps manuales (adb). */
    boolean aaFixedRate() {
        return sp.getInt(FPS, 0) <= 0 && videoProfile().fixedRate;
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
        return sp.getString(MODE, MODE_AA_EXT);
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
