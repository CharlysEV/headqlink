package com.headqlink.link

import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.SoftApState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/** Clasificación de interfaces, subredes y estado de la zona Wi-Fi (qdauto §5.4, §5.5). */
class NetIfacesTest {
    private fun ip(s: String): InetAddress = InetAddress.getByName(s)
    private fun iface(name: String, addr: String, prefix: Int) = NetIfaces.Iface(name, ip(addr), prefix, NetIfaces.kindOf(name))

    @Test
    fun kindsByName() {
        val k = NetIfaces::kindOf
        assertEquals(NetIfaces.Kind.HOTSPOT, k("swlan0"))
        assertEquals(NetIfaces.Kind.HOTSPOT, k("ap0"))
        assertEquals(NetIfaces.Kind.HOTSPOT, k("softap0"))
        assertEquals(NetIfaces.Kind.HOTSPOT_OR_STA, k("wlan1"))
        assertEquals(NetIfaces.Kind.STATION, k("wlan0"))
        assertEquals(NetIfaces.Kind.P2P, k("p2p-wlan0-0"))
        assertEquals(NetIfaces.Kind.MOBILE, k("rmnet_data0"))
        assertEquals(NetIfaces.Kind.MOBILE, k("ccmni1"))
        assertEquals(NetIfaces.Kind.VPN, k("tun0"))
        assertEquals(NetIfaces.Kind.USB, k("rndis0"))
        assertEquals(NetIfaces.Kind.BT, k("bt-pan"))
        assertEquals(NetIfaces.Kind.LOOPBACK, k("lo"))
        assertEquals(NetIfaces.Kind.OTHER, k("dummy0"))
    }

    @Test
    fun subnetsAndLookup() {
        val hs = iface("swlan0", "10.212.226.210", 24)
        val sta = iface("wlan0", "192.168.1.20", 24)
        val mob = iface("rmnet_data0", "100.64.3.9", 30)
        val list = listOf(hs, sta, mob)
        assertTrue(hs.contains(ip("10.212.226.80")))
        assertFalse(hs.contains(ip("10.212.227.80")))
        assertEquals(hs, NetIfaces.find(list, ip("10.212.226.80")))
        assertEquals(sta, NetIfaces.find(list, ip("192.168.1.1")))
        assertNull(NetIfaces.find(list, ip("8.8.8.8")))
        assertEquals(NetIfaces.Kind.LOOPBACK, NetIfaces.find(list, ip("127.0.0.1"))!!.kind)
        // La más específica gana.
        val wide = iface("wlan1", "10.0.0.5", 8)
        assertEquals(hs, NetIfaces.find(listOf(wide, hs), ip("10.212.226.80")))
        assertEquals(hs, NetIfaces.hotspot(list))
        assertNull(NetIfaces.hotspot(listOf(sta, mob)))
    }

    @Test
    fun hotspotStateCombinesTheThreeSources() {
        val hs = listOf(iface("swlan0", "10.212.226.210", 24))
        val none = listOf(iface("wlan0", "192.168.1.20", 24))
        assertEquals(HotspotWatcher.State.ON, HotspotWatcher.combine(13, SoftApState.UNKNOWN, none).state)
        assertEquals(HotspotWatcher.State.ON, HotspotWatcher.combine(-1, SoftApState.ENABLED, none).state)
        val byIface = HotspotWatcher.combine(-1, SoftApState.UNKNOWN, hs)
        assertEquals(HotspotWatcher.State.ON, byIface.state)
        assertEquals("swlan0", byIface.iface!!.name)
        assertEquals(HotspotWatcher.State.OFF, HotspotWatcher.combine(11, SoftApState.UNKNOWN, none).state)
        assertEquals(HotspotWatcher.State.OFF, HotspotWatcher.combine(14, SoftApState.UNKNOWN, none).state)
        assertEquals(HotspotWatcher.State.OFF, HotspotWatcher.combine(-1, SoftApState.NOT_ENABLED, none).state)
        assertEquals(HotspotWatcher.State.UNKNOWN, HotspotWatcher.combine(-1, SoftApState.UNKNOWN, none).state)
        // La interfaz manda sobre una respuesta "no" de la reflexión (que puede ir por detrás).
        assertEquals(HotspotWatcher.State.ON, HotspotWatcher.combine(-1, SoftApState.NOT_ENABLED, hs).state)
    }
}
