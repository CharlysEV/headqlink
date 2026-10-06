package com.headqlink.link;

import java.util.Locale;

/**
 * Lo que enseñan el widget de la pantalla de inicio y el botón de los ajustes rápidos, sacado del estado del enlace
 * ({@link LinkState}) y de los ajustes: el color, qué hace el botón grande, el estado con su detalle, la conexión que
 * se marca y el modo. Puro (sin Android): lo prueban los tests. Los textos los pone {@link LinkWidgetViews} con los
 * recursos del idioma de la app.
 *
 * <pre>
 * parado ─────────────────────────────▶ gris  «Apagado»                    Conectar
 * preparando (servidor de Android Auto) ▶ ámbar «Preparando…»              Conectar (se ignora otro toque)
 * en marcha, sin coche ────────────────▶ ámbar «Buscando el coche…»        Desconectar
 * coche anunciado / accesorio abierto ─▶ ámbar «Coche encontrado…»
 * sesión sin imagen todavía ───────────▶ ámbar «Coche conectado»
 * sesión con imagen ───────────────────▶ verde «Coche conectado» · 30 fps · 4,8 Mbit/s
 * coche perdido (AA vivo o en pausa) ──▶ ámbar «Esperando a que vuelva el coche»
 * arranque manual, AA no atiende ──────▶ ámbar «Esperando al servidor de Android Auto» (con o sin coche)
 * un problema (puerto, red, Auto) ─────▶ rojo
 * </pre>
 */
final class LinkGlance {
    /** Color del botón y del estado: gris apagado, ámbar en curso, verde con imagen en el coche, rojo con un problema. */
    enum Look { OFF, BUSY, LIVE, ERROR }

    /** El botón grande: Conectar (parado) o Desconectar (en marcha). */
    enum Action { CONNECT, DISCONNECT }

    /** El estado principal (el título). */
    enum Status {
        /** «Apagado». */
        OFF,
        /** «Preparando…»: Conectar arranca el servidor de Android Auto antes del servicio. */
        PREPARING,
        /** «Buscando el coche…». */
        SEARCHING,
        /** «Coche encontrado, conectando…» (anuncio por Wi-Fi o accesorio USB abierto). */
        FOUND,
        /** «Coche conectado», sin imagen todavía. */
        CONNECTED,
        /** «Coche conectado» con imagen: fps y Mbit/s. */
        LIVE,
        /** «Esperando a que vuelva el coche»: sesión perdida, Android Auto vivo o en pausa. */
        WAITING_RETURN,
        /** Arranque manual con los intentos fallando: «Esperando al servidor de Android Auto». */
        AA_SERVER,
        /** El UDP 18463 lo tiene otra app (QDLink abierto). */
        PORT_BUSY,
        /** Problema de la conexión con el coche (detalle: el texto de la fila «Red»). */
        NETWORK_PROBLEM,
        /** Problema de Android Auto o de la app (detalle: el texto de la fila «Auto»). */
        SOURCE_PROBLEM,
    }

    /** Lo que va debajo del título. */
    enum Detail {
        NONE,
        /** «Toca para conectar». */
        TAP_TO_CONNECT,
        /** «Arrancando Auto». */
        STARTING_AA,
        /** El texto de la fila «Red» ({@link #text}). */
        NETWORK,
        /** El texto de la fila «Auto» ({@link #text}). */
        SOURCE,
        /** fps y Mbit/s ({@link #fps}, {@link #mbps}). */
        VIDEO,
        /** «Esperando la imagen…». */
        WAITING_VIDEO,
        /** «Android Auto en pausa». */
        AA_PAUSED,
        /** «El puerto 18463 está ocupado». */
        PORT_BUSY,
        /** Arranque manual: cómo reiniciar el servidor («Android Auto › ⋮ › Parar e Iniciar servidor»). */
        AA_SERVER_HINT,
    }

    /**
     * Versión del widget según su tamaño: 2x2 (botón, estado e icono de la conexión), bajo o no; 4x2 bajo (sin la cabecera
     * con la marca y el modo) o 4x2.
     */
    enum Size {
        /** 2x2 muy bajo: el título en una línea y sin detalle, para que quepa el botón. */
        COMPACT_SHORT,
        COMPACT,
        /** 4x2 bajo: sin la cabecera (marca y modo) ni la etiqueta del botón. */
        WIDE_SHORT,
        WIDE;

        boolean compact() {
            return this == COMPACT || this == COMPACT_SHORT;
        }
    }

