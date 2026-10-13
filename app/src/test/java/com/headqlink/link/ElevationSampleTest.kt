package com.headqlink.link

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Alturas de la ruta: como mucho ELEV_SAMPLES puntos pedidos y el resto interpolado por km. */
class ElevationSampleTest {
    @Test
    fun shortRoutesAskForEveryPoint() {
        assertArrayEquals(intArrayOf(0, 1, 2, 3), RoutePlanner.sampleIdx(4, 400))
    }

    @Test
    fun longRoutesAreSampledWithBothEnds() {
        val idx = RoutePlanner.sampleIdx(3000, 400)
        assertEquals(400, idx.size)
        assertEquals(0, idx[0])
        assertEquals(2999, idx[399])
        for (j in 1 until idx.size) assertEquals(true, idx[j] > idx[j - 1])
    }

    @Test
    fun interpolatesByKm() {
        // Puntos a 0, 1, 3 y 4 km; alturas pedidas en los puntos 0 y 3 (100 y 400 m).
        val km = doubleArrayOf(0.0, 1.0, 3.0, 4.0)
        val out = RoutePlanner.interpolate(km, intArrayOf(0, 3), doubleArrayOf(100.0, 400.0))
        assertEquals(100.0, out[0], 1e-9)
        assertEquals(175.0, out[1], 1e-9)
        assertEquals(325.0, out[2], 1e-9)
        assertEquals(400.0, out[3], 1e-9)
        // Con todos los puntos pedidos, tal cual.
        assertArrayEquals(doubleArrayOf(5.0, 6.0, 7.0, 8.0),
            RoutePlanner.interpolate(km, intArrayOf(0, 1, 2, 3), doubleArrayOf(5.0, 6.0, 7.0, 8.0)), 1e-9)
    }
}
