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

    /** Texto e iconos de los botones del panel lateral: claros de noche, casi negros de día. */
    static int navText() {
        return night ? 0xFFDADCE0 : 0xFF22282E;
    }

    /** Color del panel: de noche, el gris elegido (que se funda con las barras del coche); de día, claro. */
    static int panelColor(int nightGray) {
        return night ? nightGray : 0xFFE9EDF1;
    }

    /** Fondo de la tarjeta de aviso del panel: ámbar o verde, oscuro de noche y claro de día. */
    static int alertBg(boolean ok) {
        if (night) return ok ? 0xFF133B2A : 0xFF3B2F14;
        return ok ? 0xFFDDF3E6 : 0xFFFCEBD3;
    }
}
