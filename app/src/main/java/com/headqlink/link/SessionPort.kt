package com.headqlink.link

import dev.qdauto.core.session.FrameCompletion
import dev.qdauto.core.session.FrameOutcome
import dev.qdauto.core.session.PhoneSession
import dev.qdauto.core.session.SessionState
import dev.qdauto.core.session.VideoOverrides
import java.util.Locale
import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * Fachada «amable para Java» sobre una [PhoneSession] del motor QDAuto (qdauto §4.6): envío de vídeo con su propia
 * finalización (estadísticas, traza de rendimiento y, encadenada, la del freno a AA), cabecera de vídeo, consultas de
 * cola y el estado del socket (`NetStat`) por hilo: cada hilo usuario tiene su duplicado del descriptor, porque el
 * array de muestras no es seguro entre hilos. Todos se cierran con [close].
 */
internal class SessionPort(val session: PhoneSession) {
    val id: Int get() = session.id

    @Volatile
    var closed = false
        private set

    val stats = SendStats()

    /** Socket (por jitter de envío, como SspSession). */
    @Volatile
    var socketJitter: Jitter? = null

    private val netStats = AtomicReferenceArray<NetStat?>(SLOTS)
    private val failed = BooleanArray(SLOTS)

    @Volatile
    private var header: VideoOverrides? = null

    /** Hilo GL: bytes en la cola del kernel (SIOCOUTQ), o -1 sin NetStat. */
    fun gateOutq(): Int = sample(GATE)?.get(0) ?: -1

    /** Hilo escritor del núcleo (puerta de escritura del freno). */
    fun writerOutq(): Int = sample(WRITER)?.get(0) ?: -1

    /** Hilo aa-ack-drain (drenado del freno). */
    fun drainOutq(): Int = sample(DRAIN)?.get(0) ?: -1

    /** Hilo net-monitor: copia de las 12 cifras de NetStat (cola, rtt, retrans, cwnd…), o null. */
    fun monitorSample(): IntArray? = sample(MONITOR)?.copyOf()

    fun isStreaming(): Boolean = session.state == SessionState.STREAMING && !closed

    fun videoQueueFrames(): Int = session.videoQueueFrames()

    fun waitingForIdr(): Boolean = session.isWaitingForKeyframe()

    /** SPS/PPS: el núcleo lo guarda y lo manda (o lo pone delante del próximo IDR). */
    fun sendConfig(csd: ByteArray) {
        session.sendCodecConfig(csd)
    }

    /**
     * Encola un frame. Siempre con una finalización propia (estadísticas, `PerfTrace.frame` y jitter del socket) que
     * después llama a [done] (la ranura del freno), si la hay. `false` si no entró (ya avisado).
     */
    fun sendFrame(data: ByteArray, off: Int, len: Int, key: Boolean, ptsUs: Long, done: FrameCompletion?): Boolean {
        val completion = FrameCompletion { d ->
            stats.onDone(d)
            if (d.outcome == FrameOutcome.WRITTEN) {
                socketJitter?.tick()
                val lagMs = (d.writeStartNanos - d.enqueuedNanos) / 1_000_000
                val writeMs = (d.writeEndNanos - d.writeStartNanos) / 1_000_000
                PerfTrace.frame(d.payloadBytes, d.isKeyframe, lagMs, writeMs, d.queuedFramesAfter)
            }
            done?.onFrameDone(d)
        }
        return session.sendFrame(data, off, len, key, ptsUs, completion)
    }

    /** Campos de la cabecera de vídeo que se fuerzan (`null` = los del coche); solo se aplica si cambia. */
    fun setHeader(w: Int, h: Int, fps: Int?, bitrate: Int?, gop: Int?) {
        val o = VideoOverrides(width = w, height = h, fps = fps, bitrate = bitrate, gop = gop)
        if (o == header) return
        header = o
        session.setVideoOverrides(o)
    }

    fun headerText(): String = header?.let { "${it.width}x${it.height}" } ?: "${session.videoParams.width}x${session.videoParams.height}"

