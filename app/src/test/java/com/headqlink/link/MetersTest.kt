package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Panel «En directo» de la pantalla principal: cifras sacadas del texto de estado del vídeo. */
class MetersTest {
    @Test
    fun readsFpsAndMbpsInAnyDecimalFormat() {
        val en = Meters.parse("30 fps · 6.5 Mbps")!!
        assertEquals(30f, en[0], 0.001f)
        assertEquals(6.5f, en[1], 0.001f)
        // Español y portugués: coma decimal (String.format con el idioma del móvil).
        val es = Meters.parse("45 fps · 12,3 Mbps")!!
        assertEquals(45f, es[0], 0.001f)
        assertEquals(12.3f, es[1], 0.001f)
    }

    @Test
    fun nothingToShowWithoutBothNumbers() {
        assertNull(Meters.parse(""))
        assertNull(Meters.parse(null))
        assertNull(Meters.parse("—"))
        assertNull(Meters.parse("30 fps"))
    }

    @Test
    fun levelIsClampedToTheExpectedMaximum() {
        assertEquals(0.5f, Meters.level(15f, 30f), 0.001f)
        assertEquals(1f, Meters.level(40f, 30f), 0.001f)
        assertEquals(0f, Meters.level(0f, 30f), 0.001f)
        assertEquals(0f, Meters.level(5f, 0f), 0.001f)
        assertEquals(0f, Meters.level(Float.NaN, 30f), 0.001f)
    }
}
