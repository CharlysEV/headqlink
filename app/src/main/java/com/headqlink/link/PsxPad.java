package com.headqlink.link;

/**
 * Puro: el mando de la PlayStation en libretro (RETRO_DEVICE_ID_JOYPAD_*), para PsxEngine y sus pruebas. Por posición,
 * como la Super Nintendo: B (abajo) es ✕, A (derecha) ○, Y (izquierda) □ y X (arriba) △; L y R son L1 y R1.
 */
final class PsxPad {
    static final int J_L2 = 12, J_R2 = 13;

    private PsxPad() {
    }

    /** Puro: los botones de RetroPad (en su orden) → la máscara de libretro para la PlayStation. */
    static int toLibretro(int bits) {
        int out = SnesPad.toLibretro(bits);
        if ((bits & RetroPad.mask(RetroPad.Btn.L2)) != 0) out |= 1 << J_L2;
        if ((bits & RetroPad.mask(RetroPad.Btn.R2)) != 0) out |= 1 << J_R2;
        return out;
    }
}
