package com.headqlink.link;

import java.util.Locale;

/**
 * Adaptación térmica del vídeo (pura, la prueban los tests). Del estado térmico de Android (PowerManager, API 29:
 * 0 nada, 1 ligero, 2 moderado, 3 grave, 4 crítico, 5 emergencia, 6 apagado) a un nivel, y del nivel y la
 * «Protección térmica» elegida (Ajustes de imagen) a los topes:
 *
 * | Nivel (estado)        | Normal                                   | Suave                              | Apagada |
 * |-----------------------|------------------------------------------|------------------------------------|---------|
 * | MODERATE (2)          | 30 fps si la sesión va a 60; bitrate ×0,8 | solo el bitrate ×0,8               | nada    |
 * | SEVERE (3)            | 24 fps; bitrate ×0,8 y ≤ 3,5 Mbit/s      | 30 fps como mucho; mismo bitrate   | nada    |
 * | CRITICAL (4 o más)    | 20 fps; bitrate ×0,8 y ≤ 3 Mbit/s        | lo mismo que Normal                | nada    |
 *
 * Sube en el acto. Baja solo tras HOLD_MS seguidos con el estado por debajo del nivel actual, al nivel más alto que
 * pidió el estado en ese rato: para volver a normal, el estado tiene que estar en LIGERO (1) o menos durante 30 s.
 * Antes (viaje del 2026-10-05: 0→1→2→3 en 15 min a 50-60 fps) el grave bajaba a 20 fps y la vuelta tardaba 60 s; en un
 * móvil Qualcomm con Wi-Fi Direct el estado 3 dejó el resto del viaje a 20 fps, «menos fluido que el original».
 */
final class ThermalPolicy {
    static final int NORMAL = 0;
    static final int MODERATE = 1;
    static final int SEVERE = 2;
    static final int CRITICAL = 3;
    /** PowerManager.THERMAL_STATUS_MODERATE, _SEVERE y _CRITICAL. */
    static final int STATUS_MODERATE = 2;
    static final int STATUS_SEVERE = 3;
    static final int STATUS_CRITICAL = 4;
    static final long HOLD_MS = 30_000;
    /** MODERATE con la sesión a 60 fps (Fluidez 60): a 30, lo que pide el coche; a 30 no cambia los fps. */
    static final int MODERATE_FPS_FROM_60 = 30;
    static final int SEVERE_FPS = 24;
    static final int CRITICAL_FPS = 20;
    /** Suave: nunca por debajo de esto salvo en CRITICAL. */
    static final int SOFT_MIN_FPS = 30;
    static final double MODERATE_BITRATE_FACTOR = 0.8;
    static final int SEVERE_MAX_BPS = 3_500_000;
    static final int CRITICAL_MAX_BPS = 3_000_000;

    /** «Protección térmica» (Config.THERMAL_MODE). */
    static final String MODE_NORMAL = "normal";
    static final String MODE_SOFT = "suave";
    static final String MODE_OFF = "apagada";

    private int level = NORMAL;
    private int status = -1;
    /** Desde cuándo (ms) el estado pide menos que el nivel actual; -1 = no lo pide. */
    private long calmSinceMs = -1;
    /** Nivel más alto que pidió el estado durante esa calma. */
    private int calmPeak;

