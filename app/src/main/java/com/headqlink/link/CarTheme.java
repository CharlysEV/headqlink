package com.headqlink.link;

/**
 * Día o noche de la interfaz propia en la pantalla del coche (modo extendido), igual que Android Auto: sigue la misma
 * señal que le mandamos a AA (AapService.ACTION_NIGHT_MODE_CHANGED, que sale de NightModeManager: amanecer y
 * atardecer, el sensor de luz o lo que se elija en sus ajustes). De noche, la paleta oscura de siempre; de día, una
 * clara con el mismo acento, algo más oscuro para que se lea sobre blanco. Cambia los colores de CarKit y CarStyle (por
 * eso no son constantes): quien los use tiene que volver a construir sus vistas (CarUi rehace la interfaz). Sin
 * Android: se prueba en el PC.
 */
final class CarTheme {
    private static volatile boolean night = true;

    private CarTheme() {
    }

    static boolean night() {
        return night;
    }

    /** Pone los colores del día o de la noche. true si ha cambiado. */
    static synchronized boolean apply(boolean isNight) {
        boolean changed = isNight != night;
        night = isNight;
        if (isNight) {
            CarKit.BG = 0xFF0A0E14;
            CarKit.SURFACE = 0xFF111821;
            CarKit.SURFACE_HI = 0xFF17202B;
            CarKit.SURFACE_TOP = 0xFF1E2935;
            CarKit.OUTLINE = 0xFF243140;
            CarKit.EDGE = 0xFF2E4256;
            CarKit.TEXT = 0xFFEAF2F7;
            CarKit.DIM = 0xFFA3B3C2;
            CarKit.FAINT = 0xFF8296A9;
            CarKit.MUTED = 0xFF5B6B7C;
            CarKit.ACCENT = 0xFF00E5C7;
            CarKit.ACCENT_2 = 0xFF4CFF9F;
            CarKit.ON_ACCENT = 0xFF00211C;
            CarKit.AMBER = 0xFFFFB547;
            CarKit.RED = 0xFFFF6B7A;
            CarKit.BLUE = 0xFF5AA9FF;
            CarKit.VIOLET = 0xFFB69CFF;
            CarKit.GREEN = 0xFF4CFF9F;

            CarStyle.BG = 0xFF0E1013;
            CarStyle.TEXT = 0xFFE8EAED;
            CarStyle.TEXT_DIM = 0xFF9AA0A6;
            CarStyle.ACCENT = 0xFF00E5C7;
            CarStyle.ACCENT_2 = 0xFF4CFF9F;
            CarStyle.ON_ACCENT = 0xFF00211C;
            CarStyle.ACCENT_BG = 0xFF0F3B38;
            CarStyle.ITEM_BG = 0xFF23272E;
            CarStyle.CARD = 0xFF28292C;
            CarStyle.GOOD = 0xFF81C995;
            CarStyle.WARN = 0xFFFDD663;
            CarStyle.BAD = 0xFFF28B82;
            CarStyle.PILL_BG = 0xEE3C4043;
        } else {
            CarKit.BG = 0xFFF1F4F7;
            CarKit.SURFACE = 0xFFFFFFFF;
            CarKit.SURFACE_HI = 0xFFF2F5F8;
            CarKit.SURFACE_TOP = 0xFFD8DFE6;
            CarKit.OUTLINE = 0xFFC2CCD6;
            CarKit.EDGE = 0xFFA9B6C3;
            CarKit.TEXT = 0xFF0F1A24;
            CarKit.DIM = 0xFF46535F;
            CarKit.FAINT = 0xFF5B6977;
            CarKit.MUTED = 0xFF8994A0;
            CarKit.ACCENT = 0xFF00897B;
            CarKit.ACCENT_2 = 0xFF15A05A;
            CarKit.ON_ACCENT = 0xFFFFFFFF;
            CarKit.AMBER = 0xFFB25A00;
            CarKit.RED = 0xFFC62837;
            CarKit.BLUE = 0xFF1C6ED6;
            CarKit.VIOLET = 0xFF6F45D1;
            CarKit.GREEN = 0xFF128A47;

            CarStyle.BG = 0xFFF1F3F4;
            CarStyle.TEXT = 0xFF202124;
            CarStyle.TEXT_DIM = 0xFF5F6368;
            CarStyle.ACCENT = 0xFF00897B;
            CarStyle.ACCENT_2 = 0xFF15A05A;
            CarStyle.ON_ACCENT = 0xFFFFFFFF;
            CarStyle.ACCENT_BG = 0xFFD4F1EC;
            CarStyle.ITEM_BG = 0xFFDDE1E5;
            CarStyle.CARD = 0xFFFFFFFF;
            CarStyle.GOOD = 0xFF188038;
            CarStyle.WARN = 0xFFB06000;
            CarStyle.BAD = 0xFFC5221F;
            CarStyle.PILL_BG = 0xFFD2D8DF;
        }
        return changed;
    }

