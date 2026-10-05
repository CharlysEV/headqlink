package dev.qdauto.core.session

import dev.qdauto.core.TestSupport.waitUntil
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** hql (C2): políticas `NONE` y `MAX_LAG` ([VideoDropPolicy]); `BACKLOG` la cubren los tests originales. */
class VideoDropPolicyTest {
    private val sps = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0xC0.toByte(), 0x29)
    private val pps = byteArrayOf(0, 0, 0, 1, 0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())
    private fun idr(size: Int, fill: Byte = 1) = byteArrayOf(0, 0, 0, 1, 0x65) + ByteArray(size) { fill }
    private fun delta(size: Int) = byteArrayOf(0, 0, 0, 1, 0x41) + ByteArray(size) { 2 }

    private val closeables = ArrayList<AutoCloseable>()

    @AfterTest
    fun cleanup() = closeables.reversed().forEach { runCatching { it.close() } }

    @Test
    fun defaultPolicyIsTheValidatedOne() {
        assertEquals(VideoDropPolicy.BACKLOG, SessionConfig().videoDropPolicy)
        assertEquals(150, SessionConfig().videoMaxLagMs)
    }

    @Test
    fun noneNeverDropsForBacklogOnlyTheDeltasBeforeTheFirstIdr() {
        val server = MirrorServer(0).also { closeables += it }
        val car = ControlledCar(server.port).also { closeables += it }
        val listener = RecordingListener()
        val s = PhoneSession(
            server.accept(5_000),
            SessionConfig(heartbeatInitialDelayMs = 60_000, sendBufferBytes = 4096, videoDropPolicy = VideoDropPolicy.NONE, maxVideoMessageBytes = 0),
            listener,
        ).also { closeables += it }.start()
        val outcomes = ConcurrentHashMap<Int, FrameOutcome>()
        fun done(i: Int) = FrameCompletion { outcomes[i] = it.outcome }
        car.handshake()
        assertTrue(listener.streaming.await(5, TimeUnit.SECONDS))
        // Antes del primer IDR los P se tiran también con NONE.
        for (i in 0 until 3) assertFalse(s.sendFrame(delta(1000), 0, 1005, false, i.toLong(), done(i)))
        assertTrue(s.sendCodecConfig(sps + pps))
        assertTrue(s.sendFrame(idr(500_000), 0, 500_005, true, 3, done(3)))
        // El coche no lee: con BACKLOG se tirarían casi todos; con NONE se encolan los 196.
        for (i in 4 until 200) assertTrue(s.sendFrame(delta(10_000), 0, 10_005, false, i.toLong(), done(i)), "P$i")
        assertEquals((0 until 3).toSet(), outcomes.filterValues { it == FrameOutcome.DROPPED }.keys)
        assertEquals(3, s.stats().videoFramesDropped)
        assertTrue(s.videoQueueFrames() >= 190, "en cola: ${s.videoQueueFrames()}")
        assertEquals(0, s.videoFlushes())
    }

    @Test
    fun maxLagFlushesStaleDeltasAndResendsConfigBeforeTheNextIdr() {
        val server = MirrorServer(0).also { closeables += it }
        val car = ControlledCar(server.port).also { closeables += it }
        val listener = RecordingListener()
        val s = PhoneSession(
            server.accept(5_000),
            SessionConfig(
                heartbeatInitialDelayMs = 60_000,
                sendBufferBytes = 4096,
                videoDropPolicy = VideoDropPolicy.MAX_LAG,
                videoMaxLagMs = 150,
                minKeyframeRequestIntervalMs = 0,
                // Un IDR de 1 MB que bloquea el write() (el coche no lee): sin tope de tamaño.
                maxVideoMessageBytes = 0,
            ),
            listener,
        ).also { closeables += it }.start()
        val outcomes = ConcurrentHashMap<Int, FrameOutcome>()
        fun done(i: Int) = FrameCompletion { outcomes[i] = it.outcome }
        car.handshake()
        assertTrue(listener.streaming.await(5, TimeUnit.SECONDS))
        assertTrue(s.sendCodecConfig(sps + pps))
        val big = idr(1_000_000, fill = 7)
        assertTrue(s.sendFrame(big, 0, big.size, true, 0, done(0)))
        assertTrue(waitUntil(5_000) { s.io().writingLabel == "VIDEO_IDR" })
        // MAX_LAG no mira el atasco al encolar: entran todos.
        for (i in 1..3) assertTrue(s.sendFrame(delta(100), 0, 105, false, i.toLong(), done(i)))
        Thread.sleep(300) // los P se quedan viejos detrás del IDR bloqueado
        listener.keyframeReasons.clear()
        car.startReading()
        assertTrue(waitUntil(5_000) { outcomes.size >= 4 }, outcomes.toString())
        assertEquals(FrameOutcome.WRITTEN, outcomes[0])
        for (i in 1..3) assertEquals(FrameOutcome.DROPPED, outcomes[i], "P$i")
        assertEquals(1, s.videoFlushes())
        assertTrue(waitUntil(2_000) { KeyframeReason.BACKLOG in listener.keyframeReasons }, listener.keyframeReasons.toString())
        assertTrue(s.isWaitingForKeyframe())
        // Hasta el IDR se tiran los P; el IDR sale con SPS/PPS delante.
        assertFalse(s.sendFrame(delta(100), 0, 105, false, 4, done(4)))
        assertEquals(FrameOutcome.DROPPED, outcomes[4])
        val small = idr(50, fill = 9)
        assertTrue(s.sendFrame(small, 0, small.size, true, 5, done(5)))
        val video = car.videoPayloads(4)
        assertEquals(4, video.size, "vídeo recibido: ${video.map { it.size }}")
        assertContentEquals(sps + pps, video[0])
        assertContentEquals(big, video[1])
        assertContentEquals(sps + pps, video[2])
        assertContentEquals(small, video[3])
        assertTrue(waitUntil(2_000) { outcomes[5] == FrameOutcome.WRITTEN })
        assertFalse(s.isWaitingForKeyframe())
    }

    @Test
    fun maxLagKeepsAFreshQueue() {
        val server = MirrorServer(0).also { closeables += it }
        val car = FakeCar(server.port).also { closeables += it }
        val s = PhoneSession(
            server.accept(5_000),
            SessionConfig(heartbeatInitialDelayMs = 60_000, videoDropPolicy = VideoDropPolicy.MAX_LAG, sendVideoBeforePlay = true),
        ).also { closeables += it }.start()
        val written = java.util.concurrent.atomic.AtomicInteger()
        for (i in 0 until 100) {
            val body = if (i == 0) idr(2000) else delta(2000)
            assertTrue(s.sendFrame(body, 0, body.size, i == 0, i.toLong()) { if (it.outcome == FrameOutcome.WRITTEN) written.incrementAndGet() })
            Thread.sleep(2)
        }
        assertTrue(waitUntil(5_000) { written.get() == 100 })
        assertEquals(0, s.videoFlushes())
        assertEquals(0, s.stats().videoFramesDropped)
        car.drain()
    }
}
