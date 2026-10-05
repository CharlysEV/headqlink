package dev.qdauto.core.session

import dev.qdauto.core.TestSupport.waitUntil
import dev.qdauto.core.json.JsonObject
import dev.qdauto.core.wire.CarMessages
import java.io.Closeable
import java.io.InputStream
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * hql: `write()` bloqueado (el coche deja de leer el TCP). Viaje 5 (2026-10-05, S7): la radio se atascó 8-10 s con el
 * coche mandando heartbeats y `WRITE_STALL` cerró la sesión a los 10 s. Ahora, con el coche hablando, la sesión aguanta
 * hasta `writeStallCarTalkingTimeoutMs`, tira el vídeo encolado y pide un IDR cuando el write vuelve; con el coche
 * callado sigue cerrando a los `writeStallTimeoutMs`.
 */
class WriteStallTest {
    /** Coche con un socket crudo que **no lee** hasta que se le dice, y manda lo que se le pide. */
    private class DeafCar(port: Int) : Closeable {
        val socket = Socket().apply {
            receiveBufferSize = 16 * 1024
            connect(java.net.InetSocketAddress("127.0.0.1", port), 5_000)
        }

        fun send(bytes: ByteArray) {
            socket.getOutputStream().write(bytes)
            socket.getOutputStream().flush()
        }

        /** Empieza a leer (y tirar) todo lo que llega: el write bloqueado del teléfono vuelve. */
        fun startReading() {
            Thread({
                val input: InputStream = socket.getInputStream()
                val buf = ByteArray(64 * 1024)
                try {
                    while (input.read(buf) >= 0) Unit
                } catch (_: Exception) {
                }
            }, "deafcar-reader").apply {
                isDaemon = true
                start()
            }
        }

        override fun close() = socket.close()
    }

    private val closeables = ArrayList<AutoCloseable>()

    @AfterTest
    fun cleanup() = closeables.reversed().forEach { runCatching { it.close() } }

    private fun config(talkingMs: Long) = SessionConfig(
        heartbeatInitialDelayMs = 60_000,
        watchdogCheckIntervalMs = 50,
        watchdogWarnMs = 60_000,
        watchdogTimeoutMs = 60_000,
        writeStallTimeoutMs = 300,
        writeStallCarTalkingTimeoutMs = talkingMs,
        writeStallCarWindowMs = 400,
        sendBufferBytes = 16 * 1024,
        maxVideoMessageBytes = 0,
        videoDropPolicy = VideoDropPolicy.NONE,
        traceVideoFrames = false,
    )