    /** Nivel que pide un estado, sin histéresis. */
    static int levelFor(int status) {
        if (status >= STATUS_CRITICAL) return CRITICAL;
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

    /** Modo válido a partir del ajuste guardado (desconocido o vacío = Normal). */
    static String mode(String raw) {
        if (MODE_SOFT.equals(raw) || MODE_OFF.equals(raw)) return raw;
        return MODE_NORMAL;
    }

    /** Tope de fps de un nivel con la protección Normal, sobre los de la sesión (baseFps): nunca por encima de ellos. */
    static int fpsCap(int level, int baseFps) {
        return fpsCap(MODE_NORMAL, level, baseFps);
    }

    /** Tope de fps de un nivel según la protección elegida (ver la tabla de la clase). */
    static int fpsCap(String mode, int level, int baseFps) {
        String m = mode(mode);
        if (m.equals(MODE_OFF) || level <= NORMAL) return baseFps;
        if (level >= CRITICAL) return Math.min(baseFps, CRITICAL_FPS);
        if (m.equals(MODE_SOFT)) return level >= SEVERE ? Math.min(baseFps, SOFT_MIN_FPS) : baseFps;
        if (level >= SEVERE) return Math.min(baseFps, SEVERE_FPS);
        return Math.min(baseFps, moderateFps(baseFps));
    }

    /** fps de MODERATE según los de la sesión: 30 con la sesión a 60 o más (Fluidez 60); a 30 o menos, los mismos. */
    static int moderateFps(int baseFps) {
        return baseFps >= 60 ? MODERATE_FPS_FROM_60 : baseFps;
    }

    /** Tope de bitrate de un nivel con la protección Normal, sobre el inicial de la sesión. */
    static int bitrateCap(int level, int baseBps) {
        return bitrateCap(MODE_NORMAL, level, baseBps);
    }

    /** Tope de bitrate según la protección (Normal y Suave bajan lo mismo; Apagada, nada). Nunca sube con el nivel. */
    static int bitrateCap(String mode, int level, int baseBps) {
        if (mode(mode).equals(MODE_OFF) || level <= NORMAL) return baseBps;
        int moderate = (int) Math.round(baseBps * MODERATE_BITRATE_FACTOR);
        if (level >= CRITICAL) return Math.min(moderate, CRITICAL_MAX_BPS);
        if (level >= SEVERE) return Math.min(moderate, SEVERE_MAX_BPS);
        return moderate;
    }

    static String name(int level) {
        switch (level) {
            case CRITICAL:
                return "crítico";
            case SEVERE:
                return "grave";
            case MODERATE:
                return "moderado";
            default:
                return "normal";
        }
    }

    static String modeName(String mode) {
        switch (mode(mode)) {
            case MODE_SOFT:
                return "suave";
            case MODE_OFF:
                return "apagada";
            default:
                return "normal";
        }
    }

    /** Qué hace un nivel con la protección Normal; baseFps = los fps de la sesión (0 si no se saben). */
    static String describe(int level, int baseFps) {
        return describe(MODE_NORMAL, level, baseFps);
    }

    /** Qué hace un nivel con esa protección, para el log. */
    static String describe(String mode, int level, int baseFps) {
        String m = mode(mode);
        if (m.equals(MODE_OFF)) return "protección térmica apagada: solo se registra";
        switch (level) {
            case CRITICAL:
                return String.format(Locale.US, "%d fps y como mucho %.1f Mbit/s", CRITICAL_FPS, CRITICAL_MAX_BPS / 1e6);
            case SEVERE:
                if (m.equals(MODE_SOFT)) {
                    return String.format(Locale.US, "como mucho %d fps y %.1f Mbit/s (bitrate ×%.1f)", SOFT_MIN_FPS, SEVERE_MAX_BPS / 1e6, MODERATE_BITRATE_FACTOR);
                }
                return String.format(Locale.US, "%d fps y como mucho %.1f Mbit/s (bitrate ×%.1f)", SEVERE_FPS, SEVERE_MAX_BPS / 1e6, MODERATE_BITRATE_FACTOR);
            case MODERATE:
                if (m.equals(MODE_SOFT)) return String.format(Locale.US, "solo el bitrate ×%.1f", MODERATE_BITRATE_FACTOR);
                String fps = baseFps >= 60 ? MODERATE_FPS_FROM_60 + " fps" : baseFps > 0 ? "los fps de la sesión"
                        : MODERATE_FPS_FROM_60 + " fps con la sesión a 60 (a 30, los mismos)";
                return String.format(Locale.US, "%s y bitrate ×%.1f", fps, MODERATE_BITRATE_FACTOR);
            default:
                return "fps y bitrate de la sesión";
        }
    }

    static String describe(int level) {
        return describe(level, 0);
    }
}
