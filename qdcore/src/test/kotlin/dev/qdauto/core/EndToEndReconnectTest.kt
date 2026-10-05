package dev.qdauto.core

import dev.qdauto.core.TestSupport.freeUdpPort
import dev.qdauto.core.TestSupport.liveThreads
import dev.qdauto.core.TestSupport.waitUntil
import dev.qdauto.core.discovery.DiscoveryConfig
import dev.qdauto.core.session.CloseReason
import dev.qdauto.core.session.FrameOutcome
import dev.qdauto.core.session.KeyframeReason
import dev.qdauto.core.session.PhoneLink
import dev.qdauto.core.session.PhoneLinkConfig
import dev.qdauto.core.session.PhoneLinkListener
import dev.qdauto.core.session.PhoneSession
import dev.qdauto.core.session.SessionConfig
import dev.qdauto.core.session.SessionListener
import dev.qdauto.core.session.VideoDropPolicy
import dev.qdauto.core.sim.CarInfoValues
import dev.qdauto.core.sim.CarSim
import dev.qdauto.core.sim.CarSimConfig
import dev.qdauto.core.sim.CarSimState
import dev.qdauto.core.sim.VideoArgsValues
import dev.qdauto.core.sim.VideoKind
import dev.qdauto.core.wire.ControlMessage
import java.io.Closeable
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * hql (C4): cinco sesiones seguidas contra [CarSim] con un «pipeline» de vídeo que vive entre sesiones (como el
 * `VideoHub` del fork): se engancha a la sesión que pide vídeo vía la fábrica de listeners de [PhoneLink], responde a
 * las peticiones de IDR y se desengancha al cerrarse. Cada sesión debe recibir SPS/PPS antes del primer IDR, y pronto.
 */