    /** Por debajo de este ancho (dp), la versión 2x2: las tres conexiones del selector necesitan ~260 dp. */
    static final int WIDE_MIN_DP = 260;
    /**
     * Desde este alto (dp), la versión completa: 4x2 con la cabecera y 2x2 con el detalle (con un título largo en dos
     * líneas y el detalle en dos, más bajo no caben). Un 4x2 de un móvil suele tener 180-220 dp.
     */
    static final int TALL_MIN_DP = 175;
    /** Las cifras del vídeo llegan cada 5 s: el widget no se repinta por ellas más a menudo que esto. */
    static final long VIDEO_EVERY_MS = 4_500;

    /** Orden de la conexión al tocar el icono de la versión 2x2. */
    private static final String[] LINK_ORDER = {Config.LINK_HOTSPOT, Config.LINK_P2P, Config.LINK_USB};

    /** Foto del estado: lo que hay en LinkState y en los ajustes. */
    static final class Input {
        boolean running;
        boolean preparing;
        LinkState.Car car = LinkState.Car.OFF;
        /** LinkState.video: «30 fps · 4,8 Mbps» cada 5 s con imagen; vacío sin ella. */
        String video = "";
        LinkState.Level networkLevel = LinkState.Level.IDLE;
        String network = "";
        LinkState.Level sourceLevel = LinkState.Level.IDLE;
        String source = "";
        boolean udpBusy;
        /** Arranque manual del servidor de Android Auto y los intentos fallan (AA no atiende; nunca por un sondeo). */
        boolean aaServerWaiting;
        /** Android Auto aparcado (en pausa, esperando al coche). */
        boolean aaParked;
        /** Conexión elegida (Config.LINK_*). */
        String linkMode = Config.DEFAULT_LINK;
        /** Conexión con la que va el servicio ("" parado) y si el cable USB va por delante de la elegida. */
        String activeLinkMode = "";
        boolean usbOverride;
        /** Modo (Config.MODE_*). */
        String mode = Config.MODE_AA;
        /** Idioma de la app (etiqueta BCP 47): formato de las cifras; si cambia, se repinta. */
        String locale = "";
    }

    final Look look;
    final Action action;
    final Status status;
    final Detail detail;
    /** Texto de la fila «Red» o «Auto» para {@link Detail#NETWORK} y {@link Detail#SOURCE}; "" si no. */
    final String text;
    /** fps y Mbit/s del vídeo al coche (NaN sin imagen). */
    final float fps;
    final float mbps;
    /** La conexión elegida: la que se marca en el selector. */
    final String linkMode;
    /** La conexión que va de verdad: la del servicio en marcha (el cable si va por delante) o, parado, la elegida. */
    final String transport;
    final String mode;
    final String locale;

    private LinkGlance(Look look, Action action, Status status, Detail detail, String text, float fps, float mbps,
                       Input in) {
        this.look = look;
        this.action = action;
        this.status = status;
        this.detail = detail;
        this.text = text == null ? "" : text;
        this.fps = fps;
        this.mbps = mbps;
        this.linkMode = Config.resolveLinkMode(in.linkMode, true);
        String active = in.activeLinkMode == null ? "" : in.activeLinkMode;
        this.transport = in.running && !active.isEmpty() ? active : this.linkMode;
        this.mode = in.mode == null ? Config.MODE_AA : in.mode;
        this.locale = in.locale == null ? "" : in.locale;
    }

    private static LinkGlance make(Look look, Action action, Status status, Detail detail, String text, Input in) {
        return new LinkGlance(look, action, status, detail, text, Float.NaN, Float.NaN, in);
    }

