package com.headqlink.link;

/**
 * Tamaño de lo que dibuja Android Auto (puro, lo prueban los tests): la densidad (dpi) que se le anuncia. Con 200 dpi la
 * pantalla de 14,6" del C10 (1920 de ancho) le da a AA ~1536 dp de ancho; en una de 10,1" a 1024x600 (T03) solo ~820:
 * todo enorme, casi como en un móvil. En pantallas de 1280 de ancho o menos se baja la densidad para que AA tenga ~1280 dp
 * (como dibujar a 1080p y reducir, lo que hace Open Headunit), y se puede elegir más grande o más pequeño
 * («Tamaño de Android Auto», Config.AA_SIZE).
 */
final class AaSize {
    /** Ancho (dp) que se busca en pantallas pequeñas. */
    static final int TARGET_DP = 1280;
    static final int MIN_DPI = 90;
    static final int MAX_DPI = 260;
    /** Opciones del ajuste (porcentaje sobre el automático): más grande, normal, más pequeño, mucho más pequeño. */
    static final int[] CHOICES = {120, 100, 85, 70};

    private AaSize() {
    }

    /** Puro: dpi automático para una pantalla de carW de ancho (200 en el C10; 128 a 1024; 160 a 1280). */
    static int autoDpi(int carW) {
        if (carW <= 0 || carW > 1280) return 200;
        return Math.max(100, Math.min(200, Math.round(carW * 160f / TARGET_DP)));
    }

    /**
     * Puro: dpi para Android Auto. manualDpi > 0 (el extra aa_dpi por adb) manda; si no, el automático por el porcentaje
     * elegido (100 = tal cual).
     */
    static int dpi(int manualDpi, int carW, int percent) {
        if (manualDpi > 0) return manualDpi;
        int pct = percent > 0 ? percent : 100;
        return Math.max(MIN_DPI, Math.min(MAX_DPI, Math.round(autoDpi(carW) * pct / 100f)));
    }
}
