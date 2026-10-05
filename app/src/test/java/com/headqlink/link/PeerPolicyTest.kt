package com.headqlink.link

import dev.qdauto.core.discovery.CarAnnouncement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress

/** Tabla de §5.4 completa con interfaces inyectadas: broadcasts, TCP y *bind* (qdauto §9.1). */
class PeerPolicyTest {
    private fun ip(s: String): InetAddress = InetAddress.getByName(s)
    private fun iface(name: String, addr: String, prefix: Int) = NetIfaces.Iface(name, ip(addr), prefix, NetIfaces.kindOf(name))

    private val ifaces = listOf(
        iface("swlan0", "10.212.226.210", 24),
        iface("p2p-wlan0-0", "192.168.49.10", 24),
        iface("wlan0", "192.168.1.20", 24),
        iface("rmnet_data0", "100.64.3.9", 29),
        iface("tun0", "10.8.0.2", 24),
        iface("dummy0", "172.16.5.1", 24),
        iface("lo", "127.0.0.1", 8),
    )

    private fun car(addr: String) = CarAnnouncement(
        ip(addr), 18464, "LeapMotor-A750", "LeapMotor-A750", "{}", ByteArray(0), null, true, emptyList(), 0, 0, 1,
    )

    private fun policy(hotspot: Boolean, strict: Boolean = false, log: MutableList<String> = ArrayList()) =
        PeerPolicy(hotspot, strict, { ifaces }, { log += it })

    private fun verdict(p: PeerPolicy, addr: String) = p.classify(ip(addr)).verdict

    @Test
    fun wifiDirectColumn() {
        val p = policy(hotspot = false)
        assertEquals(PeerPolicy.Verdict.REJECT, verdict(p, "127.0.0.1"))
        assertEquals(PeerPolicy.Verdict.REJECT, verdict(p, "::1"))
        assertEquals(PeerPolicy.Verdict.REJECT, verdict(p, "224.0.0.1"))
        assertEquals(PeerPolicy.Verdict.REJECT, verdict(p, "100.64.3.10")) // datos móviles
        assertEquals(PeerPolicy.Verdict.REJECT, verdict(p, "10.8.0.1")) // VPN
        assertEquals(PeerPolicy.Verdict.ACCEPT_WARN, verdict(p, "8.8.8.8")) // fuera de toda subred: lo que hace el fork
        assertEquals(PeerPolicy.Verdict.ACCEPT, verdict(p, "192.168.49.1")) // grupo del coche
        assertEquals(PeerPolicy.Verdict.ACCEPT_WARN, verdict(p, "10.212.226.80")) // zona Wi-Fi
        assertEquals(PeerPolicy.Verdict.ACCEPT_WARN, verdict(p, "192.168.1.30")) // wlan0 (pruebas en LAN)
        assertEquals(PeerPolicy.Verdict.ACCEPT_WARN, verdict(p, "172.16.5.9")) // interfaz desconocida
    }

    @Test
    fun hotspotColumn() {
        val p = policy(hotspot = true)
        assertEquals(PeerPolicy.Verdict.REJECT, verdict(p, "127.0.0.1"))
        assertEquals(PeerPolicy.Verdict.REJECT, verdict(p, "100.64.3.10"))
        assertEquals(PeerPolicy.Verdict.REJECT, verdict(p, "10.8.0.1"))
        assertEquals(PeerPolicy.Verdict.REJECT, verdict(p, "8.8.8.8"))
        assertEquals(PeerPolicy.Verdict.REJECT, verdict(p, "192.168.49.1"))
        assertEquals(PeerPolicy.Verdict.ACCEPT, verdict(p, "10.212.226.80"))
        assertEquals(PeerPolicy.Verdict.ACCEPT_WARN, verdict(p, "192.168.1.30")) // zona Wi-Fi en wlan0
    }

    @Test
    fun strictTurnsWarningsIntoRejections() {
        val p = policy(hotspot = false, strict = true)
        assertEquals(PeerPolicy.Verdict.REJECT, verdict(p, "8.8.8.8"))
        assertEquals(PeerPolicy.Verdict.REJECT, verdict(p, "10.212.226.80"))
        assertEquals(PeerPolicy.Verdict.ACCEPT, verdict(p, "192.168.49.1"))
        val h = policy(hotspot = true, strict = true)
        assertEquals(PeerPolicy.Verdict.ACCEPT, verdict(h, "10.212.226.80"))
        assertEquals(PeerPolicy.Verdict.REJECT, verdict(h, "192.168.1.30"))
    }

    @Test
    fun broadcastDecisionsAreLoggedOncePerSource() {
        val log = ArrayList<String>()
        val p = policy(hotspot = true, log = log)
        repeat(5) { assertTrue(p.acceptBroadcast(car("10.212.226.80"))) }
        repeat(3) { assertFalse(p.acceptBroadcast(car("192.168.49.1"))) }
        assertEquals(2, log.size)
        assertTrue(log[0].contains("aceptado"))
        assertTrue(log[1].contains("rechazado"))
    }

    @Test
    fun tcpOnlyFromTheAckedAddress() {
        val p = policy(hotspot = true)
        val c = car("10.212.226.80")
        assertTrue(p.acceptTcp(InetSocketAddress(ip("10.212.226.80"), 40736), c))
        assertFalse(p.acceptTcp(InetSocketAddress(ip("10.212.226.81"), 40736), c))
        assertFalse(p.acceptTcp(InetSocketAddress(ip("127.0.0.1"), 40736), car("127.0.0.1")))
        assertFalse(p.acceptTcp(null, c))
    }

    @Test
    fun bindToTheCarInterfaceInHotspotModeWithWildcardAfterATimeout() {
        val h = policy(hotspot = true)
        val c = car("10.212.226.80")
        assertEquals(ip("10.212.226.210"), h.bindAddressFor(c))
        h.noteAcceptTimeout()
        assertNull(h.bindAddressFor(c)) // el intento siguiente, en todas las interfaces
        assertEquals(ip("10.212.226.210"), h.bindAddressFor(c)) // y luego otra vez en la de la zona Wi-Fi
        assertNull(h.bindAddressFor(car("8.8.8.8"))) // sin interfaz: todas
        val p2p = policy(hotspot = false)
        assertNull(p2p.bindAddressFor(car("192.168.49.1"))) // Wi-Fi Direct: comodín, como hoy
        p2p.noteAcceptTimeout()
        assertNull(p2p.bindAddressFor(car("192.168.49.1")))
    }
}
