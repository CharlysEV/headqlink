package dev.qdauto.core.sim

import dev.qdauto.core.TestSupport.freeUdpPort
import dev.qdauto.core.TestSupport.waitUntil
import dev.qdauto.core.discovery.CarAnnouncement
import dev.qdauto.core.discovery.DiscoveryConfig
import dev.qdauto.core.discovery.DiscoveryListener
import dev.qdauto.core.session.MirrorServer
import dev.qdauto.core.wire.Cmd
import dev.qdauto.core.wire.ControlMessage
import dev.qdauto.core.wire.FrameReader
import dev.qdauto.core.wire.MsgType
import dev.qdauto.core.wire.PhoneMessages
import dev.qdauto.core.wire.VideoMessage
import dev.qdauto.core.wire.VideoParams
import dev.qdauto.core.wire.WireMessage
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Manía del C10 (2026-10-05): con un mensaje de vídeo de más de 512 KiB el receptor deja de leer, sigue mandando
 * heartbeats y la sesión cae ~10 s después. Un teléfono "crudo" (socket a pelo) manda uno de más y mira qué hace
 * el simulador.
 */
class ReceiverHangTest {
    private val loopback: InetAddress = InetAddress.getLoopbackAddress()
    private val params = VideoParams(1920, 1080, 30, 4_000_000, 1, 3, appType = 1, angle = 90, orientation = 1)
    private val config = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0xC0.toByte(), 0x29, 0, 0, 0, 1, 0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())

    private fun frame(key: Boolean, bodyBytes: Int): ByteArray = byteArrayOf(0, 0, 0, 1, if (key) 0x65 else 0x41) + ByteArray(bodyBytes) { 7 }

    private class RawPhone(phonePort: Int, carPort: Int) : AutoCloseable {
        private val loopback: InetAddress = InetAddress.getLoopbackAddress()
        val accepted = CountDownLatch(1)
        val heartbeats = AtomicInteger()
        val eof = CountDownLatch(1)

        @Volatile
        var socket: Socket? = null
        private val server = MirrorServer(0, loopback)
        private lateinit var discovery: DiscoveryListener
        private val config = DiscoveryConfig(port = phonePort, ackPort = carPort, bindAddress = loopback)

        private val callback = object : DiscoveryListener.Callback {
                override fun onCarFound(car: CarAnnouncement) {
                    thread(name = "raw-phone-accept", isDaemon = true) {
                        val ack = discovery.sendAck(car, server.port)
                        val s = server.accept(10_000)
                        ack.cancel()
                        socket = s
                        s.getOutputStream().write(PhoneMessages.appStatus(34))
                        accepted.countDown()
                        thread(name = "raw-phone-reader", isDaemon = true) {
                            val reader = FrameReader(s.getInputStream())
                            try {
                                while (true) {
                                    val m = reader.next() ?: break
                                    if (m is WireMessage.Frame && m.header.msgType == MsgType.CONTROL &&
                                        ControlMessage.parse(m.header, m.payloadText()).cmd == Cmd.HEARTBEAT
                                    ) heartbeats.incrementAndGet()
                                }
                            } catch (_: IOException) {
                            }
                            eof.countDown()
                        }
                    }
                }
            }

        fun start() = apply { discovery = DiscoveryListener(config, callback).start() }

        override fun close() {
            runCatching { socket?.close() }
            server.close()
            discovery.close()
        }
    }

    private fun simConfig(phonePort: Int, carPort: Int, limit: Int, hangMs: Long) = CarSimConfig(
        broadcastAddress = loopback,
        phoneDiscoveryPort = phonePort,
        carAckPort = carPort,
        broadcastIntervalMs = 100,
        discoveryTimeoutMs = 10_000,
        waitForReplies = false,
        heartbeatPeriodMs = 100,
        expectedAppType = null,
        receiverLimitBytes = limit,
        receiverHangMs = hangMs,
    )

    @Test
    fun anOversizedVideoMessageStopsTheReaderKeepsHeartbeatsAndThenCloses() {
        val phonePort = freeUdpPort()
        var carPort = freeUdpPort()
        while (carPort == phonePort) carPort = freeUdpPort()
        val hangs = ArrayList<ReceiverHang>()
        val phone = RawPhone(phonePort, carPort).start()
        val sim = CarSim(simConfig(phonePort, carPort, limit = 512 * 1024, hangMs = 1_500), object : CarSimListener {
            override fun onReceiverHang(hang: ReceiverHang) {
                hangs += hang
            }
        }).start()
        try {
            assertTrue(phone.accepted.await(10, TimeUnit.SECONDS), "el coche no conectó")
            assertTrue(sim.awaitState(CarSimState.STREAMING, 5_000))
            val out = phone.socket!!.getOutputStream()
            out.write(VideoMessage.build(params, config))
            out.write(VideoMessage.build(params, frame(true, 10_000)))
            out.write(VideoMessage.build(params, frame(false, 2_000)))
            assertTrue(sim.awaitVideoMessages(3, 5_000))
            val oversized = VideoMessage.build(params, frame(true, 600 * 1024))
            out.write(oversized)
            out.flush()
            assertTrue(waitUntil(5_000) { hangs.isNotEmpty() }, "el simulador no se colgó")
            val hang = hangs.single()
            assertEquals(3, hang.messageIndex)
            assertEquals(oversized.size, hang.messageBytes)
            assertEquals(512 * 1024, hang.limitBytes)
            assertEquals(1_500, hang.hangMs)
            // Mientras está colgado sigue sin leer (lo que mandemos no cuenta) pero manda heartbeats.
            val heartbeatsAtHang = phone.heartbeats.get()
            val videoAtHang = sim.report().videoMessages
            assertEquals(4, videoAtHang)
            Thread.sleep(600)
            out.write(VideoMessage.build(params, frame(false, 2_000)))
            out.flush()
            Thread.sleep(300)
            assertEquals(4, sim.report().videoMessages, "leyó durante el cuelgue")
            assertTrue(phone.heartbeats.get() >= heartbeatsAtHang + 3, "sin heartbeats durante el cuelgue")
            assertTrue(sim.awaitClosed(5_000), "no cerró tras el cuelgue")
            assertTrue(phone.eof.await(5, TimeUnit.SECONDS), "el teléfono no vio el cierre")
            val r = sim.report()
            assertNotNull(r.receiverHang)
            assertEquals(oversized.size, r.videoMaxMessageBytes)
            assertEquals(1, r.videoLargeMessages)
            assertTrue(r.closeReason!!.contains("receptor colgado"), r.closeReason)
            assertTrue(r.closeReason!!.contains("${oversized.size} B"), r.closeReason)
        } finally {
            sim.close()
            phone.close()
            sim.awaitTermination(5_000)
        }
    }

    @Test
    fun withTheLimitOffBigMessagesAreJustCounted() {
        val phonePort = freeUdpPort()
        var carPort = freeUdpPort()
        while (carPort == phonePort) carPort = freeUdpPort()
        val phone = RawPhone(phonePort, carPort).start()
        val sim = CarSim(simConfig(phonePort, carPort, limit = 0, hangMs = 1_000)).start()
        try {
            assertTrue(phone.accepted.await(10, TimeUnit.SECONDS))
            assertTrue(sim.awaitState(CarSimState.STREAMING, 5_000))
            val out = phone.socket!!.getOutputStream()
            out.write(VideoMessage.build(params, config))
            out.write(VideoMessage.build(params, frame(true, 600 * 1024)))
            out.write(VideoMessage.build(params, frame(false, 490 * 1024)))
            out.write(VideoMessage.build(params, frame(false, 1_000)))
            out.flush()
            assertTrue(sim.awaitVideoMessages(4, 5_000))
            Thread.sleep(300)
            val r = sim.report()
            assertNull(r.receiverHang)
            assertEquals(CarSimState.STREAMING, r.state)
            assertEquals(4, r.videoMessages)
            assertEquals(2, r.videoLargeMessages) // 600 KiB y 490 KiB pasan de 480 KiB
            assertEquals(48 + 5 + 600 * 1024, r.videoMaxMessageBytes)
            assertEquals(1, r.codecConfigs.size)
            assertEquals(CodecConfigVerdict.FIRST, r.codecConfigs[0].verdict)
        } finally {
            sim.close()
            phone.close()
            sim.awaitTermination(5_000)
        }
    }
}