    /** Cierra todos los NetStat (en `onClosed`). El FIN al coche lo garantiza el `shutdown` del núcleo. */
    fun close() {
        closed = true
        for (i in 0 until SLOTS) {
            val ns = netStats.getAndSet(i, null) ?: continue
            try {
                ns.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun sample(slot: Int): IntArray? {
        if (closed) return null
        var ns = netStats.get(slot)
        if (ns == null) {
            if (failed[slot]) return null
            ns = NetStat.open(session.socket)
            if (ns == null) {
                failed[slot] = true
                return null
            }
            if (!netStats.compareAndSet(slot, null, ns)) {
                try {
                    ns.close()
                } catch (_: Exception) {
                }
                ns = netStats.get(slot) ?: return null
            }
            if (closed) {
                close()
                return null
            }
        }
        return try {
            if (ns.sample()) ns.v else null
        } catch (_: RuntimeException) {
            null
        }
    }

    /**
     * Estadísticas de envío de una sesión: la ventana de 5 s (para el log, como `maybeLogStats` de SspSession) y los
     * totales (para el resumen de la sesión). Las actualizan el escritor, los productores y el que cierra.
     */
    class SendStats {
        private var winStartNs = System.nanoTime()
        private var winFrames = 0
        private var winBytes = 0L
        private var winMaxWriteMs = 0L
        private var winMaxLagMs = 0L
        private var winDropped = 0

        var frames = 0L
            private set
        var bytes = 0L
            private set
        var keyframes = 0L
            private set
        var dropped = 0L
            private set
        var failed = 0L
            private set
        var maxWriteMs = 0L
            private set
        var maxLagMs = 0L
            private set

        @Volatile
        var firstFrameNanos = 0L
            private set

        @Volatile
        var firstIdrNanos = 0L
            private set

        @Synchronized
        fun onDone(d: dev.qdauto.core.session.FrameDone) {
            when (d.outcome) {
                FrameOutcome.WRITTEN -> {
                    val writeMs = (d.writeEndNanos - d.writeStartNanos) / 1_000_000
                    val lagMs = (d.writeStartNanos - d.enqueuedNanos) / 1_000_000
                    frames++
                    bytes += d.payloadBytes
                    if (d.isKeyframe) {
                        keyframes++
                        if (firstIdrNanos == 0L) firstIdrNanos = d.writeEndNanos
                    }
                    if (firstFrameNanos == 0L) firstFrameNanos = d.writeEndNanos
                    if (writeMs > maxWriteMs) maxWriteMs = writeMs
                    if (lagMs > maxLagMs) maxLagMs = lagMs
                    winFrames++
                    winBytes += d.payloadBytes
                    if (writeMs > winMaxWriteMs) winMaxWriteMs = writeMs
                    if (lagMs > winMaxLagMs) winMaxLagMs = lagMs
                }
                FrameOutcome.DROPPED -> {
                    dropped++
                    winDropped++
                }
                FrameOutcome.FAILED -> failed++
                else -> Unit
            }
        }

        /** Resumen de la ventana (fps, kbps, máximos y descartes) y la reinicia. Null si ha pasado menos de 5 s. */
        @Synchronized
        fun takeWindow(flushes: Long, force: Boolean = false): Window? {
            val now = System.nanoTime()
            val secs = (now - winStartNs) / 1e9
            if (!force && secs < 5.0) return null
            val w = Window(
                fps = if (secs > 0) winFrames / secs else 0.0,
                kbps = if (secs > 0) winBytes * 8 / secs / 1000 else 0.0,
                maxWriteMs = winMaxWriteMs,
                maxLagMs = winMaxLagMs,
                dropped = winDropped,
                flushes = flushes,
            )
            winStartNs = now
            winFrames = 0
            winBytes = 0
            winMaxWriteMs = 0
            winMaxLagMs = 0
            winDropped = 0
            return w
        }
    }

    class Window(val fps: Double, val kbps: Double, val maxWriteMs: Long, val maxLagMs: Long, val dropped: Int, val flushes: Long) {
        /** Misma línea que `SspSession.maybeLogStats`. */
        fun line(): String = String.format(
            Locale.US, "TX %.1f fps  %.0f kbps  max write %d ms  max lag %d ms  descartados %d (vaciados %d)",
            fps, kbps, maxWriteMs, maxLagMs, dropped, flushes,
        )

        fun short(): String = String.format(Locale.getDefault(), "%.0f fps · %.1f Mbps", fps, kbps / 1000)
    }

    private companion object {
        const val GATE = 0
        const val WRITER = 1
        const val DRAIN = 2
        const val MONITOR = 3
        const val SLOTS = 4
    }
}
