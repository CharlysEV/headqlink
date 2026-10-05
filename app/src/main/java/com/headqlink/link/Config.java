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

    /**
     * Conexión con el coche (qdauto §5.1): Wi-Fi Direct (el coche crea la red, como siempre) o la zona Wi-Fi del móvil
     * (el coche se une a ella). Se aplica al arrancar el transporte (al volver a conectar).
     */
    static final String LINK_MODE = "link_mode";
    static final String LINK_P2P = "p2p";
    static final String LINK_HOTSPOT = "hotspot";
    /** Conexión de una instalación nueva: la zona Wi-Fi del móvil, la validada en el C10 (se marca «Recomendado»). */
    static final String DEFAULT_LINK = LINK_HOTSPOT;

    /**
     * Motor de protocolo (qdauto §4.3): QDAuto (núcleo validado en el C10) u original (SspSession). Se lee al arrancar el
     * transporte: un cambio se aplica al desconectar y volver a conectar.
     */
    static final String LINK_ENGINE = "link_engine";
    static final String ENGINE_QDAUTO = "qdauto";
    static final String ENGINE_ORIGINAL = "original";
    /**
     * Motor por defecto mientras no se elija otro: QDAuto. El original sigue disponible como respaldo (Ajustes de imagen ›
     * Avanzado › Motor de protocolo, o el extra link_engine=original).
     */
    static final String DEFAULT_ENGINE = ENGINE_QDAUTO;
    /** Mantener el vídeo (y Android Auto) vivo entre sesiones con el coche (qdauto §6); false = como el fork. */
    static final String QD_KEEP_VIDEO = "qd_keep_video";
    static final boolean DEFAULT_KEEP_VIDEO = true;
    /** Tras perder al coche, cuánto se espera a que vuelva antes de cerrarlo todo (ms). */
    static final String CAR_GONE_MS = "car_gone_ms";
    /** Filtro de pares estricto: los orígenes «aceptar con aviso» se rechazan. */
    static final String PEER_STRICT = "peer_strict";
    /** Relevo de la sesión cuando el coche se vuelve a anunciar con ella abierta. */
    static final String QD_SUPERSEDE = "qd_supersede";
    /** PHONE_INFO y ACK: "fork" (MODEL, UUID propio y tamaño del vídeo) o "qdlink" (vacíos y geometría de QDLink). */
    static final String QD_PHONE_INFO = "qd_phone_info";
    /** Modo guardado al activar la prueba con patrón desde Diagnóstico. */
    static final String MODE_BEFORE_PATTERN = "mode_before_pattern";

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
        if (i.hasExtra(LINK_MODE)) {
            String lm = i.getStringExtra(LINK_MODE);
            if (LINK_P2P.equals(lm) || LINK_HOTSPOT.equals(lm)) e.putString(LINK_MODE, lm);
        }
        if (i.hasExtra(LINK_ENGINE)) {
            String en = i.getStringExtra(LINK_ENGINE);
            if (ENGINE_QDAUTO.equals(en) || ENGINE_ORIGINAL.equals(en)) e.putString(LINK_ENGINE, en);
        }
        for (String k : new String[]{QD_KEEP_VIDEO, PEER_STRICT, QD_SUPERSEDE}) {
            if (i.hasExtra(k)) e.putBoolean(k, i.getBooleanExtra(k, false));
        }
        if (i.hasExtra(CAR_GONE_MS)) e.putInt(CAR_GONE_MS, i.getIntExtra(CAR_GONE_MS, 0));
        if (i.hasExtra(QD_PHONE_INFO)) e.putString(QD_PHONE_INFO, i.getStringExtra(QD_PHONE_INFO));
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

    /**
     * «Mantener la pantalla del móvil encendida» (por defecto, no): el modo coche de Android sin
     * ENABLE_CAR_MODE_ALLOW_SLEEP, como antes; enchufado, Android no deja apagar la pantalla (PhoneScreen).
     */
    static final String KEEP_SCREEN_ON = "keep_screen_on";

    boolean keepScreenOn() {
        return sp.getBoolean(KEEP_SCREEN_ON, false);
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

    /** fps de Android Auto: ajuste manual o los del perfil (en Coche, los que pide el coche en VIDEO_ARGS). */
    int aaFps(int carFps) {
        int v = sp.getInt(FPS, 0);
        if (v > 0) return v;
        return videoProfile().fpsFor(carFps);
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

    /** Conexión con el coche: LINK_HOTSPOT (por defecto en una instalación nueva) o LINK_P2P. */
    String linkMode() {
        return resolveLinkMode(sp.getString(LINK_MODE, null), setupDone());
    }

    /**
     * La conexión guardada si la hay. Sin guardar: DEFAULT_LINK en una instalación nueva; con la configuración inicial ya
     * hecha (una versión sin esta elección, que solo usaba Wi-Fi Direct), Wi-Fi Direct, para no cambiarle la conexión.
     */
    static String resolveLinkMode(String stored, boolean setupDone) {
        if (stored == null) return setupDone ? LINK_P2P : DEFAULT_LINK;
        return LINK_HOTSPOT.equals(stored) ? LINK_HOTSPOT : LINK_P2P;
    }

    void setLinkMode(String m) {
        sp.edit().putString(LINK_MODE, LINK_HOTSPOT.equals(m) ? LINK_HOTSPOT : LINK_P2P).apply();
    }

    boolean isHotspotMode() {
        return LINK_HOTSPOT.equals(linkMode());
    }

    /** Motor de protocolo: ENGINE_QDAUTO u ENGINE_ORIGINAL. */
    String linkEngine() {
        String en = sp.getString(LINK_ENGINE, DEFAULT_ENGINE);
        return ENGINE_QDAUTO.equals(en) || ENGINE_ORIGINAL.equals(en) ? en : DEFAULT_ENGINE;
    }

    void setLinkEngine(String en) {
        sp.edit().putString(LINK_ENGINE, ENGINE_QDAUTO.equals(en) ? ENGINE_QDAUTO : ENGINE_ORIGINAL).apply();
    }

    boolean isQdEngine() {
        return ENGINE_QDAUTO.equals(linkEngine());
    }

    boolean qdKeepVideo() {
        return sp.getBoolean(QD_KEEP_VIDEO, DEFAULT_KEEP_VIDEO);
    }

    /** Espera a que vuelva el coche antes de cerrarlo todo (por defecto 30 s; entre 5 s y 10 min). */
    long carGoneMs() {
        int v = sp.getInt(CAR_GONE_MS, 0);
        return v > 0 ? Math.max(5_000, Math.min(600_000, v)) : 30_000;
    }

    boolean peerStrict() {
        return sp.getBoolean(PEER_STRICT, false);
    }

    boolean qdSupersede() {
        return sp.getBoolean(QD_SUPERSEDE, true);
    }

    /** "fork" (por defecto) o "qdlink". */
    String qdPhoneInfo() {
        return "qdlink".equals(sp.getString(QD_PHONE_INFO, "fork")) ? "qdlink" : "fork";
    }

    String getString(String k) {
        return sp.getString(k, null);
    }

    void putString(String k, String v) {
        if (v == null) sp.edit().remove(k).apply();
        else sp.edit().putString(k, v).apply();
    }

    /**
     * Huella de todos los ajustes que cambian el vídeo de una sesión (qdauto §4.7): si cambia, la VideoPipeline viva no
     * sirve para la sesión siguiente y se recrea.
     */
    String videoFingerprint() {
        return "mode=" + mode() + " perfil=" + (isAa(mode()) ? videoProfile().id : "") + " reencode=" + aaReencode()
                + " brake=" + aaBrake() + " window=" + aaWindow() + " fps=" + sp.getInt(FPS, 0) + " kbps=" + sp.getInt(KBPS, 0)
                + " size=" + sp.getInt(WIDTH, 0) + "x" + sp.getInt(HEIGHT, 0) + " h264=" + profile() + " prepend=" + prependSpsPps()
                + " lowlat=" + lowLatency() + " maxclk=" + encMaxClocks() + " norepeat=" + getBool("enc_no_repeat")
                + " cbr=" + getBool("enc_cbr") + " noir=" + getBool("enc_no_ir") + " aadpi=" + aaDpi() + " dpi=" + dpi()
                + " pkg=" + targetPackage();
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
                + " link=" + linkMode() + " engine=" + linkEngine() + " pantallaEncendida=" + keepScreenOn()
                + (isQdEngine() ? " keepVideo=" + qdKeepVideo() + " supersede=" + qdSupersede() + " phoneInfo=" + qdPhoneInfo()
                + " carGone=" + carGoneMs() / 1000 + "s" + (peerStrict() ? " strict" : "") : "")
                + " (0 = lo que pida el coche)";
    }
}
