package com.headqlink.link;

/** Puro: el mando de la Super Nintendo en libretro (RETRO_DEVICE_ID_JOYPAD_*), para SnesEngine y sus pruebas. */
final class SnesPad {
    static final int J_B = 0, J_Y = 1, J_SELECT = 2, J_START = 3, J_UP = 4, J_DOWN = 5, J_LEFT = 6, J_RIGHT = 7, J_A = 8, J_X = 9,
            J_L = 10, J_R = 11;

    private SnesPad() {
    }

    /** Puro: los botones de RetroPad (en su orden) → la máscara de libretro para la SNES. */
    static int toLibretro(int bits) {
        int out = 0;
        out |= bit(bits, RetroPad.Btn.UP, J_UP);
        out |= bit(bits, RetroPad.Btn.DOWN, J_DOWN);
        out |= bit(bits, RetroPad.Btn.LEFT, J_LEFT);
        out |= bit(bits, RetroPad.Btn.RIGHT, J_RIGHT);
        out |= bit(bits, RetroPad.Btn.A, J_A);
        out |= bit(bits, RetroPad.Btn.B, J_B);
        out |= bit(bits, RetroPad.Btn.X, J_X);
        out |= bit(bits, RetroPad.Btn.Y, J_Y);
        out |= bit(bits, RetroPad.Btn.L, J_L);
        out |= bit(bits, RetroPad.Btn.R, J_R);
        out |= bit(bits, RetroPad.Btn.START, J_START);
        out |= bit(bits, RetroPad.Btn.SELECT, J_SELECT);
        return out;
    }

    private static int bit(int bits, RetroPad.Btn b, int to) {
        return (bits & RetroPad.mask(b)) != 0 ? 1 << to : 0;
    }
}
