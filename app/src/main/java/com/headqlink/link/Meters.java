package com.headqlink.link;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cifras del panel «En directo» de la pantalla principal, sacadas del texto de estado del vídeo
 * ({@link LinkState#video}: «30 fps · 6,5 Mbps», con el formato numérico del idioma del móvil). Solo lectura: si el
 * texto no tiene esa forma, el panel se queda en «—».
 */
final class Meters {
    private static final Pattern FPS = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*fps");
    private static final Pattern MBPS = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*Mbps");

    private Meters() {
    }

    /** {fps, Mbps}, o null si el texto no trae las dos cifras. */
    static float[] parse(String video) {
        if (video == null || video.isEmpty()) return null;
        Float fps = number(FPS.matcher(video));
        Float mbps = number(MBPS.matcher(video));
        if (fps == null || mbps == null) return null;
        return new float[]{fps, mbps};
    }

    /** Fracción (0-1) de las barras: el valor sobre el máximo esperado (si se pasa, lleno). */
    static float level(float value, float max) {
        if (!(value > 0) || !(max > 0)) return 0f;
        return Math.min(1f, value / max);
    }

    private static Float number(Matcher m) {
        if (!m.find()) return null;
        try {
            return Float.parseFloat(m.group(1).replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
