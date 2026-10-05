package dev.qdauto.core.util

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UtilTest {
    @Test
    fun bigEndianRoundTrips() {
        val b = ByteArray(12)
        BE.putInt(b, 0, 0x01020304)
        BE.putShort(b, 4, 0xFFFE)
        BE.putFloat(b, 6, 960.0f)
        assertContentEquals(byteArrayOf(1, 2, 3, 4, -1, -2, 0x44, 0x70, 0, 0, 0, 0), b)
        assertEquals(0x01020304, BE.getInt(b, 0))
        assertEquals(0xFFFE, BE.getUShort(b, 4))
        assertEquals(-2, BE.getShort(b, 4))
        assertEquals(960.0f, BE.getFloat(b, 6))
        BE.putInt(b, 0, -1)
        assertEquals(-1, BE.getInt(b, 0))
        assertEquals(0xFFFF_FFFFL, BE.getUInt(b, 0))
        assertEquals(255, BE.getUByte(b, 0))
        assertEquals(-1, BE.getByte(b, 0))
    }

    @Test
    fun hexDumpUsesSpecFormat() {
        val heartbeat = "5A5A".toByteArray() + byteArrayOf(0, 0, 0, 0x23, 0, 0, 0, 0, 0, 1, 0, 0) + "{\"CMD\":\"HEARTBEAT\"}".toByteArray()
        val expected = """
            0000: 35 41 35 41 00 00 00 23  00 00 00 00 00 01 00 00  |5A5A...#........|
            0010: 7b 22 43 4d 44 22 3a 22  48 45 41 52 54 42 45 41  |{"CMD":"HEARTBEA|
            0020: 54 22 7d                                          |T"}|
        """.trimIndent()
        assertEquals(expected, Hex.dump(heartbeat))
        // La columna ASCII empieza siempre en la misma posición (56), aunque la línea esté incompleta.
        assertEquals("0000: 35 41" + " ".repeat(45) + "|5A|\n… (33 bytes más, 35 en total)", Hex.dump(heartbeat, maxBytes = 2))
    }

    @Test
    fun hexEncodeDecodeAndPrefix() {
        val bytes = byteArrayOf(0, 0x7F, -1, 0x10)
        assertEquals("00 7f ff 10", Hex.encode(bytes))
        assertContentEquals(bytes, Hex.decode("00 7F\nff:10"))
        assertEquals("00 7f … (+2 B)", Hex.prefix(bytes, maxBytes = 2))
        assertFailsWith<IllegalArgumentException> { Hex.decode("0") }
        assertFailsWith<IllegalArgumentException> { Hex.decode("zz") }
    }

    @Test
    fun rateWindowCountsLastSecond() {
        val r = RateWindow()
        val t0 = 1_000_000_000L
        repeat(30) { r.add(1000, t0 + it * 33_000_000L) }
        val (fps, kbps) = r.rates(t0 + 29 * 33_000_000L)
        assertEquals(30.0, fps)
        assertEquals(240.0, kbps)
        assertEquals(0.0, r.rates(t0 + 5_000_000_000L).first)
    }
}
