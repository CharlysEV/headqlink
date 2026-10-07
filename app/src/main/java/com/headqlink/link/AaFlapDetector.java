package com.headqlink.link;

/**
 * El síntoma de Open Headunit #985 (Android Auto 17.8): el Self-Mode conecta, AA atiende y a los 1-2 s se desconecta,
 * una y otra vez. Puro (sin Android, reloj de quien llama): lo prueban los tests; lo alimenta {@link AaFlapWatch}.
 *
 * - Una sesión con AA (handshake hecho) que se corta sola en menos de {@link #SHORT_MS} es «corta». Las que cierra
 *   HeadQLink (Desconectar, fin del viaje, cambio de perfil) no cuentan ni rompen la racha.
 * - {@link #STREAK} cortas seguidas: diagnóstico (una línea en el log cada vez que se llega y un aviso, solo la primera
 *   vez del proceso). Una sesión larga rompe la racha.
 * - Mientras haya racha, los relanzamientos automáticos esperan al menos {@link #MIN_RELAUNCH_GAP_MS} desde el último
 *   corte ({@link #relaunchHoldMs}): nunca un bucle más rápido que cada 10 s.
 */
final class AaFlapDetector {
    /** Una sesión que dura menos que esto y se corta sola es «corta». */
    static final long SHORT_MS = 10_000;
    /** Cortas seguidas para el diagnóstico. */
    static final int STREAK = 2;
    /** Con racha, lo mínimo entre un corte y el siguiente relanzamiento automático. */
    static final long MIN_RELAUNCH_GAP_MS = 10_000;

    enum Result {
        /** Nada que contar (no había sesión, o la cerró HeadQLink). */
        NONE,
        /** Sesión larga: fuera la racha. */
        LONG,
        /** Sesión corta, sin llegar aún a la racha. */
        SHORT,
        /** Sesión corta que completa (o alarga) la racha: diagnóstico. */
        FLAPPING,
    }

    private long connectedAt = -1;
    private int streak;
    private long lastShortDropAt = -1;
    private long lastDurationMs = -1;
    private boolean noticeShown;

    /** AA atendió (handshake hecho). Repetido sin corte en medio, no cambia nada. */
    void onConnected(long nowMs) {
        if (connectedAt < 0) connectedAt = nowMs;
    }

    /**
     * La sesión con AA terminó. ours: la cerró HeadQLink (no cuenta). Devuelve qué fue; con {@link Result#FLAPPING},
     * {@link #takeNotice()} dice si hay que avisar (solo la primera vez).
     */
    Result onDisconnected(long nowMs, boolean ours) {
        if (connectedAt < 0) return Result.NONE;
        long d = nowMs - connectedAt;
        connectedAt = -1;
        lastDurationMs = d;
        if (ours) return Result.NONE;
        if (d >= SHORT_MS) {
            streak = 0;
            lastShortDropAt = -1;
            return Result.LONG;
        }
        streak++;
        lastShortDropAt = nowMs;
        return streak >= STREAK ? Result.FLAPPING : Result.SHORT;
    }

    /** ¿Hay que poner el aviso? Solo la primera vez que se llega a la racha (luego, solo el log). */
    boolean takeNotice() {
        if (noticeShown || streak < STREAK) return false;
        noticeShown = true;
        return true;
    }

    /** Cuánto debe esperar aún un relanzamiento automático (0: ya puede). */
    long relaunchHoldMs(long nowMs) {
        if (streak <= 0 || lastShortDropAt < 0) return 0;
        return Math.max(0, lastShortDropAt + MIN_RELAUNCH_GAP_MS - nowMs);
    }

    /** Sesiones cortas seguidas. */
    int streak() {
        return streak;
    }

    /** Lo que duró la última sesión (ms), o -1. */
    long lastDurationMs() {
        return lastDurationMs;
    }

    boolean connected() {
        return connectedAt >= 0;
    }
}
