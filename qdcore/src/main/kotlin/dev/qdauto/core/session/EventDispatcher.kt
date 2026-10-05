package dev.qdauto.core.session

import dev.qdauto.core.util.QdLog
import dev.qdauto.core.util.e
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Entrega los callbacks del listener en orden y en un único hilo propio, para que el código de la app nunca corra
 * en el lector, el escritor ni el hilo del encoder. Tras [closeWith] no se acepta ningún evento más, así que el
 * evento final es siempre el último.
 */
internal class EventDispatcher(
    threadName: String,
    private val log: QdLog,
    private val tag: String,
    /** hql (C3): se ejecuta al empezar el hilo de eventos, dentro de él. */
    private val onThreadStart: (() -> Unit)? = null,
) {
    private val executor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue()) { r ->
        Thread({
            onThreadStart?.invoke()
            r.run()
        }, threadName).apply { isDaemon = true }
    }
    private val lock = Any()
    private var closed = false

    fun post(block: () -> Unit) {
        synchronized(lock) {
            if (closed) return
            try {
                executor.execute {
                    try {
                        block()
                    } catch (t: Throwable) {
                        log.e(tag, "excepción en el listener", t)
                    }
                }
            } catch (_: RejectedExecutionException) {
            }
        }
    }

    /** Encola [last] como último evento y deja de aceptar más. */
    fun closeWith(last: () -> Unit) {
        synchronized(lock) {
            post(last)
            closed = true
            executor.shutdown()
        }
    }

    fun awaitTermination(timeoutMs: Long): Boolean = executor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)
}
