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
    fun theSameMapsRouteIsAskedOnce() {
        // La misma ruta aunque Maps la recalcule un poco; otra ruta, otra pregunta.
        assertTrue(MapsNavListener.sameRoute(434_000, 436_500))
        assertFalse(MapsNavListener.sameRoute(434_000, 411_000))
        assertTrue(MapsNavListener.sameRoute(5_000, 5_800))
        assertFalse(MapsNavListener.sameRoute(5_000, 7_000))
        // Contestada hace una hora: no se vuelve a preguntar; hace 13 h, o por otra ruta, sí.
        val now = 1_760_000_000_000L
        assertTrue(RoutePlanner.answered("434000," + (now - 3_600_000) + ",n", 435_000, now))
        assertFalse(RoutePlanner.answered("434000," + (now - 13 * 3_600_000L) + ",s", 434_000, now))
        assertFalse(RoutePlanner.answered("434000," + (now - 3_600_000) + ",s", 298_000, now))
        assertFalse(RoutePlanner.answered("", 434_000, now))
        assertFalse(RoutePlanner.answered("basura", 434_000, now))
        // Con la respuesta: «Sí» planifica sin preguntar; «No» y las de antes (sin respuesta guardada), silencio.
        assertTrue(RoutePlanner.answered("434000," + (now - 3_600_000) + ",s", 434_000, now))
        assertTrue(RoutePlanner.answeredYes("434000," + (now - 3_600_000) + ",s"))
        assertFalse(RoutePlanner.answeredYes("434000," + (now - 3_600_000) + ",n"))
        assertFalse(RoutePlanner.answeredYes("434000," + (now - 3_600_000)))
        assertFalse(RoutePlanner.answered("434000," + (now - 3_600_000), 434_000, now))
    }

    @Test
    fun thePlanMustAgreeWithMaps() {
        assertTrue(DestGuess.planMatches(14.0, 13.0))
        assertTrue(DestGuess.planMatches(110.0, 100.0))     // 20 % de 100
        assertFalse(DestGuess.planMatches(20.0, 13.0))
        assertTrue(DestGuess.planMatches(Double.NaN, 13.0)) // sin dato, no se deshace nada
    }
}
