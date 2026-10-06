package dev.qdauto.core.session

import dev.qdauto.core.util.RateWindow
import dev.qdauto.core.wire.FrameReader
import java.util.concurrent.atomic.AtomicLong

/** Contadores de la sesión; los actualizan varios hilos y [snapshot] los reúne en un [SessionStats]. */
internal class SessionCounters {
    private val startNanos = System.nanoTime()
    val bytesSent = AtomicLong()
    val messagesSent = AtomicLong()
    val messagesReceived = AtomicLong()
    val videoFramesSent = AtomicLong()
    val videoBytesSent = AtomicLong()
    val codecConfigsSent = AtomicLong()
    val keyframesSent = AtomicLong()
    val videoFramesRejected = AtomicLong()
    val keyframeRequests = AtomicLong()
    val carHeartbeats = AtomicLong()
    val touchEvents = AtomicLong()
    val garbageBytes = AtomicLong()
    val videoRate = RateWindow()

    /** hql: writes bloqueados con el coche hablando que la sesión aguantó (más de `writeStallTimeoutMs`). */
    val writeStalls = AtomicLong()

    /** hql: ceros de relleno escritos (trama por bloques del USB; 0 en TCP). */
    val paddingBytesSent = AtomicLong()

    /** hql: mensaje de vídeo más grande escrito (solo lo actualiza el hilo escritor). */
    @Volatile
    var maxVideoMessageBytes = 0L
        private set

    /** hql: el mayor frame descartado por tamaño (mensaje entero). */
    private val maxOversized = AtomicLong()

    /** hql: un mensaje de vídeo escrito (solo desde el hilo escritor). */
    fun videoMessageWritten(bytes: Int) {
        if (bytes > maxVideoMessageBytes) maxVideoMessageBytes = bytes.toLong()
    }

    /** hql: un frame descartado por tamaño (cualquier productor). */
    fun oversized(messageBytes: Int) {
        while (true) {
            val cur = maxOversized.get()
            if (messageBytes <= cur || maxOversized.compareAndSet(cur, messageBytes.toLong())) return
        }
    }

    @Volatile
    private var lastCarMessageNanos = 0L

    @Volatile
    private var maxCarGapNanos = 0L

    @Volatile
    private var lastCarHeartbeatNanos = 0L

    @Volatile
    private var lastCarHeartbeatIntervalMs: Long? = null

    /** Un mensaje del coche (solo desde el hilo lector). */
    fun carMessage() {
        val now = System.nanoTime()
        val last = lastCarMessageNanos
        if (last != 0L && now - last > maxCarGapNanos) maxCarGapNanos = now - last
        lastCarMessageNanos = now
        messagesReceived.incrementAndGet()
    }

    /** Un `HEARTBEAT` del coche (solo desde el hilo lector). */
    fun carHeartbeat() {
        carHeartbeats.incrementAndGet()
        val now = System.nanoTime()
        val last = lastCarHeartbeatNanos
        if (last != 0L) lastCarHeartbeatIntervalMs = (now - last) / 1_000_000
        lastCarHeartbeatNanos = now
    }

    fun snapshot(state: SessionState, queue: SendQueue, reader: FrameReader?, blockSize: Int = 0): SessionStats {
        val now = System.nanoTime()
        val (fps, kbps) = videoRate.rates(now)
        return SessionStats(
            state = state,
            uptimeMs = (now - startNanos) / 1_000_000,
            bytesSent = bytesSent.get(),
            bytesReceived = reader?.totalBytesRead ?: 0,
            messagesSent = messagesSent.get(),
            messagesReceived = messagesReceived.get(),
            videoFramesSent = videoFramesSent.get(),
            videoBytesSent = videoBytesSent.get(),
            codecConfigsSent = codecConfigsSent.get(),
            keyframesSent = keyframesSent.get(),
            videoFramesDropped = queue.droppedFrames,
            videoFramesRejected = videoFramesRejected.get(),
            keyframeRequests = keyframeRequests.get(),
            fps = fps,
            kbps = kbps,
            videoQueueFrames = queue.videoFrameDepth(),
            videoQueueBytes = queue.videoByteDepth(),
            controlQueueDepth = queue.controlDepth(),
            lastReceiveAgoMs = reader?.let { (now - it.lastActivityNanos) / 1_000_000 } ?: 0,
            maxCarGapMs = maxCarGapNanos / 1_000_000,
            carHeartbeats = carHeartbeats.get(),
            lastCarHeartbeatIntervalMs = lastCarHeartbeatIntervalMs,
            touchEvents = touchEvents.get(),
            garbageBytes = garbageBytes.get(),
            maxVideoMessageBytes = maxVideoMessageBytes,
            videoFramesOversized = queue.oversizedFrames,
            maxOversizedBytes = maxOversized.get(),
            writeStalls = writeStalls.get(),
            blockSize = blockSize,
            paddingBytesSent = paddingBytesSent.get(),
            paddingBytesReceived = reader?.paddingBytes ?: 0,
            carMessagesPadded = reader?.paddedMessages ?: 0,
            carMessagesUnpadded = reader?.unpaddedMessages ?: 0,
        )
    }
}
