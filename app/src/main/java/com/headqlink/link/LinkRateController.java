package com.headqlink.link;

import java.util.Locale;

/**
 * Bitrate (y fps) del encoder propio según el enlace con el coche (puro, lo prueban los tests). Para los perfiles de
 * bitrate fijo («Coche», con Fluidez 30 o 60; Medio; Muy bajo): el bitrate que pide el coche es el techo, pero la radio
 * entre la zona Wi-Fi del móvil y el coche no siempre lo lleva (viaje 5, 2026-10-05: cola del kernel de 80-130 KB,
 * 50-60 segmentos sin confirmar, rtt de 100-300 ms, 1 388 retransmisiones en 6 min y P-frames de 276 KB; la imagen
 * iba a saltos y un write() acabó bloqueado más de 10 s).
 *
 * Se alimenta cada ~100 ms con la muestra de NetStat (cola del kernel, sin confirmar, retransmisiones, rtt) y con la
 * puerta GL (esperas por el enlace en ese rato, vaciados de la cola por retraso). Reglas:
 * - **Congestión**: cola del kernel ≥ OUTQ_HIGH_BYTES (o la puerta GL cerrada por el enlace casi todo el rato) durante
 *   ≥ OUTQ_HIGH_MS seguidos; o las retransmisiones suben ≥ RETRANS_RISE en el último segundo; o el rtt pasa de
 *   RTT_FACTOR veces el mínimo de la sesión (y de RTT_MIN_MS); o el núcleo tiró frames por retraso (> 150 ms en cola).
 * - **Bajar**: con congestión, bitrate × STEP_DOWN (sin bajar de FLOOR_BPS), como mucho un paso cada STEP_HOLD_MS. Si
 *   ya está en el suelo y la congestión sigue, tope de LOW_FPS fps (si la sesión va por encima).
 * - **Subir**: tras RECOVER_MS seguidos sin congestión, primero vuelven los fps de la sesión; después, bitrate
 *   × STEP_UP cada RECOVER_MS limpios, hasta el techo.
 * - **Techo**: el bitrate de partida o, con calor, el tope térmico (el menor); bajarlo recorta el bitrate en el acto.
 * Cada paso devuelve un {@link Step} con el texto para el log. Thread-safe (lo usan hql-video y el resumen).
 */
final class LinkRateController {
    static final double STEP_DOWN = 0.7;
    static final double STEP_UP = 1.15;
    static final int FLOOR_BPS = 1_500_000;
    static final int OUTQ_HIGH_BYTES = 48 * 1024;
    static final long OUTQ_HIGH_MS = 300;
    /** Esperas por el enlace en una muestra de 100 ms que cuentan como cola alta (el relay GL reintenta cada 4 ms). */
    static final int LINK_WAITS_HIGH = 15;
    static final int RETRANS_RISE = 2;
    /** Las retransmisiones solo cuentan con cola del kernel ≥ esto o con el rtt disparado (síntomas de retardo). */
    static final int RETRANS_OUTQ_BYTES = OUTQ_HIGH_BYTES;
    static final long RETRANS_WINDOW_MS = 1_000;
    static final double RTT_FACTOR = 3.0;
    static final int RTT_MIN_MS = 80;
    /** El rtt alto tiene que durar esto (en el coche eran 100-300 ms sostenidos; en casa salen picos sueltos de 60 ms). */
    static final long RTT_HIGH_MS = 500;
    static final long STEP_HOLD_MS = 500;
    static final long RECOVER_MS = 5_000;
    static final int LOW_FPS = 24;
    /** Muestras de la ventana de retransmisiones (a 100 ms por muestra). */
    private static final int RETRANS_SAMPLES = (int) (RETRANS_WINDOW_MS / 100);

