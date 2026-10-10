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
    /**
     * Cable USB (experimental): el coche pone el móvil en modo accesorio (AOA, «Neusoft QDriveLink») y la sesión va por
     * el cable con la trama de bloques de 512 B, sin Wi-Fi. Con un accesorio del coche conectado, el cable tiene
     * prioridad aunque la conexión elegida sea otra (UsbLink).
     */
    static final String LINK_USB = "usb";
    /** Conexión de una instalación nueva: la zona Wi-Fi del móvil, la validada en el C10 (se marca «Recomendado»). */
    static final String DEFAULT_LINK = LINK_HOTSPOT;

    /**
     * La conexión con la marca «Recomendado» en el asistente, según el modo: en Auto extendido, el cable USB (el más
     * estable para la imagen compuesta: 60 fps y sin cortes de radio); en Auto, el punto de acceso del móvil, el probado
     * en el C10 (decisión del 2026-10-09).
     */
    static String recommendedLink(String mode) {
        return MODE_AA_EXT.equals(mode) ? LINK_USB : LINK_HOTSPOT;
    }

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
    /**
     * Tras perder al coche, cuánto sigue vivo el vídeo (y AA proyectando) para una reconexión sin cortes (ms). Después,
     * AA queda en pausa hasta que vence «Esperar al coche» (CAR_WAIT_MIN).
     */
    static final String CAR_GONE_MS = "car_gone_ms";
    /**
     * «Esperar al coche» (minutos): tras perderlo, el enlace sigue escuchando y AA en pausa este tiempo; si vuelve, se
     * reanuda al instante. Después se cierra todo (y se apaga el servidor de AA). Opciones: CAR_WAIT_CHOICES.
     */
    static final String CAR_WAIT_MIN = "car_wait_min";
    static final int[] CAR_WAIT_CHOICES = {1, 5, 15};
    static final int DEFAULT_CAR_WAIT_MIN = 5;
    /** Filtro de pares estricto: los orígenes «aceptar con aviso» se rechazan. */
    static final String PEER_STRICT = "peer_strict";
    /** Relevo de la sesión cuando el coche se vuelve a anunciar con ella abierta. */
    static final String QD_SUPERSEDE = "qd_supersede";
    /*
     * Vuelta del coche tras un corte de radio (qdcore RecoveryConfig; C10, 2026-10-06). Todo activado por defecto; se
     * puede apagar por partes con los extras (p. ej. --ez qd_reclaim false) para comparar.
     */
    /** ACK reenviado cada 2 s hasta que llega el TCP (false = uno solo, como QDLink). */
    static final String QD_ACK_RESEND = "qd_ack_resend";
    /** Reutilizar el MirrorPort de la última sesión en los intentos siguientes. */
    static final String QD_STABLE_PORT = "qd_stable_port";
    /** Re-acogida tras un final anormal: el mismo puerto con una espera larga y ACK no pedidos al coche. */
    static final String QD_RECLAIM = "qd_reclaim";
    /** Reabrir el UDP 18463 tras 20 s sin anuncios esperando al coche (y volver a coger el MulticastLock). */
    static final String QD_UDP_REFRESH = "qd_udp_refresh";
    /** Ping a la IP del coche en el diagnóstico de la espera (cada 10 s). */
    static final String QD_CAR_PING = "qd_car_ping";
    /** PHONE_INFO y ACK: "fork" (MODEL, UUID propio y tamaño del vídeo) o "qdlink" (vacíos y geometría de QDLink). */
    static final String QD_PHONE_INFO = "qd_phone_info";
    /**
     * Prueba (Diagnóstico › Opciones de prueba): la trama por bloques del cable USB sobre el TCP del Wi-Fi, para probarla
     * desde el PC con «qdsim --usb-framing». Por defecto, no (el C10 por Wi-Fi no la usa).
     */
    static final String QD_USB_OVER_TCP = "qd_usb_over_tcp";
    /** Modo guardado al activar la prueba con patrón desde Diagnóstico. */
    static final String MODE_BEFORE_PATTERN = "mode_before_pattern";
    /**
     * «Arranque del servidor de Android Auto» (AA 17.4+): AA_SERVER_AUTO (por defecto y recomendado: la accesibilidad
     * pulsa su menú de desarrollador para arrancarlo y pararlo) o AA_SERVER_MANUAL (sin accesibilidad: lo arranca el
     * usuario; HeadQLink nunca lo sondea ni lo para: una sonda lo bloquearía, así que lo dice el intento real del
     * Self-Mode, y si no atiende, avisa y reintenta; tras un cierre limpio sigue atendiendo). Se lee en cada decisión.
     */
    static final String AA_SERVER_START = "aa_server_start";
    static final String AA_SERVER_AUTO = "auto";
    static final String AA_SERVER_MANUAL = "manual";

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

    /**
     * Avisos de cambios en los ajustes, en el hilo principal (el widget sigue la conexión y el modo, se cambien donde se
     * cambien). Android solo guarda una referencia débil al oyente: quien llama tiene que guardarlo.
     */
    void listen(SharedPreferences.OnSharedPreferenceChangeListener l, boolean on) {
        if (on) sp.registerOnSharedPreferenceChangeListener(l);
        else sp.unregisterOnSharedPreferenceChangeListener(l);
    }

    static final String VIDEO_PROFILE = "video_profile";

    /** Perfil elegido por el usuario, o "" si sigue el recomendado. */
    String videoProfileChoice() {
        return sp.getString(VIDEO_PROFILE, "");
    }

    /**
     * Perfil en uso: el elegido o el recomendado para este móvil, con la «Fluidez» elegida (solo cuenta en Coche). En el
     * modo ampliado no hay reenvío directo (el panel exige recodificar), así que Básico se comporta como Medio.
     */
    VideoProfile videoProfile() {
        String id = videoProfileChoice();
        if (id.isEmpty()) id = VideoProfile.recommended(app);
        VideoProfile p = VideoProfile.of(id);
        if (MODE_AA_EXT.equals(mode()) && !p.reencode) p = VideoProfile.of(VideoProfile.MEDIUM);
        return p.withFluidity(fluidity());
    }

    /**
     * «Fluidez» (Ajustes de imagen): VideoProfile.FLUID_30 (lo que pide el coche; menos calor, recomendado) o FLUID_60
     * (60 fps y 8-12 Mbit/s, como el HeadQLink original). Solo actúa con los perfiles Coche y Automático (cuando el
     * recomendado es Coche). Se aplica en la sesión siguiente (o al instante con «Guardar» estando conectado).
     */
    static final String FLUIDITY = "fluidez";

    int fluidity() {
        return sp.getInt(FLUIDITY, 0) >= VideoProfile.FLUID_60 ? VideoProfile.FLUID_60 : VideoProfile.FLUID_30;
    }

    void setFluidity(int fps) {
        sp.edit().putInt(FLUIDITY, fps >= VideoProfile.FLUID_60 ? VideoProfile.FLUID_60 : VideoProfile.FLUID_30).apply();
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
        for (String k : new String[]{FPS, KBPS, WIDTH, HEIGHT, DPI, "aa_dpi", GATE_OUTQ_KB, WRITE_STALL_MS}) {
            if (i.hasExtra(k)) e.putInt(k, i.getIntExtra(k, 0));
        }
        if (i.hasExtra(THERMAL_MODE)) e.putString(THERMAL_MODE, ThermalPolicy.mode(i.getStringExtra(THERMAL_MODE)));
        if (i.hasExtra(PROFILE)) e.putString(PROFILE, i.getStringExtra(PROFILE));
        if (i.hasExtra(PREPEND)) e.putBoolean(PREPEND, i.getBooleanExtra(PREPEND, false));
        if (i.hasExtra(LOW_LATENCY)) e.putBoolean(LOW_LATENCY, i.getBooleanExtra(LOW_LATENCY, false));
        if (i.hasExtra("stop_aa_server")) e.putBoolean("stop_aa_server", i.getBooleanExtra("stop_aa_server", false));
        // Variantes de prueba del encoder (ver VideoEncoder.Params).
        // enc_vbr: el perfil Coche sin CBR; link_fixed: sin bitrate adaptable al enlace.
        for (String k : new String[]{"enc_no_repeat", "enc_max_clocks", "enc_cbr", "enc_no_ir", "enc_vbr", "link_fixed"}) {
            if (i.hasExtra(k)) e.putBoolean(k, i.getBooleanExtra(k, false));
        }
        if (i.hasExtra(PANEL_AUTOHIDE)) e.putBoolean(PANEL_AUTOHIDE, i.getBooleanExtra(PANEL_AUTOHIDE, true));
        if (i.hasExtra(SEND_WHITELIST)) e.putBoolean(SEND_WHITELIST, i.getBooleanExtra(SEND_WHITELIST, true));
        if (i.hasExtra(AA_REENCODE)) e.putBoolean(AA_REENCODE, i.getBooleanExtra(AA_REENCODE, true));
        if (i.hasExtra(VIDEO_PROFILE)) e.putString(VIDEO_PROFILE, i.getStringExtra(VIDEO_PROFILE));
        if (i.hasExtra(FLUIDITY)) e.putInt(FLUIDITY, i.getIntExtra(FLUIDITY, 0) >= VideoProfile.FLUID_60 ? VideoProfile.FLUID_60 : VideoProfile.FLUID_30);
        if (i.hasExtra("clear_manual")) e.remove(FPS).remove(WIDTH).remove(HEIGHT).remove(AA_REENCODE);
        if (i.hasExtra(AA_BRAKE)) e.putBoolean(AA_BRAKE, i.getBooleanExtra(AA_BRAKE, true));
        if (i.hasExtra(AA_WINDOW)) e.putInt(AA_WINDOW, i.getIntExtra(AA_WINDOW, 0));
        if (i.hasExtra(MODE)) e.putString(MODE, i.getStringExtra(MODE));
        if (i.hasExtra(LINK_MODE)) {
            String lm = i.getStringExtra(LINK_MODE);
            if (LINK_P2P.equals(lm) || LINK_HOTSPOT.equals(lm) || LINK_USB.equals(lm)) e.putString(LINK_MODE, lm);
        }
        if (i.hasExtra(LINK_ENGINE)) {
            String en = i.getStringExtra(LINK_ENGINE);
            if (ENGINE_QDAUTO.equals(en) || ENGINE_ORIGINAL.equals(en)) e.putString(LINK_ENGINE, en);
        }
        for (String k : new String[]{QD_KEEP_VIDEO, PEER_STRICT, QD_SUPERSEDE, QD_ACK_RESEND, QD_STABLE_PORT, QD_RECLAIM,
                QD_UDP_REFRESH, QD_CAR_PING, QD_USB_OVER_TCP}) {
            if (i.hasExtra(k)) e.putBoolean(k, i.getBooleanExtra(k, false));
        }
        if (i.hasExtra(CAR_GONE_MS)) e.putInt(CAR_GONE_MS, i.getIntExtra(CAR_GONE_MS, 0));
        if (i.hasExtra(CAR_WAIT_MIN)) e.putInt(CAR_WAIT_MIN, carWaitMinFor(i.getIntExtra(CAR_WAIT_MIN, 0)));
        if (i.hasExtra(QD_PHONE_INFO)) e.putString(QD_PHONE_INFO, i.getStringExtra(QD_PHONE_INFO));
        if (i.hasExtra(PKG)) e.putString(PKG, i.getStringExtra(PKG));
        if (i.hasExtra("force_legacy_launch")) e.putBoolean("force_legacy_launch", i.getBooleanExtra("force_legacy_launch", false));
        if (i.hasExtra(AA_SERVER_START)) e.putString(AA_SERVER_START, aaServerStartFor(i.getStringExtra(AA_SERVER_START)));
        e.apply();
    }

    boolean getBool(String k) {
        return sp.getBoolean(k, false);
    }

    void putBool(String k, boolean v) {
        sp.edit().putBoolean(k, v).apply();
    }

    static final String PANEL_GRAY = "panel_gray";
    /** Tema de la pantalla del C10 (Global/DarkModeOn): -1 sin saber, 0 claro, 1 oscuro. */
    static final String CAR_DARK = "car_dark";

    /** El último tema que dijo el coche (-1 si nunca lo ha dicho). */
    int carDark() {
        return sp.getInt(CAR_DARK, -1);
    }

    void setCarDark(boolean dark) {
        sp.edit().putInt(CAR_DARK, dark ? 1 : 0).apply();
    }

    /** versionCode de AA con el que el Self-Mode va directo al servidor de head unit (SelfModeShortcut); -1 = no. */
    static final String SELF_MODE_DIRECT_AA = "self_mode_direct_aa";

    long selfModeDirectAa() {
        return sp.getLong(SELF_MODE_DIRECT_AA, -1);
    }

    void setSelfModeDirectAa(long aaCode) {
        sp.edit().putLong(SELF_MODE_DIRECT_AA, aaCode).apply();
    }
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

    /**
     * «Protección térmica» (Ajustes de imagen): ThermalPolicy.MODE_NORMAL (recomendada), MODE_SOFT (solo baja el bitrate;
     * nunca por debajo de 30 fps salvo en estado crítico) o MODE_OFF (solo se registra). Se aplica en el acto.
     */
    static final String THERMAL_MODE = "thermal_mode";

    String thermalMode() {
        return ThermalPolicy.mode(sp.getString(THERMAL_MODE, ThermalPolicy.MODE_NORMAL));
    }

    void setThermalMode(String mode) {
        sp.edit().putString(THERMAL_MODE, ThermalPolicy.mode(mode)).apply();
    }

    /** Puerta «último frame»: cola del kernel máxima en KB para codificar otro frame (0 = VideoPipeline.GATE_OUTQ). */
    static final String GATE_OUTQ_KB = "gate_outq_kb";

    /** Write bloqueado con el coche hablando: ms hasta cerrar la sesión (0 = SessionConfigs.WRITE_STALL_CAR_TALKING_MS). */
    static final String WRITE_STALL_MS = "write_stall_ms";

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

    /** €/kWh para el coste de los viajes (Ajustes del coche), entre 0 y 1 €. */
    void setElectricityPrice(double eurPerKwh) {
        sp.edit().putFloat("price_kwh", (float) Math.max(0, Math.min(1, Math.round(eurPerKwh * 100) / 100.0))).apply();
    }

    double fuelPrice() {
        return sp.getFloat("price_fuel", 1.60f);
    }

    double fuelLitersPer100() {
        return sp.getFloat("fuel_l100", 6.5f);
    }

    /** Ajuste de la previsión de las rutas (RouteCalibration): las últimas llegadas, en JSON. */
    String routeCalibration() {
        return sp.getString("route_calib", "");
    }

    void setRouteCalibration(String json) {
        sp.edit().putString("route_calib", json).apply();
    }

    /** Filtro de los cargadores de la Ruta (ChargerFilter): potencia mínima (kW, 0 = cualquiera) y redes («tesla,zunder»). */
    int chargerMinKw() {
        return ChargerFilter.snapMinKw(sp.getInt("charger_min_kw", 0));
    }

    void setChargerMinKw(int kw) {
        sp.edit().putInt("charger_min_kw", ChargerFilter.snapMinKw(kw)).apply();
    }

    /** Con potencia mínima, incluir también los cargadores sin potencia confirmada en OpenStreetMap (la mayoría). */
    boolean chargerIncludeUnknown() {
        return sp.getBoolean("charger_include_unknown", true);
    }

    void setChargerIncludeUnknown(boolean on) {
        sp.edit().putBoolean("charger_include_unknown", on).apply();
    }

    String chargerNetworks() {
        return sp.getString("charger_networks", "");
    }

    void setChargerNetworks(String keys) {
        sp.edit().putString("charger_networks", keys == null ? "" : keys).apply();
    }

    /** Color del C10 en 3D (#rrggbb), elegido en la pestaña Estado; "" = el de serie (verde). */
    String carColor() {
        String c = sp.getString("car_color", "");
        return c.matches("#[0-9a-f]{6}") ? c : "";
    }

    void setCarColor(String hex) {
        if (hex != null && hex.matches("#[0-9a-fA-F]{6}")) sp.edit().putString("car_color", hex.toLowerCase(java.util.Locale.ROOT)).apply();
    }

    /** Plan de carga (ChargePlanner): % mínimo al llegar y % máximo al que cargar en cada parada. */
    int planArrivePct() {
        return Math.max(5, Math.min(50, sp.getInt("plan_arrive_pct", 15)));
    }

    void setPlanArrivePct(int pct) {
        sp.edit().putInt("plan_arrive_pct", pct).apply();
    }

    int planMaxPct() {
        return Math.max(50, Math.min(100, sp.getInt("plan_max_pct", 80)));
    }

    void setPlanMaxPct(int pct) {
        sp.edit().putInt("plan_max_pct", pct).apply();
    }

    static final String NAV_MAPS = "maps";
    static final String NAV_WAZE = "waze";

    /** Navegador para guiar desde la Ruta: NAV_MAPS o NAV_WAZE. */
    String navApp() {
        return NAV_WAZE.equals(sp.getString("nav_app", NAV_MAPS)) ? NAV_WAZE : NAV_MAPS;
    }

    void setNavApp(String app) {
        sp.edit().putString("nav_app", NAV_WAZE.equals(app) ? NAV_WAZE : NAV_MAPS).apply();
    }

    /** Avisar por voz si el plan de carga cambia durante el viaje. */
    boolean planVoice() {
        return sp.getBoolean("plan_voice", true);
    }

    void setPlanVoice(boolean on) {
        sp.edit().putBoolean("plan_voice", on).apply();
    }

    /** Últimos destinos elegidos en el coche (JSON, del más reciente al más antiguo). Solo en el móvil. */
    String recentPlaces() {
        return sp.getString("recent_places", "");
    }

    void setRecentPlaces(String json) {
        sp.edit().putString("recent_places", json).apply();
    }

    /** Marcadores de la pantalla Web del coche: {nombre, url}; los de serie si no se han cambiado. */
    static final String WEB_SHORTCUTS = "web_shortcuts";

    String[][] webShortcuts() {
        return webShortcutsFrom(sp.getString(WEB_SHORTCUTS, null));
    }

    void setWebShortcuts(String[][] list) {
        sp.edit().putString(WEB_SHORTCUTS, webShortcutsJson(list)).apply();
    }

    /** Puro: el JSON guardado ([["YouTube","https://…"],…]) → lista; null o roto → los de serie. Entradas sin URL, fuera. */
    static String[][] webShortcutsFrom(String json) {
        if (json == null) return WebScreen.DEFAULT_SHORTCUTS;
        try {
            org.json.JSONArray a = new org.json.JSONArray(json);
            java.util.List<String[]> out = new java.util.ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONArray e = a.optJSONArray(i);
                if (e == null || e.length() < 2) continue;
                String name = e.optString(0).trim();
                String url = e.optString(1).trim();
                if (url.isEmpty()) continue;
                if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://" + url;
                if (name.isEmpty()) name = url.replaceFirst("^https?://(www\\.)?", "").replaceFirst("/.*$", "");
                out.add(new String[]{name, url});
            }
            return out.toArray(new String[0][]);
        } catch (org.json.JSONException e) {
            return WebScreen.DEFAULT_SHORTCUTS;
        }
    }

    static String webShortcutsJson(String[][] list) {
        org.json.JSONArray a = new org.json.JSONArray();
        for (String[] e : list) {
            org.json.JSONArray x = new org.json.JSONArray();
            x.put(e[0]);
            x.put(e[1]);
            a.put(x);
        }
        return a.toString();
    }

    /** Gris del panel propio (0-80), ajustable para que se funda con las barras del coche. */
    int panelGray() {
        return sp.getInt(PANEL_GRAY, 30);
    }

    int panelColor() {
        int g = panelGray();
        return 0xFF000000 | (g << 16) | (g << 8) | Math.min(255, g + 2);
    }

    /** Avisos de radar por voz (RadarVoice): radar delante y exceso de velocidad junto a él. */
    static final String RADAR_VOICE = "radar_voice";

    boolean radarVoice() {
        return sp.getBoolean(RADAR_VOICE, true);
    }

    /** Panel del modo extendido a la derecha (AA pegado al conductor en coches con volante a la derecha, o a gusto). */
    static final String PANEL_RIGHT = "panel_right";

    boolean panelRight() {
        return sp.getBoolean(PANEL_RIGHT, false);
    }

    /** Transparencia del panel (0-100): cuanta más, más se funde con el negro de la pantalla. */
    static final String PANEL_ALPHA = "panel_alpha";

    int panelAlpha() {
        return sp.getInt(PANEL_ALPHA, 0);
    }

    void setPanelAlpha(int pct) {
        sp.edit().putInt(PANEL_ALPHA, Math.max(0, Math.min(100, pct))).apply();
    }

    /** Botones del panel que se ven, en su orden (entre «Auto», fijo arriba, y «Ajustes», fijo abajo). */
    static final String PANEL_BUTTONS = "panel_buttons";
    static final String[] PANEL_BUTTONS_ALL = {"car", "photos", "videos", "web", "tv", "radio", "games"};

    java.util.List<String> panelButtons() {
        String v = sp.getString(PANEL_BUTTONS, null);
        return panelButtonsFrom(v);
    }

    /** Puro: la lista guardada («car,web,radio»), o todos si no hay nada guardado; ignora lo que no exista. */
    static java.util.List<String> panelButtonsFrom(String csv) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (csv == null) {
            java.util.Collections.addAll(out, PANEL_BUTTONS_ALL);
            return out;
        }
        for (String id : csv.split(",")) {
            String t = id.trim();
            if (!t.isEmpty() && java.util.Arrays.asList(PANEL_BUTTONS_ALL).contains(t) && !out.contains(t)) out.add(t);
        }
        return out;
    }

    void setPanelButtons(java.util.List<String> ids) {
        sp.edit().putString(PANEL_BUTTONS, String.join(",", ids)).apply();
    }

    /** Aviso de versión nueva (UpdateCheck): cuándo se consultó GitHub y la última release recordada. */
    long updateCheckedAt() {
        return sp.getLong("update_checked_at", 0);
    }

    void setUpdateCheckedAt(long ms) {
        sp.edit().putLong("update_checked_at", ms).apply();
    }

    UpdateCheck.Release updateRelease() {
        String v = sp.getString("update_version", "");
        if (v == null || v.isEmpty()) return null;
        return new UpdateCheck.Release(v, sp.getString("update_url", UpdateCheck.RELEASES_WEB), sp.getString("update_notes", ""));
    }

    void setUpdateRelease(UpdateCheck.Release r) {
        sp.edit().putString("update_version", r.version).putString("update_url", r.url).putString("update_notes", r.notes).apply();
    }

    /** Último versionCode que arrancó (para «Novedades» al actualizar); 0 = nunca. */
    long seenVersionCode() {
        return sp.getLong("seen_version_code", 0);
    }

    void setSeenVersionCode(long code) {
        sp.edit().putLong("seen_version_code", code).apply();
    }

    /** Color fijo de la barra del modo extendido (CarTheme.PANEL_COLORS); 0 = automático (día y noche). */
    static final String PANEL_FIXED = "panel_fixed_color";

    int panelFixedColor() {
        return sp.getInt(PANEL_FIXED, 0);
    }

    void setPanelFixedColor(int color) {
        sp.edit().putInt(PANEL_FIXED, color).apply();
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
        // Por defecto, Auto a secas: en el coche (2026-10-06) Auto extendido dio peores sesiones (más carga y menos fps).
        return sp.getString(MODE, MODE_AA);
    }

    void setMode(String m) {
        sp.edit().putString(MODE, m).apply();
    }

    /** Conexión con el coche: LINK_HOTSPOT (por defecto en una instalación nueva), LINK_P2P o LINK_USB. */
    String linkMode() {
        return resolveLinkMode(sp.getString(LINK_MODE, null), setupDone());
    }

    /**
     * La conexión guardada si la hay. Sin guardar: DEFAULT_LINK en una instalación nueva; con la configuración inicial ya
     * hecha (una versión sin esta elección, que solo usaba Wi-Fi Direct), Wi-Fi Direct, para no cambiarle la conexión.
     */
    static String resolveLinkMode(String stored, boolean setupDone) {
        if (stored == null) return setupDone ? LINK_P2P : DEFAULT_LINK;
        if (LINK_HOTSPOT.equals(stored)) return LINK_HOTSPOT;
        return LINK_USB.equals(stored) ? LINK_USB : LINK_P2P;
    }

    void setLinkMode(String m) {
        sp.edit().putString(LINK_MODE, resolveLinkMode(m, true)).apply();
    }

    boolean isUsbMode() {
        return LINK_USB.equals(linkMode());
    }

    /** Trama del cable USB sobre el TCP del Wi-Fi (prueba con qdsim --usb-framing). */
    boolean qdUsbOverTcp() {
        return sp.getBoolean(QD_USB_OVER_TCP, false);
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

    /** Vídeo vivo tras perder al coche (por defecto 30 s; entre 5 s y 10 min). */
    long carGoneMs() {
        int v = sp.getInt(CAR_GONE_MS, 0);
        return v > 0 ? Math.max(5_000, Math.min(600_000, v)) : 30_000;
    }

    /** «Esperar al coche», en minutos (una de CAR_WAIT_CHOICES). */
    int carWaitMin() {
        return carWaitMinFor(sp.getInt(CAR_WAIT_MIN, 0));
    }

    long carWaitMs() {
        return carWaitMin() * 60_000L;
    }

    void setCarWaitMin(int min) {
        sp.edit().putInt(CAR_WAIT_MIN, carWaitMinFor(min)).apply();
    }

    /** Lo guardado si es una de las opciones; si no (o sin guardar), DEFAULT_CAR_WAIT_MIN. */
    static int carWaitMinFor(int stored) {
        for (int c : CAR_WAIT_CHOICES) {
            if (c == stored) return c;
        }
        return DEFAULT_CAR_WAIT_MIN;
    }

    /** Arranque manual del servidor de Android Auto (sin accesibilidad); por defecto, no (automático). */
    boolean aaServerManual() {
        return AA_SERVER_MANUAL.equals(aaServerStartFor(sp.getString(AA_SERVER_START, AA_SERVER_AUTO)));
    }

    void setAaServerManual(boolean manual) {
        sp.edit().putString(AA_SERVER_START, manual ? AA_SERVER_MANUAL : AA_SERVER_AUTO).apply();
    }

    /** Lo guardado si es una de las dos opciones; si no, el automático. */
    static String aaServerStartFor(String stored) {
        return AA_SERVER_MANUAL.equals(stored) ? AA_SERVER_MANUAL : AA_SERVER_AUTO;
    }

    boolean peerStrict() {
        return sp.getBoolean(PEER_STRICT, false);
    }

    boolean qdSupersede() {
        return sp.getBoolean(QD_SUPERSEDE, true);
    }

    boolean qdAckResend() {
        return sp.getBoolean(QD_ACK_RESEND, true);
    }

    boolean qdStablePort() {
        return sp.getBoolean(QD_STABLE_PORT, true);
    }

    boolean qdReclaim() {
        return sp.getBoolean(QD_RECLAIM, true);
    }

    boolean qdUdpRefresh() {
        return sp.getBoolean(QD_UDP_REFRESH, true);
    }

    boolean qdCarPing() {
        return sp.getBoolean(QD_CAR_PING, true);
    }

    /** Los ajustes de la vuelta del coche tras un corte, para el log. */
    String qdRecoverySummary() {
        return "reenvíoACK=" + qdAckResend() + " puertoEstable=" + qdStablePort() + " reacogida=" + qdReclaim()
                + " refrescoUDP=" + qdUdpRefresh() + " ping=" + qdCarPing();
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
        return "mode=" + mode() + " perfil=" + (isAa(mode()) ? videoProfile().id : "") + " fluidez=" + fluidity() + " reencode=" + aaReencode()
                + " brake=" + aaBrake() + " window=" + aaWindow() + " fps=" + sp.getInt(FPS, 0) + " kbps=" + sp.getInt(KBPS, 0)
                + " size=" + sp.getInt(WIDTH, 0) + "x" + sp.getInt(HEIGHT, 0) + " h264=" + profile() + " prepend=" + prependSpsPps()
                + " lowlat=" + lowLatency() + " maxclk=" + encMaxClocks() + " norepeat=" + getBool("enc_no_repeat")
                + " cbr=" + getBool("enc_cbr") + " vbr=" + getBool("enc_vbr") + " noir=" + getBool("enc_no_ir")
                + " linkfixed=" + getBool("link_fixed") + " gateoutq=" + getInt(GATE_OUTQ_KB) + " aadpi=" + aaDpi() + " dpi=" + dpi()
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
                + " fluidez=" + fluidity() + " mode=" + mode() + (MODE_APP.equals(mode()) ? " pkg=" + targetPackage() + " dpi=" + dpi() : "")
                + " link=" + linkMode() + " engine=" + linkEngine() + " pantallaEncendida=" + keepScreenOn() + " termica=" + thermalMode()
                + " esperarCoche=" + carWaitMin() + "min"
                + (isAa(mode()) ? " servidorAA=" + (aaServerManual() ? "manual" : "automático")
                + " androidAuto=" + AaVersions.readAndDescribe(app) : "")
                + (isQdEngine() ? " keepVideo=" + qdKeepVideo() + " supersede=" + qdSupersede() + " " + qdRecoverySummary()
                + " phoneInfo=" + qdPhoneInfo() + (qdUsbOverTcp() ? " tramaUsbPorWifi" : "")
                + " carGone=" + carGoneMs() / 1000 + "s" + (peerStrict() ? " strict" : "") : "")
                + " (0 = lo que pida el coche)";
    }
}
