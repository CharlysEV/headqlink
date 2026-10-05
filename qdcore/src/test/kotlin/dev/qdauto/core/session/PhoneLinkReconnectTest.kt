package dev.qdauto.core.session

import dev.qdauto.core.TestSupport.freeUdpPort
import dev.qdauto.core.TestSupport.waitUntil
import dev.qdauto.core.discovery.CarAnnouncement
import dev.qdauto.core.discovery.DiscoveryConfig
import dev.qdauto.core.sim.CarSim
import dev.qdauto.core.sim.CarSimConfig
import dev.qdauto.core.sim.CarSimState
import dev.qdauto.core.util.QdLog
import dev.qdauto.core.wire.UdpCodec
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** hql (C4): escucha por sesión, re-ACK, relevo, filtros de IP, *bind* y reconexión inmediata de [PhoneLink]. */
class PhoneLinkReconnectTest {
    private val loopback = InetAddress.getLoopbackAddress()
    private val closeables = ArrayList<Closeable>()

    @AfterTest
    fun cleanup() = closeables.reversed().forEach { runCatching { it.close() } }

    private class Events : PhoneLinkListener {
        val started = CopyOnWriteArrayList<PhoneSession>()
        val ended = CopyOnWriteArrayList<Pair<PhoneSession, CloseReason>>()
        val acks = CopyOnWriteArrayList<Pair<Int, Long>>()
        val seenAt = CopyOnWriteArrayList<Long>()
        val superseded = CopyOnWriteArrayList<Pair<PhoneSession, Long>>()
        val rejected = CopyOnWriteArrayList<InetSocketAddress?>()
        val startedAt = CopyOnWriteArrayList<Long>()
        val states = CopyOnWriteArrayList<LinkState>()
        override fun onLinkStateChanged(state: LinkState) {
            states += state
        }

        override fun onCarFound(car: CarAnnouncement) {
            seenAt += System.nanoTime()
        }

        override fun onCarSeen(car: CarAnnouncement) {
            seenAt += System.nanoTime()
        }

        override fun onAckSent(car: CarAnnouncement, mirrorPort: Int, attempt: Int) {
            acks += attempt to System.nanoTime()
        }

        override fun onSessionStarted(session: PhoneSession) {
            startedAt += System.nanoTime()
            started += session
        }

        override fun onSessionEnded(session: PhoneSession, reason: CloseReason) {
            ended += session to reason
        }

        override fun onSessionSuperseded(old: PhoneSession, car: CarAnnouncement) {
            superseded += old to System.nanoTime()
        }

        override fun onConnectionRejected(from: InetSocketAddress?, car: CarAnnouncement) {
            rejected += from
        }
    }

    private class Ports {
        val discovery = freeUdpPort()
        val ack: Int = run {
            var p = freeUdpPort()
            while (p == discovery) p = freeUdpPort()
            p
        }
    }

    private fun simConfig(p: Ports, uuid: String = "SIM-RECONNECT", heartbeatMs: Long = 100, broadcastMs: Long = 50, ignoreAcks: Int = 0) =
        CarSimConfig(
            broadcastAddress = loopback,
            phoneDiscoveryPort = p.discovery,
            carAckPort = p.ack,
            broadcastIntervalMs = broadcastMs,
            heartbeatPeriodMs = heartbeatMs,
            deviceUuid = uuid,
            ignoreAcks = ignoreAcks,
        )

    private fun sim(config: CarSimConfig): CarSim = CarSim(config).also { closeables += it }.start()

    private fun link(p: Ports, events: Events, config: PhoneLinkConfig, factory: ((PhoneSession) -> SessionListener)? = null, log: QdLog = QdLog.NONE): PhoneLink =
        PhoneLink(
            config.copy(
                discovery = DiscoveryConfig(port = p.discovery, ackPort = p.ack),
                session = SessionConfig(heartbeatInitialDelayMs = 100, heartbeatPeriodMs = 500),
            ),
            events,
            object : SessionListener {},
            log,
            factory,
        ).also { closeables += it }.start()

    @Test
    fun forwardingListenerOverridesEverySessionListenerMethod() {
        val wanted = SessionListener::class.java.declaredMethods.filter { !it.isSynthetic }.map { it.name to it.parameterTypes.toList() }.toSet()
        val have = ForwardingSessionListener::class.java.declaredMethods.map { it.name to it.parameterTypes.toList() }.toSet()
        assertTrue(wanted.isNotEmpty())
        assertEquals(emptySet(), wanted - have, "métodos de SessionListener sin reenviar")
    }