    /** Una muestra (cada ~100 ms). Las cifras de NetStat a -1 si no se pueden leer; las demás, acumuladas. */
    static final class Sample {
        final long nowMs;
        final int outq;
        final int unacked;
        final int retrans;
        final int rttMs;
        /** Esperas de la puerta GL por el enlace (cola del kernel) desde la muestra anterior. */
        final int linkWaits;
        /** Vaciados de la cola del núcleo por retraso (acumulado de la sesión). */
        final long lateFlushes;

        Sample(long nowMs, int outq, int unacked, int retrans, int rttMs, int linkWaits, long lateFlushes) {
            this.nowMs = nowMs;
            this.outq = outq;
            this.unacked = unacked;
            this.retrans = retrans;
            this.rttMs = rttMs;
            this.linkWaits = linkWaits;
            this.lateFlushes = lateFlushes;
        }

        /** Sin las esperas de la puerta ni los vaciados (los añade quien los conoce con {@link #with}). */
        Sample(long nowMs, int outq, int unacked, int retrans, int rttMs) {
            this(nowMs, outq, unacked, retrans, rttMs, 0, 0);
        }

        Sample with(int linkWaits, long lateFlushes) {
            return new Sample(nowMs, outq, unacked, retrans, rttMs, linkWaits, lateFlushes);
        }
    }

    /** Un cambio de bitrate o de fps, con su explicación («congestión (outq 96 KB, retrans +21) → bitrate 3.5 Mbit/s»). */
    static final class Step {
        final int bitrateBefore;
        final int bitrateAfter;
        final int fpsBefore;
        final int fpsAfter;
        final boolean congestion;
        final String text;

        Step(int bitrateBefore, int bitrateAfter, int fpsBefore, int fpsAfter, boolean congestion, String text) {
            this.bitrateBefore = bitrateBefore;
            this.bitrateAfter = bitrateAfter;
            this.fpsBefore = fpsBefore;
            this.fpsAfter = fpsAfter;
            this.congestion = congestion;
            this.text = text;
        }

        boolean bitrateChanged() {
            return bitrateAfter != bitrateBefore;
        }

        boolean fpsChanged() {
            return fpsAfter != fpsBefore;
        }

        @Override
        public String toString() {
            return text;
        }
    }

    private final int profileBps;
    private final int sessionFps;
    private int ceiling;
    private int bitrate;
    private int fpsCap;

    /** Instantes (ms); -1 = ninguno. */
    private long outqHighSinceMs = -1;
    private long cleanSinceMs = -1;
    private long rttHighSinceMs = -1;
    private long lastStepMs = -1;
    private int baselineRttMs = -1;
    private long lastFlushes;
    private final int[] retransRing = new int[RETRANS_SAMPLES];
    private int retransCount;
    private boolean floorNoted;

    // Estadísticas de la sesión (resumen).
    private int minBitrate;
    private int congestionEvents;
    private int stepsDown;
    private int stepsUp;

    /** profileBps: bitrate de partida (el que pide el coche); sessionFps: los de la sesión (30 o 60). */
    LinkRateController(int profileBps, int sessionFps) {
        this.profileBps = Math.max(FLOOR_BPS, profileBps);
        this.sessionFps = Math.max(1, sessionFps);
        this.ceiling = this.profileBps;
        beginSession();
    }

    /** Sesión nueva con el coche: se empieza en el techo, con los fps de la sesión y las estadísticas a cero. */
    synchronized void beginSession() {
        bitrate = ceiling;
        fpsCap = sessionFps;
        minBitrate = bitrate;
        congestionEvents = 0;
        stepsDown = 0;
        stepsUp = 0;
        outqHighSinceMs = -1;
        cleanSinceMs = -1;
        lastStepMs = -1;
        baselineRttMs = -1;
        lastFlushes = -1;
        retransCount = 0;
        rttHighSinceMs = -1;
        floorNoted = false;
    }

    /**
     * Techo nuevo (tope térmico, o el del perfil al enfriarse): nunca por encima del perfil. Si el bitrate actual lo
     * pasa, se recorta en el acto; devuelve true si cambió.
     */
    synchronized boolean setCeiling(int bps) {
        ceiling = Math.max(floor(), Math.min(profileBps, bps));
        if (bitrate > ceiling) {
            bitrate = ceiling;
            noteBitrate();
            return true;
        }
        return false;
    }

