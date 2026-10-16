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
        // Apuntada hace una hora: cuenta; hace 13 h, o por otra ruta, no.
        val now = 1_760_000_000_000L
        assertTrue(RoutePlanner.answered("434000," + (now - 3_600_000) + ",x", 435_000, now))
        assertFalse(RoutePlanner.answered("434000," + (now - 13 * 3_600_000L) + ",x", 434_000, now))
        assertFalse(RoutePlanner.answered("434000," + (now - 3_600_000) + ",x", 298_000, now))
        assertFalse(RoutePlanner.answered("", 434_000, now))
        assertFalse(RoutePlanner.answered("basura", 434_000, now))
        assertFalse(RoutePlanner.answered("434000," + (now - 3_600_000), 434_000, now))
        // Solo «No es» (x) deja la ruta sin planificar; las respuestas de la pregunta de antes (s, n) se planifican.
        assertTrue(RoutePlanner.rejected("434000," + (now - 3_600_000) + ",x"))
        assertFalse(RoutePlanner.rejected("434000," + (now - 3_600_000) + ",n"))
        assertFalse(RoutePlanner.rejected("434000," + (now - 3_600_000) + ",s"))
        assertFalse(RoutePlanner.rejected("434000," + (now - 3_600_000)))
    }

    @Test
    fun thePlanMustAgreeWithMaps() {
        assertTrue(DestGuess.planMatches(14.0, 13.0))
        assertTrue(DestGuess.planMatches(110.0, 100.0))     // 20 % de 100
        assertFalse(DestGuess.planMatches(20.0, 13.0))
        assertTrue(DestGuess.planMatches(Double.NaN, 13.0)) // sin dato, no se deshace nada
    }

    @Test
    fun theRouteStaysNearAChargingStop() {
        val c = RoutePlanner.Charger()
        c.lat = 40.4000
        c.lon = -3.7000
        val stops = listOf(ChargePlanner.Stop(c, 120.0, 15.0, 60.0, 25.0, 30.0))
        // A unos 550 m de la parada: sigue; a unos 2,2 km, no; sin paradas o sin posición, no.
        assertTrue(RoutePlanner.nearAnyStop(40.4050, -3.7000, stops, RoutePlanner.STOP_KEEP_KM))
        assertFalse(RoutePlanner.nearAnyStop(40.4200, -3.7000, stops, RoutePlanner.STOP_KEEP_KM))
        assertFalse(RoutePlanner.nearAnyStop(40.4050, -3.7000, emptyList(), RoutePlanner.STOP_KEEP_KM))
        assertFalse(RoutePlanner.nearAnyStop(Double.NaN, -3.7000, stops, RoutePlanner.STOP_KEEP_KM))
    }
}
