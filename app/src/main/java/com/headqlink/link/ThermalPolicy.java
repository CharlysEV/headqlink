package com.headqlink.link;

import java.util.Locale;

/**
 * Adaptación térmica del vídeo (pura, la prueban los tests). Del estado térmico de Android (PowerManager, API 29:
 * 0 nada, 1 ligero, 2 moderado, 3 grave, 4 crítico, 5 emergencia, 6 apagado) a un nivel:
 * - estado >= MODERADO (2): nivel MODERATE, 24 fps (30 si la sesión va a 60: «Fluidez» 60) y el bitrate ×0,7;
 * - estado >= GRAVE (3): nivel SEVERE, 20 fps y el bitrate de MODERATE con un tope de 3 Mbit/s.
 * Sube en el acto. Baja solo tras HOLD_MS seguidos con el estado por debajo del nivel actual, al nivel más alto que
 * pidió el estado en ese rato: para volver a normal, el estado tiene que estar en LIGERO (1) o menos durante 60 s.
 * Viaje del 2026-10-05: 0→1→2→3 en 15 min con 50-60 fps, y 4 (crítico) en la segunda sesión.
 */
final class ThermalPolicy {
    static final int NORMAL = 0;
    static final int MODERATE = 1;
    static final int SEVERE = 2;
    /** PowerManager.THERMAL_STATUS_MODERATE y THERMAL_STATUS_SEVERE. */
    static final int STATUS_MODERATE = 2;
    static final int STATUS_SEVERE = 3;
    static final long HOLD_MS = 60_000;
    static final int MODERATE_FPS = 24;
    /** MODERATE con la sesión a 60 fps (Fluidez 60): a 30, lo que pide el coche, en vez de 24. */
    static final int MODERATE_FPS_FROM_60 = 30;
    static final int SEVERE_FPS = 20;
    static final double MODERATE_BITRATE_FACTOR = 0.7;
    static final int SEVERE_MAX_BPS = 3_000_000;

    private int level = NORMAL;
    private int status = -1;
    /** Desde cuándo (ms) el estado pide menos que el nivel actual; -1 = no lo pide. */
    private long calmSinceMs = -1;
    /** Nivel más alto que pidió el estado durante esa calma. */
    private int calmPeak;

    /** Nivel que pide un estado, sin histéresis. */
    static int levelFor(int status) {
        if (status >= STATUS_SEVERE) return SEVERE;
        if (status >= STATUS_MODERATE) return MODERATE;
        return NORMAL;
    }

    /** Estado térmico nuevo (o el mismo, al vencer la espera) en nowMs. true si el nivel ha cambiado. */
    synchronized boolean update(int status, long nowMs) {
        this.status = status;
        int want = levelFor(status);
        if (want >= level) {
            calmSinceMs = -1;
            if (want == level) return false;
            level = want;
            return true;
        }
        if (calmSinceMs < 0) {
            calmSinceMs = nowMs;
            calmPeak = want;
        } else if (want > calmPeak) {
            calmPeak = want;
        }
        if (nowMs - calmSinceMs < HOLD_MS) return false;
        level = calmPeak;
        // Si aún pide menos, la calma para bajar otro nivel cuenta desde ahora.
        if (want < level) {
            calmSinceMs = nowMs;
            calmPeak = want;
        } else {
            calmSinceMs = -1;
        }
        return true;
    }

    synchronized int level() {
        return level;
    }

    /** Último estado térmico visto (-1 = ninguno). */
    synchronized int status() {
        return status;
    }

    /** ms hasta que haya que volver a mirar con el mismo estado (para bajar de nivel), o -1 si no hace falta. */
    synchronized long msUntilCheck(long nowMs) {
        return calmSinceMs < 0 ? -1 : Math.max(0, calmSinceMs + HOLD_MS - nowMs);
    }

    /** Tope de fps de un nivel, sobre los de la sesión (baseFps): nunca por encima de ellos. */
    static int fpsCap(int level, int baseFps) {
        if (level >= SEVERE) return Math.min(baseFps, SEVERE_FPS);
        if (level >= MODERATE) return Math.min(baseFps, moderateFps(baseFps));
        return baseFps;
    }

    /** fps de MODERATE según los de la sesión: 30 con la sesión a 60 o más (Fluidez 60), 24 por debajo. */
    static int moderateFps(int baseFps) {
        return baseFps >= 60 ? MODERATE_FPS_FROM_60 : MODERATE_FPS;
    }

    /** Tope de bitrate de un nivel, sobre el inicial de la sesión (nunca más alto en SEVERE que en MODERATE). */
    static int bitrateCap(int level, int baseBps) {
        if (level == NORMAL) return baseBps;
        int moderate = (int) Math.round(baseBps * MODERATE_BITRATE_FACTOR);
        return level >= SEVERE ? Math.min(moderate, SEVERE_MAX_BPS) : moderate;
    }

    static String name(int level) {
        switch (level) {
            case SEVERE:
                return "grave";
            case MODERATE:
                return "moderado";
            default:
                return "normal";
        }
    }

    /** Qué hace un nivel, para el log; baseFps = los fps de la sesión (0 si no se saben: se citan los dos casos). */
    static String describe(int level, int baseFps) {
        switch (level) {
            case SEVERE:
                return String.format(Locale.US, "%d fps y como mucho %.1f Mbit/s", SEVERE_FPS, SEVERE_MAX_BPS / 1e6);
            case MODERATE:
                String fps = baseFps > 0 ? String.valueOf(moderateFps(baseFps))
                        : MODERATE_FPS + " (" + MODERATE_FPS_FROM_60 + " con la sesión a 60)";
                return String.format(Locale.US, "%s fps y bitrate ×%.1f", fps, MODERATE_BITRATE_FACTOR);
            default:
                return "fps y bitrate de la sesión";
        }
    }

    static String describe(int level) {
        return describe(level, 0);
    }
}
