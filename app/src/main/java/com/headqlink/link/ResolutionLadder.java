package com.headqlink.link;

/**
 * Resolución según el calor (pura, la prueban los tests). La sesión empieza en 720p (Android Auto dibuja a 720p y el
 * vídeo va a 1280x588: menos trabajo y menos calor); con el móvil fresco un buen rato sube a 1080p, y con calor de
 * verdad vuelve a 720p. Cada cambio reconecta (AA renegocia la resolución: 2-4 s en negro), así que se cambia poco:
 *
 * - Sube a 1080p tras UP_MS seguidos fresco: estado térmico de Android ligero o nada, margen previsto por debajo de
 *   UP_HEADROOM (o sin margen) y sin anticipación; y nunca antes de NO_UP_AFTER_DOWN_MS desde la última bajada.
 * - Baja a 720p tras DOWN_MS seguidos con calor: estado grave o peor, o la anticipación en su etapa 2.
 *
 * Los fps y el bitrate siguen ajustándose en marcha sin cortes (ThermalPolicy y ThermalForecast).
 */
final class ResolutionLadder {
    static final long UP_MS = 10 * 60_000L;
    static final long DOWN_MS = 2 * 60_000L;
    static final long NO_UP_AFTER_DOWN_MS = 20 * 60_000L;
    static final float UP_HEADROOM = 0.75f;

    private boolean high;
    private long coolSinceMs = -1;
    private long hotSinceMs = -1;
    private long lastDownMs = Long.MIN_VALUE / 2;

    ResolutionLadder(boolean high) {
        this.high = high;
    }

    /** Puro: ¿fresco para subir? */
    static boolean cool(int status, float headroom, int stage) {
        return status <= 1 && stage == 0 && (Float.isNaN(headroom) || headroom < UP_HEADROOM);
    }

    /** Puro: ¿calor para bajar? */
    static boolean hot(int status, int stage) {
        return status >= ThermalPolicy.STATUS_SEVERE || stage >= 2;
    }

    /** Un dato nuevo en nowMs: +1 si toca subir a 1080p, -1 si toca bajar a 720p, 0 si nada. */
    synchronized int update(int status, float headroom, int stage, long nowMs) {
        if (!high) {
            if (!cool(status, headroom, stage)) {
                coolSinceMs = -1;
                return 0;
            }
            if (coolSinceMs < 0) coolSinceMs = nowMs;
            if (nowMs - coolSinceMs >= UP_MS && nowMs - lastDownMs >= NO_UP_AFTER_DOWN_MS) {
                high = true;
                coolSinceMs = -1;
                return 1;
            }
            return 0;
        }
        if (!hot(status, stage)) {
            hotSinceMs = -1;
            return 0;
        }
        if (hotSinceMs < 0) hotSinceMs = nowMs;
        if (nowMs - hotSinceMs >= DOWN_MS) {
            high = false;
            hotSinceMs = -1;
            lastDownMs = nowMs;
            return -1;
        }
        return 0;
    }

    synchronized boolean high() {
        return high;
    }
}