    /** Del estado a lo que se enseña. */
    static LinkGlance of(Input in) {
        if (!in.running) {
            if (in.preparing) return make(Look.BUSY, Action.CONNECT, Status.PREPARING, Detail.STARTING_AA, "", in);
            return make(Look.OFF, Action.CONNECT, Status.OFF, Detail.TAP_TO_CONNECT, "", in);
        }
        // En marcha: el botón desconecta. Primero, lo que impide ver el coche.
        Action stop = Action.DISCONNECT;
        boolean connected = in.car == LinkState.Car.CONNECTED;
        if (in.sourceLevel == LinkState.Level.ERROR) {
            return make(Look.ERROR, stop, Status.SOURCE_PROBLEM, Detail.SOURCE, in.source, in);
        }
        // Arranque manual y AA no atiende los intentos: lo único que lo arregla es reiniciar su servidor. Va por delante de
        // la sesión con el coche (sin AA, lo que llega al coche es la animación de espera, no la imagen).
        if (in.aaServerWaiting) return make(Look.BUSY, stop, Status.AA_SERVER, Detail.AA_SERVER_HINT, "", in);
        if (!connected && in.udpBusy) {
            return make(Look.ERROR, stop, Status.PORT_BUSY, Detail.PORT_BUSY, "", in);
        }
        if (!connected && in.networkLevel == LinkState.Level.ERROR) {
            return make(Look.ERROR, stop, Status.NETWORK_PROBLEM, Detail.NETWORK, in.network, in);
        }
        switch (in.car) {
            case CONNECTED: {
                float[] v = Meters.parse(in.video);
                if (v != null) return new LinkGlance(Look.LIVE, stop, Status.LIVE, Detail.VIDEO, "", v[0], v[1], in);
                boolean sourceNews = in.sourceLevel == LinkState.Level.BUSY && !empty(in.source);
                return make(Look.BUSY, stop, Status.CONNECTED, sourceNews ? Detail.SOURCE : Detail.WAITING_VIDEO,
                        sourceNews ? in.source : "", in);
            }
            case SEEN:
                return make(Look.BUSY, stop, Status.FOUND, empty(in.network) ? Detail.NONE : Detail.NETWORK, in.network, in);
            case RECONNECTING:
                return make(Look.BUSY, stop, Status.WAITING_RETURN, in.aaParked ? Detail.AA_PAUSED : Detail.NONE, "", in);
            default:
                return make(Look.BUSY, stop, Status.SEARCHING, empty(in.network) ? Detail.NONE : Detail.NETWORK,
                        in.network, in);
        }
    }

    /** El botón de los ajustes rápidos va «activo» con el enlace en marcha. */
    boolean active() {
        return action == Action.DISCONNECT;
    }

    /** «30 fps · 4,8 Mbit/s» con el formato de números del idioma. */
    static String videoText(float fps, float mbps, Locale locale) {
        return String.format(locale, "%.0f fps · %.1f Mbit/s", fps, mbps);
    }

    /** Las cifras de esta foto, con su idioma («» sin imagen). */
    String videoText() {
        if (Float.isNaN(fps) || Float.isNaN(mbps)) return "";
        return videoText(fps, mbps, locale.isEmpty() ? Locale.getDefault() : Locale.forLanguageTag(locale));
    }

    /** La conexión siguiente al tocar el icono de la versión 2x2: zona Wi-Fi → Wi-Fi Direct → cable USB → zona Wi-Fi. */
    static String nextLink(String linkMode) {
        String cur = Config.resolveLinkMode(linkMode, true);
        for (int i = 0; i < LINK_ORDER.length; i++) {
            if (LINK_ORDER[i].equals(cur)) return LINK_ORDER[(i + 1) % LINK_ORDER.length];
        }
        return LINK_ORDER[0];
    }

    /** Qué versión del widget cabe en widthDp × heightDp. */
    static Size sizeFor(int widthDp, int heightDp) {
        boolean tall = heightDp >= TALL_MIN_DP;
        if (widthDp < WIDE_MIN_DP) return tall ? Size.COMPACT : Size.COMPACT_SHORT;
        return tall ? Size.WIDE : Size.WIDE_SHORT;
    }

    /** Todo lo que se ve: si no cambia, no hay que repintar. */
    String signature() {
        return stateKey() + "|" + fps + "|" + mbps;
    }

    /** Igual salvo las cifras del vídeo. */
    boolean sameButVideo(LinkGlance o) {
        return o != null && stateKey().equals(o.stateKey());
    }

    /** Lo que se ve salvo las cifras del vídeo (cambia con el estado, la conexión, el modo o el idioma). */
    String stateKey() {
        return look + "|" + action + "|" + status + "|" + detail + "|" + text + "|" + linkMode + "|" + transport + "|"
                + mode + "|" + locale;
    }

    /**
     * Cuándo mandar {@code next} al widget si lo último que se mandó fue {@code last} en {@code lastMs}: -1 si no hay
     * nada nuevo, 0 ya, o cuánto esperar (ms) cuando solo cambian las cifras del vídeo (como mucho una vez cada
     * {@link #VIDEO_EVERY_MS}). Los cambios de estado salen al momento.
     */
    static long pushDelay(LinkGlance last, long lastMs, LinkGlance next, long nowMs) {
        if (last == null) return 0;
        if (next.signature().equals(last.signature())) return -1;
        if (!next.sameButVideo(last)) return 0;
        long wait = lastMs + VIDEO_EVERY_MS - nowMs;
        return wait > 0 ? wait : 0;
    }

    private static boolean empty(String s) {
        return s == null || s.isEmpty();
    }

    @Override
    public String toString() {
        return signature();
    }
}
