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

    fun snapshot(state: SessionState, queue: SendQueue, reader: FrameReader?): SessionStats {
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
        )
    }
}
