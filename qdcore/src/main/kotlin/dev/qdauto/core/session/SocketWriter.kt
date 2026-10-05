package dev.qdauto.core.session

import dev.qdauto.core.util.QdLog
import dev.qdauto.core.util.d
import dev.qdauto.core.util.w
import dev.qdauto.core.wire.Direction
import dev.qdauto.core.wire.Header
import dev.qdauto.core.wire.MsgType
import dev.qdauto.core.wire.TraceEvent
import dev.qdauto.core.wire.Traces
import dev.qdauto.core.wire.VideoExtHeader
import dev.qdauto.core.wire.VideoMessage
import java.io.IOException
import java.io.OutputStream

/**
 * Bucle del hilo escritor: único que escribe en el socket, un `write()` por mensaje (como QDLink, LC/a.java:2666-2667),
 * en el orden que da [SendQueue] (control antes que vídeo).
 *
 * hql (C2): avisa de la finalización de cada frame ([finish]: `WRITTEN`, `FAILED` y los descartes por retraso) y
 * deja ver qué está escribiendo ([current], [lastWriteEndNanos]) para las consultas sin candado.
 */
internal class SocketWriter(
    private val queue: SendQueue,
    private val counters: SessionCounters,
    private val config: SessionConfig,
    private val trace: (TraceEvent) -> Unit,
    private val onWriteError: (IOException) -> Unit,
    private val onCloseAfter: (Outgoing) -> Unit,
    /** Finalización de un elemento (solo hace algo si es un frame con [FrameCompletion]). */
    private val finish: (Outgoing, FrameOutcome) -> Unit = { _, _ -> },
    /** `MAX_LAG` vació la cola: hay que pedir un IDR forzado. */
    private val onFlush: (Int) -> Unit = {},
    /** hql (C3): puerta de escritura de vídeo ([VideoWriteGate]); `null` = abierta. */
    private val gate: (() -> Boolean)? = null,
    private val gateMaxWaitNanos: Long = 250_000_000L,
    private val gatePollNanos: Long = 2_000_000L,
    private val log: QdLog = QdLog.NONE,
    private val tag: String = "QD/Writer",
) {
    /**
     * Elemento de vídeo que espera a la puerta y desde cuándo. Son campos (no variables de [nextItem]) para que el
     * control que sale mientras tanto no reinicie la espera máxima. Solo el hilo escritor.
     */
    private var waitingFor: Outgoing? = null
    private var waitStart = 0L

    /** Últimos registros de la puerta (como mucho uno cada 5 s por tipo). Solo el hilo escritor. */
    private var lastGateTimeoutLog = 0L
    private var lastGateErrorLog = 0L
    private var gateTimeouts = 0L

    /** `System.nanoTime()` del `write()` en curso, o 0 (para detectar escrituras bloqueadas). */
    @Volatile
    var writeStartNanos = 0L
        private set

    /** Elemento que se está escribiendo, o `null`. */
    @Volatile
    var current: Outgoing? = null
        private set

    /** Fin del último `write()` completo (0 = ninguno). */
    @Volatile
    var lastWriteEndNanos = 0L
        private set

    private val dropped = ArrayList<Outgoing>()

    fun run(out: OutputStream) {
        while (true) {
            val item = nextItem() ?: return
            val start = System.nanoTime()
            item.writeStartNanos = start
            current = item
            writeStartNanos = start
            try {
                out.write(item.bytes)
                out.flush()
            } catch (e: IOException) {
                writeStartNanos = 0
                current = null
                finish(item, FrameOutcome.FAILED)
                onWriteError(e)
                return
            }
            val end = System.nanoTime()
            item.writeEndNanos = end
            writeStartNanos = 0
            lastWriteEndNanos = end
            current = null
            written(item)
            finish(item, FrameOutcome.WRITTEN)
            if (item.closeAfter) {
                onCloseAfter(item)
                return
            }
        }
    }

    /**
     * Siguiente elemento a escribir (control primero), o `null` si la cola se cerró. Con [gate], el vídeo espera a que
     * se abra (como mucho [gateMaxWaitNanos] por elemento) sin frenar el control.
     */
    private fun nextItem(): Outgoing? {
        while (true) {
            val r = queue.poll(dropped)
            completeDropped()
            when (r) {
                is Polled.Control -> return r.item
                is Polled.Video -> {
                    val g = gate
                    if (g == null) {
                        queue.takeVideoHead(r.item)?.let { return it }
                        continue
                    }
                    if (waitingFor !== r.item) {
                        waitingFor = r.item
                        waitStart = System.nanoTime()
                    }
                    val open = gateOpen(g)
                    val waited = System.nanoTime() - waitStart
                    if (open || waited >= gateMaxWaitNanos) {
                        if (!open) noteGateTimeout(r.item, waited)
                        queue.takeVideoHead(r.item)?.let { return it }
                        continue
                    }
                    queue.awaitControl(gatePollNanos)
                }
                is Polled.Flushed -> onFlush(r.frames)
                Polled.Empty -> queue.awaitAny()
                Polled.Closed -> return null
            }
        }
    }

    private fun gateOpen(g: () -> Boolean): Boolean = try {
        g()
    } catch (t: Throwable) {
        val now = System.nanoTime()
        if (now - lastGateErrorLog > LOG_EVERY_NANOS || lastGateErrorLog == 0L) {
            lastGateErrorLog = now
            log.w(tag, "la puerta de vídeo lanzó una excepción; se trata como abierta", t)
        }
        true
    }

    private fun noteGateTimeout(item: Outgoing, waitedNanos: Long) {
        gateTimeouts++
        val now = System.nanoTime()
        if (now - lastGateTimeoutLog > LOG_EVERY_NANOS || lastGateTimeoutLog == 0L) {
            lastGateTimeoutLog = now
            log.d(tag, "puerta de vídeo cerrada ${waitedNanos / 1_000_000} ms: ${item.label} sale igualmente ($gateTimeouts veces)")
        }
    }

    private fun completeDropped() {
        if (dropped.isEmpty()) return
        for (d in dropped) finish(d, FrameOutcome.DROPPED)
        dropped.clear()
    }

    private companion object {
        const val LOG_EVERY_NANOS = 5_000_000_000L
    }

    private fun written(item: Outgoing) {
        val size = item.bytes.size
        counters.bytesSent.addAndGet(size.toLong())
        counters.messagesSent.incrementAndGet()
        if (item.kind == OutKind.CONTROL) {
            val text = item.text
            trace(
                if (text != null) {
                    Traces.ofJson(Direction.OUT, item.label, item.msgType ?: MsgType.CONTROL, item.bytes, text, config.traceMaxJsonChars)
                } else {
                    Traces.ofBinary(Direction.OUT, item.label, item.msgType, item.bytes, item.label, config.traceHexPrefixBytes)
                },
            )
            return
        }
        counters.videoBytesSent.addAndGet(size.toLong())
        when (item.kind) {
            OutKind.VIDEO_CONFIG -> counters.codecConfigsSent.incrementAndGet()
            OutKind.VIDEO_KEY -> counters.keyframesSent.incrementAndGet()
            else -> Unit
        }
        if (item.isFrame) {
            counters.videoFramesSent.incrementAndGet()
            counters.videoRate.add(size)
        }
        if (config.traceVideoFrames) {
            val p = VideoExtHeader.decode(item.bytes, Header.SIZE).params
            val waitedMs = (System.nanoTime() - item.enqueuedNanos) / 1_000_000
            val summary = "${size - VideoMessage.HEADER_SIZE} B ${p.width}x${p.height} app=${p.appType} " +
                "ang=${p.angle} or=${p.orientation} cola=${waitedMs}ms" + (if (item.ptsUs >= 0) " pts=${item.ptsUs}" else "")
            trace(Traces.ofVideo(Direction.OUT, item.label, size, summary))
        }
    }
}
