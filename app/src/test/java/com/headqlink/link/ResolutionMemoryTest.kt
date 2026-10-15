package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** La resolución con la que empieza cada viaje, según cómo le fue a este móvil en los anteriores. */
class ResolutionMemoryTest {
    private fun e(high: Boolean, max: Int, min: Int) = ResolutionMemory.Entry(high, max, min)
    private fun start(vararg h: ResolutionMemory.Entry, now: Int = 0) = ResolutionMemory.start(h.toList(), now).high

    @Test
    fun theFirstTripStartsAt720() {
        assertFalse(start())
        // Viajes cortos y frescos no dicen nada todavía.
        assertFalse(start(e(false, 0, 5), e(false, 1, 12)))
    }

    @Test
    fun aCoolPhoneGoesTo1080AndStaysThere() {
        // 720p, 35 min sin pasar de ligero: el siguiente prueba 1080p; y si a 1080p tampoco se calienta, sigue a 1080p.
        assertTrue(start(e(false, 1, 35)))
        assertTrue(start(e(false, 1, 35), e(true, 1, 40)))
        // Un viaje corto después no cambia lo aprendido.
        assertTrue(start(e(false, 1, 35), e(true, 1, 40), e(true, 0, 6)))
    }

    @Test
    fun aPhoneThatHeatsUpAt1080GoesBackTo720() {
        // Como el del 15-10: a 1080p llega a moderado (2) a los 20 min.
        assertFalse(start(e(true, 2, 25)))
        // Se calentó aunque el viaje fuera corto: cuenta.
        assertFalse(start(e(true, 3, 8)))
        // Después, un viaje a 720p fresco: se vuelve a probar 1080p…
        assertTrue(start(e(true, 2, 25), e(false, 1, 30)))
        // …salvo que los dos últimos a 1080p se calentaran.
        assertFalse(start(e(true, 2, 25), e(false, 1, 30), e(true, 2, 22), e(false, 1, 40)))
    }

    @Test
    fun alreadyHotWhenConnectingStartsAt720() {
        assertFalse(start(e(true, 1, 40), now = 2))
        assertTrue(start(e(true, 1, 40), now = 1))
        // A 720p y aun así caliente (un día de calor): 720p.
        assertFalse(start(e(false, 2, 30)))
    }

    @Test
    fun historyIsKeptShortAndSurvivesAsText() {
        var h = listOf<ResolutionMemory.Entry>()
        for (i in 1..10) h = ResolutionMemory.add(h, e(i % 2 == 0, i % 3, 20 + i))
        assertEquals(ResolutionMemory.KEEP, h.size)
        val back = ResolutionMemory.decode(ResolutionMemory.encode(h))
        assertEquals(ResolutionMemory.encode(h), ResolutionMemory.encode(back))
        assertEquals(30, back.last().minutes)
        // Basura: se ignora.
        assertEquals(1, ResolutionMemory.decode("h,2,35;x;l,a,3").size)
        assertTrue(ResolutionMemory.decode("").isEmpty())
    }
}
