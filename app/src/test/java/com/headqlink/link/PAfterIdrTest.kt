package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El primer P-frame tras cada IDR (docs §23): en el C10 el 86 % pesaba más que su IDR (mediana 2,3 veces; IDR de 60 KB
 * y P-frame de 168 KB 33 ms después).
 */
class PAfterIdrTest {
    private val kb = 1024

    @Test
    fun onlyTheFirstPFrameAfterAnIdrCounts() {
        val w = PAfterIdr()
        // Sin IDR todavía: ninguno es «el de tras el IDR».
        assertNull(w.onP(10 * kb, 0))
        w.onIdr(60 * kb)
        val first = w.onP(12 * kb, 33)
        assertNotNull(first)
        assertEquals(12 * kb, first!!.bytes)
        assertEquals(60 * kb, first.idrBytes)
        assertFalse(first.biggerThanIdr())
        assertNull(first.line)
        assertNull(w.onP(10 * kb, 66))
        assertEquals(1, w.count())
        assertEquals(0, w.bigger())
    }

    @Test
    fun aPFrameBiggerThanItsIdrIsLoggedAtMostEveryTenSeconds() {
        val w = PAfterIdr()
        w.onIdr(61_412)
        val s = w.onP(171_895, 1_000)!!
        assertTrue(s.biggerThanIdr())
        assertEquals("P-frame tras IDR: 168 KB, 2.8 veces el IDR (60 KB)", s.line)
        // Otro antes de 10 s: se cuenta, sin línea; el siguiente pasado el plazo dice cuántos se callaron.
        w.onIdr(60 * kb)
        assertNull(w.onP(150 * kb, 5_000)!!.line)
        w.onIdr(56 * kb)
        assertEquals("P-frame tras IDR: 164 KB, 2.9 veces el IDR (56 KB) · +1 más desde la línea anterior",
                w.onP(164 * kb, 11_000)!!.line)
        assertEquals(3, w.count())
        assertEquals(3, w.bigger())
        assertEquals(164.0 / 56.0, w.maxRatio(), 1e-9)
        assertEquals("P-frames tras IDR 3 · más grandes que su IDR 3 · hasta 2.9 veces el IDR · el mayor 168 KB", w.summary())
    }

    @Test
    fun withTheQpFloorTheFirstPFrameStaysSmallAndTheSummarySaysSo() {
        val w = PAfterIdr()
        repeat(13) {
            w.onIdr(93 * kb)
            assertNull(w.onP(11 * kb, it * 30_000L)!!.line)
            repeat(29) { assertNull(w.onP(10 * kb, 0)) }
        }
        assertEquals("P-frames tras IDR 13 · más grandes que su IDR 0 · hasta 0.1 veces el IDR · el mayor 11 KB", w.summary())
    }

    @Test
    fun aNewSessionClearsTheStatsButKeepsThePendingIdr() {
        val w = PAfterIdr()
        w.onIdr(60 * kb)
        assertNotNull(w.onP(200 * kb, 0))
        w.onIdr(70 * kb)
        w.beginSession()
        assertNull(w.summary())
        assertEquals(0, w.count())
        // El IDR de antes del enganche sigue pendiente: el siguiente P-frame es el suyo, y la línea vuelve a salir.
        val s = w.onP(150 * kb, 1_000)!!
        assertEquals(70 * kb, s.idrBytes)
        assertNotNull(s.line)
        // Un IDR vacío no cuenta.
        w.onIdr(0)
        assertNull(w.onP(10 * kb, 2_000))
    }

    @Test
    fun theEncoderNeverGetsAQpPMinBelowTheConfiguredFloor() {
        val floor = PFrameSizeController.QP_FLOOR
        assertEquals(IdrSizeController.QP_START, floor)
        // «Sin mínimo propio» del controlador (QP_P_NONE) se queda en el suelo; las subidas pasan tal cual.
        assertEquals(floor, VideoEncoder.qpPMinFor(VideoEncoder.QP_P_NONE, floor))
        assertEquals(floor, VideoEncoder.qpPMinFor(0, floor))
        assertEquals(26, VideoEncoder.qpPMinFor(26, floor))
        assertEquals(40, VideoEncoder.qpPMinFor(40, floor))
        // Sin suelo (encoder configurado sin QP-P): el de siempre.
        assertEquals(VideoEncoder.QP_P_NONE, VideoEncoder.qpPMinFor(0, 0))
        assertEquals(30, VideoEncoder.qpPMinFor(30, 0))
    }
}
