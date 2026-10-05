package com.headqlink.link;

/**
 * Ranura de un MediaAck de vídeo de Android Auto retenido por el freno (qdauto §4.8). Estados:
 * PENDIENTE → RETENIDO(release) → HECHO, o PENDIENTE → HECHO si el frame termina antes de que AA llame a hold.
 * Garantiza que release (el ack a AA) se ejecuta exactamente una vez si hold devolvió true, y nunca si devolvió false
 * (AA confirma en el acto). Sin Android: la prueban los tests. Thread-safe.
 */
final class AckSlot {
    private static final int PENDING = 0;
    private static final int HELD = 1;
    private static final int DONE = 2;

    /** Puerto (SessionPort) por el que salió el frame, para el drenado; puede ser null en los tests. */
    final Object port;
    private int state = PENDING;
    private Runnable release;
    private long heldAtNs;

    AckSlot(Object port) {
        this.port = port;
    }

    /** VideoTap.AckGate.hold: true = AA espera a release (se ejecutará una vez); false = AA confirma ya. */
    synchronized boolean hold(Runnable r) {
        if (state != PENDING || r == null) return false;
        state = HELD;
        release = r;
        heldAtNs = System.nanoTime();
        return true;
    }

    /**
     * Termina la ranura (frame escrito y drenado, descartado o sesión cerrada). Si estaba retenida, ejecuta el ack
     * (una sola vez, fuera del candado y sin dejar escapar excepciones). Devuelve los ms que estuvo retenida, o -1.
     */
    long release() {
        Runnable r;
        long held;
        synchronized (this) {
            if (state == DONE) return -1;
            boolean wasHeld = state == HELD;
            state = DONE;
            r = wasHeld ? release : null;
            release = null;
            held = wasHeld ? (System.nanoTime() - heldAtNs) / 1_000_000 : -1;
        }
        if (r != null) {
            try {
                r.run();
            } catch (RuntimeException e) {
                try {
                    L.w("ack a AA: " + e);
                } catch (Throwable ignored) {
                    // en los tests no hay L
                }
            }
        }
        return held;
    }

    synchronized boolean isDone() {
        return state == DONE;
    }

    synchronized boolean isHeld() {
        return state == HELD;
    }
}
