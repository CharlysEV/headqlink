package dev.qdauto.core.session

import dev.qdauto.core.TestSupport.waitUntil
import dev.qdauto.core.wire.CarMessages
import dev.qdauto.core.wire.MsgType
import dev.qdauto.core.wire.WireMessage
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** hql (C2): [FrameCompletion] llega exactamente una vez por frame, con el resultado correcto y sin candados tomados. */
class FrameCompletionTest {
    private val sps = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0xC0.toByte(), 0x29)
    private val pps = byteArrayOf(0, 0, 0, 1, 0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())
    private fun idr(size: Int) = byteArrayOf(0, 0, 0, 1, 0x65) + ByteArray(size) { 1 }
    private fun delta(size: Int) = byteArrayOf(0, 0, 0, 1, 0x41) + ByteArray(size) { 2 }

    private val closeables = ArrayList<AutoCloseable>()

    @AfterTest
    fun cleanup() = closeables.reversed().forEach { runCatching { it.close() } }

    /** Apunta cada finalización por índice y comprueba que nunca llega con el candado de la cola tomado. */
    private class Recorder(private val queueOf: () -> SendQueue) {
        val done = ConcurrentLinkedQueue<Pair<Int, FrameDone>>()
        val lockViolations = AtomicInteger()

        fun forFrame(i: Int) = FrameCompletion { d ->
            if (queueOf().isLockHeldByCurrentThread()) lockViolations.incrementAndGet()
            done += i to d
        }

        fun outcomeOf(i: Int): FrameOutcome? = done.firstOrNull { it.first == i }?.second?.outcome
        fun count(i: Int): Int = done.count { it.first == i }
    }

    private fun session(socket: java.net.Socket, config: SessionConfig, listener: SessionListener = RecordingListener()): PhoneSession =
        PhoneSession(socket, config, listener).also { closeables += it }.start()

    @Test
    fun writtenOncePerFrameWithAReadingCar() {
        val server = MirrorServer(0).also { closeables += it }
        val car = FakeCar(server.port).also { closeables += it }
        val listener = RecordingListener()
        // Sin descartes por atasco: los 10 frames se encolan de golpe.
        val s = session(server.accept(5_000), SessionConfig(heartbeatInitialDelayMs = 60_000, videoBacklogFrames = 100), listener)
        val rec = Recorder { s.queue }
        car.next() // AppStatus
        car.send(CarMessages.videoArgs(1920, 1080, 3, 30, 4_000_000, 1))
        car.send(CarMessages.videoCtrl(1))
        assertTrue(listener.streaming.await(5, TimeUnit.SECONDS))
        assertTrue(s.sendCodecConfig(sps + pps))
        assertTrue(s.sendFrame(idr(3000), 0, 3005, true, 0, rec.forFrame(0)))
        for (i in 1 until 10) assertTrue(s.sendFrame(delta(500), 0, 505, false, i * 33_000L, rec.forFrame(i)))
        assertTrue(waitUntil(5_000) { rec.done.size == 10 })
        for (i in 0 until 10) {
            assertEquals(1, rec.count(i), "frame $i")
            assertEquals(FrameOutcome.WRITTEN, rec.outcomeOf(i))
        }
        val first = rec.done.first { it.first == 0 }.second
        assertTrue(first.isKeyframe)
        assertEquals(3005, first.payloadBytes)
        val times = "encolado ${first.enqueuedNanos} escritura ${first.writeStartNanos}..${first.writeEndNanos}"
        assertTrue(first.enqueuedNanos != 0L && first.writeStartNanos - first.enqueuedNanos >= 0, times)
        assertTrue(first.writeEndNanos - first.writeStartNanos >= 0 && first.writeStartNanos != 0L, times)
        val video = generateSequence { car.nextFrame() }.filter { it.header.msgType == MsgType.VIDEO }.take(11).toList()
        assertEquals(11, video.size)
        assertEquals(0, rec.lockViolations.get())
    }

    @Test
    fun rejectedBeforePlayDroppedBeforeTheIdrAndClosedAfterClose() {
        val server = MirrorServer(0).also { closeables += it }
        val car = FakeCar(server.port).also { closeables += it }
        val listener = RecordingListener()
        val s = session(server.accept(5_000), SessionConfig(heartbeatInitialDelayMs = 60_000), listener)
        val rec = Recorder { s.queue }
        // Sin VIDEO_CTRL{1}: rechazado, avisado antes de volver.
        assertFalse(s.sendFrame(idr(10), 0, 15, true, 0, rec.forFrame(0)))
        assertEquals(FrameOutcome.REJECTED, rec.outcomeOf(0))
        car.send(CarMessages.videoArgs(1920, 1080, 3, 30, 4_000_000, 1))
        car.send(CarMessages.videoCtrl(1))
        assertTrue(listener.streaming.await(5, TimeUnit.SECONDS))
        // Esperando el primer IDR: el P se tira (y se avisa en el acto).
        assertFalse(s.sendFrame(delta(10), 0, 15, false, 1, rec.forFrame(1)))
        assertEquals(FrameOutcome.DROPPED, rec.outcomeOf(1))
        s.close()
        assertFalse(s.sendFrame(idr(10), 0, 15, true, 2, rec.forFrame(2)))
        assertEquals(FrameOutcome.CLOSED, rec.outcomeOf(2))
        assertEquals(listOf(1, 1, 1), (0..2).map { rec.count(it) })
        assertEquals(1, s.stats().videoFramesRejected)
        assertEquals(0, rec.lockViolations.get())
    }

    @Test
    fun backlogDropsQueuedDeltasAndTheFrameInFlightFailsWhenTheCarCuts() {
        val server = MirrorServer(0).also { closeables += it }
        val car = ControlledCar(server.port).also { closeables += it }
        val listener = RecordingListener()
        val s = session(
            server.accept(5_000),
            SessionConfig(heartbeatInitialDelayMs = 60_000, sendBufferBytes = 4096, videoBacklogFrames = 3),
            listener,
        )
        val rec = Recorder { s.queue }
        car.handshake()
        assertTrue(listener.streaming.await(5, TimeUnit.SECONDS))
        assertTrue(s.sendCodecConfig(sps + pps))
        assertTrue(s.sendFrame(idr(2_000_000), 0, 2_000_005, true, 0, rec.forFrame(0)))
        // El coche no lee: el escritor se queda en el write() del IDR.
        assertTrue(waitUntil(5_000) { s.io().writingLabel == "VIDEO_IDR" }, s.io().toString())
        for (i in 1..3) assertTrue(s.sendFrame(delta(100), 0, 105, false, i.toLong(), rec.forFrame(i)))
        assertEquals(3, s.videoQueueFrames())
        // Cuarto P con 3 en cola: se tiran los encolados y él mismo.
        assertFalse(s.sendFrame(delta(100), 0, 105, false, 4, rec.forFrame(4)))
        for (i in 1..4) assertEquals(FrameOutcome.DROPPED, rec.outcomeOf(i), "P$i")
        assertEquals(0, s.videoQueueFrames())
        // El coche corta (RST) a mitad del IDR: FAILED para el que se estaba escribiendo.
        car.closeAbruptly()
        assertTrue(waitUntil(5_000) { rec.count(0) == 1 })
        assertEquals(FrameOutcome.FAILED, rec.outcomeOf(0))
        assertTrue(listener.awaitClosed(5_000))
        assertTrue(listener.closeReason!!.kind in setOf(CloseReason.Kind.WRITE_ERROR, CloseReason.Kind.READ_ERROR, CloseReason.Kind.EOF))
        assertEquals((0..4).map { 1 }, (0..4).map { rec.count(it) })
        assertEquals(0, rec.lockViolations.get())
    }

    @Test
    fun queuedFramesAreClosedOnLocalClose() {
        val server = MirrorServer(0).also { closeables += it }
        val car = ControlledCar(server.port).also { closeables += it }
        val listener = RecordingListener()
        val s = session(server.accept(5_000), SessionConfig(heartbeatInitialDelayMs = 60_000, sendBufferBytes = 4096, videoBacklogFrames = 100), listener)
        val rec = Recorder { s.queue }
        car.handshake()
        assertTrue(listener.streaming.await(5, TimeUnit.SECONDS))
        assertTrue(s.sendFrame(idr(2_000_000), 0, 2_000_005, true, 0, rec.forFrame(0)))
        assertTrue(waitUntil(5_000) { s.io().writingLabel == "VIDEO_IDR" })
        for (i in 1..5) assertTrue(s.sendFrame(delta(100), 0, 105, false, i.toLong(), rec.forFrame(i)))
        s.close()
        assertTrue(waitUntil(5_000) { rec.done.size == 6 })
        for (i in 1..5) assertEquals(FrameOutcome.CLOSED, rec.outcomeOf(i), "P$i")
        assertEquals(FrameOutcome.FAILED, rec.outcomeOf(0))
        assertEquals(0, rec.lockViolations.get())
    }

    @Test
    fun stressEveryFrameCompletesExactlyOnceWhateverTheCloseTiming() {
        val random = Random(42)
        repeat(50) { rep ->
            val server = MirrorServer(0)
            val car = FakeCar(server.port)
            val s = PhoneSession(server.accept(5_000), SessionConfig(heartbeatInitialDelayMs = 60_000, sendVideoBeforePlay = true)).start()
            val total = 2000
            val counts = Array(total) { AtomicInteger() }
            val outcomes = Collections.synchronizedList(ArrayList<FrameOutcome>())
            val violations = AtomicInteger()
            val producer = Thread {
                for (i in 0 until total) {
                    val key = i % 30 == 0
                    val body = if (key) idr(1000 + i % 700) else delta(100 + i % 900)
                    s.sendFrame(body, 0, body.size, key, i.toLong()) { d ->
                        if (s.queue.isLockHeldByCurrentThread()) violations.incrementAndGet()
                        counts[i].incrementAndGet()
                        outcomes += d.outcome
                    }
                }
            }
            producer.start()
            Thread.sleep(random.nextLong(0, 25))
            if (random.nextBoolean()) s.close() else car.close()
            producer.join(10_000)
            assertTrue(waitUntil(10_000) { counts.all { it.get() >= 1 } }, "rep $rep: ${counts.count { it.get() == 0 }} sin finalizar")
            Thread.sleep(20)
            assertTrue(counts.all { it.get() == 1 }, "rep $rep: finalizaciones repetidas")
            assertEquals(total, outcomes.size)
            assertEquals(0, violations.get(), "rep $rep: finalización con el candado tomado")
            s.close()
            car.close()
            server.close()
            assertTrue(s.awaitTermination(5_000))
        }
    }

    @Test
    fun aThrowingCompletionDoesNotBreakTheWriter() {
        val server = MirrorServer(0).also { closeables += it }
        val car = FakeCar(server.port).also { closeables += it }
        val s = session(server.accept(5_000), SessionConfig(heartbeatInitialDelayMs = 60_000, sendVideoBeforePlay = true))
        car.next() // AppStatus
        assertTrue(s.sendFrame(idr(100), 0, 105, true, 0) { throw IllegalStateException("prueba") })
        val ok = AtomicInteger()
        assertTrue(s.sendFrame(delta(100), 0, 105, false, 1) { if (it.outcome == FrameOutcome.WRITTEN) ok.incrementAndGet() })
        assertTrue(waitUntil(5_000) { ok.get() == 1 })
        val video = generateSequence { car.nextFrame() }.filter { it.header.msgType == MsgType.VIDEO }.take(2).toList()
        assertEquals(2, video.size)
        assertFalse(s.isClosed)
        assertTrue(car.drain().none { it is WireMessage.Garbage })
    }
}
