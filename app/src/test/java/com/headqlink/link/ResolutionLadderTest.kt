package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Resolución según el calor: empieza en 720p, sube tras 10 min fresco, baja tras 2 min de calor y no sube enseguida. */
class ResolutionLadderTest {
    private val min = 60_000L

    @Test
    fun goesUpAfterTenCoolMinutes() {
        val r = ResolutionLadder(false)
        assertEquals(0, r.update(0, 0.6f, 0, 0))
        assertEquals(0, r.update(1, 0.7f, 0, 9 * min))
        assertEquals(1, r.update(1, 0.7f, 0, 10 * min))
        assertTrue(r.high())
        // Un rato templado corta la cuenta.
        val s = ResolutionLadder(false)
        s.update(0, 0.6f, 0, 0)
        s.update(2, 0.85f, 0, 5 * min)
        assertEquals(0, s.update(0, 0.6f, 0, 11 * min))
        assertFalse(s.high())
    }

    @Test
    fun goesDownAfterTwoHotMinutesAndWaits() {
        val r = ResolutionLadder(true)
        assertEquals(0, r.update(3, 0.95f, 2, 0))
        assertEquals(0, r.update(3, 0.95f, 2, 1 * min))
        assertEquals(-1, r.update(3, 0.95f, 2, 2 * min))
        assertFalse(r.high())
        // Fresco enseguida: no vuelve a subir hasta 20 min después de bajar.
        for (t in 3..21) r.update(0, 0.5f, 0, t * min)
        assertFalse(r.high())
        assertEquals(1, r.update(0, 0.5f, 0, 22 * min))
    }

    @Test
    fun moderateIsNotEnoughToGoDown() {
        val r = ResolutionLadder(true)
        // Moderado con margen alto pero anticipación 1: ni sube ni baja.
        for (t in 0..10) assertEquals(0, r.update(2, 0.88f, 1, t * min))
        assertTrue(r.high())
        assertFalse(ResolutionLadder.cool(2, 0.6f, 0))
        assertTrue(ResolutionLadder.hot(0, 2))
    }

    @Test
    fun infoLine() {
        assertEquals("720p · 30 fps · 38 °C", CarUi.videoInfoLine(1280, 30, 38.4))
        assertEquals("1080p · 60 fps", CarUi.videoInfoLine(1920, 60, Double.NaN))
    }
}
