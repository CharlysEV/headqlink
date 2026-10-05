package com.headqlink.link;

/**
 * Antirrebote de las peticiones de IDR al encoder propio («último frame», patrón y app). El coche manda ráfagas de
 * KEY_FRAME_REQ (21 en una sesión del 2026-10-05) y el IDR es el frame más grande: uno por petición atascaba más el
 * enlace. El reenvío directo de AA tiene su propia política (KeyframePolicy).
 *
 * - Urgentes (STREAM_START, el primer IDR de la sesión; OVERSIZED, el anterior se descartó por grande): al momento.
 * - Las demás, al momento si el último IDR pedido fue hace DEBOUNCE_MS o más.
 * - Antes de eso, si el IDR pedido aún no ha salido, la petición se sirve con él (se salta); si ya salió, se aplaza
 *   una sola petición al final de la ventana (las demás se suman a ella).
 *
 * Sin Android y con el reloj (ms) como parámetro: la prueban los tests. Thread-safe.
 */
final class IdrRequestGate {
    static final long DEBOUNCE_MS = 600;
    /** Resultado de onRequest: pedir el IDR ya. */
    static final long NOW = 0;
    /** Resultado de onRequest: no hace falta (la sirve un IDR pedido o aplazado). */
    static final long SKIP = -1;
    private static final long NEVER = Long.MIN_VALUE / 4;

    private long lastFireMs = NEVER;
    private boolean idrSinceFire = true;
    private boolean deferred;

    private int fired;
    private int skipped;
    private int deferrals;
    private int windowFired;
    private int windowSkipped;
    private int windowDeferred;

    /** NOW, SKIP o los ms que hay que esperar para llamar a onDue. */
    synchronized long onRequest(long nowMs, boolean urgent) {
        if (urgent || nowMs - lastFireMs >= DEBOUNCE_MS) {
            fire(nowMs);
            return NOW;
        }
        if (!idrSinceFire || deferred) {
            skipped++;
            windowSkipped++;
            return SKIP;
        }
        deferred = true;
        deferrals++;
        windowDeferred++;
        return Math.max(1, lastFireMs + DEBOUNCE_MS - nowMs);
    }

    /** Vence el aplazamiento: true = pedir el IDR ya (false si otro se adelantó). */
    synchronized boolean onDue(long nowMs) {
        if (!deferred) return false;
        fire(nowMs);
        return true;
    }

    /** Salió un IDR del encoder. */
    synchronized void onIdr() {
        idrSinceFire = true;
    }

    /** Hay un IDR pedido que aún no ha salido. */
    synchronized boolean pending() {
        return !idrSinceFire;
    }

    private void fire(long nowMs) {
        lastFireMs = nowMs;
        idrSinceFire = false;
        deferred = false;
        fired++;
        windowFired++;
    }

    /** Línea para las estadísticas de 5 s si hubo antirrebote en la ventana (o null), y la reinicia. */
    synchronized String takeWindowLine() {
        String line = windowSkipped + windowDeferred == 0 ? null
                : "IDR pedidos al encoder: " + windowFired + " · aplazados " + windowDeferred + " · servidos con otro "
                + windowSkipped + " (antirrebote " + DEBOUNCE_MS + " ms)";
        windowFired = 0;
        windowSkipped = 0;
        windowDeferred = 0;
        return line;
    }

    synchronized String summary() {
        return "IDR pedidos " + fired + " · aplazados " + deferrals + " · servidos con otro " + skipped;
    }
}
