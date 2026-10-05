package dev.qdauto.core

import dev.qdauto.core.TestSupport.freeUdpPort
import dev.qdauto.core.TestSupport.liveThreads
import dev.qdauto.core.TestSupport.waitUntil
import dev.qdauto.core.discovery.AckPolicy
import dev.qdauto.core.discovery.CarAnnouncement
import dev.qdauto.core.discovery.DiscoveryConfig
import dev.qdauto.core.discovery.DiscoveryListener
import dev.qdauto.core.session.CloseReason
import dev.qdauto.core.session.MirrorServer
import dev.qdauto.core.session.PhoneSession
import dev.qdauto.core.session.RecordingListener
import dev.qdauto.core.session.SessionConfig
import dev.qdauto.core.session.SessionState
import dev.qdauto.core.sim.CarInfoValues
import dev.qdauto.core.sim.CarSim
import dev.qdauto.core.sim.CarSimConfig
import dev.qdauto.core.sim.CarSimListener
import dev.qdauto.core.sim.CarSimState
import dev.qdauto.core.sim.SentTouch
import dev.qdauto.core.sim.VideoArgsValues
import dev.qdauto.core.sim.VideoKind
import dev.qdauto.core.util.QdLog
import dev.qdauto.core.wire.CarKey
import dev.qdauto.core.wire.Cmd
import dev.qdauto.core.wire.Direction
import dev.qdauto.core.wire.FunctionIds
import dev.qdauto.core.wire.TouchCodec
import dev.qdauto.core.wire.TouchPointer
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Teléfono (DiscoveryListener + MirrorServer + PhoneSession) contra el simulador de coche, en 127.0.0.1 y con puertos
 * no estándar: descubrimiento, handshake completo, 60 frames de vídeo mientras el coche manda táctil, watchdog y cierre.
 */