    @Test
    fun factoryGivesEachSessionItsOwnListenerAndSupersedeKeepsThemApart() {
        val p = Ports()
        val events = Events()
        val created = CopyOnWriteArrayList<Pair<PhoneSession, RecordingListener>>()
        val startedBeforeFactory = AtomicInteger()
        val link = link(
            p,
            events,
            PhoneLinkConfig(retryDelayMs = 0, supersedeOnRebroadcast = true, supersedeMinSessionAgeMs = 400, supersedeMinSilenceMs = 300),
            factory = { s ->
                if (s.startedAtNanos != 0L) startedBeforeFactory.incrementAndGet()
                RecordingListener().also { created += s to it }
            },
        )
        val sim1 = sim(simConfig(p))
        assertTrue(sim1.awaitState(CarSimState.STREAMING, 10_000), sim1.report().summary())
        assertTrue(waitUntil(5_000) { created.size == 1 && events.started.size == 1 })
        val (s1, l1) = created[0]
        assertTrue(l1.streaming.await(5, java.util.concurrent.TimeUnit.SECONDS))
        // Corte de radio en los dos sentidos: el coche ni habla ni lee; al rato otro arranque suyo se anuncia.
        sim1.goSilent()
        sim1.pauseReading(0)
        Thread.sleep(600)
        val sim2 = sim(simConfig(p))
        assertTrue(sim2.awaitState(CarSimState.STREAMING, 10_000), sim2.report().summary())
        assertTrue(waitUntil(5_000) { created.size == 2 && events.started.size == 2 })
        val (s2, l2) = created[1]
        assertTrue(l1.awaitClosed(5_000))
        assertEquals(CloseReason.Kind.SUPERSEDED, l1.closeReason?.kind)
        assertTrue(waitUntil(2_000) { events.ended.any { it.first === s1 && it.second.kind == CloseReason.Kind.SUPERSEDED } })
        assertEquals(1, events.superseded.size)
        // Nada de S1 llega al listener de S2 (tampoco su cierre); S2 sigue viva y es la actual.
        assertNull(l2.closeReason)
        assertFalse(s2.isClosed)
        assertTrue(link.currentSession === s2)
        assertEquals(0, startedBeforeFactory.get())
        assertTrue(l2.streaming.await(5, java.util.concurrent.TimeUnit.SECONDS))
        // Del broadcast que provoca el relevo al TCP de la sesión nueva: un instante.
        val relayMs = (events.startedAt[1] - events.superseded[0].second) / 1_000_000
        assertTrue(relayMs < 200, "relevo → TCP: $relayMs ms")
        sim1.close()
        sim2.close()
    }

    @Test
    fun noSupersedeWhileTheSessionTalksOrIsYoung() {
        val p = Ports()
        val events = Events()
        val link = link(p, events, PhoneLinkConfig(retryDelayMs = 0, supersedeOnRebroadcast = true, supersedeMinSessionAgeMs = 300, supersedeMinSilenceMs = 400))
        val sim1 = sim(simConfig(p, heartbeatMs = 100))
        assertTrue(sim1.awaitState(CarSimState.STREAMING, 10_000))
        assertTrue(waitUntil(5_000) { events.started.size == 1 })
        Thread.sleep(400)
        // Un broadcast del mismo coche con la sesión hablando (heartbeat cada 100 ms): no hay relevo.
        val sim2 = sim(simConfig(p))
        Thread.sleep(1_000)
        assertTrue(events.superseded.isEmpty())
        assertFalse(events.started[0].isClosed)
        sim2.close()

        // Sesión recién creada: tampoco, aunque el coche esté callado.
        val p2 = Ports()
        val events2 = Events()
        link(p2, events2, PhoneLinkConfig(retryDelayMs = 0, supersedeOnRebroadcast = true, supersedeMinSessionAgeMs = 5_000, supersedeMinSilenceMs = 100))
        val sim3 = sim(simConfig(p2))
        assertTrue(sim3.awaitState(CarSimState.STREAMING, 10_000))
        assertTrue(waitUntil(5_000) { events2.started.size == 1 })
        sim3.goSilent()
        Thread.sleep(300)
        val sim4 = sim(simConfig(p2))
        Thread.sleep(800)
        assertTrue(events2.superseded.isEmpty())
        assertFalse(events2.started[0].isClosed)
        sim4.close()
        assertTrue(link.currentSession != null)
    }

    @Test
    fun aLostAckIsResentWithTheNextBroadcastsButNotTooOften() {
        val p = Ports()
        val events = Events()
        link(p, events, PhoneLinkConfig(retryDelayMs = 0, reAckIntervalMs = 400))
        val sim = sim(simConfig(p, broadcastMs = 50, ignoreAcks = 1))
        assertTrue(sim.awaitState(CarSimState.STREAMING, 10_000), sim.report().summary())
        assertTrue(waitUntil(5_000) { events.started.size == 1 })
        val acks = events.acks.toList()
        assertEquals(listOf(1, 2), acks.map { it.first })
        val gapMs = (acks[1].second - acks[0].second) / 1_000_000
        assertTrue(gapMs in 380..1_500, "entre ACK: $gapMs ms")
    }

