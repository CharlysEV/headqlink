package com.headqlink.link;

import java.util.Locale;

/**
 * Tamaño de los P-frames del encoder propio («último frame», patrón y app). En el C10 (2026-10-07, S26, perfil Coche,
 * VBR, el enlace ya en 2,5 Mbit/s) salían P-frames de 250-270 KB: a 2,5 Mbit/s y 30 fps un P-frame medio son ~10 KB,
 * así que cada uno era una ráfaga de 25 veces lo normal que ocupaba casi un segundo de radio y coincidía con los
 * «Corte … RADIO» (TX atascado más de 400 ms). El VBR del c2.qti.avc.encoder los deja pasar; el bitrate solo no basta.
 *
 * Reglas (puro, lo prueban los tests con un reloj inyectado; thread-safe):
 * - **Tope** de un P-frame: max(MIN_CAP_BYTES, CAP_FRAMES × bitrate/fps), con el bitrate pedido al encoder (el del
 *   enlace o el térmico, sin la bajada de un IDR) y los fps que lleva la sesión. Con el enlace congestionado (el control
 *   del enlace vio congestión, o empezó un corte de radio, en los últimos CONGESTION_WINDOW_MS):
 *   max(MIN_CAP_BYTES, CONGESTED_CAP_FRAMES × bitrate/fps).
 * - **Subir**: un P-frame por encima del tope sube el QP mínimo de los P-frames (Android 12+, KEY_VIDEO_QP_P_MIN) en
 *   STEP, como mucho una vez cada RAISE_HOLD_MS y sin pasar de QP_CEIL. Desde «sin mínimo propio» (el suelo de
 *   configure, QP_FLOOR: VideoEncoder.setQpPMin nunca baja de él) la primera subida va a QP_BASE + STEP.
 *   Con el mínimo ya en QP_CEIL, cada P-frame por encima del tope (con la misma espera) baja
 *   el bitrate como en el plan B.
 * - **Bajar**: RELAX_AFTER_MS sin ningún P-frame por encima del tope (ni otro cambio) lo bajan 1; por debajo de
 *   QP_BASE + STEP vuelve a «sin mínimo propio» (el suelo de configure).
 *
 * Ojo (docs §23): el c2.qti.avc.encoder no parece hacer caso de los cambios de QP en marcha; lo que de verdad acota los
 * P-frames allí es el suelo QP_FLOOR al configurar. Este control sirve para los encoders que sí los respetan y, con el
 * plan B, baja el bitrate.
 * - **Plan B, bajada del bitrate** (como la del IDR): si el encoder no tiene las claves de QP (Android < 12 o las
 *   rechazó), si rechaza el QP nuevo en marcha, o si tras subir el mínimo INEFFECTIVE_QP_RISE o más los P-frames por
 *   encima del tope siguen siendo casi tan grandes como el primero (INEFFECTIVE_RATIO): desde entonces cada subida (o, sin
 *   claves, cada P-frame por encima del tope, con la misma espera) baja además el bitrate a DIP durante DIP_MS.
 *
 * No toca los IDR (IdrSizeController): aquí solo llegan los frames que no son clave, el QP de los I-frames no cambia
 * (VideoEncoder manda los dos rangos juntos en cada setParameters) y la bajada del bitrate se combina con la de un IDR
 * pedido (manda la mayor). El intra-refresh va en los P-frames: el tope de 6 frames medios le deja sitio de sobra.
 */
final class PFrameSizeController {
    /** Reloj en ms (SystemClock.elapsedRealtime en la app; uno falso en los tests). */
    interface Clock {
        long nowMs();
    }

    static final int MIN_CAP_BYTES = 24 * 1024;
    static final int CAP_FRAMES = 6;
    static final int CONGESTED_CAP_FRAMES = 3;
    static final long CONGESTION_WINDOW_MS = 2_000;
    /** Sin mínimo propio: el suelo de configure (QP_FLOOR). */
    static final int QP_NONE = 0;
    static final int QP_BASE = 24;
    /**
     * Suelo del QP de los P-frames al configurar el encoder (VideoEncoder.Params.qpPMin): el QP-I de partida, para que el
     * P-frame que sigue a un IDR no «afine» la imagen entera por debajo de la calidad del IDR (docs §23).
     */
    static final int QP_FLOOR = IdrSizeController.QP_START;
    static final int STEP = 2;
    static final int QP_CEIL = 40;
    /** QP máximo de los P-frames (el de H.264: sin tope). */
    static final int QP_MAX = 51;
    static final long RAISE_HOLD_MS = 500;
    static final long RELAX_AFTER_MS = 5_000;
    static final int INEFFECTIVE_QP_RISE = 8;
    static final double INEFFECTIVE_RATIO = 0.9;
    static final double DIP = 0.6;
    static final long DIP_MS = 1_000;

    private final Clock clock;
    private boolean qpKeys;
    /** Plan B activo: cada subida baja además el bitrate. */
    private boolean dipping;

