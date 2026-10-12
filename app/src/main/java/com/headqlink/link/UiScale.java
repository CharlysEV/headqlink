package com.headqlink.link;

/**
 * Puro: a qué escala se dibuja nuestra interfaz del modo extendido en la pantalla del coche. Está hecha en píxeles para
 * la del C10 (1920x882); en una más pequeña (la del T03, 10,1") no cabe: se dibuja en un lienzo mayor (unos 1600x900) y
 * el relay la reduce a la pantalla (GlFrameRelay escala la capa), con los toques al revés. A partir de 1700 de ancho y 800
 * de alto, tal cual.
 */
final class UiScale {
    /** Lienzo mínimo en el que cabe todo: el ancho y el alto a los que se reduce la interfaz como mucho. */
    static final int MIN_W = 1600;
    static final int MIN_H = 860;
    /** Por debajo de esto el texto ya no se lee en el coche: no se reduce más. */
    static final float MIN_SCALE = 0.6f;

    private UiScale() {
    }

    /**
     * Puro: escala (lienzo → pantalla, ≤ 1) para una pantalla del coche de w x h. Con {@code percent} entre 50 y 100,
     * la que se elige a mano; con 0, la automática.
     */
    static float forCar(int w, int h, int percent) {
        if (w <= 0 || h <= 0) return 1f;
        if (percent >= 50 && percent <= 100) return percent / 100f;
        if (w >= 1700 && h >= 800) return 1f;
        float s = Math.min(w / (float) MIN_W, h / (float) MIN_H);
        return Math.max(MIN_SCALE, Math.min(1f, s));
    }

    /** Puro: tamaño del lienzo de la interfaz (px) para un lado de la pantalla con esa escala. */
    static int canvas(int screenPx, float scale) {
        return Math.round(screenPx / scale);
    }

    /** Puro: un tamaño o una posición del lienzo, en px de la pantalla del coche. */
    static int toCar(int canvasPx, float scale) {
        return Math.round(canvasPx * scale);
    }
}
