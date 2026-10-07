package com.headqlink.link

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Caché de cargadores por cuadrículas: qué cuadrículas cubren una ruta, qué tramos quedan sin datos y el guardado. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class)
class ChargerCacheTest {
    @Test
    fun tilesAreTwoTenthsOfADegreeAlsoWestOfGreenwich() {
        assertEquals("200_-30", ChargerCache.tileOf(40.05, -5.95))
        val b = ChargerCache.tileBox("200_-30")
        assertEquals(40.0, b[0], 1e-9)
        assertEquals(-6.0, b[1], 1e-9)
        assertEquals(40.2, b[2], 1e-9)
        assertEquals(-5.8, b[3], 1e-9)
    }

    @Test
    fun theTilesOfABoxInOrderWithoutRepeats() {
        val boxes = listOf(doubleArrayOf(40.05, 0.05, 40.25, 0.15), doubleArrayOf(40.15, 0.1, 40.3, 0.3))
        assertEquals(listOf("200_0", "201_0", "200_1", "201_1"), ChargerCache.tilesFor(boxes))
    }

    @Test
    fun gapsAreTheStretchesThroughTilesWithoutData() {
        val lat = doubleArrayOf(40.05, 40.1, 40.15, 40.25, 40.3, 40.45)
        val lon = DoubleArray(6) { 0.05 }
        val km = doubleArrayOf(0.0, 5.0, 10.0, 20.0, 25.0, 40.0)
        // La cuadrícula 201_0 (de 40,2 a 40,4) sin datos: del km 10 (el último con datos) al 40 (el primero después).
        val g = ChargerCache.gaps(lat, lon, km, setOf("201_0"))
        assertEquals(1, g.size)
        assertEquals(10.0, g[0][0], 1e-9)
        assertEquals(40.0, g[0][1], 1e-9)
        assertTrue(ChargerCache.gaps(lat, lon, km, emptySet()).isEmpty())
    }

    @Test
    fun savedTilesComeBackTheSameIncludingEmptyOnes() {
        val it = ChargerCache.Item().apply {
            id = "node1"
            lat = 40.1
            lon = 0.1
            name = "Zunder"
            detail = "CCS"
            network = "zunder"
            kw = 180.0
        }
        val json = ChargerCache.toJson(mapOf("200_0" to 5L, "201_0" to 7L), mapOf("200_0" to listOf(it), "201_0" to emptyList()))
        val at = HashMap<String, Long>()
        val items = HashMap<String, List<ChargerCache.Item>>()
        ChargerCache.fromJson(json, at, items)
        assertEquals(5L, at["200_0"])
        assertEquals(0, items["201_0"]!!.size)
        val back = items["200_0"]!![0]
        assertEquals("Zunder", back.name)
        assertEquals("zunder", back.network)
        assertEquals(180.0, back.kw, 0.0)
        assertEquals(40.1, back.toCharger().lat, 0.0)
    }
}