    private int qpMin = QP_NONE;
    private long lastRaiseMs = Long.MIN_VALUE / 4;
    private long lastOversizeMs = Long.MIN_VALUE / 4;
    private long lastChangeMs = Long.MIN_VALUE / 4;
    private long lastCongestionMs = Long.MIN_VALUE / 4;
    /** Primer P-frame por encima del tope de la subida actual (desde «sin mínimo propio») y el QP con que empezó. */
    private int climbBytes;
    private int climbQp;

    // Estadísticas de la sesión (resumen) y de la ventana de 5 s.
    private long frames;
    private int maxBytes;
    private int overCap;
    private int raises;
    private int relaxes;
    private int dips;
    private int maxQp;
    private int winMaxBytes;
    private int winOverCap;
    private int winCap;

    PFrameSizeController(boolean qpKeys, Clock clock) {
        this.qpKeys = qpKeys;
        this.dipping = !qpKeys;
        this.clock = clock;
    }

    /** Lo que cambió con un P-frame (para aplicarlo y para el log). */
    static final class Step {
        final int bytes;
        final int cap;
        final boolean congested;
        final int qpBefore;
        final int qpAfter;
        /** Bajar el bitrate a este factor durante DIP_MS, o 0. */
        final double dip;
        final boolean qpKeys;
        /** Cambio de modo (el encoder no hace caso del QP…), o null. */
        final String note;

        Step(int bytes, int cap, boolean congested, int qpBefore, int qpAfter, double dip, boolean qpKeys, String note) {
            this.bytes = bytes;
            this.cap = cap;
            this.congested = congested;
            this.qpBefore = qpBefore;
            this.qpAfter = qpAfter;
            this.dip = dip;
            this.qpKeys = qpKeys;
            this.note = note;
        }

        boolean qpChanged() {
            return qpAfter != qpBefore;
        }

        /** «P-frames: 263 KB > tope 60 KB → QP-P mín 26», o la bajada tras RELAX_AFTER_MS limpios. */
        String line() {
            StringBuilder sb = new StringBuilder("P-frames: ");
            boolean up = qpAfter > qpBefore || dip > 0;
            if (up) {
                sb.append(IdrSizeController.kb(bytes)).append(" KB > tope ").append(IdrSizeController.kb(cap)).append(" KB");
                if (congested) sb.append(" (enlace congestionado)");
            } else {
                sb.append(RELAX_AFTER_MS / 1000).append(" s sin pasar del tope (").append(IdrSizeController.kb(cap)).append(" KB)");
            }
            if (qpChanged()) sb.append(" → ").append(qpText(qpAfter));
            if (dip > 0) {
                sb.append(qpChanged() ? " y " : " → ").append("bitrate al ").append(IdrSizeController.pct(dip)).append(" % ")
                        .append(DIP_MS).append(" ms");
            }
            if (note != null) sb.append(" · ").append(note);
            return sb.toString();
        }
    }

    static String qpText(int qp) {
        return qp == QP_NONE ? "QP-P en el suelo (" + QP_FLOOR + ")" : "QP-P mín " + qp;
    }

    /** Tope de un P-frame: max(MIN_CAP_BYTES, k × bitrate/fps) con k = CAP_FRAMES, o CONGESTED_CAP_FRAMES congestionado. */
    static int capFor(int bitrateBps, int fps, boolean congested) {
        long perFrame = (long) Math.max(0, bitrateBps) / 8 / Math.max(1, fps);
        long cap = perFrame * (congested ? CONGESTED_CAP_FRAMES : CAP_FRAMES);
        return (int) Math.max(MIN_CAP_BYTES, Math.min(Integer.MAX_VALUE, cap));
    }

    /** El enlace está congestionado ahora (el control del enlace lo vio, o empezó un corte de radio). */
    synchronized void onCongestion() {
        lastCongestionMs = clock.nowMs();
    }

    synchronized boolean congested() {
        return congestedAt(clock.nowMs());
    }

    private boolean congestedAt(long now) {
        return now - lastCongestionMs < CONGESTION_WINDOW_MS;
    }

