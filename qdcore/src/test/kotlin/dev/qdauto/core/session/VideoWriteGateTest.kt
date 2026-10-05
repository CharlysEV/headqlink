package dev.qdauto.core.session

import dev.qdauto.core.TestSupport.waitUntil
import dev.qdauto.core.wire.CarMessages
import dev.qdauto.core.wire.Cmd
import dev.qdauto.core.wire.ControlMessage
import dev.qdauto.core.wire.MsgType
import dev.qdauto.core.wire.WireMessage
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** hql (C3): la puerta de escritura frena el vídeo sin frenar el control ([VideoWriteGate]). */
class VideoWriteGateTest {
    private val idr = byteArrayOf(0, 0, 0, 1, 0x65, 0x11, 0x22, 0x33)
    private val closeables = ArrayList<AutoCloseable>()

    @AfterTest
    fun cleanup() = closeables.reversed().forEach { runCatching { it.close() } }

    private class Rig(val car: FakeCar, val session: PhoneSession, val listener: RecordingListener)

    private fun rig(config: SessionConfig): Rig {
        val server = MirrorServer(0).also { closeables += it }
        val car = FakeCar(server.port).also { closeables += it }
        val listener = RecordingListener()
        val session = PhoneSession(server.accept(5_000), config, listener).also { closeables += it }
        session.start()
        car.next() // AppStatus
        car.send(CarMessages.videoArgs(1920, 1080, 3, 30, 4_000_000, 1))
        car.send(CarMessages.videoCtrl(1))
        assertTrue(listener.streaming.await(5, TimeUnit.SECONDS))
        return Rig(car, session, listener)
    }

    /** Tiempo (ms) hasta que el coche recibe el siguiente mensaje que cumple [match]; el resto se va guardando. */
    private fun FakeCar.msUntil(timeoutMs: Long, seen: MutableList<WireMessage>, match: (WireMessage) -> Boolean): Long {
        val t0 = System.nanoTime()
        while (true) {
            val m = next(timeoutMs)
            seen += m
            if (match(m)) return (System.nanoTime() - t0) / 1_000_000
        }
    }

    private fun isVideo(m: WireMessage) = m is WireMessage.Frame && m.header.msgType == MsgType.VIDEO

    @Test
    fun closedGateHoldsVideoButNotHeartbeatsOrReplies() {
        val open = AtomicBoolean(false)
        val calls = AtomicInteger()
        val lockViolations = AtomicInteger()
        var sessionSeen: PhoneSession? = null
        val r = rig(
            SessionConfig(
                heartbeatInitialDelayMs = 20,
                heartbeatPeriodMs = 50,
                videoWriteGateMaxWaitMs = 600,
                videoWriteGatePollMs = 2,
                videoWriteGate = VideoWriteGate { s ->
                    calls.incrementAndGet()
                    sessionSeen = s
                    if (s.queue.isLockHeldByCurrentThread()) lockViolations.incrementAndGet()
                    open.get()
                },
            ),
        )
        val seen = ArrayList<WireMessage>()
        r.car.drain()
        assertTrue(r.session.sendFrame(idr, true, 0))
        val sentAt = System.nanoTime()
        // Con el vídeo retenido, la respuesta de control sale enseguida y los heartbeats siguen.
        r.car.send(CarMessages.landModeReq(1))
        val replyMs = r.car.msUntil(2_000, seen) { it is WireMessage.Frame && ControlMessage.parse(it.header, it.payloadText()).cmd == Cmd.LAND_MODE_RSP }
        assertTrue(replyMs < 300, "LAND_MODE_RSP tardó $replyMs ms")
        // Los heartbeats (cada 50 ms) no reinician la espera máxima del vídeo.
        r.car.msUntil(2_000, seen) { isVideo(it) }
        val videoMs = (System.nanoTime() - sentAt) / 1_000_000
        assertTrue(videoMs >= 500, "el vídeo salió a los $videoMs ms con la puerta cerrada")
        assertTrue(videoMs < 3_000, "el vídeo tardó $videoMs ms")
        assertTrue(FakeCar.heartbeatCount(seen) >= 3, "heartbeats durante la espera: ${FakeCar.heartbeatCount(seen)}")
        assertTrue(calls.get() > 10, "consultas a la puerta: ${calls.get()}")
        assertSame(r.session, sessionSeen)
        assertEquals(0, lockViolations.get())
        // Con la puerta abierta, el vídeo sale al momento.
        open.set(true)
        val t0 = System.nanoTime()
        assertTrue(r.session.sendFrame(idr, true, 1))
        r.car.msUntil(2_000, seen) { isVideo(it) }
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 300)
    }

    @Test
    fun aThrowingGateCountsAsOpen() {
        val r = rig(
            SessionConfig(
                heartbeatInitialDelayMs = 60_000,
                videoWriteGateMaxWaitMs = 5_000,
                videoWriteGate = VideoWriteGate { throw IllegalStateException("prueba") },
            ),
        )
        val t0 = System.nanoTime()
        assertTrue(r.session.sendFrame(idr, true, 0))
        r.car.msUntil(3_000, ArrayList()) { isVideo(it) }
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 1_000)
        assertFalse(r.session.isClosed)
    }

    @Test
    fun theGateOpeningReleasesVideoWithinAPoll() {
        val open = AtomicBoolean(false)
        val r = rig(
            SessionConfig(
                heartbeatInitialDelayMs = 60_000,
                videoWriteGateMaxWaitMs = 10_000,
                videoWriteGatePollMs = 2,
                videoWriteGate = VideoWriteGate { open.get() },
            ),
        )
        assertTrue(r.session.sendFrame(idr, true, 0))
        Thread.sleep(200)
        assertEquals(1, r.session.videoQueueFrames())
        val t0 = System.nanoTime()
        open.set(true)
        r.car.msUntil(2_000, ArrayList()) { isVideo(it) }
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 200)
        assertTrue(waitUntil(1_000) { r.session.videoQueueFrames() == 0 })
    }
}
