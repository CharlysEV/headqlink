package dev.qdauto.core.session

import dev.qdauto.core.TestSupport.freeUdpPort
import dev.qdauto.core.TestSupport.liveThreads
import dev.qdauto.core.TestSupport.waitUntil
import dev.qdauto.core.discovery.CarAnnouncement
import dev.qdauto.core.discovery.DiscoveryConfig
import dev.qdauto.core.sim.CarSim
import dev.qdauto.core.sim.CarSimConfig
import dev.qdauto.core.sim.CarSimState
import dev.qdauto.core.wire.UdpCodec
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PhoneLinkTest {
    private val loopback = InetAddress.getLoopbackAddress()

    private class LinkRecorder : PhoneLinkListener {
        val states: MutableList<LinkState> = Collections.synchronizedList(ArrayList())
        val started: MutableList<PhoneSession> = Collections.synchronizedList(ArrayList())
        val ended: MutableList<CloseReason> = Collections.synchronizedList(ArrayList())
        val ports: MutableList<Int> = Collections.synchronizedList(ArrayList())
        val timeouts: MutableList<CarAnnouncement> = Collections.synchronizedList(ArrayList())
        val extras: MutableList<InetSocketAddress?> = Collections.synchronizedList(ArrayList())
        override fun onLinkStateChanged(state: LinkState) {
            states += state
        }
        override fun onConnecting(car: CarAnnouncement, mirrorPort: Int) {
            ports += mirrorPort
        }
        override fun onSessionStarted(session: PhoneSession) {
            started += session
        }
        override fun onSessionEnded(session: PhoneSession, reason: CloseReason) {
            ended += reason
        }
        override fun onAcceptTimeout(car: CarAnnouncement) {
            timeouts += car
        }
        override fun onExtraConnection(from: InetSocketAddress?) {
            extras += from
        }
    }

    private fun simConfig(discoveryPort: Int, ackPort: Int) = CarSimConfig(
        broadcastAddress = loopback,
        phoneDiscoveryPort = discoveryPort,
        carAckPort = ackPort,
        broadcastIntervalMs = 50,
        heartbeatPeriodMs = 200,
    )

    @Test
    fun connectsStreamsAndReconnectsAfterTheCarLeaves() {
        val discoveryPort = freeUdpPort()
        val ackPort = freeUdpPort()
        val rec = LinkRecorder()
        val sessionEvents = RecordingListener()
        val link = PhoneLink(
            PhoneLinkConfig(
                discovery = DiscoveryConfig(port = discoveryPort, ackPort = ackPort),
                session = SessionConfig(heartbeatInitialDelayMs = 100, heartbeatPeriodMs = 500),
                retryDelayMs = 100,
            ),
            rec,
            sessionEvents,
        ).start()
        assertEquals(LinkState.SEARCHING, link.state)

        val sim1 = CarSim(simConfig(discoveryPort, ackPort)).start()
        assertTrue(sim1.awaitState(CarSimState.STREAMING, 10_000), sim1.report().summary())
        assertTrue(waitUntil(5_000) { rec.started.size == 1 && link.state == LinkState.CONNECTED })
        assertNotNull(link.currentSession)
        assertTrue(sessionEvents.streaming.await(5, java.util.concurrent.TimeUnit.SECONDS)) // los eventos de sesión llegan a la app

        // Una conexión TCP extra al mismo puerto se registra y se cierra; la sesión sigue.
        Socket(loopback, rec.ports.single()).use { extra ->
            assertTrue(waitUntil(5_000) { rec.extras.size == 1 })
            extra.soTimeout = 2_000
            assertEquals(-1, extra.getInputStream().read())
        }
        assertEquals(LinkState.CONNECTED, link.state)

        // El coche se va: la sesión termina (EOF) y el enlace vuelve a buscar.
        sim1.close()
        assertTrue(waitUntil(5_000) { rec.ended.size == 1 && link.state == LinkState.SEARCHING })
        assertEquals(CloseReason.Kind.EOF, rec.ended.single().kind)
        assertTrue(sim1.awaitTermination(5_000))

        // Otro arranque del coche: reconexión automática con un puerto nuevo.
        val sim2 = CarSim(simConfig(discoveryPort, ackPort)).start()
        assertTrue(sim2.awaitState(CarSimState.STREAMING, 10_000), sim2.report().summary())
        assertTrue(waitUntil(5_000) { rec.started.size == 2 && link.state == LinkState.CONNECTED })
        assertEquals(2, link.attemptCount)

        link.close()
        assertTrue(sim2.awaitClosed(5_000))
        assertTrue(link.awaitTermination(5_000))
        sim2.close()
        assertTrue(sim2.awaitTermination(5_000))
        assertEquals(LinkState.STOPPED, link.state)
        assertTrue(waitUntil(5_000) { liveThreads("qd-link", "qd-discovery", "carsim-").isEmpty() }, liveThreads("qd-", "carsim-").toString())
        assertEquals(
            listOf(LinkState.SEARCHING, LinkState.CONNECTING, LinkState.CONNECTED, LinkState.SEARCHING, LinkState.CONNECTING, LinkState.CONNECTED, LinkState.STOPPED),
            rec.states.toList(),
        )
    }

    @Test
    fun acceptTimeoutRetriesWithANewAck() {
        val car = DatagramSocket(0, loopback)
        val rec = LinkRecorder()
        val link = PhoneLink(
            PhoneLinkConfig(
                discovery = DiscoveryConfig(port = 0, ackPort = car.localPort),
                acceptTimeoutMs = 200,
                retryDelayMs = 100,
            ),
            rec,
        ).start()
        try {
            val broadcast = UdpCodec.buildConnectBroadcast("u", "C10")
            val phone = InetSocketAddress(loopback, link.discovery.localPort)
            val acks = ArrayList<Int>()
            car.soTimeout = 50
            val deadline = System.currentTimeMillis() + 10_000
            while (acks.size < 2 && System.currentTimeMillis() < deadline) {
                car.send(DatagramPacket(broadcast, broadcast.size, phone)) // el coche anuncia pero nunca conecta
                val p = DatagramPacket(ByteArray(1024), 1024)
                try {
                    car.receive(p)
                    acks += UdpCodec.parseBroadcastAck(p.data, 0, p.length)!!.mirrorPort
                } catch (_: SocketTimeoutException) {
                }
            }
            assertEquals(2, acks.size)
            assertTrue(waitUntil(3_000) { rec.timeouts.size >= 1 })
            assertEquals(acks, rec.ports.take(2))
            assertTrue(rec.started.isEmpty())
        } finally {
            link.close()
            car.close()
        }
        assertTrue(link.awaitTermination(5_000))
    }
}
