package com.headqlink.link

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Interfaces de red del móvil (qdauto §5.4): tipo probable por el nombre (no hay API pública para la zona Wi-Fi),
 * IPv4 con su prefijo, y en cuál cae una IP. Sin Android: se prueba en la JVM ([PeerPolicy] recibe la lista).
 */
internal object NetIfaces {
    enum class Kind(val label: String) {
        HOTSPOT("zona Wi-Fi"),
        HOTSPOT_OR_STA("Wi-Fi / zona Wi-Fi"),
        STATION("Wi-Fi"),
        P2P("Wi-Fi Direct"),
        MOBILE("datos móviles"),
        VPN("VPN"),
        USB("USB"),
        BT("Bluetooth"),
        LOOPBACK("loopback"),
        OTHER("otra"),
    }

    /** Una dirección IPv4 de una interfaz. */
    class Iface(val name: String, val address: InetAddress, val prefix: Int, val kind: Kind) {
        /** [ip] (IPv4) está en la subred de esta interfaz. */
        fun contains(ip: InetAddress): Boolean {
            val a = address.address
            val b = ip.address
            if (a.size != 4 || b.size != 4) return false
            val p = prefix.coerceIn(0, 32)
            val mask = if (p == 0) 0 else -1 shl (32 - p)
            return (toInt(a) and mask) == (toInt(b) and mask)
        }

        val host: String get() = address.hostAddress ?: address.toString()

        override fun toString(): String = "$name $host/$prefix (${kind.label})"
        override fun equals(other: Any?): Boolean =
            other is Iface && other.name == name && other.address == address && other.prefix == prefix
        override fun hashCode(): Int = name.hashCode() * 31 + address.hashCode() + prefix
    }

    /** Tipo probable por el nombre de la interfaz. */
    fun kindOf(name: String): Kind = when {
        name == "lo" -> Kind.LOOPBACK
        name.startsWith("swlan") || name.startsWith("softap") || name.startsWith("ap") -> Kind.HOTSPOT
        name == "wlan0" -> Kind.STATION
        name.startsWith("wlan") -> Kind.HOTSPOT_OR_STA
        name.startsWith("p2p") -> Kind.P2P
        name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("seth") -> Kind.MOBILE
        name.startsWith("tun") || name.startsWith("ppp") || name.startsWith("ipsec") -> Kind.VPN
        name.startsWith("rndis") || name.startsWith("usb") || name.startsWith("ncm") -> Kind.USB
        name.startsWith("bt-pan") -> Kind.BT
        else -> Kind.OTHER
    }

    /** IPv4 de todas las interfaces levantadas (loopback incluida). Nunca lanza: si no se pueden leer, lista vacía. */
    fun scan(): List<Iface> {
        val out = ArrayList<Iface>()
        try {
            val all = NetworkInterface.getNetworkInterfaces() ?: return out
            for (ni in all) {
                val up = try {
                    ni.isUp
                } catch (_: Exception) {
                    true
                }
                if (!up) continue
                for (ia in ni.interfaceAddresses) {
                    val a = ia.address
                    if (a is Inet4Address) out += Iface(ni.name, a, ia.networkPrefixLength.toInt(), kindOf(ni.name))
                }
            }
        } catch (_: Exception) {
        }
        out.sortWith(compareBy({ it.name }, { it.host }))
        return out
    }

    /** Interfaz cuya subred contiene [ip] (la más específica), o `null`. Loopback se reconoce aunque no esté en la lista. */
    fun find(ifaces: List<Iface>, ip: InetAddress): Iface? {
        if (ip.isLoopbackAddress) return ifaces.firstOrNull { it.kind == Kind.LOOPBACK } ?: Iface("lo", ip, 8, Kind.LOOPBACK)
        return ifaces.filter { it.contains(ip) }.maxByOrNull { it.prefix }
    }

    /** Primera interfaz de zona Wi-Fi levantada con IPv4 privada (o `null`). */
    fun hotspot(ifaces: List<Iface>): Iface? =
        ifaces.firstOrNull { (it.kind == Kind.HOTSPOT || it.kind == Kind.HOTSPOT_OR_STA) && it.address.isSiteLocalAddress }

    fun describe(ifaces: List<Iface>): String = if (ifaces.isEmpty()) "ninguna" else ifaces.joinToString(" · ")

    private fun toInt(b: ByteArray): Int =
        ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
}
