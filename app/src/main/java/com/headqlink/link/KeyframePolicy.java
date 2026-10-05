package com.headqlink.link;

/**
 * Cuándo pedir un IDR en el reenvío directo de Android Auto (qdauto §4.9). Forzar un IDR cuesta un ciclo de foco de
 * vídeo que congela la imagen ~0,7-0,9 s, así que:
 * - una petición se SALTA si hay un ciclo en curso (hasta WATCHDOG_MS) o si el último empezó hace menos de DEBOUNCE_MS;
 * - si la palanca de AA estaba ocupada (el ciclo no empezó), se reintenta una sola vez a RETRY_MS (la palanca solo
 *   está ocupada los ~100 ms del hueco); en el fork esa petición se perdía;
 * - un IDR visto cierra el ciclo;
 * - vigilante: si la sesión espera un IDR (el núcleo tira los P) y no ha llegado ninguno WATCHDOG_MS después del último
 *   ciclo, se lanza otro, como mucho uno cada WATCHDOG_MS (evita la pantalla negra cuando se pierde un ciclo).
 *
 * Sin Android y con el reloj (ms) como parámetro: la prueban los tests. Thread-safe.
 */
final class KeyframePolicy {
    static final long DEBOUNCE_MS = 600;
    static final long RETRY_MS = 150;
    static final long WATCHDOG_MS = 1500;
    private static final long NEVER = Long.MIN_VALUE / 4;

    private long lastAskMs = NEVER;
    private long lastWatchdogMs = NEVER;
    private boolean inFlight;
    private boolean retryUsed;

    // Estadísticas (para el log y el resumen de la sesión).
    private int fired;
    private int skipped;
    private int refused;
    private int retried;
    private int watchdogFired;
    private int served;

    /** Un ciclo que empezó por otro lado (al arrancar el reenvío, AaPassthroughSource ya pide uno). */
    synchronized void noteRequested(long nowMs) {
        lastAskMs = nowMs;
        inFlight = true;
        retryUsed = false;
    }

    /** ¿Lanzar un ciclo para esta petición? true = lanzarlo ya (queda anotado como en curso). */
    synchronized boolean onRequest(long nowMs) {
        long since = nowMs - lastAskMs;
        if ((inFlight && since < WATCHDOG_MS) || since < DEBOUNCE_MS) {
            skipped++;
            return false;
        }
        lastAskMs = nowMs;
        inFlight = true;
        retryUsed = false;
        fired++;
        return true;
    }

    /** El ciclo no pudo empezar. Devuelve el retardo del único reintento (ms), o -1 si ya se reintentó. */
    synchronized long onLeverRefused(long nowMs) {
        refused++;
        inFlight = false;
        if (retryUsed) return -1;
        retryUsed = true;
        return RETRY_MS;
    }

    /** Se lanza el reintento (sin mirar el antirrebote: la petición era buena). */
    synchronized void onRetry(long nowMs) {
        lastAskMs = nowMs;
        inFlight = true;
        retried++;
    }

    /** Llegó un IDR del origen: el ciclo en curso (si lo hay) está servido. */
    synchronized void onIdrSeen(long nowMs) {
        if (inFlight) served++;
        inFlight = false;
    }

    /** Cada ~500 ms con la sesión enganchada. true = lanzar un ciclo ya (queda anotado). */
    synchronized boolean watchdog(long nowMs, boolean waitingForIdr) {
        if (!waitingForIdr) return false;
        if (nowMs - lastAskMs < WATCHDOG_MS || nowMs - lastWatchdogMs < WATCHDOG_MS) return false;
        lastWatchdogMs = nowMs;
        lastAskMs = nowMs;
        inFlight = true;
        retryUsed = false;
        watchdogFired++;
        return true;
    }

    synchronized boolean inFlight() {
        return inFlight;
    }

    /** Ciclos lanzados en total (peticiones, reintentos y vigilante). */
    synchronized int cycles() {
        return fired + retried + watchdogFired;
    }

    synchronized String summary() {
        return "ciclos " + (fired + retried + watchdogFired) + " (peticiones " + fired + ", reintentos " + retried
                + ", vigilante " + watchdogFired + ") · saltadas " + skipped + " · palanca ocupada " + refused
                + " · servidos " + served;
    }
}