    private int floor() {
        return Math.min(FLOOR_BPS, profileBps);
    }

    /** Una muestra: el paso a aplicar (bitrate o fps nuevos), o null si no cambia nada. */
    synchronized Step onSample(Sample s) {
        long now = s.nowMs;
        StringBuilder why = new StringBuilder();

        // Cola del kernel alta (o la puerta GL cerrada por el enlace casi todo el rato) durante OUTQ_HIGH_MS.
        boolean high = s.outq >= OUTQ_HIGH_BYTES || s.linkWaits >= LINK_WAITS_HIGH;
        if (high) {
            if (outqHighSinceMs < 0) outqHighSinceMs = now;
        } else {
            outqHighSinceMs = -1;
        }
        if (high && now - outqHighSinceMs >= OUTQ_HIGH_MS) {
            add(why, s.outq >= 0 ? "outq " + kb(s.outq) + " " + (now - outqHighSinceMs) + " ms" : "enlace cerrado " + (now - outqHighSinceMs) + " ms");
        }

        // Retransmisiones en el último segundo.
        if (s.retrans >= 0) {
            int oldest = retransCount >= RETRANS_SAMPLES ? retransRing[retransCount % RETRANS_SAMPLES] : (retransCount > 0 ? retransRing[0] : s.retrans);
            retransRing[retransCount % RETRANS_SAMPLES] = s.retrans;
            retransCount++;
            int rise = s.retrans - oldest;
            // Unas pocas retransmisiones por segundo son normales en Wi-Fi (en casa, con outq 0 y rtt 5 ms, salían
            // +2..+5 y el controlador se iba al suelo). Solo cuentan si además hay cola, datos sin confirmar o rtt alto.
            // (Los segmentos sin confirmar no valen: con 5 Mbit/s hay 10-12 en vuelo con rtt de 10 ms.)
            boolean corroborated = s.outq >= RETRANS_OUTQ_BYTES;
            if (rise >= RETRANS_RISE && corroborated) add(why, "retrans +" + rise);
        }

        // rtt frente al mínimo de la sesión.
        if (s.rttMs > 0) {
            if (baselineRttMs < 0 || s.rttMs < baselineRttMs) baselineRttMs = s.rttMs;
            boolean rttHigh = s.rttMs >= RTT_MIN_MS && s.rttMs > RTT_FACTOR * baselineRttMs;
            if (rttHigh) {
                if (rttHighSinceMs < 0) rttHighSinceMs = now;
                if (now - rttHighSinceMs >= RTT_HIGH_MS) add(why, "rtt " + s.rttMs + " ms (mín. " + baselineRttMs + ") " + (now - rttHighSinceMs) + " ms");
            } else {
                rttHighSinceMs = -1;
            }
        }

        // Frames tirados por retraso (> 150 ms en la cola del núcleo).
        if (lastFlushes >= 0 && s.lateFlushes > lastFlushes) add(why, "vaciados por retraso +" + (s.lateFlushes - lastFlushes));
        lastFlushes = s.lateFlushes;

        boolean congested = why.length() > 0;
        if (congested) {
            cleanSinceMs = -1;
            if (lastStepMs >= 0 && now - lastStepMs < STEP_HOLD_MS) return null;
            int before = bitrate;
            int floor = floor();
            if (bitrate > floor) {
                lastStepMs = now;
                congestionEvents++;
                stepsDown++;
                bitrate = Math.max(floor, (int) Math.round(bitrate * STEP_DOWN));
                noteBitrate();
                return new Step(before, bitrate, fpsCap, fpsCap, true,
                        "congestión (" + why + ") → bitrate " + mbit(bitrate) + (bitrate == floor ? " (suelo)" : ""));
            }
            if (fpsCap > LOW_FPS) {
                lastStepMs = now;
                congestionEvents++;
                int fpsBefore = fpsCap;
                fpsCap = LOW_FPS;
                return new Step(before, bitrate, fpsBefore, fpsCap, true,
                        "congestión (" + why + ") con el bitrate en el suelo (" + mbit(bitrate) + ") → " + LOW_FPS + " fps");
            }
            if (!floorNoted) {
                floorNoted = true;
                lastStepMs = now;
                congestionEvents++;
                return new Step(before, bitrate, fpsCap, fpsCap, true,
                        "congestión (" + why + ") con el bitrate en el suelo (" + mbit(bitrate) + ") y " + fpsCap
                                + " fps: no hay más que bajar");
            }
            return null;
        }

        // Enlace limpio: tras RECOVER_MS seguidos, primero los fps y luego el bitrate (un paso por periodo).
        if (cleanSinceMs < 0) {
            cleanSinceMs = now;
            return null;
        }
        if (now - cleanSinceMs < RECOVER_MS) return null;
        cleanSinceMs = now;
        floorNoted = false;
        if (fpsCap < sessionFps) {
            int fpsBefore = fpsCap;
            fpsCap = sessionFps;
            return new Step(bitrate, bitrate, fpsBefore, fpsCap, false,
                    "enlace limpio " + RECOVER_MS / 1000 + " s → " + fpsCap + " fps (bitrate " + mbit(bitrate) + ")");
        }
        if (bitrate < ceiling) {
            int before = bitrate;
            stepsUp++;
            bitrate = Math.min(ceiling, (int) Math.round(bitrate * STEP_UP));
            return new Step(before, bitrate, fpsCap, fpsCap, false,
                    "enlace limpio " + RECOVER_MS / 1000 + " s → bitrate " + mbit(bitrate) + (bitrate == ceiling ? " (techo)" : ""));
        }
        return null;
    }

