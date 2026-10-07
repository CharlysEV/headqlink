package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Paradas a partir del recorrido con tiempos: huecos largos en el mismo sitio sí; el GPS sin datos, no. */
class TripStopsTest {
    /** Un recorrido recto hacia el norte: un punto cada 100 m, a 36 km/h (10 s por punto), con huecos donde se diga. */
    private fun track(points: Int, extraSecAt: Map<Int, Double> = emptyMap(), jumpAt: Map<Int, Double> = emptyMap()): Array<DoubleArray> {
        var t = 0.0
        var lat = 40.40
        return Array(points) { i ->
            t += (if (i == 0) 0.0 else 10.0) + (extraSecAt[i] ?: 0.0)
            lat += if (i == 0) 0.0 else 0.0009 + (jumpAt[i] ?: 0.0)
            doubleArrayOf(lat, -3.40, t)
        }
    }

    @Test
    fun aLongGapInTheSamePlaceIsAStop() {
        // El punto 30 llega 15 min después del 29, a 100 m: parada en el 29.
        val stops = TripStops.find(track(60, extraSecAt = mapOf(30 to 900.0)))
        assertEquals(1, stops.size)
        val s = stops[0]
        assertEquals(900.0, s.durationSec, 30.0)
        assertEquals(290.0, s.atSec, 1e-9)
        assertEquals(2.9, s.km, 0.1)
    }

    @Test
    fun aShortWaitIsNotAStop() {
        assertTrue(TripStops.find(track(60, extraSecAt = mapOf(30 to 90.0))).isEmpty())
    }

    @Test
    fun aGpsGapWithDistanceIsNotAStop() {
        // 10 min sin GPS y el coche apareció 3 km más allá: no estuvo parado.
        assertTrue(TripStops.find(track(60, extraSecAt = mapOf(30 to 600.0), jumpAt = mapOf(30 to 0.027))).isEmpty())
    }

    @Test
    fun twoGapsInTheSameSpotAreOneStop() {
        val stops = TripStops.find(track(60, extraSecAt = mapOf(30 to 300.0, 31 to 400.0)))
        assertEquals(1, stops.size)
        assertTrue(stops[0].durationSec > 690)
        assertEquals(stops[0].durationSec, TripStops.totalSec(stops), 1e-9)
    }

    @Test
    fun oldTracksWithoutTimesHaveNoStops() {
        val old = Array(20) { i -> doubleArrayOf(40.4 + i * 0.001, -3.4) }
        assertTrue(TripStops.find(old).isEmpty())
        val nan = Array(20) { i -> doubleArrayOf(40.4 + i * 0.001, -3.4, Double.NaN) }
        assertTrue(TripStops.find(nan).isEmpty())
        assertTrue(TripStops.find(arrayOf()).isEmpty())
    }
}
