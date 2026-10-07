package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tamaño de los P-frames del encoder propio: en el C10 (2026-10-07, S26) salían P-frames de 250-270 KB a 2,5 Mbit/s
 * (un P-frame medio son ~10 KB a 30 fps) que ocupaban casi un segundo de radio cada uno.
 */
class PFrameSizeControllerTest {
    private val kb = 1024
    private val bps = 2_500_000
    private val fps = 30
    private var now = 0L
    private fun ctl(qpKeys: Boolean = true) = PFrameSizeController(qpKeys) { now }

    @Test
    fun capIsSixAverageFramesWithAFloorAndThreeWhenCongested() {
        assertEquals(62_496, PFrameSizeController.capFor(2_500_000, 30, false)) // ≈ 61 KB
        assertEquals(99_996, PFrameSizeController.capFor(8_000_000, 60, false))
        assertEquals(199_998, PFrameSizeController.capFor(8_000_000, 30, false))
        assertEquals(31_248, PFrameSizeController.capFor(2_500_000, 30, true))
        // Suelo de 24 KB (también congestionado): bitrates bajos o en la emergencia.
        assertEquals(24 * kb, PFrameSizeController.capFor(500_000, 30, false))
        assertEquals(24 * kb, PFrameSizeController.capFor(1_200_000, 20, true))
    }

    @Test
    fun normalPFramesWithTheIntraRefreshStripChangeNothing() {
        val c = ctl()
        repeat(300) {
            now += 33
            assertNull(c.onPFrame(if (it % 30 == 0) 40 * kb else 10 * kb, bps, fps))
        }
        assertEquals(PFrameSizeController.QP_NONE, c.qpMin())
        assertEquals(0, c.overCap())
        assertNull(c.takeWindowLine())
    }

    @Test
    fun anOversizedPFrameRaisesQpPMinFromNoneTo26ThenByTwoAtMostEvery500Ms() {
        val c = ctl()
        now = 1_000
        val first = c.onPFrame(263_150, bps, fps)
        assertNotNull(first)
        assertEquals(PFrameSizeController.QP_NONE, first!!.qpBefore)
        assertEquals(26, first.qpAfter)
        assertEquals(0.0, first.dip, 0.0)
        assertEquals("P-frames: 257 KB > tope 61 KB → QP-P mín 26", first.line())
        // Otro grande antes de 500 ms: cuenta, pero no sube.
        now = 1_400
        assertNull(c.onPFrame(200 * kb, bps, fps))
        assertEquals(26, c.qpMin())
        now = 1_500
        val second = c.onPFrame(150 * kb, bps, fps)
        assertEquals(28, second!!.qpAfter)
        assertEquals("P-frames: 150 KB > tope 61 KB → QP-P mín 28", second.line())
        assertEquals(3, c.overCap())
        assertEquals(263_150, c.maxBytes())
    }

    @Test
    fun theCeilingIs40AndThenOnlyTheBitrateDips() {
        val c = ctl()
        val seen = ArrayList<Int>()
        // El encoder hace caso: cada P-frame grande es más pequeño que el anterior (sin plan B).
        var size = 400 * kb
        while (c.qpMin() < PFrameSizeController.QP_CEIL) {
            now += 500
            val st = c.onPFrame(size, bps, fps)!!
            assertEquals(0.0, st.dip, 0.0)
            seen += st.qpAfter
            size = maxOf(size * 3 / 4, 80 * kb)
        }
        assertEquals(listOf(26, 28, 30, 32, 34, 36, 38, 40), seen)
        assertFalse(c.dipping())
        now += 500
        val st = c.onPFrame(100 * kb, bps, fps)!!
        assertFalse(st.qpChanged())
        assertEquals(PFrameSizeController.DIP, st.dip, 0.0)
        assertEquals("P-frames: 100 KB > tope 61 KB → bitrate al 60 % 1000 ms", st.line())
    }

    @Test
    fun fiveCleanSecondsLowerItByOneDownToNone() {
        val c = ctl()
        now = 0
        c.onPFrame(300 * kb, bps, fps)
        now = 500
        c.onPFrame(200 * kb, bps, fps)
        assertEquals(28, c.qpMin())
        // Un P-frame grande durante la espera de 500 ms también reinicia los 5 s.
        now = 800
        assertNull(c.onPFrame(100 * kb, bps, fps))
        now = 5_799
        assertNull(c.onPFrame(10 * kb, bps, fps))
        now = 5_800
        val down = c.onPFrame(10 * kb, bps, fps)!!
        assertEquals(27, down.qpAfter)
        assertEquals("P-frames: 5 s sin pasar del tope (61 KB) → QP-P mín 27", down.line())
        now = 10_799
        assertNull(c.onPFrame(10 * kb, bps, fps))
        now = 10_800
        assertEquals(26, c.onPFrame(10 * kb, bps, fps)!!.qpAfter)
        now = 15_800
        val none = c.onPFrame(10 * kb, bps, fps)!!
        assertEquals(PFrameSizeController.QP_NONE, none.qpAfter)
        assertEquals("P-frames: 5 s sin pasar del tope (61 KB) → QP-P en el suelo (24)", none.line())
        now = 30_000
        assertNull(c.onPFrame(10 * kb, bps, fps))
        assertTrue(c.summary(), c.summary().contains("QP-P en el suelo (24) (máx. 28, subidas 2, bajadas 3)"))
    }