    private fun streaming(config: SessionConfig): Triple<DeafCar, PhoneSession, RecordingListener> {
        val server = MirrorServer(0).also { closeables += it }
        val car = DeafCar(server.port).also { closeables += it }
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
        assertTrue(s.sendCodecConfig(byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0, 0, 0, 1, 0x68, 0xCE.toByte())))
        return Triple(car, s, listener)
    }

    private fun idr(bytes: Int) = byteArrayOf(0, 0, 0, 1, 0x65) + ByteArray(bytes) { 1 }
    private fun delta(bytes: Int) = byteArrayOf(0, 0, 0, 1, 0x41) + ByteArray(bytes) { 2 }

    /** Encola vídeo hasta que un write queda bloqueado (el coche no lee) y algo más detrás. */
    private fun blockTheWriter(s: PhoneSession) {
        assertTrue(s.sendFrame(idr(2_000_000), true, 0))
        assertTrue(s.sendFrame(delta(200_000), false, 1))
        assertTrue(s.sendFrame(delta(200_000), false, 2))
        assertTrue(waitUntil(3_000) { s.io().writingForMs > 100 }, "el write no se ha bloqueado: ${s.io()}")
    }

    @Test
    fun carTalkingTheSessionHoldsOnDropsQueuedVideoAndClosesOnlyAtTheLongerTimeout() {
        val (car, s, listener) = streaming(config(talkingMs = 1_200))
        blockTheWriter(s)
        assertEquals(2, s.videoQueueFrames())
        // El coche sigue hablando: un heartbeat cada 100 ms.
        val talker = Thread({
            try {
                while (!s.isClosed) {
                    car.send(CarMessages.heartbeat())
                    Thread.sleep(100)
                }
            } catch (_: Exception) {
            }
        }, "car-talker").apply {
            isDaemon = true
            start()
        }
        // Pasados los 300 ms no se cierra: se aguanta y la cola de vídeo se vacía.
        assertTrue(waitUntil(2_000) { s.stats().writeStalls == 1L }, "no contó el write bloqueado: ${s.io()}")
        assertFalse(s.isClosed)
        assertEquals(0, s.videoQueueFrames())
        assertTrue(s.isWaitingForKeyframe())
        // Lo que se encole mientras tanto se tira en la siguiente comprobación (los P ni entran).
        assertFalse(s.sendFrame(delta(1_000), false, 3))
        assertTrue(s.sendFrame(idr(1_000), true, 4))
        assertTrue(waitUntil(1_000) { s.videoQueueFrames() == 0 })
        // A los 1 200 ms sí se cierra, y el motivo lo dice.
        assertTrue(listener.awaitClosed(3_000))
        assertEquals(CloseReason.Kind.WRITE_STALL, listener.closeReason?.kind)
        val msg = listener.closeReason?.message ?: ""
        assertTrue(msg.contains("1200 ms") && msg.contains("sigue hablando"), msg)
        talker.join(1_000)
        assertTrue(s.awaitTermination(5_000))
    }

    @Test
    fun carSilentTheSessionClosesAtTheShortTimeout() {
        val (_, s, listener) = streaming(config(talkingMs = 1_200))
        // El coche se calla (su último mensaje fue el VIDEO_CTRL del handshake) antes de que el write se atasque.
        Thread.sleep(450)
        val started = System.nanoTime()
        blockTheWriter(s)
        assertTrue(listener.awaitClosed(3_000))
        val ms = (System.nanoTime() - started) / 1_000_000
        assertEquals(CloseReason.Kind.WRITE_STALL, listener.closeReason?.kind)
        val msg = listener.closeReason?.message ?: ""
        assertTrue(msg.contains("300 ms") && msg.contains("callado"), msg)
        assertTrue(ms < 1_100, "cerró a los $ms ms (debía ser poco más de 300)")
        assertEquals(0L, s.stats().writeStalls)
    }

    @Test
    fun whenTheWriteReturnsAnIdrIsRequestedAndTheSessionGoesOn() {
        val (car, s, listener) = streaming(config(talkingMs = 5_000))
        blockTheWriter(s)
        car.send(CarMessages.heartbeat())
        assertTrue(waitUntil(2_000) { s.stats().writeStalls == 1L })
        val before = listener.keyframeReasons.size
        // El coche vuelve a leer: el write termina, lo encolado mientras tanto fuera y un IDR nuevo.
        car.startReading()
        assertTrue(waitUntil(5_000) { s.io().writingSinceNanos == 0L }, "el write no volvió: ${s.io()}")
        assertTrue(waitUntil(2_000) { listener.keyframeReasons.size > before && listener.keyframeReasons.last() == KeyframeReason.BACKLOG }, listener.keyframeReasons.toString())
        assertFalse(s.isClosed)
        assertTrue(s.isWaitingForKeyframe())
        // Con el IDR se reanuda el vídeo.
        assertTrue(s.sendFrame(idr(1_000), true, 5))
        assertTrue(s.sendFrame(delta(100), false, 6))
        assertTrue(waitUntil(2_000) { s.stats().videoFramesSent >= 3 }, s.stats().toString())
        assertFalse(s.isClosed)
    }

    /** Sin prórroga (el largo no es mayor que el corto) el write bloqueado cierra aunque el coche hable. */

    @Test
    fun noExtensionWhenTheTalkingTimeoutIsNotLonger() {
        val (car, s, listener) = streaming(config(talkingMs = 300))
        blockTheWriter(s)
        car.send(CarMessages.heartbeat())
        assertTrue(listener.awaitClosed(3_000))
        assertEquals(CloseReason.Kind.WRITE_STALL, listener.closeReason?.kind)
        assertEquals(0L, s.stats().writeStalls)
    }
}
