package com.headqlink.link;

/**
 * Ritmo de dibujo del relay GL hacia el encoder (puro, lo prueban los tests). Dos formas:
 * - rejilla (cadencia fija, o con una pantalla nuestra delante): un dibujo por tic de 1/fps, siempre con el frame más
 *   reciente;
 * - ritmo medio (GCRA): cada frame en cuanto llega, con medio periodo de tolerancia para el frame de AA que llega pronto
 *   tras uno tardío, pero nunca más de fps de media. Antes solo se exigía medio periodo desde el último dibujo, y dos
 *   fuentes a la vez (AA y nuestra capa) podían sumar el doble de dibujos (60 por segundo enviando a 30).
 * Solo lo usa el hilo GL (y el que crea el relay, antes de arrancarlo).
 */
final class FramePacer {
    private int fps;
    private long periodNs;
    private boolean fixed;
    /** Ritmo medio: hora teórica del siguiente dibujo (válida con primed). */
    private long dueNs;
    /** Rejilla: hora del siguiente tic (válida con primed). */
    private long nextTickNs;
    private boolean primed;

    FramePacer(int fps) {
        setFps(fps, false);
    }

    /** fps máximos (0 = sin límite) y si van en rejilla; vuelve a empezar el ritmo. */
    void setFps(int fps, boolean fixed) {
        this.fps = Math.max(0, fps);
        periodNs = this.fps > 0 ? 1_000_000_000L / this.fps : 0;
        this.fixed = fixed && periodNs > 0;
        primed = false;
    }

    int fps() {
        return fps;
    }

    boolean fixed() {
        return fixed;
    }

    long periodNs() {
        return periodNs;
    }

    /** ns que faltan para poder dibujar (<= 0: ya). grid: en rejilla (cadencia fija o pantalla nuestra delante). */
    long waitNs(long nowNs, boolean grid) {
        if (periodNs <= 0 || !primed) return 0;
        return grid ? nextTickNs - nowNs : dueNs - periodNs / 2 - nowNs;
    }

    /** Se ha dibujado en nowNs. */
    void onDraw(long nowNs, boolean grid) {
        if (periodNs <= 0) return;
        if (!primed) {
            primed = true;
            dueNs = nowNs + periodNs;
            nextTickNs = nowNs + periodNs;
            return;
        }
        // Ritmo medio: un hueco (enlace cerrado, sin frames) no deja «crédito» para una ráfaga después.
        dueNs = Math.max(dueNs, nowNs) + periodNs;
        // Rejilla: siguiente tic; si nos hemos retrasado más de un periodo, vuelve a empezar aquí.
        if (grid) nextTickNs = nowNs - nextTickNs > periodNs ? nowNs + periodNs : nextTickNs + periodNs;
        else nextTickNs = nowNs + periodNs;
    }
}