    /**
     * Un P-frame de bytes (Annex-B) con el bitrate pedido al encoder y los fps de la sesión. Devuelve el cambio a aplicar
     * (QP-P nuevo y/o bajada del bitrate), o null si no cambia nada.
     */
    synchronized Step onPFrame(int bytes, int bitrateBps, int fps) {
        long now = clock.nowMs();
        frames++;
        if (bytes > maxBytes) maxBytes = bytes;
        if (bytes > winMaxBytes) winMaxBytes = bytes;
        boolean congested = congestedAt(now);
        int cap = capFor(bitrateBps, fps, congested);
        winCap = cap;
        int before = qpMin;
        if (bytes > cap) {
            overCap++;
            winOverCap++;
            lastOversizeMs = now;
            if (now - lastRaiseMs < RAISE_HOLD_MS) return null;
            String note = null;
            int after = qpMin;
            if (qpKeys && qpMin < QP_CEIL) {
                after = qpMin == QP_NONE ? QP_BASE + STEP : Math.min(QP_CEIL, qpMin + STEP);
                if (qpMin == QP_NONE) {
                    climbBytes = bytes;
                    climbQp = after;
                } else if (!dipping && after - climbQp >= INEFFECTIVE_QP_RISE
                        && bytes >= climbBytes * INEFFECTIVE_RATIO) {
                    dipping = true;
                    note = "con el QP-P mínimo " + (after - climbQp) + " más alto los P-frames siguen igual de grandes ("
                            + IdrSizeController.kb(bytes) + " KB frente a " + IdrSizeController.kb(climbBytes)
                            + " KB): el encoder no parece respetarlo; desde ahora cada subida baja además el bitrate";
                }
            }
            // Con el mínimo ya en el techo no queda más QP que subir: también la bajada del bitrate.
            double dip = dipping || before >= QP_CEIL ? DIP : 0;
            if (after == qpMin && dip == 0) return null;
            lastRaiseMs = now;
            lastChangeMs = now;
            if (after > qpMin) raises++;
            if (dip > 0) dips++;
            qpMin = after;
            if (qpMin > maxQp) maxQp = qpMin;
            return new Step(bytes, cap, congested, before, qpMin, dip, qpKeys, note);
        }
        if (qpMin != QP_NONE && now - lastOversizeMs >= RELAX_AFTER_MS && now - lastChangeMs >= RELAX_AFTER_MS) {
            qpMin = qpMin - 1 < QP_BASE + STEP ? QP_NONE : qpMin - 1;
            lastChangeMs = now;
            relaxes++;
            return new Step(bytes, cap, congested, before, qpMin, 0, qpKeys, null);
        }
        return null;
    }

    /**
     * El encoder rechazó el QP-P en marcha (setParameters): sin claves desde ahora, con la bajada del bitrate. Devuelve
     * la nota para el log.
     */
    synchronized String onQpRejected() {
        qpKeys = false;
        dipping = true;
        qpMin = QP_NONE;
        return "el encoder no aceptó el QP-P en marcha: desde ahora cada P-frame por encima del tope baja el bitrate al "
                + IdrSizeController.pct(DIP) + " % " + DIP_MS + " ms";
    }

    /** Sesión nueva con el coche: estadísticas a cero (el QP-P sigue: es del encoder, que sigue vivo). */
    synchronized void beginSession() {
        frames = 0;
        maxBytes = 0;
        overCap = 0;
        raises = 0;
        relaxes = 0;
        dips = 0;
        maxQp = qpMin;
        winMaxBytes = 0;
        winOverCap = 0;
        lastCongestionMs = Long.MIN_VALUE / 4;
    }

    synchronized int qpMin() {
        return qpMin;
    }

    synchronized boolean dipping() {
        return dipping;
    }

    /** P-frame más grande de la sesión (bytes). */
    synchronized int maxBytes() {
        return maxBytes;
    }

    /** P-frames por encima del tope en la sesión. */
    synchronized int overCap() {
        return overCap;
    }

    /** Para el log al arrancar. */
    synchronized String describe() {
        return "tamaño de los P-frames: tope max(" + MIN_CAP_BYTES / 1024 + " KB, " + CAP_FRAMES + " × bitrate/fps), "
                + CONGESTED_CAP_FRAMES + " × con el enlace congestionado · "
                + (qpKeys ? "QP-P con suelo " + QP_FLOOR + " al configurar y mínimo adaptable (+" + STEP + " por P-frame grande cada " + RAISE_HOLD_MS + " ms, desde "
                + (QP_BASE + STEP) + " hasta " + QP_CEIL + "; -1 tras " + RELAX_AFTER_MS / 1000 + " s limpios)"
                : "sin claves de QP (Android < 12): cada P-frame grande baja el bitrate al " + IdrSizeController.pct(DIP)
                + " % " + DIP_MS + " ms");
    }

    /** Línea de las estadísticas de 5 s, o null si no hubo nada que contar (ningún P-frame grande y sin mínimo propio). */
    synchronized String takeWindowLine() {
        if (winOverCap == 0 && qpMin == QP_NONE) {
            winMaxBytes = 0;
            return null;
        }
        String s = "P-frames: máx. " + IdrSizeController.kb(winMaxBytes) + " KB (tope " + IdrSizeController.kb(winCap)
                + " KB) · por encima del tope " + winOverCap + " · " + (qpKeys ? qpText(qpMin) : "sin QP-P")
                + (dipping && qpKeys ? " · con bajada del bitrate" : "");
        winMaxBytes = 0;
        winOverCap = 0;
        return s;
    }

    /** Al parar el vídeo y en el resumen de la sesión. */
    synchronized String summary() {
        return "P-frames " + frames + " · máx. " + IdrSizeController.kb(maxBytes) + " KB · por encima del tope " + overCap
                + " · " + (qpKeys ? qpText(qpMin) + " (máx. " + (maxQp == QP_NONE ? "—" : String.valueOf(maxQp))
                + ", subidas " + raises + ", bajadas " + relaxes + ")" : "sin QP-P")
                + (dips > 0 ? " · bitrate bajado " + dips : "");
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "PFrameSizeController(qp=%b, qpMin=%d, dip=%b)", qpKeys, qpMin, dipping);
    }
}
