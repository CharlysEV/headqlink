package com.headqlink.link

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Buscador de destinos: leer Photon, juntar con el de Android sin repetidos, nombres y destinos recientes. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class)
class PlaceSearchTest {
    private fun place(name: String, lat: Double, lon: Double, detail: String = "") = RoutePlanner.Place().apply {
        this.name = name
        this.detail = detail
        this.lat = lat
        this.lon = lon
    }

    @Test
    fun readsPhoton() {
        val json = """{"type":"FeatureCollection","features":[
            {"geometry":{"type":"Point","coordinates":[-3.4801,40.4021]},
             "properties":{"name":"Mercadona","street":"Calle Mayor","housenumber":"12","postcode":"28800","city":"Alcalá de Henares","state":"Comunidad de Madrid","osm_key":"shop"}},
            {"geometry":{"type":"Point","coordinates":[-3.6887,40.4199]},
             "properties":{"street":"Calle de Alcalá","housenumber":"27","postcode":"28014","city":"Madrid","country":"España","type":"house"}},
            {"geometry":{"type":"Point","coordinates":[-3.6,40.5]},"properties":{"country":"España"}}
        ]}"""
        val r = PlaceSearch.parsePhoton(json)
        assertEquals(2, r.size)
        assertEquals("Mercadona", r[0].name)
        assertEquals("Calle Mayor 12, 28800 Alcalá de Henares, Comunidad de Madrid", r[0].detail)
        assertEquals(40.4021, r[0].lat, 1e-9)
        assertEquals(-3.4801, r[0].lon, 1e-9)
        assertEquals("Calle de Alcalá 27", r[1].name)
        assertEquals("28014 Madrid", r[1].detail)
    }

    @Test
    fun androidAddressNames() {
        // De un comercio: su nombre.
        assertEquals("Mercadona", PlaceSearch.addressName("Mercadona", "Calle Mayor", "12", "Alcalá"))
        // De una dirección: la calle con el número (getFeatureName suele ser solo el número).
        assertEquals("Calle de Alcalá 27", PlaceSearch.addressName("27", "Calle de Alcalá", null, "Madrid"))
        assertEquals("Calle de Alcalá 27", PlaceSearch.addressName("Calle de Alcalá", "Calle de Alcalá", "27", "Madrid"))
        // Solo el pueblo.
        assertEquals("Madrid", PlaceSearch.addressName(null, null, null, "Madrid"))
    }

    @Test
    fun mergeAlternatesWithoutDuplicates() {
        val android = listOf(place("Calle de Alcalá 27", 40.4199, -3.6887), place("Calle de Alcalá", 40.4205, -3.6895))
        val photon = listOf(place("C. de Alcalá, 27", 40.41995, -3.68875), // el mismo sitio con otro nombre: a 6 m
            place("Alcalá", 40.9, -3.9))
        val m = PlaceSearch.merge(android, photon, 40.4, -3.5, 8)
        assertEquals(listOf("Calle de Alcalá 27", "Calle de Alcalá", "Alcalá"), m.map { it.name })
        assertEquals(1, PlaceSearch.merge(android, photon, 40.4, -3.5, 1).size)
        assertTrue(PlaceSearch.merge(null, null, Double.NaN, Double.NaN, 8).isEmpty())
    }

    @Test
    fun sameNameIgnoresAccentsCaseAndPunctuation() {
        assertTrue(PlaceSearch.sameName("Alcalá de Henares", "alcala de henares"))
        assertTrue(PlaceSearch.sameName("C. de Alcalá, 27", "c de alcala 27"))
        assertFalse(PlaceSearch.sameName("Calle Mayor 1", "Calle Mayor 2"))
    }

    @Test
    fun searchWhileTypingFromThreeLetters() {
        assertFalse(PlaceSearch.worthTyping("av"))
        assertFalse(PlaceSearch.worthTyping("  a "))
        assertTrue(PlaceSearch.worthTyping("ave"))
    }

    @Test
    fun recentDestinations() {
        var json = PlaceSearch.remember("", place("Casa", 40.41, -3.50, "Calle de Alcalá 27"))
        json = PlaceSearch.remember(json, place("Trabajo", 40.45, -3.69))
        json = PlaceSearch.remember(json, place("Casa", 40.41001, -3.50001)) // la misma: sube arriba, sin repetirse
        val r = PlaceSearch.recents(json)
        assertEquals(listOf("Casa", "Trabajo"), r.map { it.name })
        for (i in 0 until 20) json = PlaceSearch.remember(json, place("P$i", 41.0 + i * 0.1, -3.0))
        assertEquals(PlaceSearch.MAX_RECENT, PlaceSearch.recents(json).size)
        assertTrue(PlaceSearch.recents("basura").isEmpty())
    }
}
