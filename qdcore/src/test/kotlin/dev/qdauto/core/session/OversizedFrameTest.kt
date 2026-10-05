package dev.qdauto.core.session

import dev.qdauto.core.TestSupport.waitUntil
import dev.qdauto.core.json.JsonObject
import dev.qdauto.core.wire.CarMessages
import dev.qdauto.core.wire.MsgType
import dev.qdauto.core.wire.VideoMessage
import dev.qdauto.core.wire.WireMessage
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * hql: tope de tamaño de los mensajes de vídeo ([SessionConfig.maxVideoMessageBytes]). En el C10 (2026-10-05) cada corte
 * grave empezó escribiendo un IDR de más de 512 KiB (525 208, 538 390, 592 913 B): el coche dejó de leer y `WRITE_STALL`
 * cerró la sesión a los 10 s. Ningún mensaje así puede llegar al socket.
 */
class OversizedFrameTest {
    private val sps = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0xC0.toByte(), 0x29)
    private val pps = byteArrayOf(0, 0, 0, 1, 0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())

    /** IDR cuyo mensaje entero (48 B de cabeceras incluidos) mide [messageBytes]. */
    private fun idrMessage(messageBytes: Int, fill: Byte = 1): ByteArray {
        val payload = messageBytes - VideoMessage.HEADER_SIZE
        return byteArrayOf(0, 0, 0, 1, 0x65) + ByteArray(payload - 5) { fill }
    }

    private fun delta(size: Int) = byteArrayOf(0, 0, 0, 1, 0x41) + ByteArray(size) { 2 }

    private val closeables = ArrayList<AutoCloseable>()

    @AfterTest
    fun cleanup() = closeables.reversed().forEach { runCatching { it.close() } }

    private fun streaming(config: SessionConfig = SessionConfig(heartbeatInitialDelayMs = 60_000)): Triple<FakeCar, PhoneSession, RecordingListener> {
        val server = MirrorServer(0).also { closeables += it }
        val car = FakeCar(server.port).also { closeables += it }
        val listener = RecordingListener()
        val s = PhoneSession(server.accept(5_000), config, listener).also { closeables += it }.start()
        car.send(
            CarMessages.carInfo(
                JsonObject.of(
                    "Version" to "1", "CarType" to "2D4", "Platform" to 0, "PlatformVersion" to "1", "CarWidth" to 1920,
                    "CarHeight" to 882, "CarFactory" to "018", "HUFactory" to "119", "MirrorTypeReq" to 0,
                ),
            ),
        )
        car.send(CarMessages.videoSupReq(3))
        car.send(CarMessages.videoArgs(1920, 882, 3, 30, 5_080_320, 3))
        car.send(CarMessages.videoCtrl(1))
        assertTrue(listener.streaming.await(5, TimeUnit.SECONDS))
        assertTrue(s.sendCodecConfig(sps + pps))
        return Triple(car, s, listener)
    }

    /** Mensajes de vídeo que ha recibido el coche hasta que llega uno cuyo payload acaba en [lastFill]. */
    private fun videoUntil(car: FakeCar, lastFill: Byte): List<WireMessage.Frame> {
        val out = ArrayList<WireMessage.Frame>()
        while (true) {
            val m = car.nextNonHeartbeat(10_000)
            if (m !is WireMessage.Frame || m.header.msgType != MsgType.VIDEO) continue
            out += m
            val p = m.payload()
            if (p.isNotEmpty() && p.last() == lastFill) return out
        }
    }

    @Test
    fun defaultCapIs480KiBUnderTheCarLimit() {
        assertEquals(480 * 1024, SessionConfig().maxVideoMessageBytes)
        assertEquals(SessionConfig.DEFAULT_MAX_VIDEO_MESSAGE_BYTES, SessionConfig().maxVideoMessageBytes)
        assertTrue(SessionConfig.DEFAULT_MAX_VIDEO_MESSAGE_BYTES < SessionConfig.CAR_RECEIVER_LIMIT_BYTES)
        // El más pequeño que colgó el coche (525 208 B) pasa del límite medido; el tope deja margen incluso bajo el más
        // grande que pasó (493 568 B).
        assertTrue(525_208 > SessionConfig.CAR_RECEIVER_LIMIT_BYTES)
        assertTrue(SessionConfig.DEFAULT_MAX_VIDEO_MESSAGE_BYTES < 493_568)
    }

    @Test
    fun oversizedIdrNeverReachesTheCarItsDeltasWaitAndASmallerIdrIsRequested() {
        val (car, s, listener) = streaming()
        val outcomes = ConcurrentHashMap<Int, FrameOutcome>()
        fun done(i: Int) = FrameCompletion { outcomes[i] = it.outcome }

        // Primer IDR normal y un P.
        val first = idrMessage(300_000, fill = 3)
        assertTrue(s.sendFrame(first, 0, first.size, true, 0, done(0)))
        assertTrue(s.sendFrame(delta(1_000), 0, 1_005, false, 1, done(1)))

        // El IDR de 538 390 B del coche (mensaje entero): fuera, sin copiarlo ni encolarlo.
        val big = idrMessage(538_390, fill = 4)
        assertFalse(s.sendFrame(big, 0, big.size, true, 2, done(2)))
        assertEquals(FrameOutcome.DROPPED, outcomes[2])
        // Los P que dependen de él tampoco salen.
        assertFalse(s.sendFrame(delta(1_000), 0, 1_005, false, 3, done(3)))
        assertEquals(FrameOutcome.DROPPED, outcomes[3])
        assertTrue(s.isWaitingForKeyframe())

        // Aviso a la app (antes que la petición de IDR) y petición de un IDR más pequeño.
        assertTrue(waitUntil(2_000) { KeyframeReason.OVERSIZED in listener.keyframeReasons }, listener.keyframeReasons.toString())
        assertEquals(1, listener.oversized.size)
        val o = listener.oversized[0]
        assertEquals(538_390, o.messageBytes)
        assertTrue(o.isKeyframe)
        assertEquals(480 * 1024, o.limitBytes)
        assertEquals(1, o.count)
        val events = listener.events.toList()
        assertTrue(events.indexOf("oversized:538390") < events.indexOf("keyframe:OVERSIZED"), events.toString())

        // El siguiente IDR (pequeño) sale con SPS/PPS delante, y con él vuelven los P (ByteBuffer, como MediaCodec).
        val small = idrMessage(250_000, fill = 5)
        assertTrue(s.sendFrame(ByteBuffer.wrap(small), true, 4, done(4)))
        val last = byteArrayOf(0, 0, 0, 1, 0x41, 6, 6, 6)
        assertTrue(s.sendFrame(last, 0, last.size, false, 5, done(5)))

        val received = videoUntil(car, lastFill = 6)
        val sizes = received.map { it.header.totalSize }
        assertTrue(sizes.all { it <= 480 * 1024 }, sizes.toString())
        // SPS/PPS, IDR 1, P, SPS/PPS reenviado, IDR pequeño, P.
        assertEquals(listOf(48 + 16, 300_000, 48 + 1_005, 48 + 16, 250_000, 48 + 8), sizes)
        assertContentEquals(sps + pps, received[3].payload())
        assertTrue(waitUntil(2_000) { outcomes[5] == FrameOutcome.WRITTEN })

        val st = s.stats()
        assertEquals(1, st.videoFramesOversized)
        assertEquals(538_390, st.maxOversizedBytes)
        assertEquals(300_000, st.maxVideoMessageBytes)
        assertEquals(2, st.videoFramesDropped)
    }

    @Test
    fun oversizedDeltaAlsoCutsTheChainAndTheCapCanBeChangedOrDisabled() {
        val (car, s, listener) = streaming(SessionConfig(heartbeatInitialDelayMs = 60_000, maxVideoMessageBytes = 100_000))
        val first = idrMessage(100_000, fill = 3)
        assertTrue(s.sendFrame(first, 0, first.size, true, 0))
        assertFalse(s.sendFrame(delta(100_000), false, 1))
        assertFalse(s.sendFrame(delta(10), false, 2))
        assertTrue(waitUntil(2_000) { listener.oversized.size == 1 })
        assertFalse(listener.oversized[0].isKeyframe)
        val next = idrMessage(50_000, fill = 7)
        assertTrue(s.sendFrame(next, 0, next.size, true, 3))
        val sizes = videoUntil(car, lastFill = 7).map { it.header.totalSize }
        assertTrue(sizes.all { it <= 100_000 }, sizes.toString())
        assertEquals(1, s.stats().videoFramesOversized)

        val (car2, s2, listener2) = streaming(SessionConfig(heartbeatInitialDelayMs = 60_000, maxVideoMessageBytes = 0))
        val huge = idrMessage(600_000, fill = 8)
        assertTrue(s2.sendFrame(huge, 0, huge.size, true, 0))
        assertEquals(listOf(48 + 16, 600_000), videoUntil(car2, lastFill = 8).map { it.header.totalSize })
        assertTrue(listener2.oversized.isEmpty())
        assertEquals(0, s2.stats().videoFramesOversized)
    }
}