    @Test
    fun aCongestedLinkOrARadioCutTightensTheCapForTwoSeconds() {
        val c = ctl()
        now = 10_000
        assertNull(c.onPFrame(40 * kb, bps, fps)) // < 61 KB
        c.onCongestion()
        assertTrue(c.congested())
        now = 10_100
        val st = c.onPFrame(40 * kb, bps, fps)!! // > 31 KB
        assertTrue(st.congested)
        assertEquals(31_248, st.cap)
        assertEquals("P-frames: 40 KB > tope 31 KB (enlace congestionado) → QP-P mín 26", st.line())
        now = 11_999
        assertTrue(c.congested())
        now = 12_000
        assertFalse(c.congested())
        assertNull(c.onPFrame(40 * kb, bps, fps))
    }

    @Test
    fun withoutQpKeysEachOversizedPFrameDipsTheBitrate() {
        val c = ctl(qpKeys = false)
        assertTrue(c.dipping())
        assertTrue(c.describe(), c.describe().contains("sin claves de QP"))
        now = 0
        val st = c.onPFrame(263_150, bps, fps)!!
        assertFalse(st.qpChanged())
        assertEquals(PFrameSizeController.DIP, st.dip, 0.0)
        assertEquals("P-frames: 257 KB > tope 61 KB → bitrate al 60 % 1000 ms", st.line())
        now = 499
        assertNull(c.onPFrame(263_150, bps, fps))
        now = 500
        assertNotNull(c.onPFrame(263_150, bps, fps))
        assertTrue(c.summary(), c.summary().contains("sin QP-P · bitrate bajado 2"))
    }

    @Test
    fun anEncoderThatIgnoresTheQpGetsTheBitrateDipAsWell() {
        val c = ctl()
        val steps = (0 until 6).map {
            now = it * 600L
            c.onPFrame(260 * kb, bps, fps)!!
        }
        assertEquals(listOf(26, 28, 30, 32, 34, 36), steps.map { it.qpAfter })
        // +8 sobre el primer mínimo (26 → 34) y los P-frames igual de grandes: plan B desde ahí.
        assertEquals(0.0, steps[3].dip, 0.0)
        assertNull(steps[3].note)
        assertEquals(PFrameSizeController.DIP, steps[4].dip, 0.0)
        assertNotNull(steps[4].note)
        assertTrue(steps[4].line(), steps[4].line().startsWith("P-frames: 260 KB > tope 61 KB → QP-P mín 34 y bitrate al 60 % 1000 ms · "))
        assertEquals(PFrameSizeController.DIP, steps[5].dip, 0.0)
        assertTrue(c.dipping())
    }

    @Test
    fun aRejectedQpSwitchesToTheBitrateDip() {
        val c = ctl()
        now = 0
        assertEquals(26, c.onPFrame(263_150, bps, fps)!!.qpAfter)
        val note = c.onQpRejected()
        assertTrue(note, note.contains("no aceptó el QP-P"))
        assertEquals(PFrameSizeController.QP_NONE, c.qpMin())
        now = 600
        val st = c.onPFrame(263_150, bps, fps)!!
        assertFalse(st.qpChanged())
        assertEquals(PFrameSizeController.DIP, st.dip, 0.0)
    }

    @Test
    fun aNewSessionResetsTheStatsButKeepsTheQp() {
        val c = ctl()
        now = 0
        c.onPFrame(263_150, bps, fps)
        c.onCongestion()
        val window = c.takeWindowLine()
        assertEquals("P-frames: máx. 257 KB (tope 61 KB) · por encima del tope 1 · QP-P mín 26", window)
        // Con un mínimo puesto sigue saliendo la línea (sin P-frames grandes en la ventana).
        assertEquals("P-frames: máx. 0 KB (tope 61 KB) · por encima del tope 0 · QP-P mín 26", c.takeWindowLine())
        c.beginSession()
        assertEquals(0, c.maxBytes())
        assertEquals(0, c.overCap())
        assertEquals(26, c.qpMin())
        assertFalse(c.congested())
        now = 100
        c.onPFrame(70 * kb, bps, fps)
        assertEquals(70 * kb, c.maxBytes())
        assertEquals(1, c.overCap())
    }
}
