package com.andrerinas.openheadunit.utils

import java.util.Locale

/**
 * Recovers an interface's MAC from its IPv6 link-local address.
 *
 * `getHardwareAddress()` has been masked for ordinary apps since Android 6.0, but
 * `getInetAddresses()` never was, and where the kernel built a link-local address by the EUI-64
 * rule that address contains the MAC. It is the one access-point address an unrooted phone can
 * still read, which is what a phone standing in for a head unit needs to describe its own network.
 *
 * Pure: no Android types, so the derivation is tested rather than assumed.
 */
object Eui64BssidPolicy {

    private val MASKED = intArrayOf(0x02, 0, 0, 0, 0, 0)

    /** One interface, and every IPv6 link-local address on it as raw 16-byte material. */
    data class Candidate(val name: String, val linkLocalIpv6: List<ByteArray>)

    /** Which interface answered, and with what. */
    data class Match(val iface: String, val mac: String)

    /**
     * The MAC encoded in [address], or null where the address was not built by the EUI-64 rule.
     *
     * Bytes 11 and 12 must carry the `ff:fe` marker, so an RFC 7217 stable-privacy address yields
     * nothing rather than a fabricated MAC. Bit 1 of byte 8 is the flipped U/L bit, which the xor
     * undoes. Only `fe80::/64` is read, and a multicast, zero or masked result is no address.
     */
    fun fromLinkLocal(address: ByteArray?): String? {
        if (address == null || address.size != 16) return null
        val bytes = IntArray(16) { address[it].toInt() and 0xFF }
        if (bytes[0] != 0xFE || bytes[1] != 0x80 || (2..7).any { bytes[it] != 0 }) return null
        if (bytes[11] != 0xFF || bytes[12] != 0xFE) return null
        val octets = intArrayOf(bytes[8] xor 0x02, bytes[9], bytes[10], bytes[13], bytes[14], bytes[15])
        if (octets[0] and 0x01 != 0) return null
        if (octets.all { it == 0 } || octets.contentEquals(MASKED)) return null
        return octets.joinToString(":") { String.format(Locale.ROOT, "%02X", it) }
    }

    /**
     * The one MAC [addresses] encode. Every address is tried, because a kernel may carry a
     * stable-privacy address beside the EUI-64 one; two different MACs on one interface say
     * nothing about which is the network's, so that answers null.
     */
    fun fromLinkLocals(addresses: List<ByteArray>): String? =
        addresses.mapNotNull { fromLinkLocal(it) }.distinct().singleOrNull()

    /** Why [fromLinkLocals] found nothing on [name], for the log a reporter sends. */
    fun describe(name: String, linkLocal: List<ByteArray>): String = when {
        linkLocal.isEmpty() -> "$name has no IPv6 link-local address"
        linkLocal.mapNotNull { fromLinkLocal(it) }.distinct().size > 1 ->
            "$name has conflicting MAC-derived IPv6 link-local addresses"
        else -> "$name has only opaque IPv6 link-local identifiers (no EUI-64 ff:fe), " +
            "so its MAC cannot be derived"
    }

    /**
     * Whether [name] is an access point or P2P interface.
     *
     * Tighter than the other name filters in the BSSID chain, which accept any "wlan": `wlan0` is
     * the station interface, so its address describes the network this device has joined rather
     * than the one it is offering, and a MAC-shaped wrong answer would outrank every later rung.
     */
    fun looksLikeApOrP2p(name: String?): Boolean {
        val lower = name?.lowercase() ?: return false
        return lower.contains("p2p") || lower.startsWith("ap") ||
            lower.startsWith("swlan") || lower == "wlan1"
    }

    /**
     * [preferred] first, then any candidate whose name passes [nameFilter].
     *
     * The filter is the caller's because the routes differ: a WiFi Direct group's address can only
     * come off a P2P interface, while the hotspot route legitimately reads `ap0` and `swlan0`.
     */
    fun choose(
        candidates: List<Candidate>,
        preferred: String?,
        nameFilter: (String?) -> Boolean = ::looksLikeApOrP2p,
    ): Match? {
        if (!preferred.isNullOrEmpty()) {
            val named = candidates.firstOrNull { it.name == preferred }
            val mac = named?.let { fromLinkLocals(it.linkLocalIpv6) }
            if (named != null && mac != null) return Match(named.name, mac)
        }
        for (candidate in candidates) {
            if (!nameFilter(candidate.name)) continue
            val mac = fromLinkLocals(candidate.linkLocalIpv6) ?: continue
            return Match(candidate.name, mac)
        }
        return null
    }
}
