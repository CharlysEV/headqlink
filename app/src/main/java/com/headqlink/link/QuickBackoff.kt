package com.headqlink.link

/**
 * Espera entre intentos de conexión del motor QDAuto (qdauto §4.4): tras una sesión que llegó a vídeo se reintenta ya
 * (el C10 se reanuncia en < 1 s tras cortar); tras sesiones fallidas, 0,5 → 1 → 2 → 4 → 5 s. Una pausa (aplicar
 * ajustes) retrasa el siguiente intento y no la anula el final de la sesión. Reloj inyectable (ms): sin Android, la
 * prueban los tests.
 */
internal class QuickBackoff(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private var failureUntil = Long.MIN_VALUE / 4
    private var pauseUntil = Long.MIN_VALUE / 4
    private var failures = 0

    /** Se puede intentar conectar ya. */
    @Synchronized
    fun canAttempt(): Boolean = clock() >= maxOf(failureUntil, pauseUntil)

    /** Milisegundos que faltan para poder intentarlo (0 = ya). */
    @Synchronized
    fun waitMs(): Long = maxOf(0L, maxOf(failureUntil, pauseUntil) - clock())

    /** Terminó una sesión; [reachedStreaming] = llegó a vídeo y escribió algún frame. */
    @Synchronized
    fun onSessionEnded(reachedStreaming: Boolean) {
        val now = clock()
        if (reachedStreaming) {
            failures = 0
            failureUntil = now
        } else {
            failures++
            failureUntil = now + minOf(MAX_MS, BASE_MS shl (failures - 1).coerceIn(0, 20))
        }
    }

    /** El coche no conectó tras el ACK: sigue anunciándose y se reintenta con el broadcast siguiente (sin espera nueva). */
    @Synchronized
    fun onAcceptTimeout() {
        failureUntil = minOf(failureUntil, clock())
    }

    /** Nada de intentos durante [ms] (p. ej. mientras Android Auto renegocia tras aplicar ajustes). */
    @Synchronized
    fun pause(ms: Long) {
        pauseUntil = maxOf(pauseUntil, clock() + ms)
    }

    @get:Synchronized
    val consecutiveFailures: Int get() = failures

    companion object {
        const val BASE_MS = 500L
        const val MAX_MS = 5_000L
    }
}
