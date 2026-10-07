package dev.qdauto.core.sim

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Radio floja simulada en el lado del coche (`qdsim --rx-kbps/--rx-stall`): cubo de fichas y parones, con reloj falso. */
class RxThrottleTest {
    private var nowNs = 0L
    private val sleeps = ArrayList<Long>()
    private fun throttle(limit: RxLimit) = RxThrottle(limit, { nowNs }) { ms ->
        sleeps += ms
        nowNs += ms * 1_000_000
    }

    @Test
    fun parsesTheStallSpec() {
        assertEquals(RxStall(20_000, 1_500), RxStall.parse("every=20s,for=1500ms"))
        assertEquals(RxStall(15_000, 1_200), RxStall.parse("every=15s, for=1.2s"))
        assertEquals(RxStall(15_000, 1_200), RxStall.parse("for=1200,every=15000"))
        assertFailsWith<IllegalArgumentException> { RxStall.parse("every=1s,for=2s") }
        assertFailsWith<IllegalArgumentException> { RxStall.parse("every=20s") }
        assertFailsWith<IllegalArgumentException> { RxStall.parse("cada=20s,for=1s") }
        assertEquals("parón de 1500 ms cada 20 s", RxStall(20_000, 1_500).describe())
    }

    @Test
    fun stallWindowsStartAtEveryAndLastFor() {
        val s = RxStall(15_000, 1_200)
        assertEquals(-1, s.stallEndAt(0))
        assertEquals(-1, s.stallEndAt(14_999))
        assertEquals(16_200, s.stallEndAt(15_000))
        assertEquals(16_200, s.stallEndAt(16_199))
        assertEquals(-1, s.stallEndAt(16_200))
        assertEquals(31_200, s.stallEndAt(30_500))
    }

    @Test
    fun theTokenBucketHoldsTheAverageRate() {
        // 1500 kbit/s = 187 500 B/s: 10 s de lectura a tope no pasan de ~1,9 MB (+ el cubo inicial).
        val t = throttle(RxLimit(kbps = 1_500))
        var read = 0L
        while (nowNs < 10_000_000_000L) {
            val n = t.beforeRead(65_536)
            assertTrue(n in 1..65_536)
            t.afterRead(n)
            read += n
        }
        val expected = 187_500L * 10
        assertTrue(read in expected - 20_000..expected + 20_000, "leídos $read, esperados ~$expected")
        val st = t.stats()
        assertEquals(read, st.bytes)
        assertTrue(st.kbps in 1_450.0..1_560.0, "kbps ${st.kbps}")
        assertTrue(st.throttledMs > 9_000, "al límite ${st.throttledMs} ms")
        assertEquals(0, st.stalls)
    }

    @Test
    fun aStallBlocksReadingUntilItEnds() {
        val t = throttle(RxLimit(stall = RxStall(15_000, 1_200)))
        assertEquals(4096, t.beforeRead(4096)) // sin límite de tasa: lo que se pida
        nowNs = 15_100_000_000L
        assertEquals(4096, t.beforeRead(4096))
        assertEquals(listOf(1_100L), sleeps)
        assertEquals(16_200_000_000L, nowNs)
        nowNs = 30_000_000_000L
        t.beforeRead(1)
        val st = t.stats()
        assertEquals(2, st.stalls)
        assertEquals(2_300, st.stalledMs)
        assertTrue(st.describe().contains("parones 2 (2.3 s)"), st.describe())
    }

    @Test
    fun theStreamNeverAsksForMoreThanTheBucketAllows() {
        val t = throttle(RxLimit(kbps = 1_000)) // 125 000 B/s, cubo de 8 KiB
        val inner = ByteArrayInputStream(ByteArray(100_000) { it.toByte() })
        val s = ThrottledInputStream(inner, t)
        val buf = ByteArray(65_536)
        val first = s.read(buf, 0, buf.size)
        assertEquals(8 * 1024, first)
        var total = first.toLong()
        while (true) {
            val n = s.read(buf, 0, buf.size)
            if (n < 0) break
            total += n
        }
        assertEquals(100_000, total)
        // 100 000 B a 125 000 B/s, con 8 KiB de entrada: unos 0,73 s de espera.
        assertTrue(nowNs in 650_000_000L..800_000_000L, "tiempo ${nowNs / 1_000_000} ms")
        assertEquals(1 and 0xFF, ThrottledInputStream(ByteArrayInputStream(byteArrayOf(1)), throttle(RxLimit(kbps = 8))).read())
    }

    @Test
    fun describesTheLimit() {
        assertEquals(
            "lectura a 1500 kbit/s · parón de 1200 ms cada 15 s · búfer de recepción 32 KiB",
            RxLimit(1_500, RxStall(15_000, 1_200)).describe(),
        )
        assertTrue(!RxLimit().active)
        assertTrue(RxLimit(stall = RxStall(2, 1)).active)
    }
}
