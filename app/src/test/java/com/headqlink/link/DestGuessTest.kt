package com.headqlink.link

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reglas del destino leído de la tarjeta de Google Maps. */
class DestGuessTest {
    @Test
    fun distanceRule() {
        assertTrue(DestGuess.fitsDistance(10.0, 13.0))
        assertTrue(DestGuess.fitsDistance(13.2, 13.0))      // GPS y punto exacto: margen
        assertFalse(DestGuess.fitsDistance(15.0, 13.0))     // más lejos en recta que por carretera: imposible
        assertFalse(DestGuess.fitsDistance(4.0, 13.0))      // rodeo de más de 2,2
    }

    @Test
    fun thePlanMustAgreeWithMaps() {
        assertTrue(DestGuess.planMatches(14.0, 13.0))
        assertTrue(DestGuess.planMatches(110.0, 100.0))     // 20 % de 100
        assertFalse(DestGuess.planMatches(20.0, 13.0))
        assertTrue(DestGuess.planMatches(Double.NaN, 13.0)) // sin dato, no se deshace nada
    }
}