    /**
     * Colores que se pueden dejar fijos en la barra (Ajustes del coche): negros y grises para fundirse con las barras
     * del C10, blanco, y colores. 0 en Config = automático.
     */
    static final int[] PANEL_COLORS = {
            0xFF000000, 0xFF1E1E20, 0xFF2E3238, 0xFF5F6368,
            0xFFBDC1C6, 0xFFF1F3F4, 0xFF0D2B4E, 0xFF1967D2,
            0xFF00695C, 0xFF137333, 0xFF4E5B31, 0xFFF9AB00,
            0xFFE8710A, 0xFFC5221F, 0xFF6D1B2B, 0xFF6A1B9A,
    };

    /** Color fijo de la barra (uno de PANEL_COLORS), o 0: sigue el día y la noche. */
    private static volatile int fixedPanel;

    static void setFixedPanel(int color) {
        fixedPanel = color;
    }

    static int fixedPanel() {
        return fixedPanel;
    }

    /** Transparencia del panel (0-100): se funde con el negro de la pantalla del coche (detrás no hay otra cosa). */
    private static volatile int panelAlpha;

    static void setPanelAlpha(int pct) {
        panelAlpha = Math.max(0, Math.min(100, pct));
    }

    static int panelAlpha() {
        return panelAlpha;
    }

    /** Puro: color fundido con negro según la transparencia (0 = tal cual, 100 = negro). */
    static int dim(int color, int pct) {
        float k = 1f - Math.max(0, Math.min(100, pct)) / 100f;
        int r = Math.round(((color >> 16) & 0xFF) * k);
        int g = Math.round(((color >> 8) & 0xFF) * k);
        int b = Math.round((color & 0xFF) * k);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** Puro: un color desde el tono (0-360) y la claridad (0-1) de la barra de ajustes, con saturación fija. */
    static int fromHue(float hue, float light) {
        return android.graphics.Color.HSVToColor(new float[]{Math.max(0, Math.min(359.9f, hue)), 0.72f,
                Math.max(0.12f, Math.min(1f, light))});
    }

    /** ¿Color oscuro? (luminancia relativa, como WCAG): decide si el texto encima va claro u oscuro. */
    static boolean isDark(int color) {
        double r = channel((color >> 16) & 0xFF);
        double g = channel((color >> 8) & 0xFF);
        double b = channel(color & 0xFF);
        double l = 0.2126 * r + 0.7152 * g + 0.0722 * b;
        // Contraste igual con blanco (1,05) que con negro (0,05) en l ≈ 0,18.
        return l < 0.18;
    }

    private static double channel(int v) {
        double c = v / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    /** Texto e iconos de los botones del panel lateral: claros de noche, casi negros de día; con color fijo, según él. */
    static int navText() {
        return panelDark() ? 0xFFDADCE0 : 0xFF22282E;
    }

    /** Texto secundario sobre el panel (la batería). */
    static int navTextDim() {
        return panelDark() ? 0xFF9AA0A6 : 0xFF5F6368;
    }

    /** ¿El panel, tal como se ve (color fijo o del día, y con su transparencia), es oscuro? */
    private static boolean panelDark() {
        if (fixedPanel != 0 || panelAlpha > 0) return isDark(panelColor(0xFF1E1E20));
        return night;
    }

    /**
     * Color del panel: el fijo si se eligió; si no, de noche el gris elegido (que se funda con las barras del coche) y de
     * día, claro. Y fundido con negro según la transparencia.
     */
    static int panelColor(int nightGray) {
        int base = fixedPanel != 0 ? fixedPanel : night ? nightGray : 0xFFE9EDF1;
        return panelAlpha > 0 ? dim(base, panelAlpha) : base;
    }

    /** Fondo de la tarjeta de aviso del panel: ámbar o verde, oscuro de noche y claro de día. */
    static int alertBg(boolean ok) {
        if (night) return ok ? 0xFF133B2A : 0xFF3B2F14;
        return ok ? 0xFFDDF3E6 : 0xFFFCEBD3;
    }
}
