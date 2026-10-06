package dev.qdauto.core.session

import dev.qdauto.core.TestSupport.freeUdpPort
import dev.qdauto.core.TestSupport.liveThreads
import dev.qdauto.core.TestSupport.waitUntil
import dev.qdauto.core.discovery.AckPolicy
import dev.qdauto.core.discovery.CarAnnouncement
import dev.qdauto.core.discovery.DiscoveryConfig
import dev.qdauto.core.sim.CarSim
import dev.qdauto.core.sim.CarSimConfig
import dev.qdauto.core.sim.CarSimState
import dev.qdauto.core.util.QdLog
import dev.qdauto.core.wire.UdpCodec
import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * hql: vuelta del coche tras un corte de radio ([RecoveryConfig]): ACK en cada broadcast y reenvíos mientras se espera
 * el TCP, puerto estable, re-acogida en el puerto de antes (sin broadcast, con ACK no pedidos o con un solo broadcast),
 * reapertura del UDP tras un silencio, diagnóstico y cierre de todo con [PhoneLink.close].
 */
class PhoneLinkRecoveryTest {
    private val loopback = InetAddress.getLoopbackAddress()
    private val closeables = ArrayList<Closeable>()

    @AfterTest
    fun cleanup() = closeables.reversed().forEach { runCatching { it.close() } }

    private class Events : PhoneLinkListener {
        val states = CopyOnWriteArrayList<LinkState>()
        val ports = CopyOnWriteArrayList<Int>()
        val started = CopyOnWriteArrayList<PhoneSession>()
        val ended = CopyOnWriteArrayList<CloseReason>()
        val reclaims = CopyOnWriteArrayList<Pair<Int, CloseReason>>()
        val back = CopyOnWriteArrayList<CarReturn>()
        val unsolicited = CopyOnWriteArrayList<Int>()
        val solicited = CopyOnWriteArrayList<Int>()
        val reopened = CopyOnWriteArrayList<Long>()
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

        override fun onReclaimStarted(car: CarAnnouncement, mirrorPort: Int, reason: CloseReason) {
            reclaims += mirrorPort to reason
        }

        override fun onCarBack(car: CarAnnouncement, how: CarReturn, afterMs: Long) {
            back += how
        }

        override fun onAckSent(car: CarAnnouncement, mirrorPort: Int, attempt: Int, unsolicited: Boolean) {
            (if (unsolicited) this.unsolicited else solicited) += mirrorPort
        }

