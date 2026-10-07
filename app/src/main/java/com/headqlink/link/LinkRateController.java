package com.headqlink.link;

import java.util.Locale;

/**
 * Bitrate (y fps) del encoder propio según el enlace con el coche (puro, lo prueban los tests). Para los perfiles de
 * bitrate fijo («Coche», con Fluidez 30 o 60; Medio; Muy bajo): el bitrate que pide el coche es el techo, pero la radio
 * entre la zona Wi-Fi del móvil y el coche no siempre lo lleva (viaje 5, 2026-10-05: cola del kernel de 80-130 KB,
 * 50-60 segmentos sin confirmar, rtt de 100-300 ms, 1 388 retransmisiones en 6 min y P-frames de 276 KB; la imagen
 * iba a saltos y un write() acabó bloqueado más de 10 s).
 *
 * Viaje 6 (2026-10-06): la primera versión bajaba por retransmisiones sueltas (normales en Wi-Fi, coincidiendo con un
 * IDR en la cola) y subía despacio: la sesión «Coche» se quedó en 1,7-2,5 Mbit/s con la imagen pixelada, cuando en el
 * mismo enlace el perfil Alto llevó 4,3-4,8 Mbit/s sin perder un frame. Ahora solo cuenta el retardo (cola sostenida,
 * rtt sostenido, frames tirados por retraso), el suelo es la mitad del bitrate del coche y la subida es rápida.
 *
 * Se alimenta cada ~100 ms con la muestra de NetStat (cola del kernel, sin confirmar, retransmisiones, rtt) y con la
 * puerta GL (esperas por el enlace en ese rato, vaciados de la cola por retraso). Reglas:
 * - **Congestión**: cola del kernel ≥ OUTQ_HIGH_BYTES (o la puerta GL cerrada por el enlace casi todo el rato) durante
 *   ≥ OUTQ_HIGH_MS seguidos; o el rtt pasa de RTT_FACTOR veces el mínimo de la sesión (y de RTT_MIN_MS) durante
 *   ≥ RTT_HIGH_MS; o el núcleo tiró frames por retraso (> 150 ms en cola). Las retransmisiones solas no cuentan.
 * - **Bajar**: con congestión, bitrate × STEP_DOWN (sin bajar del suelo: FLOOR_FRACTION del bitrate de partida, y nunca
 *   menos de FLOOR_BPS), como mucho un paso cada STEP_HOLD_MS. Si
 *   ya está en el suelo y la congestión sigue, tope de LOW_FPS fps (si la sesión va por encima).
 * - **Subir**: tras RECOVER_MS seguidos sin congestión, primero vuelven los fps de la sesión; después, bitrate
 *   × STEP_UP cada RECOVER_MS limpios, hasta el techo.
 * - **Techo**: el bitrate de partida o, con calor, el tope térmico (el menor); bajarlo recorta el bitrate en el acto.
 * - **Emergencia** (viaje del 2026-10-07: 127 cortes de radio en 22 min; S26 en el suelo de 2,54 Mbit/s y a 24 fps
 *   con 45 cortes y 10,5 fps): si ya está en el suelo normal y a LOW_FPS y la congestión sigue EMERGENCY_AFTER_MS (con
 *   al menos EMERGENCY_SAMPLES muestras congestionadas separadas STEP_HOLD_MS), se baja del suelo normal hasta
 *   EMERGENCY_FLOOR_BPS y después a EMERGENCY_FPS. Una vez: se rearma tras EMERGENCY_REARM_MS limpios con el bitrate en
 *   el suelo normal o por encima (y en cada sesión nueva). En un enlace bueno nunca se llega: desde el techo hacen falta
 *   unos 5,5 s de congestión sostenida.
 * - **Cortes de radio**: el inicio de un corte del detector (TX atascado más de 400 ms, `StallDetector` RADIO) llega
 *   en la muestra ({@link Sample#radioCut}) y cuenta como congestión en el acto.
 * - **Sin cola del kernel** (cable USB: no hay socket ni NetStat, outq y rtt a -1): la «cola alta» es el vídeo más viejo
 *   aún sin salir de la cola de la sesión (o escribiéndose) con ≥ QUEUE_LAG_HIGH_MS durante OUTQ_HIGH_MS; además siguen
 *   los vaciados por retraso. Con NetStat, el retraso de la cola no cuenta (ya lo dicen la cola del kernel y el rtt).
 * Cada paso devuelve un {@link Step} con el texto para el log. Thread-safe (lo usan hql-video y el resumen).
 */
