package com.headqlink.link

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** El log en directo solo atiende a la red privada Tailscale (y al propio móvil). */
class LiveLogTest {
    private fun ip(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test
    fun onlyTailscaleAddressesGetTheLog() {
        assertTrue(LiveLog.allowed(ip(100, 64, 0, 1)))
        assertTrue(LiveLog.allowed(ip(100, 101, 7, 9)))
        assertTrue(LiveLog.allowed(ip(100, 127, 255, 254)))
        assertTrue(LiveLog.allowed(ip(127, 0, 0, 1)))
        assertFalse(LiveLog.allowed(ip(100, 128, 0, 1)))
        assertFalse(LiveLog.allowed(ip(100, 63, 0, 1)))
        assertFalse(LiveLog.allowed(ip(192, 168, 1, 20)))
        assertFalse(LiveLog.allowed(ip(10, 0, 0, 1)))
        val v6 = ByteArray(16)
        intArrayOf(0xfd, 0x7a, 0x11, 0x5c, 0xa1, 0xe0).forEachIndexed { i, b -> v6[i] = b.toByte() }
        assertTrue(LiveLog.allowed(v6))
        v6[0] = 0xfe.toByte()
        assertFalse(LiveLog.allowed(v6))
        assertFalse(LiveLog.allowed(null))
    }
}
