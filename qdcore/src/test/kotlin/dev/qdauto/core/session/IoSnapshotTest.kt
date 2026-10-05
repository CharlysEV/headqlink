package dev.qdauto.core.session

import dev.qdauto.core.TestSupport.waitUntil
import dev.qdauto.core.wire.CarMessages
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** hql (C2): consultas sin candado ([PhoneSession.io], [PhoneSession.videoQueueFrames]…). */
class IoSnapshotTest {
    private val closeables = ArrayList<AutoCloseable>()

    @AfterTest
    fun cleanup() = closeables.reversed().forEach { runCatching { it.close() } }

    @Test
    fun writingSinceIsSetOnlyDuringABlockedWrite() {
        val server = MirrorServer(0).also { closeables += it }
        val car = ControlledCar(server.port).also { closeables += it }
        val listener = RecordingListener()
        val s = PhoneSession(server.accept(5_000), SessionConfig(heartbeatInitialDelayMs = 60_000, sendBufferBytes = 4096), listener)
            .also { closeables += it }.start()
        car.handshake()
        assertTrue(listener.streaming.await(5, TimeUnit.SECONDS))
        val payload = byteArrayOf(0, 0, 0, 1, 0x65) + ByteArray(1_500_000) { 3 }
        assertTrue(s.sendFrame(payload, true, 0))
        for (i in 1..2) assertTrue(s.sendFrame(byteArrayOf(0, 0, 0, 1, 0x41, 5), false, i.toLong()))
        assertTrue(waitUntil(5_000) { s.io().writingLabel == "VIDEO_IDR" })
        Thread.sleep(100)
        val blocked = s.io()
        assertTrue(blocked.writingSinceNanos != 0L)
        assertTrue(blocked.writingForMs >= 100, blocked.toString())
        assertEquals(48 + payload.size, blocked.writingBytes)
        assertEquals(2, blocked.videoQueueFrames)
        assertEquals(2, s.videoQueueFrames())
        assertTrue(s.videoQueueBytes() > 0)
        car.startReading()
        assertTrue(waitUntil(5_000) { s.io().let { it.writingSinceNanos == 0L && it.videoQueueFrames == 0 } })
        val after = s.io()
        assertNull(after.writingLabel)
        assertTrue(after.lastWriteEndNanos > blocked.writingSinceNanos)
        assertTrue(after.bytesSent > payload.size)
    }

    @Test
    fun lastReceiveAdvancesWithEachMessage() {
        val server = MirrorServer(0).also { closeables += it }
        val car = FakeCar(server.port).also { closeables += it }
        val s = PhoneSession(server.accept(5_000), SessionConfig(heartbeatInitialDelayMs = 60_000)).also { closeables += it }.start()
        car.send(CarMessages.heartbeat())
        assertTrue(waitUntil(2_000) { s.stats().carHeartbeats == 1L })
        val first = s.io()
        Thread.sleep(60)
        assertTrue(s.io().receiveSilenceMs >= 50)
        car.send(CarMessages.heartbeat())
        assertTrue(waitUntil(2_000) { s.io().lastReceiveNanos > first.lastReceiveNanos })
        val second = s.io()
        assertTrue(second.bytesReceived > first.bytesReceived)
        assertTrue(second.receiveSilenceMs < 50)
        assertEquals(0, second.controlQueue)
    }
}