final class LinkRateController {
    static final double STEP_DOWN = 0.75;
    static final double STEP_UP = 1.25;
    static final int FLOOR_BPS = 1_500_000;
    /** Suelo relativo: por debajo de la mitad del bitrate del coche la imagen se pixela (1920x882 a 2 Mbit/s). */
    static final double FLOOR_FRACTION = 0.5;
    static final int OUTQ_HIGH_BYTES = 48 * 1024;
    /** Un IDR de 100-150 KB tarda unos 250 ms en salir: la cola alta tiene que durar más que eso. */
    static final long OUTQ_HIGH_MS = 500;
    /** Esperas por el enlace en una muestra de 100 ms que cuentan como cola alta (el relay GL reintenta cada 4 ms). */
    static final int LINK_WAITS_HIGH = 15;
    static final double RTT_FACTOR = 3.0;
    static final int RTT_MIN_MS = 80;
    /** El rtt alto tiene que durar esto (en el coche eran 100-300 ms sostenidos; en casa salen picos sueltos de 60 ms). */
    static final long RTT_HIGH_MS = 500;
    static final long STEP_HOLD_MS = 500;
    /**
     * Sin NetStat (USB): retraso del vídeo en la cola de la sesión que cuenta como cola alta. Dos frames a 30 fps; el
     * núcleo tira los P-frames de más de 150 ms (MAX_LAG), y eso ya cuenta aparte como vaciado.
     */
    static final long QUEUE_LAG_HIGH_MS = 66;
    static final long RECOVER_MS = 3_000;
    static final int LOW_FPS = 24;
    /** Suelo de emergencia: por debajo del normal, solo con la radio saturada en el suelo (se pixela, pero se mueve). */
    static final int EMERGENCY_FLOOR_BPS = 1_200_000;
    static final int EMERGENCY_FPS = 20;
    /** Congestión sostenida en el suelo normal y a LOW_FPS antes de bajar de él. */
    static final long EMERGENCY_AFTER_MS = 3_000;
    /** Y al menos estas muestras congestionadas (separadas STEP_HOLD_MS) en ese rato: no basta un golpe suelto. */
    static final int EMERGENCY_SAMPLES = 3;
    /** Limpio este rato con el bitrate en el suelo normal o por encima: la emergencia se puede volver a usar. */
    static final long EMERGENCY_REARM_MS = 30_000;

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
        /** Antigüedad del vídeo más viejo aún sin salir de la cola de la sesión (ms; -1 = no se sabe). */
        final long queueLagMs;
        /** En esta muestra empezó un corte de radio (StallDetector, RADIO): congestión en el acto. */
        final boolean radioCut;

        Sample(long nowMs, int outq, int unacked, int retrans, int rttMs, int linkWaits, long lateFlushes) {
            this(nowMs, outq, unacked, retrans, rttMs, linkWaits, lateFlushes, -1);
        }

        Sample(long nowMs, int outq, int unacked, int retrans, int rttMs, int linkWaits, long lateFlushes, long queueLagMs) {
            this(nowMs, outq, unacked, retrans, rttMs, linkWaits, lateFlushes, queueLagMs, false);
        }

        Sample(long nowMs, int outq, int unacked, int retrans, int rttMs, int linkWaits, long lateFlushes, long queueLagMs,
                boolean radioCut) {
            this.nowMs = nowMs;
            this.outq = outq;
            this.unacked = unacked;
            this.retrans = retrans;
            this.rttMs = rttMs;
            this.linkWaits = linkWaits;
            this.lateFlushes = lateFlushes;
            this.queueLagMs = queueLagMs;
            this.radioCut = radioCut;
        }

        /** Sin las esperas de la puerta ni los vaciados (los añade quien los conoce con {@link #with}). */
        Sample(long nowMs, int outq, int unacked, int retrans, int rttMs) {
            this(nowMs, outq, unacked, retrans, rttMs, 0, 0);
        }

        Sample with(int linkWaits, long lateFlushes) {
            return new Sample(nowMs, outq, unacked, retrans, rttMs, linkWaits, lateFlushes, queueLagMs, radioCut);
        }

