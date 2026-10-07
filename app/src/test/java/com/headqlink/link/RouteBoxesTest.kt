package com.headqlink.link

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Cargadores junto a la ruta: los recuadros que se piden a Overpass y la distancia real a la carretera. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class)
class RouteBoxesTest {
    // Ruta recta hacia el este por el paralelo 40, un punto por km (~85 km por grado de longitud).
    private val n = 101
    private val lat = DoubleArray(n) { 40.0 }
    private val lon = DoubleArray(n) { it / (111.32 * Math.cos(Math.toRadians(40.0))) }
    private val km = DoubleArray(n) { it.toDouble() }

    @Test
    fun boxesCoverTheWholeRouteWithTheMargin() {
        val boxes = RoutePlanner.routeBoxes(lat, lon, km, 20.0, 2.5)
        assertEquals(5, boxes.size)
        for (i in 0 until n) {
            assertTrue("punto $i", boxes.any { lat[i] >= it[0] && lon[i] >= it[1] && lat[i] <= it[2] && lon[i] <= it[3] })
        }
        // 2,5 km de margen a cada lado (en latitud, ~0,0225°).
        assertEquals(40.0 - 2.5 / 111.32, boxes[0][0], 1e-9)
        assertEquals(40.0 + 2.5 / 111.32, boxes[0][2], 1e-9)
        // Cada recuadro, unos 20 km de ruta (más el margen): nada de recuadros enormes.
        for (b in boxes) assertTrue((b[3] - b[1]) * 111.32 * Math.cos(Math.toRadians(40.0)) <= 25.1)
    }

    @Test
    fun longStretchesWithoutPointsStillGetABox() {
        val la = doubleArrayOf(40.0, 40.0, 41.0)
        val lo = doubleArrayOf(0.0, 0.1, 0.1)
        val k = doubleArrayOf(0.0, 8.5, 120.0)
        val boxes = RoutePlanner.routeBoxes(la, lo, k, 20.0, 2.5)
        assertEquals(2, boxes.size)
        assertTrue(boxes[1][0] < 40.0 && boxes[1][2] > 41.0)
    }

    @Test
    fun distanceToTheRoadIsToTheSegmentNotTheNearestPoint() {
        // Punto 1 km al norte, a mitad entre los puntos 10 y 11.
        val la = 40.0 + 1.0 / 111.32
        val lo = (lon[10] + lon[11]) / 2
        assertEquals(1.0, RoutePlanner.distToRouteKm(lat, lon, 10, la, lo), 0.01)
        // Y a 3 km: fuera de los 2,5 km.
        assertTrue(RoutePlanner.distToRouteKm(lat, lon, 10, 40.0 + 3.0 / 111.32, lo) > RoutePlanner.CHARGER_RADIUS_KM)
    }
}
