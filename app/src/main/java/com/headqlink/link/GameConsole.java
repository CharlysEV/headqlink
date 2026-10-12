package com.headqlink.link;

import android.view.KeyEvent;

import java.util.Locale;

/**
 * Las consolas de Juegos: qué archivos son suyos (por extensión; los .zip, por el archivo de dentro), dónde se copian
 * las ROM (y su partida guardada) y cómo se reparte el mando Bluetooth entre sus botones. Todas comparten la misma
 * carpeta de juegos del móvil.
 */
enum GameConsole {
    NES("NES", "nes", new String[]{".nes", ".nsf"}),
    MD("Mega Drive", "md", new String[]{".md", ".gen", ".bin", ".smd"}),
    SNES("Super Nintendo", "snes", new String[]{".sfc", ".smc"});

    final String title;
    /** Subcarpeta de files/ donde se copia la ROM; ahí queda también la partida guardada (.sav). */
    final String dir;
    private final String[] exts;

    GameConsole(String title, String dir, String[] exts) {
        this.title = title;
        this.dir = dir;
        this.exts = exts;
    }

    /** Puro: ¿archivo de esta consola? (sin mirar dentro de los .zip). */
    boolean accepts(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        for (String e : exts) if (n.endsWith(e)) return true;
        return false;
    }

    static boolean isZip(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(".zip");
    }

    /** Puro: la consola de un archivo por su extensión, o null. */
    static GameConsole of(String name) {
        for (GameConsole c : values()) if (c.accepts(name)) return c;
        return null;
    }

    /** Puro: el nombre sin la extensión del juego (ni .zip). */
    static String title(String name) {
        return name.replaceAll("(?i)\\.(zip|nes|nsf|md|gen|bin|smd|sfc|smc)$", "");
    }

    /**
     * Puro: tecla de un mando de Android → botón, o null si no es del mando. En la NES, A y Y del mando son A, y B y X son
     * B (como estaba). En la Mega Drive, los tres de abajo en fila: X → A, A → B, B → C; y LB, Y, RB → X, Y, Z. En la
     * Super Nintendo, por posición (el mando de la SNES tiene la B abajo y la A a la derecha): A → B, B → A, X → Y,
     * Y → X, LB → L y RB → R.
     */
    RetroPad.Btn map(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                return RetroPad.Btn.UP;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return RetroPad.Btn.DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return RetroPad.Btn.LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return RetroPad.Btn.RIGHT;
            case KeyEvent.KEYCODE_BUTTON_START:
            case KeyEvent.KEYCODE_MENU:
                return RetroPad.Btn.START;
            case KeyEvent.KEYCODE_BUTTON_SELECT:
            case KeyEvent.KEYCODE_BACK:
                return RetroPad.Btn.SELECT;
            default:
                break;
        }
        if (this == SNES) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_BUTTON_A:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                    return RetroPad.Btn.B;
                case KeyEvent.KEYCODE_BUTTON_B:
                    return RetroPad.Btn.A;
                case KeyEvent.KEYCODE_BUTTON_X:
                    return RetroPad.Btn.Y;
                case KeyEvent.KEYCODE_BUTTON_Y:
                    return RetroPad.Btn.X;
                case KeyEvent.KEYCODE_BUTTON_L1:
                    return RetroPad.Btn.L;
                case KeyEvent.KEYCODE_BUTTON_R1:
                    return RetroPad.Btn.R;
                default:
                    return null;
            }
        }
        if (this == NES) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_BUTTON_A:
                case KeyEvent.KEYCODE_BUTTON_Y:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                    return RetroPad.Btn.A;
                case KeyEvent.KEYCODE_BUTTON_B:
                case KeyEvent.KEYCODE_BUTTON_X:
                    return RetroPad.Btn.B;
                default:
                    return null;
            }
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_X:
                return RetroPad.Btn.A;
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_DPAD_CENTER:
                return RetroPad.Btn.B;
            case KeyEvent.KEYCODE_BUTTON_B:
                return RetroPad.Btn.C;
            case KeyEvent.KEYCODE_BUTTON_L1:
                return RetroPad.Btn.X;
            case KeyEvent.KEYCODE_BUTTON_Y:
                return RetroPad.Btn.Y;
            case KeyEvent.KEYCODE_BUTTON_R1:
                return RetroPad.Btn.Z;
            default:
                return null;
        }
    }
}