        /** La misma muestra, con el inicio de un corte de radio. */
        Sample withRadioCut() {
            return new Sample(nowMs, outq, unacked, retrans, rttMs, linkWaits, lateFlushes, queueLagMs, true);
        }
    }

    /** Un cambio de bitrate o de fps, con su explicación («congestión (outq 96 KB, retrans +21) → bitrate 3.5 Mbit/s»). */
    static final class Step {
        final int bitrateBefore;
        final int bitrateAfter;
        final int fpsBefore;
        final int fpsAfter;
        final boolean congestion;
        /** Paso por debajo del suelo normal: el texto ya empieza por «enlace muy congestionado». */
        final boolean emergency;
        final String text;

        Step(int bitrateBefore, int bitrateAfter, int fpsBefore, int fpsAfter, boolean congestion, String text) {
            this(bitrateBefore, bitrateAfter, fpsBefore, fpsAfter, congestion, false, text);
        }

        Step(int bitrateBefore, int bitrateAfter, int fpsBefore, int fpsAfter, boolean congestion, boolean emergency, String text) {
            this.bitrateBefore = bitrateBefore;
            this.bitrateAfter = bitrateAfter;
            this.fpsBefore = fpsBefore;
            this.fpsAfter = fpsAfter;
            this.congestion = congestion;
            this.emergency = emergency;
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
    private boolean floorNoted;

    /** Emergencia: por debajo del suelo normal (hasta que la subida vuelva a él). */
    private boolean emergency;
    /** Se puede entrar en emergencia (al empezar la sesión y tras EMERGENCY_REARM_MS limpios en el suelo normal o más). */
    private boolean emergencyArmed;
    /** Congestión en el suelo normal y a LOW_FPS: desde cuándo, cuántas muestras (separadas STEP_HOLD_MS) y la última. */
    private long floorCongestedSinceMs = -1;
    private int floorCongestedSamples;
    private long floorCongestedLastMs = -1;
    /** Limpio desde (para rearmar la emergencia); -1 = no. */
    private long rearmCleanSinceMs = -1;

    // Estadísticas de la sesión (resumen).
    private int minBitrate;
    private int congestionEvents;
    private int stepsDown;
    private int stepsUp;
    private int emergencies;

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
        rttHighSinceMs = -1;
        floorNoted = false;
        emergency = false;
        emergencyArmed = true;
        emergencies = 0;
        floorCongestedSinceMs = -1;
        floorCongestedSamples = 0;
        floorCongestedLastMs = -1;
        rearmCleanSinceMs = -1;
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
        return Math.min(profileBps, Math.max(FLOOR_BPS, (int) Math.round(profileBps * FLOOR_FRACTION)));
    }

    /** Una muestra: el paso a aplicar (bitrate o fps nuevos), o null si no cambia nada. */
    synchronized Step onSample(Sample s) {
        long now = s.nowMs;
        StringBuilder why = new StringBuilder();

        // Cola del kernel alta (o la puerta GL cerrada por el enlace casi todo el rato) durante OUTQ_HIGH_MS. Sin cola del
        // kernel (USB), el retraso de la cola de la sesión.
        boolean lagHigh = s.outq < 0 && s.queueLagMs >= QUEUE_LAG_HIGH_MS;
        boolean high = s.outq >= OUTQ_HIGH_BYTES || s.linkWaits >= LINK_WAITS_HIGH || lagHigh;
        if (high) {
            if (outqHighSinceMs < 0) outqHighSinceMs = now;
        } else {
            outqHighSinceMs = -1;
        }
        if (high && now - outqHighSinceMs >= OUTQ_HIGH_MS) {
            String held = (now - outqHighSinceMs) + " ms";
            add(why, s.outq >= 0 ? "outq " + kb(s.outq) + " " + held
                    : lagHigh ? "cola de la sesión " + s.queueLagMs + " ms de retraso, " + held
                    : "enlace cerrado " + held);
        }

        // Las retransmisiones no se miran: unas pocas por segundo son normales en Wi-Fi y, si de verdad frenan el
        // enlace, ya se ven como cola o rtt sostenidos.

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

        // Inicio de un corte (TX atascado más de 400 ms): congestión en el acto.
        if (s.radioCut) add(why, s.outq >= 0 || s.rttMs >= 0 ? "corte de radio" : "corte del cable");

        boolean congested = why.length() > 0;
        if (congested) {
            cleanSinceMs = -1;
            rearmCleanSinceMs = -1;
            int floor = floor();
            // Emergencia: cuenta cada muestra congestionada en el suelo normal y a LOW_FPS (antes de la espera entre pasos).
            boolean emergencyDue = false;
            if (!emergency) {
                if (emergencyArmed && bitrate <= floor && fpsCap <= LOW_FPS) {
                    if (floorCongestedSinceMs < 0) {
                        floorCongestedSinceMs = now;
                        floorCongestedSamples = 0;
                        floorCongestedLastMs = -1;
                    }
                    if (floorCongestedLastMs < 0 || now - floorCongestedLastMs >= STEP_HOLD_MS) {
                        floorCongestedSamples++;
                        floorCongestedLastMs = now;
                    }
                    emergencyDue = now - floorCongestedSinceMs >= EMERGENCY_AFTER_MS && floorCongestedSamples >= EMERGENCY_SAMPLES;
                } else {
                    floorCongestedSinceMs = -1;
                }
            }
            if (lastStepMs >= 0 && now - lastStepMs < STEP_HOLD_MS) return null;
            int before = bitrate;
            if (emergencyDue) {
                long held = now - floorCongestedSinceMs;
                emergency = true;
                emergencyArmed = false;
                emergencies++;
                floorNoted = false;
                floorCongestedSinceMs = -1;
                lastStepMs = now;
                congestionEvents++;
                stepsDown++;
                bitrate = Math.max(EMERGENCY_FLOOR_BPS, (int) Math.round(bitrate * STEP_DOWN));
                noteBitrate();
                return new Step(before, bitrate, fpsCap, fpsCap, true, true,
                        "enlace muy congestionado: bajo del suelo normal (" + mbit(floor) + ") tras "
                                + String.format(Locale.US, "%.1f", held / 1000.0) + " s de congestión en él a " + fpsCap
                                + " fps (" + why + ") → bitrate " + mbit(bitrate) + emergencyFloorMark());
            }
            int stepFloor = emergency ? EMERGENCY_FLOOR_BPS : floor;
            int lowFps = emergency ? EMERGENCY_FPS : LOW_FPS;
            if (bitrate > stepFloor) {
                lastStepMs = now;
                congestionEvents++;
                stepsDown++;
                bitrate = Math.max(stepFloor, (int) Math.round(bitrate * STEP_DOWN));
                noteBitrate();
                if (emergency) {
                    return new Step(before, bitrate, fpsCap, fpsCap, true, true,
                            "enlace muy congestionado (" + why + ") → bitrate " + mbit(bitrate) + emergencyFloorMark());
                }
                return new Step(before, bitrate, fpsCap, fpsCap, true,
                        "congestión (" + why + ") → bitrate " + mbit(bitrate) + (bitrate == floor ? " (suelo)" : ""));
            }
            if (fpsCap > lowFps) {
                lastStepMs = now;
                congestionEvents++;
                int fpsBefore = fpsCap;
                fpsCap = lowFps;
                if (emergency) {
                    return new Step(before, bitrate, fpsBefore, fpsCap, true, true,
                            "enlace muy congestionado (" + why + ") con el bitrate en el suelo de emergencia (" + mbit(bitrate)
                                    + ") → " + lowFps + " fps");
                }
                return new Step(before, bitrate, fpsBefore, fpsCap, true,
                        "congestión (" + why + ") con el bitrate en el suelo (" + mbit(bitrate) + ") → " + LOW_FPS + " fps");
            }
            if (!floorNoted) {
                floorNoted = true;
                lastStepMs = now;
                congestionEvents++;
                if (emergency) {
                    return new Step(before, bitrate, fpsCap, fpsCap, true, true,
                            "enlace muy congestionado (" + why + ") con el bitrate en el suelo de emergencia (" + mbit(bitrate)
                                    + ") y " + fpsCap + " fps: no hay más que bajar");
                }
                return new Step(before, bitrate, fpsCap, fpsCap, true,
                        "congestión (" + why + ") con el bitrate en el suelo (" + mbit(bitrate) + ") y " + fpsCap
                                + " fps: no hay más que bajar");
            }
            return null;
        }

        // Enlace limpio. La emergencia se rearma tras EMERGENCY_REARM_MS limpios en el suelo normal o por encima.
        String rearmed = null;
        if (!emergencyArmed && !emergency && bitrate >= floor()) {
            if (rearmCleanSinceMs < 0) {
                rearmCleanSinceMs = now;
            } else if (now - rearmCleanSinceMs >= EMERGENCY_REARM_MS) {
                emergencyArmed = true;
                rearmCleanSinceMs = -1;
                rearmed = "emergencia rearmada (" + EMERGENCY_REARM_MS / 1000 + " s limpio con " + mbit(bitrate) + ")";
            }
        } else {
            rearmCleanSinceMs = -1;
        }

        // Tras RECOVER_MS seguidos, primero los fps y luego el bitrate (un paso por periodo).
        if (cleanSinceMs < 0) {
            cleanSinceMs = now;
            return info(rearmed);
        }
        if (now - cleanSinceMs < RECOVER_MS) return info(rearmed);
        cleanSinceMs = now;
        floorNoted = false;
        floorCongestedSinceMs = -1;
        String tail = rearmed == null ? "" : " · " + rearmed;
        if (fpsCap < sessionFps) {
            int fpsBefore = fpsCap;
            fpsCap = sessionFps;
            return new Step(bitrate, bitrate, fpsBefore, fpsCap, false,
                    "enlace limpio " + RECOVER_MS / 1000 + " s → " + fpsCap + " fps (bitrate " + mbit(bitrate) + ")" + tail);
        }
        if (bitrate < ceiling) {
            int before = bitrate;
            stepsUp++;
            bitrate = Math.min(ceiling, (int) Math.round(bitrate * STEP_UP));
            String back = "";
            if (emergency && bitrate >= floor()) {
                emergency = false;
                back = " · fuera de la emergencia (suelo normal " + mbit(floor()) + ")";
            }
            return new Step(before, bitrate, fpsCap, fpsCap, false,
                    "enlace limpio " + RECOVER_MS / 1000 + " s → bitrate " + mbit(bitrate) + (bitrate == ceiling ? " (techo)" : "")
                            + back + tail);
        }
        return info(rearmed);
    }

    /** Un aviso sin cambios (la emergencia rearmada), o null. */
    private Step info(String text) {
        return text == null ? null : new Step(bitrate, bitrate, fpsCap, fpsCap, false, text);
    }

    private String emergencyFloorMark() {
        return bitrate == EMERGENCY_FLOOR_BPS ? " (suelo de emergencia)" : "";
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

    /** Veces que se bajó del suelo normal (emergencia) en la sesión. */
    synchronized int emergencies() {
        return emergencies;
    }

    /** Por debajo del suelo normal ahora mismo. */
    synchronized boolean inEmergency() {
        return emergency;
    }

    /** Hay algo que contar en las estadísticas de 5 s (bitrate por debajo del techo, fps bajados o congestiones). */
    synchronized boolean active() {
        return bitrate < ceiling || fpsCap < sessionFps || congestionEvents > 0;
    }

    /** Para el log al arrancar. */
    String describe() {
        return String.format(Locale.US, "bitrate adaptable al enlace %s-%s (baja ×%.2f con retardo sostenido o un corte de radio, sube %d %% cada %d s limpio; %d fps si en el suelo sigue; %d s más así: emergencia hasta %s y %d fps)",
                mbit(floor()), mbit(profileBps), STEP_DOWN, Math.round((STEP_UP - 1) * 100), RECOVER_MS / 1000, LOW_FPS,
                EMERGENCY_AFTER_MS / 1000, mbit(EMERGENCY_FLOOR_BPS), EMERGENCY_FPS);
    }

    /** Línea de las estadísticas de 5 s. */
    synchronized String statsLine() {
        return "enlace: bitrate " + mbit(bitrate) + " (mín. " + mbit(minBitrate) + ", techo " + mbit(ceiling) + ")"
                + (fpsCap < sessionFps ? " · " + fpsCap + " fps" : "") + " · congestiones " + congestionEvents
                + " (bajadas " + stepsDown + ", subidas " + stepsUp + ")"
                + (emergency ? " · emergencia (suelo normal " + mbit(floor()) + ")" : emergencies > 0 ? " · emergencias " + emergencies : "");
    }

    static String mbit(int bps) {
        return String.format(Locale.US, "%.1f Mbit/s", bps / 1e6);
    }

    private static String kb(int bytes) {
        return bytes < 1024 ? bytes + " B" : (bytes / 1024) + " KB";
    }
}