        override fun onDiscoveryReopened(quietMs: Long) {
            reopened += quietMs
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

    private val lines = CopyOnWriteArrayList<String>()
    private val log = QdLog { _, tag, message, _ -> lines += "$tag: $message" }

    /** Sesión que detecta pronto un corte de radio (el coche ni habla ni lee): WATCHDOG a los ~700 ms. */
    private val fastSession = SessionConfig(
        heartbeatInitialDelayMs = 100,
        heartbeatPeriodMs = 300,
        watchdogCheckIntervalMs = 100,
        watchdogWarnMs = 300,
        watchdogTimeoutMs = 700,
        watchdogRequiresCarTraffic = false,
    )

    private fun simConfig(p: Ports, broadcastMs: Long = 50) = CarSimConfig(
        broadcastAddress = loopback,
        phoneDiscoveryPort = p.discovery,
        carAckPort = p.ack,
        broadcastIntervalMs = broadcastMs,
        heartbeatPeriodMs = 100,
        deviceUuid = "SIM-RECOVERY",
    )

    private fun sim(config: CarSimConfig): CarSim = CarSim(config).also { closeables += it }.start()

    private fun link(p: Ports, events: Events, config: PhoneLinkConfig = PhoneLinkConfig()): PhoneLink =
        PhoneLink(
            config.copy(
                discovery = config.discovery.copy(port = p.discovery, ackPort = p.ack),
                session = fastSession,
                retryDelayMs = 0,
            ),
            events,
            object : SessionListener {},
            log,
        ).also { closeables += it }.start()

    private fun receive(s: DatagramSocket, timeoutMs: Int): DatagramPacket? {
        s.soTimeout = timeoutMs
        val p = DatagramPacket(ByteArray(2048), 2048)
        return try {
            s.receive(p)
            p
        } catch (_: SocketTimeoutException) {
            null
        }
    }

    private fun mirrorPortOf(p: DatagramPacket): Int = assertNotNull(UdpCodec.parseBroadcastAck(p.data, 0, p.length)).mirrorPort

    /** Primera sesión con un coche simulado y corte de radio: el coche ni habla ni lee hasta que el móvil la cierra. */
    private fun firstSessionThenRadioDrop(p: Ports, link: PhoneLink, events: Events): Int {
        val sim1 = sim(simConfig(p))
        assertTrue(sim1.awaitState(CarSimState.STREAMING, 10_000), sim1.report().summary())
        assertTrue(waitUntil(5_000) { events.started.size == 1 && link.state == LinkState.CONNECTED })
        val port = events.ports.single()
        sim1.goSilent()
        sim1.pauseReading(0)
        assertTrue(waitUntil(5_000) { events.ended.size == 1 }, "la sesión no se cerró")
        assertEquals(CloseReason.Kind.WATCHDOG, events.ended[0].kind)
        return port
    }

    @Test
    fun whileConnectingEveryBroadcastGetsAFreshAckAndTheAckIsResentUntilTheTcpArrives() {
        val car = DatagramSocket(0, loopback).also { closeables += it }
        val events = Events()
        val link = PhoneLink(
            PhoneLinkConfig(
                discovery = DiscoveryConfig(port = 0, ackPort = car.localPort, ackPolicy = AckPolicy(retries = 50, firstRetryDelayMs = 300, retryIntervalMs = 300)),
                session = SessionConfig(heartbeatInitialDelayMs = 60_000),
                acceptTimeoutMs = 10_000,
            ),
            events,
        ).also { closeables += it }.start()
        val phone = InetSocketAddress(loopback, link.discovery.localPort)
        val broadcast = UdpCodec.buildConnectBroadcast("u-resend", "C10")
        car.send(DatagramPacket(broadcast, broadcast.size, phone))
        val port = mirrorPortOf(assertNotNull(receive(car, 3_000)))
        // Otro broadcast del mismo coche: ACK en el acto, con el mismo puerto y sin intento nuevo.
        val t = System.nanoTime()
        car.send(DatagramPacket(broadcast, broadcast.size, phone))
        assertEquals(port, mirrorPortOf(assertNotNull(receive(car, 3_000))))
        assertTrue((System.nanoTime() - t) / 1_000_000 < 250, "el ACK del broadcast no fue inmediato")
        // Sin más broadcasts, el ACK se reenvía solo (cada 300 ms aquí; cada 2 s por defecto).
        repeat(2) { assertEquals(port, mirrorPortOf(assertNotNull(receive(car, 1_000), "reenvío #$it"))) }
        assertEquals(1, link.attemptCount)
        assertEquals(LinkState.CONNECTING, link.state)
        assertTrue(link.isConnecting)
        // Llega el TCP: se acabaron los ACK.
        Socket(loopback, port).use { s ->
            s.soTimeout = 3_000
            assertEquals('!'.code, s.getInputStream().read())
            assertTrue(waitUntil(3_000) { events.started.size == 1 })
            while (receive(car, 100) != null) Unit // el que estuviera en el aire
            assertNull(receive(car, 800))
        }
        assertTrue(events.solicited.size >= 4)
        assertTrue(events.unsolicited.isEmpty())
    }

    @Test
    fun theMirrorPortOfThePreviousSessionIsReusedAndARandomOneIfItIsBusy() {
        val p = Ports()
        val events = Events()
        val link = link(p, events)
        val sim1 = sim(simConfig(p))
        assertTrue(sim1.awaitState(CarSimState.STREAMING, 10_000))
        val port1 = events.ports[0]
        sim1.close()
        assertTrue(waitUntil(5_000) { events.ended.size == 1 && link.state == LinkState.SEARCHING })
        assertEquals(CloseReason.Kind.EOF, events.ended[0].kind) // final normal: sin re-acogida
        assertTrue(events.reclaims.isEmpty())
        assertEquals(port1, link.stableMirrorPort)
        val sim2 = sim(simConfig(p))
        assertTrue(sim2.awaitState(CarSimState.STREAMING, 10_000), sim2.report().summary())
        assertEquals(port1, events.ports[1])
        sim2.close()
        assertTrue(waitUntil(5_000) { events.ended.size == 2 && link.state == LinkState.SEARCHING })
        // Otro programa ocupa el puerto: se avisa y se usa uno aleatorio.
        ServerSocket(port1).use {
            val sim3 = sim(simConfig(p))
            assertTrue(sim3.awaitState(CarSimState.STREAMING, 10_000), sim3.report().summary())
            assertNotEquals(port1, events.ports[2])
            assertTrue(lines.any { "no se pudo reutilizar el puerto $port1" in it }, lines.filter { "QD/Link" in it }.toString())
        }
        assertEquals(events.ports[2], link.stableMirrorPort)
    }

    @Test
    fun withoutStablePortEachAttemptGetsANewPort() {
        val p = Ports()
        val events = Events()
        val link = link(p, events, PhoneLinkConfig(recovery = RecoveryConfig.OFF))
        repeat(2) {
            val s = sim(simConfig(p))
            assertTrue(s.awaitState(CarSimState.STREAMING, 10_000))
            s.close()
            assertTrue(waitUntil(5_000) { events.ended.size == it + 1 && link.state == LinkState.SEARCHING })
        }
        assertNotEquals(events.ports[0], events.ports[1])
        assertEquals(0, link.stableMirrorPort)
    }

    @Test
    fun reclaimAcceptsTheCarOnTheOldPortWithoutAnyBroadcast() {
        val p = Ports()
        val events = Events()
        val link = link(p, events, PhoneLinkConfig(recovery = RecoveryConfig(unsolicitedAcks = false, reclaimWindowMs = 20_000)))
        val port = firstSessionThenRadioDrop(p, link, events)
        assertTrue(waitUntil(3_000) { link.state == LinkState.RECOVERING })
        assertEquals(listOf(port to CloseReason.Kind.WATCHDOG), events.reclaims.map { it.first to it.second.kind })
        assertTrue(link.isRecovering)
        assertFalse(link.isConnecting, "una re-acogida sin anuncio no es un intento en marcha")
        Thread.sleep(500) // ni broadcast ni ACK: el coche vuelve solo, directo al puerto de antes
        val sim2 = sim(simConfig(p).copy(directMirrorPort = port, maxBroadcasts = 0))
        assertTrue(sim2.awaitState(CarSimState.STREAMING, 10_000), sim2.report().summary())
        assertEquals(0, sim2.report().broadcastsSent)
        assertTrue(waitUntil(3_000) { events.back.size == 1 })
        assertEquals(CarReturn.OLD_PORT, events.back[0])
        assertEquals(LinkState.CONNECTED, link.state)
        assertEquals(2, link.attemptCount)
        assertEquals(
            listOf(LinkState.SEARCHING, LinkState.CONNECTING, LinkState.CONNECTED, LinkState.RECOVERING, LinkState.CONNECTED),
            events.states.toList(),
        )
        assertTrue(lines.any { it.startsWith("QD/Link: vuelta del coche tras ") && "por puerto anterior" in it }, lines.filter { "vuelta" in it }.toString())
    }

    @Test
    fun unsolicitedAcksFollowTheirCadenceWithTheOldPortAndCloseCancelsEverything() {
        val p = Ports()
        val events = Events()
        val policy = AckPolicy(retries = Int.MAX_VALUE, firstRetryDelayMs = 100, retryIntervalMs = 100, slowAfterMs = 600, slowIntervalMs = 300)
        val link = link(p, events, PhoneLinkConfig(recovery = RecoveryConfig(unsolicitedAckPolicy = policy)))
        val sim1 = sim(simConfig(p))
        assertTrue(sim1.awaitState(CarSimState.STREAMING, 10_000))
        val port = events.ports.single()
        // El «coche» escucha en su puerto UDP (el simulador ya lo cerró tras su ACK).
        val car = DatagramSocket(null as InetSocketAddress?).apply {
            reuseAddress = true
            bind(InetSocketAddress(loopback, p.ack))
        }.also { closeables += it }
        sim1.goSilent()
        sim1.pauseReading(0)
        val at = ArrayList<Long>()
        val deadline = System.nanoTime() + 6_000_000_000L
        while (System.nanoTime() < deadline) {
            val pk = receive(car, 3_000) ?: break
            assertEquals(port, mirrorPortOf(pk))
            at += System.nanoTime()
            if (at.isNotEmpty() && (at.last() - at.first()) / 1_000_000 >= 1_600) break
        }
        assertEquals(CloseReason.Kind.WATCHDOG, events.ended.single().kind)
        val t = at.map { (it - at[0]) / 1_000_000 }
        val gaps = t.zipWithNext { a, b -> b - a }
        val fast = gaps.filterIndexed { i, _ -> t[i + 1] <= 550 }
        val slow = gaps.filterIndexed { i, _ -> t[i] >= 650 }
        assertTrue(fast.size >= 3 && fast.all { it in 40..250 }, "rápidos: $t")
        assertTrue(slow.size >= 2 && slow.all { it in 200..500 }, "lentos: $t")
        assertTrue(waitUntil(1_000) { events.unsolicited.size >= at.size })
        assertTrue(events.unsolicited.all { it == port })
        assertEquals(LinkState.RECOVERING, link.state)

        // Desconectar: ni un ACK más, el puerto deja de escuchar y no queda ningún hilo.
        link.close()
        while (receive(car, 150) != null) Unit
        assertNull(receive(car, 800), "ACK no pedido después de cerrar")
        assertFailsWith<IOException> { Socket(loopback, port).close() }
        sim1.close()
        assertTrue(link.awaitTermination(5_000))
        assertTrue(waitUntil(5_000) { liveThreads("qd-link", "qd-discovery").isEmpty() }, liveThreads("qd-").toString())
        assertEquals(LinkState.STOPPED, link.state)
    }

    @Test
    fun aCarThatOnlyReactsToAnUnsolicitedAckComesBack() {
        val p = Ports()
        val events = Events()
        val policy = AckPolicy(retries = Int.MAX_VALUE, firstRetryDelayMs = 300, retryIntervalMs = 300)
        val link = link(p, events, PhoneLinkConfig(recovery = RecoveryConfig(unsolicitedAckPolicy = policy)))
        val port = firstSessionThenRadioDrop(p, link, events)
        // Variante C: el coche no se anuncia; solo escucha en su puerto UDP y conecta con el ACK que le llegue.
        val sim2 = sim(simConfig(p).copy(maxBroadcasts = 0))
        assertTrue(sim2.awaitState(CarSimState.STREAMING, 10_000), sim2.report().summary())
        assertEquals(0, sim2.report().broadcastsSent)
        assertEquals("127.0.0.1:$port", sim2.report().connectedTo)
        assertTrue(waitUntil(3_000) { events.back.size == 1 })
        assertEquals(CarReturn.UNSOLICITED_ACK, events.back[0])
        assertEquals(2, link.attemptCount)
    }

    @Test
    fun aSingleBroadcastDuringTheReclaimIsAnsweredAtOnceWithTheSamePortAndNoNewServer() {
        val p = Ports()
        val events = Events()
        val link = link(p, events, PhoneLinkConfig(recovery = RecoveryConfig(unsolicitedAcks = false)))
        val port = firstSessionThenRadioDrop(p, link, events)
        assertTrue(waitUntil(3_000) { link.state == LinkState.RECOVERING })
        val car = DatagramSocket(null as InetSocketAddress?).apply {
            reuseAddress = true
            bind(InetSocketAddress(loopback, p.ack))
        }.also { closeables += it }
        val broadcast = UdpCodec.buildConnectBroadcast("SIM-RECOVERY", "C10-SIM")
        car.send(DatagramPacket(broadcast, broadcast.size, InetSocketAddress(loopback, p.discovery)))
        assertEquals(port, mirrorPortOf(assertNotNull(receive(car, 1_000))))
        assertEquals(2, link.attemptCount) // el normal y la re-acogida: ninguno nuevo
        assertEquals(LinkState.RECOVERING, link.state)
        assertTrue(link.isConnecting, "con el coche anunciado, la re-acogida cuenta como intento en marcha")
        assertTrue(lines.any { "re-acogida: 127.0.0.1 se anuncia; ACK en el acto con el puerto $port" in it })
        // Y, hasta que conecte, los reenvíos de un intento normal (cada 2 s por defecto).
        assertEquals(port, mirrorPortOf(assertNotNull(receive(car, 3_000))))
        // El coche vuelve a ese puerto: «por anuncio».
        val sim2 = sim(simConfig(p).copy(directMirrorPort = port, maxBroadcasts = 0))
        assertTrue(sim2.awaitState(CarSimState.STREAMING, 10_000), sim2.report().summary())
        assertTrue(waitUntil(3_000) { events.back.size == 1 })
        assertEquals(CarReturn.BROADCAST, events.back[0])
        while (receive(car, 100) != null) Unit
        assertNull(receive(car, 2_500), "reenvío del ACK con la sesión ya en marcha")
    }

    @Test
    fun aSingleBroadcastWithTheFirstAckLostReconnectsThanksToTheResends() {
        val p = Ports()
        val events = Events()
        // Sin ACK no pedidos: el segundo ACK es el reenvío que arranca el propio anuncio (aquí cada 400 ms).
        val resend = AckPolicy(retries = 20, firstRetryDelayMs = 400, retryIntervalMs = 400)
        val link = link(p, events, PhoneLinkConfig(discovery = DiscoveryConfig(ackPolicy = resend), recovery = RecoveryConfig(unsolicitedAcks = false)))
        firstSessionThenRadioDrop(p, link, events)
        assertTrue(waitUntil(3_000) { link.state == LinkState.RECOVERING })
        // Variante B: el coche se anuncia una vez y pierde el ACK de ese anuncio; conecta con el siguiente.
        val sim2 = sim(simConfig(p).copy(maxBroadcasts = 1, ignoreAcks = 1))
        assertTrue(sim2.awaitState(CarSimState.STREAMING, 10_000), sim2.report().summary())
        assertEquals(1, sim2.report().broadcastsSent)
        assertTrue(waitUntil(3_000) { events.back.size == 1 })
        assertEquals(CarReturn.BROADCAST, events.back[0])
    }

    @Test
    fun theDiscoverySocketIsReopenedAfterSilenceAndStillWorksAndTheWaitIsDiagnosed() {
        val p = Ports()
        val events = Events()
        val pinged = CopyOnWriteArrayList<InetAddress>()
        val link = link(
            p,
            events,
            PhoneLinkConfig(
                recovery = RecoveryConfig(
                    unsolicitedAcks = false,
                    tickMs = 50,
                    diagIntervalMs = 300,
                    udpRefreshSilenceMs = 400,
                    udpRefreshMinIntervalMs = 400,
                    carReachable = { ip -> pinged += ip; true },
                ),
            ),
        )
        firstSessionThenRadioDrop(p, link, events)
        Thread.sleep(1_500)
        val reopens = link.discovery.reopenCount
        assertTrue(reopens in 2..4, "reaperturas: $reopens")
        assertEquals(reopens, events.reopened.size)
        assertTrue(lines.any { it.startsWith("QD/Discovery: descubrimiento: reabro el socket UDP (") && "s sin anuncios)" in it }, lines.filter { "Discovery" in it }.toString())
        assertTrue(lines.any { it.startsWith("QD/Link: esperando al coche tras WATCHDOG (") && "coche en la zona Wi-Fi: sí (ping)" in it }, lines.filter { "esperando" in it }.toString())
        assertTrue(pinged.isNotEmpty() && pinged.all { it == loopback })
        // El socket reabierto recibe: el coche vuelve anunciándose.
        val sim2 = sim(simConfig(p))
        assertTrue(sim2.awaitState(CarSimState.STREAMING, 10_000), sim2.report().summary())
        assertTrue(waitUntil(3_000) { events.back.size == 1 })
        assertEquals(CarReturn.BROADCAST, events.back[0])
        // Con el coche de vuelta, la vigilancia para.
        val after = link.discovery.reopenCount
        Thread.sleep(800)
        assertEquals(after, link.discovery.reopenCount)
    }

    @Test
    fun aNormalOrLocalEndDoesNotReclaimAndReclaimOffGoesBackToSearching() {
        val p = Ports()
        val events = Events()
        val link = link(p, events, PhoneLinkConfig(recovery = RecoveryConfig(reclaim = false)))
        firstSessionThenRadioDrop(p, link, events)
        assertTrue(waitUntil(3_000) { link.state == LinkState.SEARCHING })
        Thread.sleep(300)
        assertTrue(events.reclaims.isEmpty())
        assertEquals(LinkState.SEARCHING, link.state)

        val p2 = Ports()
        val events2 = Events()
        val link2 = link(p2, events2)
        val sim = sim(simConfig(p2))
        assertTrue(sim.awaitState(CarSimState.STREAMING, 10_000))
        assertTrue(waitUntil(3_000) { events2.started.size == 1 })
        link2.closeSession("aplicar ajustes")
        assertTrue(waitUntil(3_000) { events2.ended.size == 1 && link2.state == LinkState.SEARCHING })
        Thread.sleep(300)
        assertTrue(events2.reclaims.isEmpty())
    }

    @Test
    fun theCarComingBackFromAnotherIpOpensANewAttempt() {
        // 127.0.0.2 también es loopback (Windows y Linux): el «mismo coche» (mismo UUID) desde otra IP.
        val other = InetAddress.getByName("127.0.0.2")
        val probe = try {
            DatagramSocket(0, other)
        } catch (_: IOException) {
            return // sin 127.0.0.2 en este sistema
        }
        probe.close()
        val p = Ports()
        val events = Events()
        val link = link(p, events, PhoneLinkConfig(recovery = RecoveryConfig(unsolicitedAcks = false)))
        firstSessionThenRadioDrop(p, link, events)
        assertTrue(waitUntil(3_000) { link.state == LinkState.RECOVERING })
        val car = DatagramSocket(InetSocketAddress(other, p.ack)).also { closeables += it }
        val broadcast = UdpCodec.buildConnectBroadcast("SIM-RECOVERY", "C10-SIM")
        car.send(DatagramPacket(broadcast, broadcast.size, InetSocketAddress(loopback, p.discovery)))
        assertNotNull(receive(car, 2_000))
        assertTrue(waitUntil(3_000) { link.state == LinkState.CONNECTING })
        assertEquals(3, link.attemptCount)
        assertTrue(lines.any { "se anuncia desde otra IP (127.0.0.1 → 127.0.0.2)" in it }, lines.filter { "QD/Link" in it }.toString())
    }
}