    @Test
    fun withoutReAckOnlyOneAckPerAttempt() {
        val p = Ports()
        val events = Events()
        link(p, events, PhoneLinkConfig(retryDelayMs = 0, acceptTimeoutMs = 1_500))
        val sim = sim(simConfig(p, broadcastMs = 50, ignoreAcks = 1))
        Thread.sleep(1_000)
        assertEquals(listOf(1), events.acks.map { it.first })
        // Tras el timeout del intento (1,5 s) se reintenta con un ACK nuevo, que esta vez sí vale.
        assertTrue(sim.awaitState(CarSimState.STREAMING, 10_000), sim.report().summary())
    }

    @Test
    fun acceptFilterRejectsAndKeepsWaitingWithinTheSameWindow() {
        val car = DatagramSocket(0, loopback).also { closeables += it }
        val events = Events()
        val calls = AtomicInteger()
        val link = PhoneLink(
            PhoneLinkConfig(
                discovery = DiscoveryConfig(port = 0, ackPort = car.localPort),
                session = SessionConfig(heartbeatInitialDelayMs = 60_000),
                acceptTimeoutMs = 5_000,
                acceptFilter = { _, _ -> calls.incrementAndGet() > 1 },
            ),
            events,
        ).also { closeables += it }.start()
        val broadcast = UdpCodec.buildConnectBroadcast("u", "C10")
        car.send(DatagramPacket(broadcast, broadcast.size, InetSocketAddress(loopback, link.discovery.localPort)))
        car.soTimeout = 5_000
        val pk = DatagramPacket(ByteArray(1024), 1024)
        car.receive(pk)
        val port = assertNotNull(UdpCodec.parseBroadcastAck(pk.data, 0, pk.length)).mirrorPort
        // Primera conexión: rechazada y cerrada.
        Socket(loopback, port).use { first ->
            first.soTimeout = 3_000
            assertEquals(-1, first.getInputStream().read())
        }
        assertTrue(waitUntil(2_000) { events.rejected.size == 1 })
        // Segunda, dentro de la misma ventana: aceptada (llega el AppStatus).
        Socket(loopback, port).use { second ->
            second.soTimeout = 3_000
            assertEquals('!'.code, second.getInputStream().read())
            assertTrue(waitUntil(2_000) { events.started.size == 1 })
        }
        assertEquals(2, calls.get())
    }

    @Test
    fun mirrorServerListensOnTheChosenAddressAndFallsBackToTheWildcard() {
        val lines = Collections.synchronizedList(ArrayList<String>())
        val log = QdLog { _, tag, message, _ -> lines += "$tag: $message" }
        val p = Ports()
        val events = Events()
        val asked = CopyOnWriteArrayList<CarAnnouncement>()
        link(p, events, PhoneLinkConfig(retryDelayMs = 0, mirrorBindAddressFor = { car -> asked += car; loopback }), log = log)
        val sim = sim(simConfig(p))
        assertTrue(sim.awaitState(CarSimState.STREAMING, 10_000))
        assertEquals("SIM-RECONNECT", asked.first().uuid)
        assertTrue(lines.any { it.startsWith("QD/MirrorServer: escuchando TCP en") && "/127.0.0.1:" in it }, lines.filter { "MirrorServer" in it }.toString())
        assertTrue(lines.none { it.startsWith("QD/MirrorServer: escuchando TCP en") && "0.0.0.0" in it })
        sim.close()

        // Una IP que no es de este equipo: se escucha en todas y la conexión funciona igual.
        val p2 = Ports()
        val events2 = Events()
        link(p2, events2, PhoneLinkConfig(retryDelayMs = 0, mirrorBindAddressFor = { InetAddress.getByName("192.0.2.1") }), log = log)
        val sim2 = sim(simConfig(p2))
        assertTrue(sim2.awaitState(CarSimState.STREAMING, 10_000), sim2.report().summary())
        assertTrue(lines.any { "se escucha en todas las interfaces" in it })
    }

    @Test
    fun zeroRetryDelayReconnectsWithTheNextBroadcast() {
        val p = Ports()
        val events = Events()
        link(p, events, PhoneLinkConfig(retryDelayMs = 0))
        val sim1 = sim(simConfig(p, broadcastMs = 100))
        assertTrue(sim1.awaitState(CarSimState.STREAMING, 10_000))
        assertTrue(waitUntil(5_000) { events.started.size == 1 })
        sim1.close()
        assertTrue(waitUntil(5_000) { events.ended.size == 1 })
        val endedAt = System.nanoTime()
        val sim2 = sim(simConfig(p, broadcastMs = 100))
        assertTrue(sim2.awaitState(CarSimState.STREAMING, 10_000))
        assertTrue(waitUntil(5_000) { events.started.size == 2 })
        val ms = (events.startedAt[1] - endedAt) / 1_000_000
        assertTrue(ms < 1_000, "reconexión en $ms ms")
        assertEquals(CloseReason.Kind.EOF, events.ended[0].second.kind)
    }

