package com.headqlink.link;

import com.grapeshot.halfnes.ui.PuppetController;

/**
 * Mando del jugador 1 de cualquier consola: cruceta y los botones de la Mega Drive de seis botones (A, B, C, X, Y, Z,
 * Start y Mode, que en la NES es Select). Lo mueven NesInput (mando Bluetooth) y los botones táctiles; cada núcleo lo lee
 * una vez por fotograma. El orden de los bits es el de ClownMDEmu, así la Mega Drive los usa tal cual.
 */
final class RetroPad {
    enum Btn {UP, DOWN, LEFT, RIGHT, A, B, C, X, Y, Z, START, SELECT}

    private int bits;
    /** Lo pulsado desde la última lectura: un toque más corto que un fotograma también cuenta. */
    private int sticky;
    private int hatX, hatY;
    /** halfNES lee su propio mando: si lo hay, se le pasan los botones de la NES. */
    private final PuppetController puppet;

    RetroPad() {
        this(null);
    }

    RetroPad(PuppetController puppet) {
        this.puppet = puppet;
    }

    static int mask(Btn b) {
        return 1 << b.ordinal();
    }

    synchronized void press(Btn b) {
        bits |= mask(b);
        sticky |= mask(b);
        PuppetController.Button n = nes(b);
        if (puppet != null && n != null) puppet.pressButton(n);
    }

    synchronized void release(Btn b) {
        bits &= ~mask(b);
        PuppetController.Button n = nes(b);
        if (puppet != null && n != null) puppet.releaseButton(n);
    }

    synchronized void releaseAll() {
        bits = 0;
        sticky = 0;
        hatX = 0;
        hatY = 0;
        if (puppet != null) puppet.resetButtons();
    }

    /** Lo pulsado ahora más lo pulsado y ya soltado desde la última lectura (la hace el núcleo, una por fotograma). */
    synchronized int read() {
        int r = bits | sticky;
        sticky = 0;
        return r;
    }

    /** Lo pulsado ahora (sin consumir los toques cortos). */
    synchronized int bits() {
        return bits;
    }

    /** Ejes de cruceta o stick (-1..1) → arriba, abajo, izquierda y derecha, soltando lo que ya no se pulsa. */
    synchronized void axes(float x, float y) {
        int nx = x < -0.5f ? -1 : x > 0.5f ? 1 : 0;
        int ny = y < -0.5f ? -1 : y > 0.5f ? 1 : 0;
        if (nx != hatX) {
            release(Btn.LEFT);
            release(Btn.RIGHT);
            if (nx < 0) press(Btn.LEFT);
            if (nx > 0) press(Btn.RIGHT);
            hatX = nx;
        }
        if (ny != hatY) {
            release(Btn.UP);
            release(Btn.DOWN);
            if (ny < 0) press(Btn.UP);
            if (ny > 0) press(Btn.DOWN);
            hatY = ny;
        }
    }

    /** Puro: los bits del mando → el byte de la NES (A, B, Select, Start, arriba, abajo, izquierda, derecha; bit 0 → 7). */
    static int nesBits(int bits) {
        int out = 0;
        if ((bits & mask(Btn.A)) != 0) out |= 0x01;
        if ((bits & mask(Btn.B)) != 0) out |= 0x02;
        if ((bits & mask(Btn.SELECT)) != 0) out |= 0x04;
        if ((bits & mask(Btn.START)) != 0) out |= 0x08;
        if ((bits & mask(Btn.UP)) != 0) out |= 0x10;
        if ((bits & mask(Btn.DOWN)) != 0) out |= 0x20;
        if ((bits & mask(Btn.LEFT)) != 0) out |= 0x40;
        if ((bits & mask(Btn.RIGHT)) != 0) out |= 0x80;
        return out;
    }

    private static PuppetController.Button nes(Btn b) {
        switch (b) {
            case UP:
                return PuppetController.Button.UP;
            case DOWN:
                return PuppetController.Button.DOWN;
            case LEFT:
                return PuppetController.Button.LEFT;
            case RIGHT:
                return PuppetController.Button.RIGHT;
            case A:
                return PuppetController.Button.A;
            case B:
                return PuppetController.Button.B;
            case START:
                return PuppetController.Button.START;
            case SELECT:
                return PuppetController.Button.SELECT;
            default:
                return null;
        }
    }
}
