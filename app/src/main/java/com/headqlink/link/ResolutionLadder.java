package com.headqlink.link;

/**
 * Resolución en marcha según el calor (pura, la prueban los tests). La del principio la decide ResolutionMemory (cómo le
 * fue a este móvil en los viajes anteriores). En marcha no se sube nunca: cada cambio reconecta Android Auto (2-4 s en
 * negro y, con el móvil bloqueado, puede pedir desbloquearlo). Solo se baja:
 *
 * - A 720p tras DOWN_MS seguidos con calor de verdad: estado grave o peor, o la anticipación en su etapa 2 (va a llegar
 *   a grave).
 *
 * Los fps y el bitrate siguen ajustándose en marcha sin cortes (ThermalPolicy y ThermalForecast).
 */
final class ResolutionLadder {
    static final long DOWN_MS = 2 * 60_000L;
    static final float UP_HEADROOM = 0.75f;

    private boolean high;
    private long hotSinceMs = -1;

    ResolutionLadder(boolean high) {
        this.high = high;
    }

    /** Puro: ¿fresco? (para el log y los tests; ya no se sube en marcha). */
    static boolean cool(int status, float headroom, int stage) {
        return status <= 1 && stage == 0 && (Float.isNaN(headroom) || headroom < UP_HEADROOM);
    }

    /** Puro: ¿calor para bajar? */
    static boolean hot(int status, int stage) {
        return status >= ThermalPolicy.STATUS_SEVERE || stage >= 2;
    }

    /** Un dato nuevo en nowMs: -1 si toca bajar a 720p, 0 si nada (nunca sube). */
    synchronized int update(int status, float headroom, int stage, long nowMs) {
        if (!high) return 0;
        if (!hot(status, stage)) {
            hotSinceMs = -1;
            return 0;
        }
        if (hotSinceMs < 0) hotSinceMs = nowMs;
        if (nowMs - hotSinceMs >= DOWN_MS) {
            high = false;
            hotSinceMs = -1;
            return -1;
        }
        return 0;
    }

    synchronized boolean high() {
        return high;
    }
}
