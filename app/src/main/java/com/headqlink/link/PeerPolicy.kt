package com.headqlink.link

import dev.qdauto.core.discovery.CarAnnouncement
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Qué broadcasts y qué conexiones TCP se aceptan, y en qué IP local se escucha (qdauto §5.4). Por cada broadcast se
 * busca la interfaz IPv4 del móvil cuya subred contiene la IP del coche:
 *
 * | Origen                         | Wi-Fi Direct        | Zona Wi-Fi          |
 * |--------------------------------|---------------------|---------------------|
 * | loopback, IPv6, multicast      | rechazar            | rechazar            |
 * | datos móviles, VPN             | rechazar            | rechazar            |
 * | fuera de toda subred local     | aceptar con aviso   | rechazar            |
 * | Wi-Fi Direct (p2p*)            | aceptar             | rechazar con aviso  |
 * | zona Wi-Fi (swlan/ap/wlan1+)   | aceptar con aviso   | aceptar             |
 * | Wi-Fi (wlan0), USB, otras      | aceptar con aviso   | aceptar con aviso   |
 *
 * Con [strict] los «aceptar con aviso» se rechazan. El TCP solo se acepta de la IP a la que se mandó el ACK (y nunca
 * de loopback). En la zona Wi-Fi el `ServerSocket` escucha en la IP local de la interfaz del coche (si un intento así
 * acaba sin conexión, el siguiente escucha en todas). Cada decisión se registra una vez por (IP, interfaz, veredicto).
 * Sin Android: las interfaces se inyectan ([interfaces]) y la prueban los tests.
 */
internal class PeerPolicy(
    private val hotspotMode: Boolean,
    private val strict: Boolean,
    private val interfaces: () -> List<NetIfaces.Iface> = { NetIfaces.scan() },
    private val log: (String) -> Unit = {},
) {
    enum class Verdict { ACCEPT, ACCEPT_WARN, REJECT }

    class Decision(val verdict: Verdict, val iface: NetIfaces.Iface?, val why: String) {
        val accepted: Boolean get() = verdict != Verdict.REJECT
        override fun toString(): String = "$verdict ($why)"
    }

    private val logged = HashSet<String>()

    @Volatile
    private var wildcardNext = false

    /** Clasifica la IP de origen de un broadcast según la tabla. */
    fun classify(ip: InetAddress): Decision {
        if (ip.isLoopbackAddress || ip !is Inet4Address || ip.isMulticastAddress || ip.isAnyLocalAddress) {
            return Decision(Verdict.REJECT, null, "loopback, IPv6 o multicast")
        }
        val iface = NetIfaces.find(interfaces(), ip)
            ?: return if (hotspotMode) {
                Decision(Verdict.REJECT, null, "fuera de las subredes del móvil")
            } else {
                warn(null, "fuera de las subredes del móvil")
            }
        return when (iface.kind) {
            NetIfaces.Kind.MOBILE, NetIfaces.Kind.VPN, NetIfaces.Kind.LOOPBACK -> Decision(Verdict.REJECT, iface, "por ${iface.kind.label}")
            NetIfaces.Kind.P2P -> if (hotspotMode) Decision(Verdict.REJECT, iface, "Wi-Fi Direct en el modo zona Wi-Fi") else Decision(Verdict.ACCEPT, iface, "Wi-Fi Direct")
            NetIfaces.Kind.HOTSPOT, NetIfaces.Kind.HOTSPOT_OR_STA ->
                if (hotspotMode) Decision(Verdict.ACCEPT, iface, "zona Wi-Fi") else warn(iface, "zona Wi-Fi en el modo Wi-Fi Direct")
            NetIfaces.Kind.STATION -> warn(iface, "Wi-Fi cliente (pruebas en LAN o zona Wi-Fi en wlan0)")
            NetIfaces.Kind.USB, NetIfaces.Kind.BT, NetIfaces.Kind.OTHER -> warn(iface, "interfaz ${iface.kind.label}")
        }
    }

    private fun warn(iface: NetIfaces.Iface?, why: String): Decision =
        Decision(if (strict) Verdict.REJECT else Verdict.ACCEPT_WARN, iface, why + if (strict) " (modo estricto)" else "")

    /** `carFilter` de [dev.qdauto.core.session.PhoneLink]: ¿se atiende este broadcast? */
    fun acceptBroadcast(car: CarAnnouncement): Boolean {
        val d = classify(car.address)
        note("broadcast de ${car.host} (${car.name})", d)
        return d.accepted
    }

    /** `acceptFilter`: solo la IP a la que se mandó el ACK, y nunca loopback. */
    fun acceptTcp(remote: InetSocketAddress?, car: CarAnnouncement): Boolean {
        val ip = remote?.address
        val ok = ip != null && !ip.isLoopbackAddress && ip == car.address
        if (!ok) log("TCP rechazado: llegó de ${remote?.address?.hostAddress} y el ACK fue a ${car.host}")
        return ok
    }

    /** `mirrorBindAddressFor`: en la zona Wi-Fi, la IP local de la interfaz del coche (o `null` = todas). */
    fun bindAddressFor(car: CarAnnouncement): InetAddress? {
        if (!hotspotMode) return null
        if (wildcardNext) {
            wildcardNext = false
            log("el intento anterior escuchando en una IP concreta no recibió al coche: este escucha en todas")
            return null
        }
        val iface = NetIfaces.find(interfaces(), car.address)
        if (iface == null || iface.kind == NetIfaces.Kind.LOOPBACK) {
            log("sin interfaz local para ${car.host}: se escucha en todas")
            return null
        }
        return iface.address
    }

    /** El coche no conectó tras el ACK: el siguiente intento escucha en todas las interfaces. */
    fun noteAcceptTimeout() {
        if (hotspotMode) wildcardNext = true
    }

    private fun note(what: String, d: Decision) {
        val key = "${d.iface?.name}|${d.verdict}|$what"
        val first = synchronized(logged) { logged.add(key) }
        if (first) {
            val v = when (d.verdict) {
                Verdict.ACCEPT -> "aceptado"
                Verdict.ACCEPT_WARN -> "aceptado con aviso"
                Verdict.REJECT -> "rechazado"
            }
            log("$what: $v — ${d.why}" + (d.iface?.let { " · interfaz $it" } ?: ""))
        }
    }
}