    private void noteBitrate() {
        if (bitrate < minBitrate) minBitrate = bitrate;
    }

    private static void add(StringBuilder sb, String s) {
        if (sb.length() > 0) sb.append(", ");
        sb.append(s);
    }

    synchronized int bitrate() {
        return bitrate;
    }

    synchronized int ceiling() {
        return ceiling;
    }

    synchronized int fpsCap() {
        return fpsCap;
    }

    int sessionFps() {
        return sessionFps;
    }

    /** Bitrate más bajo aplicado en la sesión. */
    synchronized int minBitrate() {
        return minBitrate;
    }

    /** Pasos por congestión (bajadas de bitrate o de fps) en la sesión. */
    synchronized int congestionEvents() {
        return congestionEvents;
    }

    /** Hay algo que contar en las estadísticas de 5 s (bitrate por debajo del techo, fps bajados o congestiones). */
    synchronized boolean active() {
        return bitrate < ceiling || fpsCap < sessionFps || congestionEvents > 0;
    }

    /** Para el log al arrancar. */
    String describe() {
        return String.format(Locale.US, "bitrate adaptable al enlace %s-%s (baja ×%.1f con congestión, sube %d %% cada %d s limpio; %d fps si en el suelo sigue)",
                mbit(floor()), mbit(profileBps), STEP_DOWN, Math.round((STEP_UP - 1) * 100), RECOVER_MS / 1000, LOW_FPS);
    }

    /** Línea de las estadísticas de 5 s. */
    synchronized String statsLine() {
        return "enlace: bitrate " + mbit(bitrate) + " (mín. " + mbit(minBitrate) + ", techo " + mbit(ceiling) + ")"
                + (fpsCap < sessionFps ? " · " + fpsCap + " fps" : "") + " · congestiones " + congestionEvents
                + " (bajadas " + stepsDown + ", subidas " + stepsUp + ")";
    }

    static String mbit(int bps) {
        return String.format(Locale.US, "%.1f Mbit/s", bps / 1e6);
    }

    private static String kb(int bytes) {
        return bytes < 1024 ? bytes + " B" : (bytes / 1024) + " KB";
    }
}
