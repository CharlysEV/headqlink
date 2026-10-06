package dev.qdauto.core.session

import dev.qdauto.core.MemoryPipe
import dev.qdauto.core.TestSupport.waitUntil
import dev.qdauto.core.sim.CarInfoValues
import dev.qdauto.core.sim.CarSim
import dev.qdauto.core.sim.CarSimConfig
import dev.qdauto.core.sim.CarSimState
import dev.qdauto.core.sim.VideoArgsValues
import dev.qdauto.core.util.QdLog
import dev.qdauto.core.wire.BlockFraming
import dev.qdauto.core.wire.Cmd
import dev.qdauto.core.wire.ControlMessage
import dev.qdauto.core.wire.TouchEvent
import java.io.Closeable
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Sesión completa teléfono↔coche por el «USB» (dos tubos en memoria, sin red) con la trama por bloques de 512 B en los
 * dos lados: handshake, AppStatus, heartbeats, táctil y vídeo; cada `write()` de los dos es un mensaje con su relleno, y
 * ninguno de los dos lectores ve un mensaje sin rellenar. También con lecturas cortas, cierre desde cada lado, el atasco
 * medido sin cola del kernel y la trama del USB sobre el TCP (`qdsim --usb-framing`).
 */
class UsbSessionTest {
    private val log = QdLog.NONE
    private val closeables = ArrayList<Closeable>()
    private val sps = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0xC0.toByte(), 0x29)
    private val pps = byteArrayOf(0, 0, 0, 1, 0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())

    @AfterTest
    fun tearDown() {
        closeables.reversed().forEach {
            try {
                it.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun frame(i: Int, key: Boolean, size: Int = 200 + (i * 37) % 900): ByteArray {
        val body = ByteArray(size) { j -> ((j * 7 + i) % 255 + 1).toByte() }
        return byteArrayOf(0, 0, 0, 1, if (key) 0x65 else 0x41) + body
    }

    /** Lo que ve el teléfono. */
    private class PhoneSide : SessionListener {
        val streaming = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val touches: MutableList<TouchEvent> = Collections.synchronizedList(ArrayList())

        @Volatile
        var closeReason: CloseReason? = null

        override fun onVideoControl(play: Boolean, playStatus: Int, message: ControlMessage) {
            if (play) streaming.countDown()
        }

        override fun onTouch(event: TouchEvent) {
            touches += event
        }

        override fun onClosed(reason: CloseReason) {
            closeReason = reason
            closed.countDown()
        }
    }

    private fun carConfig() = CarSimConfig(
        blockFraming = true,
        heartbeatPeriodMs = 200,
        carInfo = CarInfoValues(carWidth = 1920, carHeight = 720),
        videoArgs = VideoArgsValues(frameRate = 30, bitRate = 5_000_000, frameInterval = 1),
    )

    private fun phoneConfig(policy: VideoDropPolicy = VideoDropPolicy.BACKLOG) = SessionConfig(
        heartbeatInitialDelayMs = 100,
        heartbeatPeriodMs = 300,
        watchdogCheckIntervalMs = 50,
        watchdogWarnMs = 2_000,
        watchdogTimeoutMs = 5_000,
        videoBacklogFrames = 1_000,
        videoDropPolicy = policy,
    )

    private class Usb(chunk: Int = Int.MAX_VALUE, capacity: Int = 1 shl 20) {
        val carToPhone = MemoryPipe(capacity, chunk)
        val phoneToCar = MemoryPipe(capacity, chunk)
    }

    private fun startCar(usb: Usb): CarSim =
        CarSim(carConfig(), log = log).startOnStreams(usb.phoneToCar.input, usb.carToPhone.output, "USB de prueba").also { closeables += it }

    private fun startPhone(usb: Usb, listener: SessionListener, policy: VideoDropPolicy = VideoDropPolicy.BACKLOG): PhoneSession {
        val transport = StreamTransport(usb.carToPhone.input, usb.phoneToCar.output, "USB", BlockFraming.BLOCK, "USB de prueba")
        return PhoneSession(transport, phoneConfig(policy), listener, log).also { closeables += it }.start()
    }

    private fun runSession(usb: Usb, frames: Int) {
        val side = PhoneSide()
        val sim = startCar(usb)
        val phone = startPhone(usb, side)
        assertNull(phone.socket)
        assertNull(phone.remoteAddress)
        assertTrue(phone.isBlockFraming)

        assertTrue(sim.awaitState(CarSimState.STREAMING, 10_000), sim.report().summary())
        assertTrue(side.streaming.await(5, TimeUnit.SECONDS))
        assertTrue(phone.sendCodecConfig(sps + pps))
        for (i in 0 until frames) {
            assertTrue(phone.sendFrame(frame(i, key = i % 10 == 0), isKeyframe = i % 10 == 0, ptsUs = i * 33_333L), "frame $i")
            if (i == frames / 2) assertTrue(sim.tap(100f, 200f))
        }
        assertTrue(sim.awaitVideoMessages(frames + 1, 10_000), sim.report().summary())
        assertTrue(waitUntil(5_000) { side.touches.size >= 2 }, "táctil: ${side.touches}")
        assertTrue(sim.awaitPhoneMessage(Cmd.HEARTBEAT, 2, 5_000))

        val r = sim.report()
        assertTrue(r.videoValid, r.summary())
        assertEquals(1, r.appStatusReceived, r.summary())
        assertEquals(emptyList(), r.appStatusErrors)
        assertTrue((r.phoneMessageCounts[Cmd.PHONE_INFO] ?: 0) >= 1, r.summary())
        assertTrue(r.blockFraming)
        assertEquals(0L, r.phoneUnpaddedMessages, r.summary())
        assertTrue(r.phonePaddedMessages >= frames + 4, r.summary())
        assertEquals(emptyList(), r.unexpected, r.summary())

        // Un mensaje = un write() con su relleno, en los dos sentidos.
        assertTrue(usb.phoneToCar.writeSizes.isNotEmpty())
        assertTrue(usb.phoneToCar.writeSizes.all { it % BlockFraming.BLOCK == 0 }, "teléfono: ${usb.phoneToCar.writeSizes}")
        assertTrue(usb.carToPhone.writeSizes.all { it % BlockFraming.BLOCK == 0 }, "coche: ${usb.carToPhone.writeSizes}")

        val st = phone.stats()
        assertEquals(BlockFraming.BLOCK, st.blockSize)
        assertTrue(st.paddingBytesSent > 0)
        assertTrue(st.paddingBytesReceived > 0)
        assertEquals(0L, st.carMessagesUnpadded)
        assertTrue(st.carMessagesPadded >= 4, "$st")

        // El coche corta (cierra su lado): EOF en el teléfono.
        sim.close()
        assertTrue(side.closed.await(5, TimeUnit.SECONDS))
        assertEquals(CloseReason.Kind.EOF, side.closeReason?.kind)
        assertTrue(phone.awaitTermination(5_000))
        // Lo escrito en el «cable» es justo lo enviado más el relleno.
        val end = phone.stats()
        assertEquals(end.bytesSent + end.paddingBytesSent, usb.phoneToCar.writeSizes.sum().toLong())
    }

    @Test
    fun fullSessionWithUsbFraming() = runSession(Usb(), frames = 40)

    @Test
    fun shortReadsBothWays() = runSession(Usb(chunk = 7), frames = 12)

    @Test
    fun phoneCloseReachesTheCar() {
        val usb = Usb()
        val side = PhoneSide()
        val sim = startCar(usb)
        val phone = startPhone(usb, side)
        assertTrue(sim.awaitState(CarSimState.STREAMING, 10_000), sim.report().summary())
        phone.closeLocal("prueba")
        assertTrue(sim.awaitClosed(5_000), sim.report().summary())
        assertTrue(sim.report().closeReason!!.contains("EOF"), sim.report().closeReason)
        assertTrue(side.closed.await(5, TimeUnit.SECONDS))
        assertEquals(CloseReason.Kind.LOCAL, side.closeReason?.kind)
        // Cerrar la entrada despierta al lector bloqueado: los hilos terminan aunque el coche no mande nada más.
        assertTrue(phone.awaitTermination(5_000))
    }

    @Test
    fun queueLagWithoutKernelQueue() {
        // 64 KiB de «USB»: si el coche deja de leer, el write() se bloquea y el atasco solo se ve en la cola de la sesión.
        val usb = Usb(capacity = 64 * 1024)
        val side = PhoneSide()
        val sim = startCar(usb)
        val phone = startPhone(usb, side, VideoDropPolicy.NONE)
        assertTrue(sim.awaitState(CarSimState.STREAMING, 10_000), sim.report().summary())
        assertTrue(side.streaming.await(5, TimeUnit.SECONDS))
        assertEquals(0L, phone.videoQueueLagMs())
        sim.pauseReading(0)
        phone.sendCodecConfig(sps + pps)
        for (i in 0 until 12) phone.sendFrame(frame(i, key = i == 0, size = 20_000), isKeyframe = i == 0, ptsUs = i * 33_333L)
        assertTrue(waitUntil(5_000) { phone.io().videoQueueLagMs >= 200 }, "${phone.io()}")
        assertTrue(phone.videoQueueLagMs() >= 200)
        sim.resumeReading()
        assertTrue(waitUntil(5_000) { phone.io().videoQueueLagMs == 0L && phone.videoQueueFrames() == 0 }, "${phone.io()}")
        assertTrue(sim.awaitVideoMessages(13, 5_000), sim.report().summary())
    }

    @Test
    fun usbFramingOverTcp() {
        // qdsim --usb-framing: el coche simulado y el teléfono con la trama del USB sobre el TCP del Wi-Fi.
        val server = MirrorServer(0).also { closeables += it }
        val sim = CarSim(carConfig().copy(directMirrorPort = server.port, connectHost = "127.0.0.1"), log = log).start().also { closeables += it }
        val side = PhoneSide()
        val phone = PhoneSession(TcpTransport(server.accept(5_000), BlockFraming.BLOCK), phoneConfig(), side, log).also { closeables += it }.start()
        assertTrue(phone.socket != null)
        assertTrue(sim.awaitState(CarSimState.STREAMING, 10_000), sim.report().summary())
        assertTrue(side.streaming.await(5, TimeUnit.SECONDS))
        phone.sendCodecConfig(sps + pps)
        for (i in 0 until 10) phone.sendFrame(frame(i, key = i == 0), isKeyframe = i == 0, ptsUs = i * 33_333L)
        assertTrue(sim.awaitVideoMessages(11, 10_000), sim.report().summary())
        val r = sim.report()
        assertTrue(r.videoValid, r.summary())
        assertEquals(0L, r.phoneUnpaddedMessages, r.summary())
        assertEquals(0L, phone.stats().carMessagesUnpadded)
    }

    @Test
    fun phoneWithoutFramingIsDetected() {
        // El coche en bloques y el teléfono sin ellos (falta la opción de prueba en el móvil): el informe lo dice.
        val server = MirrorServer(0).also { closeables += it }
        val sim = CarSim(carConfig().copy(directMirrorPort = server.port, connectHost = "127.0.0.1"), log = log).start().also { closeables += it }
        val phone = PhoneSession(server.accept(5_000), phoneConfig(), PhoneSide(), log).also { closeables += it }.start()
        assertTrue(sim.awaitPhoneMessage(Cmd.HEARTBEAT, 2, 10_000), sim.report().summary())
        assertTrue(sim.report().phoneUnpaddedMessages > 0, sim.report().summary())
        assertTrue(!phone.isBlockFraming)
    }
}