class EndToEndReconnectTest {
    private val sps = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0xC0.toByte(), 0x29)
    private val pps = byteArrayOf(0, 0, 0, 1, 0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())

    private fun fakeFrame(i: Int, key: Boolean): ByteArray {
        val body = ByteArray(300 + (i * 37) % 900) { j -> ((j * 7 + i) % 255 + 1).toByte() }
        return byteArrayOf(0, 0, 0, 1, if (key) 0x65 else 0x41) + body
    }

    /** Pipeline de prueba: un "encoder" a ~60 fps que manda a la sesión enganchada. */
    private inner class TestPipeline : Closeable {
        @Volatile
        var active: PhoneSession? = null
        private val keyRequested = AtomicBoolean(true)
        private val running = AtomicBoolean(true)
        val attachedAt = ConcurrentHashMap<Int, Long>()
        val firstIdrWrittenAt = ConcurrentHashMap<Int, Long>()
        private val pump = Thread({
            var i = 0
            while (running.get()) {
                val s = active
                if (s != null) {
                    val key = keyRequested.getAndSet(false)
                    val frame = fakeFrame(i++, key)
                    s.sendFrame(frame, 0, frame.size, key, i * 16_000L) { d ->
                        if (d.outcome == FrameOutcome.WRITTEN && d.isKeyframe) firstIdrWrittenAt.putIfAbsent(s.id, System.nanoTime())
                    }
                }
                Thread.sleep(16)
            }
        }, "test-pipeline").apply {
            isDaemon = true
            start()
        }

        fun attach(s: PhoneSession) {
            s.sendCodecConfig(sps + pps)
            attachedAt[s.id] = System.nanoTime()
            active = s
        }

        fun detach(s: PhoneSession) {
            if (active === s) active = null
        }

        fun keyframe(s: PhoneSession) {
            if (active === s) keyRequested.set(true)
        }

        override fun close() {
            running.set(false)
            pump.join(2_000)
        }
    }

    @Test
    fun fiveSessionsInARowWithAPipelineThatOutlivesThem() {
        val discoveryPort = freeUdpPort()
        var ackPort = freeUdpPort()
        while (ackPort == discoveryPort) ackPort = freeUdpPort()
        val pipeline = TestPipeline()
        val videoCtrlAt = ConcurrentHashMap<Int, Long>()
        val ended = CopyOnWriteArrayList<Pair<Long, CloseReason>>()
        val startedAt = CopyOnWriteArrayList<Long>()
        val link = PhoneLink(
            PhoneLinkConfig(
                discovery = DiscoveryConfig(port = discoveryPort, ackPort = ackPort),
                session = SessionConfig(heartbeatInitialDelayMs = 200, heartbeatPeriodMs = 500, videoDropPolicy = VideoDropPolicy.MAX_LAG),
                retryDelayMs = 0,
                reAckIntervalMs = 400,
                supersedeOnRebroadcast = true,
            ),
            object : PhoneLinkListener {
                override fun onSessionStarted(session: PhoneSession) {
                    startedAt += System.nanoTime()
                }

                override fun onSessionEnded(session: PhoneSession, reason: CloseReason) {
                    ended += System.nanoTime() to reason
                }
            },
            sessionListenerFactory = { s ->
                object : SessionListener {
                    override fun onVideoControl(play: Boolean, playStatus: Int, message: ControlMessage) {
                        if (play) {
                            videoCtrlAt[s.id] = System.nanoTime()
                            pipeline.attach(s)
                        }
                    }

                    override fun onKeyframeRequested(reason: KeyframeReason) = pipeline.keyframe(s)

                    override fun onClosed(reason: CloseReason) = pipeline.detach(s)
                }
            },
        ).start()
        val sessions = 5
        try {
            for (n in 1..sessions) {
                val sim = CarSim(
                    CarSimConfig(
                        broadcastAddress = InetAddress.getLoopbackAddress(),
                        phoneDiscoveryPort = discoveryPort,
                        carAckPort = ackPort,
                        broadcastIntervalMs = 100,
                        heartbeatPeriodMs = 300,
                        deviceUuid = "SIM-E2E",
                        carInfo = CarInfoValues(carWidth = 1920, carHeight = 882),
                        videoArgs = VideoArgsValues(frameRate = 30, bitRate = 5_080_320, frameInterval = 3),
                    ),
                ).start()
                assertTrue(sim.awaitState(CarSimState.STREAMING, 10_000), "sesión $n: ${sim.report().summary()}")
                assertTrue(sim.awaitVideoMessages(20, 10_000), "sesión $n: ${sim.report().summary()}")
                sim.requestKeyframe() // como el KEY_FRAME_REQ del C10 ~340 ms después del primer frame
                assertTrue(sim.awaitVideoMessages(40, 10_000))
                val report = sim.report()
                assertTrue(report.videoValid, "sesión $n: ${report.summary()}")
                assertEquals(VideoKind.CONFIG, report.firstVideoKind)
                assertTrue(report.idrFrames >= 2, "sesión $n: IDR=${report.idrFrames}")
                sim.close()
                assertTrue(waitUntil(5_000) { ended.size == n }, "sesión $n no terminó")
                assertTrue(sim.awaitTermination(5_000))
            }
            assertEquals(sessions, startedAt.size)
            // Del VIDEO_CTRL{1} al primer IDR escrito en cada sesión: < 500 ms (objetivo del diseño, §9.3).
            val ids = videoCtrlAt.keys.sorted()
            assertEquals(sessions, ids.size)
            for (id in ids) {
                val ms = (pipeline.firstIdrWrittenAt.getValue(id) - videoCtrlAt.getValue(id)) / 1_000_000
                assertTrue(ms < 500, "S$id: IDR a los $ms ms del VIDEO_CTRL")
            }
            // Del cierre de una sesión al TCP de la siguiente: con retryDelayMs = 0, el primer broadcast (≤ 100 ms) basta.
            for (k in 1 until sessions) {
                val gapMs = (startedAt[k] - ended[k - 1].first) / 1_000_000
                assertTrue(gapMs < 1_000, "reconexión ${k + 1}: $gapMs ms")
            }
            assertTrue(ended.all { it.second.kind == CloseReason.Kind.EOF }, ended.toString())
        } finally {
            link.close()
            pipeline.close()
        }
        assertTrue(link.awaitTermination(5_000))
        assertTrue(waitUntil(5_000) { liveThreads("qd-link", "qd-discovery", "carsim-").isEmpty() }, liveThreads("qd-", "carsim-").toString())
    }
}