class EndToEndTest {
    private val log = QdLog.stdout(dev.qdauto.core.util.LogLevel.INFO)
    private val sps = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0xC0.toByte(), 0x29)
    private val pps = byteArrayOf(0, 0, 0, 1, 0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())

    /** Frame falso: start code + cabecera NAL (IDR 0x65 / P 0x41) + bytes sin ceros (no crean start codes). */
    private fun fakeFrame(i: Int, key: Boolean): ByteArray {
        val body = ByteArray(200 + (i * 37) % 500) { j -> ((j * 7 + i) % 255 + 1).toByte() }
        return byteArrayOf(0, 0, 0, 1, if (key) 0x65 else 0x41) + body
    }

    @Test
    fun phoneAgainstSimulatedCar() {
        val discoveryPort = freeUdpPort()
        var ackPort = freeUdpPort()
        while (ackPort == discoveryPort) ackPort = freeUdpPort()
        val recording = File.createTempFile("qdauto-e2e", ".h264").apply { deleteOnExit() }

        // ---- teléfono: descubrimiento ----
        val found = LinkedBlockingQueue<CarAnnouncement>()
        val acksSent = LinkedBlockingQueue<Int>()
        val discovery = DiscoveryListener(
            DiscoveryConfig(port = discoveryPort, ackPort = ackPort),
            object : DiscoveryListener.Callback {
                override fun onCarFound(car: CarAnnouncement) {
                    found.put(car)
                }

                override fun onAckSent(target: InetSocketAddress, mirrorPort: Int, attempt: Int, bytes: ByteArray) {
                    acksSent.put(attempt)
                }
            },
            log,
        ).start()

        // ---- coche simulado ----
        val simStates = LinkedBlockingQueue<CarSimState>()
        val sim = CarSim(
            CarSimConfig(
                broadcastAddress = InetAddress.getLoopbackAddress(),
                phoneDiscoveryPort = discoveryPort,
                carAckPort = ackPort,
                broadcastIntervalMs = 100,
                heartbeatPeriodMs = 200,
                carInfo = CarInfoValues(carWidth = 1920, carHeight = 720),
                videoArgs = VideoArgsValues(frameRate = 30, bitRate = 6_000_000, frameInterval = 1),
                recordVideoTo = recording,
            ),
            object : CarSimListener {
                override fun onStateChanged(state: CarSimState) {
                    simStates.put(state)
                }
            },
            log,
        ).start()

        val car = assertNotNull(found.poll(10, TimeUnit.SECONDS), "no se descubrió el coche")
        assertEquals("C10-SIM", car.name)
        assertEquals("QDAUTO-SIM-0001", car.uuid)
        assertTrue(car.qdlinkCompatible)
        assertEquals(ackPort, car.sourcePort)

        // ---- teléfono: servidor TCP, ACK y sesión ----
        val server = MirrorServer(0, log = log)
        val ack = discovery.sendAck(car, server.port, AckPolicy(retries = 3, firstRetryDelayMs = 2_000, retryIntervalMs = 2_000))
        val socket = server.accept(10_000)
        ack.cancel()
        assertEquals(1, acksSent.poll(1, TimeUnit.SECONDS))
        val listener = RecordingListener()
        val session = PhoneSession(
            socket,
            SessionConfig(
                heartbeatInitialDelayMs = 100,
                heartbeatPeriodMs = 300,
                watchdogCheckIntervalMs = 50,
                watchdogWarnMs = 1_000, // el coche simulado manda heartbeat cada 200 ms
                watchdogTimeoutMs = 2_000,
                videoBacklogFrames = 1_000,
            ),
            listener,
            log,
        ).start()

        // ---- handshake completo ----
        assertTrue(sim.awaitState(CarSimState.STREAMING, 10_000), sim.report().summary())
        assertTrue(listener.streaming.await(5, TimeUnit.SECONDS))
        assertEquals(1920, listener.carInfo?.carWidth)
        assertEquals(6_000_000, listener.videoArgs?.bitRate)
        assertEquals(
            listOf(SessionState.CAR_INFO, SessionState.VIDEO_SUPPORTED, SessionState.VIDEO_ARGS, SessionState.STREAMING),
            listener.states.toList(),
        )

        // ---- vídeo (60 frames, 3 IDR) mientras el coche manda táctil y teclas ----
        val accepted = ArrayList<Boolean>()
        val video = thread(name = "test-encoder") {
            accepted += session.sendCodecConfig(sps + pps)
            for (i in 0 until 60) {
                val key = i % 20 == 0
                accepted += session.sendFrame(fakeFrame(i, key), key, i * 33_333L)
                Thread.sleep(5)
            }
        }
        assertTrue(sim.tap(100.5f, 200.25f))
        assertTrue(sim.drag(10f, 20f, 300f, 400f, steps = 5, durationMs = 50))
        assertTrue(sim.pinch(960f, 360f, 100f, 300f, steps = 3, durationMs = 30))
        assertTrue(sim.sendTouch(TouchCodec.ACTION_MOVE, listOf(TouchPointer(9, 3, -0.0f, Float.MAX_VALUE))))
        assertTrue(sim.sendPhoneKey(1))
        assertTrue(sim.sendMusicKey(FunctionIds.PLAY_CONTROL_PAUSE))
        video.join(10_000)
        assertEquals(61, accepted.size)
        assertTrue(accepted.all { it })

        // El coche recibe un vídeo válido y ordenado: SPS/PPS, IDR, P…
        assertTrue(sim.awaitVideoMessages(61, 10_000), sim.report().summary())
        val report = sim.report()
        assertTrue(report.videoValid, report.summary())
        assertEquals(VideoKind.CONFIG, report.firstVideoKind)
        assertEquals(1, report.codecConfigMessages)
        assertEquals(3, report.idrFrames)
        assertEquals(57, report.pFrames)
        assertEquals(1920, report.lastVideoHeader?.params?.width)
        assertEquals(720, report.lastVideoHeader?.params?.height)
        assertEquals(6_000_000, report.lastVideoHeader?.params?.bitrate)
        // Handshake visto desde el coche: AppStatus correcto y respuestas en orden.
        assertEquals(1, report.appStatusReceived)
        assertEquals(emptyList(), report.appStatusErrors)
        assertEquals(36, report.appStatusSdkInt)
        val order = report.phoneMessageOrder.filter { it != Cmd.HEARTBEAT }
        assertEquals(listOf("!BIN AppStatus", Cmd.PHONE_INFO, Cmd.UPDATE_NOTIFY, Cmd.VIDEO_SUP_RSP, Cmd.SPEECH_ARGS), order.take(5))
        assertEquals(1920, report.lastPhoneInfo?.int("MirrorWidthInApp"))
        assertEquals(1560, report.lastPhoneInfo?.int("MirrorWidth")) // spec §8.6: S25U + 1920×720 → 1560×720
        assertEquals(server.port, report.mirrorPort)
        assertTrue(report.phoneHeartbeats >= 1)
        assertEquals(emptyList(), report.handshakeErrors)

        // El teléfono recibe exactamente los mismos táctiles (todos los dedos, valores crudos) y las teclas.
        val sent = sim.sentTouches()
        assertEquals(2 + 7 + 7 + 1, sent.size)
        assertTrue(waitUntil(5_000) { listener.touches.size == sent.size })
        assertEquals(sent, listener.touches.map { SentTouch(it.action, it.pointers) })
        assertEquals(SentTouch(0, listOf(TouchPointer.down(0, 100.5f, 200.25f))), sent[0])
        assertEquals(TouchPointer(9, 3, -0.0f, Float.MAX_VALUE), listener.touches.last().pointers.single())
        assertTrue(waitUntil(5_000) { listener.keys.size == 2 })
        assertEquals(listOf(CarKey.HOME, CarKey.MEDIA_PAUSE), listener.keys.toList())

        val stats = session.stats()
        assertEquals(60, stats.videoFramesSent)
        assertEquals(3, stats.keyframesSent)
        assertEquals(0, stats.videoFramesDropped)
        assertTrue(stats.carHeartbeats >= 1)
        assertTrue(listener.traces.any { it.direction == Direction.OUT && it.isVideo })
        assertTrue(listener.unknown.isEmpty(), listener.unknown.toString())
        assertTrue(listener.watchdogWarnings.isEmpty())

        // ---- el coche se calla: el watchdog del teléfono corta la sesión ----
        sim.goSilent()
        assertTrue(listener.awaitClosed(10_000))
        assertEquals(CloseReason.Kind.WATCHDOG, listener.closeReason?.kind)
        assertEquals(1, listener.watchdogWarnings.size)
        assertTrue(sim.awaitClosed(5_000))
        assertTrue(sim.report().closeReason!!.contains("EOF"), sim.report().closeReason)
        assertTrue(waitUntil(2_000) { simStates.toList().lastOrNull() == CarSimState.CLOSED }, simStates.toString())

        // ---- cierre limpio: todos los hilos terminan ----
        assertTrue(session.awaitTermination(5_000))
        sim.close()
        discovery.close()
        server.close()
        assertTrue(sim.awaitTermination(5_000))
        assertTrue(discovery.awaitTermination(5_000))
        assertTrue(waitUntil(5_000) { liveThreads("qd-s${session.id}-", "qd-discovery", "carsim-").isEmpty() }, liveThreads("qd-", "carsim-").toString())
        // El Annex-B grabado por el coche es exactamente lo que se envió.
        assertEquals(report.videoPayloadBytes, recording.length())
        assertTrue(recording.delete())
    }
}
