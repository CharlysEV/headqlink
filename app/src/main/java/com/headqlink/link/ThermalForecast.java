package com.headqlink.link;

import java.util.Locale;

/**
 * Anticipación térmica (pura, la prueban los tests): con la previsión del margen térmico de Android
 * (PowerManager.getThermalHeadroom, API 30; 1,0 = donde empieza a frenar fuerte) se baja el vídeo poco a poco ANTES de
 * que el estado llegue a grave, en vez de esperar al aviso y caer de golpe a 20-24 fps. Viaje del 2026-10-11 por cable:
 * margen 0,88-0,95 casi todo el rato a 60 fps, estado moderado y a ratos grave.
 *
 * | Etapa | Entra con el margen          | fps (sesión a 50 o más) | bitrate |
 * |-------|------------------------------|-------------------------|---------|
 * | 0     | —                            | los de la sesión        | ×1      |
 * | 1     | ≥ 0,85 durante ENTER_MS      | 45                      | ×0,9    |
 * | 2     | ≥ 0,92 durante ENTER_MS      | 30                      | ×0,8    |
 *
 * Sube de etapa tras ENTER_MS seguidos por encima de su umbral; baja una etapa tras EXIT_MS seguidos por debajo de
 * EXIT_BELOW. Con la «Protección térmica» apagada no hace nada. Se suma a ThermalPolicy (se toma el tope más bajo).
 */
final class ThermalForecast {
    static final float STAGE1_AT = 0.85f;
    static final float STAGE2_AT = 0.92f;
    static final float EXIT_BELOW = 0.78f;
    static final long ENTER_MS = 20_000;
    static final long EXIT_MS = 60_000;
    static final int STAGE1_FPS = 45;
    static final int STAGE2_FPS = 30;
    /** Por debajo de esto los fps de la sesión ya son bajos: solo se toca el bitrate. */
    static final int HIGH_FPS_FROM = 50;
    static final double STAGE1_BITRATE = 0.9;
    static final double STAGE2_BITRATE = 0.8;

    private int stage;
    /** Desde cuándo el margen pide la etapa siguiente (-1 = no la pide). */
    private long upSinceMs = -1;
    /** Desde cuándo el margen está por debajo de EXIT_BELOW (-1 = no lo está). */
    private long calmSinceMs = -1;
    private float last = Float.NaN;

    /** Etapa que pide un margen, sin esperas. */
    static int stageFor(float headroom) {
        if (Float.isNaN(headroom)) return 0;
        if (headroom >= STAGE2_AT) return 2;
        if (headroom >= STAGE1_AT) return 1;
        return 0;
    }

    /** Un margen nuevo en nowMs (NaN si Android no lo da). true si la etapa ha cambiado. */
    synchronized boolean update(float headroom, long nowMs) {
        last = headroom;
        if (Float.isNaN(headroom)) return false;
        int want = stageFor(headroom);
        if (want > stage) {
            calmSinceMs = -1;
            if (upSinceMs < 0) upSinceMs = nowMs;
            if (nowMs - upSinceMs >= ENTER_MS) {
                stage++;
                upSinceMs = stage < want ? nowMs : -1;
                return true;
            }
            return false;
        }
        upSinceMs = -1;
        if (stage > 0 && headroom < EXIT_BELOW) {
            if (calmSinceMs < 0) calmSinceMs = nowMs;
            if (nowMs - calmSinceMs >= EXIT_MS) {
                stage--;
                calmSinceMs = stage > 0 ? nowMs : -1;
                return true;
            }
            return false;
        }
        calmSinceMs = -1;
        return false;
    }

    synchronized int stage() {
        return stage;
    }

    synchronized float last() {
        return last;
    }

    /** Tope de fps de una etapa sobre los de la sesión (nunca por encima de ellos). */
    static int fpsCap(String mode, int stage, int baseFps) {
        if (ThermalPolicy.mode(mode).equals(ThermalPolicy.MODE_OFF) || stage <= 0 || baseFps < HIGH_FPS_FROM) return baseFps;
        return Math.min(baseFps, stage >= 2 ? STAGE2_FPS : STAGE1_FPS);
    }

    /** Tope de bitrate de una etapa sobre el de la sesión. */
    static int bitrateCap(String mode, int stage, int baseBps) {
        if (ThermalPolicy.mode(mode).equals(ThermalPolicy.MODE_OFF) || stage <= 0) return baseBps;
        return (int) Math.round(baseBps * (stage >= 2 ? STAGE2_BITRATE : STAGE1_BITRATE));
    }

    static String describe(int stage) {
        if (stage <= 0) return "sin anticipación";
        return String.format(Locale.US, "anticipación %d: %d fps como mucho (si la sesión va a 50 o más) y bitrate ×%.1f", stage,
                stage >= 2 ? STAGE2_FPS : STAGE1_FPS, stage >= 2 ? STAGE2_BITRATE : STAGE1_BITRATE);
    }
}