    @Test
    fun aBroadcastWhileTheClosedSessionIsStillWindingDownReconnectsAtOnce() {
        val p = Ports()
        val events = Events()
        // El onClosed de la sesión tarda (el endAttempt del enlace va detrás): el broadcast llega en ese hueco.
        link(p, events, PhoneLinkConfig(retryDelayMs = 0), factory = {
            object : SessionListener {
                override fun onClosed(reason: CloseReason) = Thread.sleep(800)
            }
        })
        val sim1 = sim(simConfig(p, broadcastMs = 500))
        assertTrue(sim1.awaitState(CarSimState.STREAMING, 10_000))
        assertTrue(waitUntil(5_000) { events.started.size == 1 })
        sim1.close()
        assertTrue(sim1.awaitTermination(5_000))
        Thread.sleep(100) // la sesión ya está cerrada; su onClosed sigue durmiendo
        val t0 = System.nanoTime()
        val sim2 = sim(simConfig(p, broadcastMs = 500))
        assertTrue(sim2.awaitState(CarSimState.STREAMING, 10_000))
        assertTrue(waitUntil(5_000) { events.started.size == 2 })
        val ms = (events.startedAt[1] - t0) / 1_000_000
        assertTrue(ms < 400, "sesión nueva a los $ms ms del primer broadcast (antes se perdía ese broadcast)")
    }

    @Test
    fun withARetryDelayTheBroadcastInTheWindingDownGapLeavesTheLinkSearching() {
        val p = Ports()
        val events = Events()
        link(p, events, PhoneLinkConfig(retryDelayMs = 1_500), factory = {
            object : SessionListener {
                override fun onClosed(reason: CloseReason) = Thread.sleep(800)
            }
        })
        val sim1 = sim(simConfig(p, broadcastMs = 500))
        assertTrue(sim1.awaitState(CarSimState.STREAMING, 10_000))
        assertTrue(waitUntil(5_000) { events.started.size == 1 })
        sim1.close()
        assertTrue(sim1.awaitTermination(5_000))
        Thread.sleep(100) // la sesión ya está cerrada; su onClosed sigue durmiendo
        val before = events.states.size
        val sim2 = sim(simConfig(p, broadcastMs = 100))
        // Con espera entre intentos no se conecta en el acto, pero el enlace tiene que volver a SEARCHING (lo que haría
        // endAttempt, que al terminar el onClosed ya no hace nada) y conectar cuando pase la espera.
        assertTrue(waitUntil(600) { LinkState.SEARCHING in events.states.drop(before) }, "estados: ${events.states}")
        assertTrue(sim2.awaitState(CarSimState.STREAMING, 10_000))
        assertTrue(waitUntil(5_000) { events.started.size == 2 })
        assertEquals(LinkState.CONNECTED, events.states.last())
    }

    @Test
    fun closeSessionCarriesTheReason() {
        val p = Ports()
        val events = Events()
        val l = RecordingListener()
        val link = link(p, events, PhoneLinkConfig(retryDelayMs = 0), factory = { l })
        val sim = sim(simConfig(p))
        assertTrue(sim.awaitState(CarSimState.STREAMING, 10_000))
        assertTrue(waitUntil(5_000) { events.started.size == 1 })
        link.closeSession("aplicar ajustes")
        assertTrue(l.awaitClosed(5_000))
        assertEquals(CloseReason(CloseReason.Kind.LOCAL, "aplicar ajustes"), l.closeReason)
        assertTrue(sim.awaitClosed(5_000))
    }

    @Test
    fun abruptCloseAndPausedReadingFromTheSimulator() {
        val p = Ports()
        val events = Events()
        link(p, events, PhoneLinkConfig(retryDelayMs = 0))
        val sim = sim(simConfig(p, heartbeatMs = 1_000))
        assertTrue(sim.awaitState(CarSimState.STREAMING, 10_000))
        assertTrue(waitUntil(5_000) { events.started.size == 1 })
        sim.pauseReading(300)
        Thread.sleep(400)
        sim.resumeReading()
        sim.closeAbruptly()
        assertTrue(waitUntil(5_000) { events.ended.size == 1 })
        assertTrue(events.ended[0].second.kind in setOf(CloseReason.Kind.READ_ERROR, CloseReason.Kind.EOF, CloseReason.Kind.WRITE_ERROR), events.ended.toString())
    }
}
