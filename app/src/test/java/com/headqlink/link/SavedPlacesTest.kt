package com.headqlink.link

import android.app.Application
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Destinos guardados de la Ruta (Casa, Trabajo, con nombre y viajes) y que no viajan en la copia de ajustes. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class)
class SavedPlacesTest {
    private fun place(name: String, lat: Double, lon: Double) = RoutePlanner.Place().apply {
        this.name = name
        this.detail = "Calle Ejemplo 1"
        this.lat = lat
        this.lon = lon
    }

    @Test
    fun aTripSurvivesTheRoundTripThroughJson() {
        val json = SavedPlaces.add("", place("Destino", 40.5, -3.5), listOf(place("Uno", 40.2, -3.2), place("Dos", 40.3, -3.3)), true,
            "Excursión")
        val list = SavedPlaces.parse(json)
        assertEquals(1, list.size)
        val s = list[0]
        assertEquals("Excursión", s.name)
        assertEquals(40.5, s.lat, 1e-9)
        assertEquals(-3.5, s.lon, 1e-9)
        assertEquals(listOf("Uno", "Dos"), s.via.map { it.name })
        assertTrue(s.roundTrip)
        assertTrue(s.isTrip())
        assertEquals("", s.tag)
        // Y vuelve a dar lo mismo.
        assertEquals(json, SavedPlaces.toJson(SavedPlaces.parse(json)))
    }

    @Test
    fun thereIsOnlyOneHomeAndItGoesFirst() {
        var json = SavedPlaces.add("", place("Uno", 40.1, -3.1), null, false, null)
        json = SavedPlaces.setTagged(json, place("Casa vieja", 40.0, -3.0), SavedPlaces.HOME)
        json = SavedPlaces.setTagged(json, place("Oficina", 40.2, -3.2), SavedPlaces.WORK)
        json = SavedPlaces.setTagged(json, place("Casa nueva", 40.05, -3.05), SavedPlaces.HOME)
        val list = SavedPlaces.parse(json)
        assertEquals(listOf(SavedPlaces.HOME, SavedPlaces.WORK, ""), list.map { it.tag })
        assertEquals("Casa nueva", SavedPlaces.tagged(list, SavedPlaces.HOME)!!.name)
        assertEquals("Oficina", SavedPlaces.tagged(list, SavedPlaces.WORK)!!.name)
        assertNull(SavedPlaces.tagged(SavedPlaces.parse(""), SavedPlaces.HOME))
    }

    @Test
    fun savingTheSamePlaceTwiceKeepsOneWithTheNewName() {
        var json = SavedPlaces.add("", place("A", 40.0, -3.0), null, false, "Primero")
        json = SavedPlaces.add(json, place("A", 40.0003, -3.0), null, false, "Segundo") // a ~30 m: el mismo
        assertEquals(listOf("Segundo"), SavedPlaces.parse(json).map { it.name })
        // Con paradas es otro (un viaje), aunque el destino sea el mismo.
        json = SavedPlaces.add(json, place("A", 40.0, -3.0), listOf(place("B", 40.1, -3.1)), false, "Viaje")
        assertEquals(2, SavedPlaces.parse(json).size)
    }

    @Test
    fun homeCanBeMovedToWhereTheCarIs() {
        // El buscador dio el punto del pueblo; estando en casa, «Aquí» lo lleva a la posición del GPS.
        var json = SavedPlaces.setTagged("", place("Calle Mayor 27", 40.0, -3.0), SavedPlaces.HOME)
        json = SavedPlaces.move(json, 0, 40.004, -3.006)
        val home = SavedPlaces.tagged(SavedPlaces.parse(json), SavedPlaces.HOME)!!
        assertEquals("Calle Mayor 27", home.name)
        assertEquals(40.004, home.lat, 1e-9)
        assertEquals(-3.006, home.lon, 1e-9)
        assertEquals(json, SavedPlaces.move(json, 0, Double.NaN, Double.NaN)) // sin GPS: igual
        assertEquals(json, SavedPlaces.move(json, 5, 41.0, -4.0)) // fuera de rango: igual
    }

    @Test
    fun renameAndRemove() {
        var json = SavedPlaces.add("", place("A", 40.0, -3.0), null, false, null)
        json = SavedPlaces.add(json, place("B", 41.0, -4.0), null, false, null)
        json = SavedPlaces.rename(json, 1, "  Playa  ")
        assertEquals(listOf("A", "Playa"), SavedPlaces.parse(json).map { it.name })
        assertEquals(json, SavedPlaces.rename(json, 1, "   ")) // vacío: igual
        assertEquals(json, SavedPlaces.rename(json, 7, "X")) // fuera de rango: igual
        json = SavedPlaces.remove(json, 0)
        assertEquals(listOf("Playa"), SavedPlaces.parse(json).map { it.name })
    }

    @Test
    fun brokenOrIncompleteJsonGivesWhatCanBeRead() {
        assertTrue(SavedPlaces.parse("no es json").isEmpty())
        assertTrue(SavedPlaces.parse(null).isEmpty())
        val list = SavedPlaces.parse("""[{"n":"Sin coordenadas"},{"n":"Bien","la":40.0,"lo":-3.0,"t":"raro"}]""")
        assertEquals(listOf("Bien"), list.map { it.name })
        assertEquals("", list[0].tag) // una etiqueta desconocida no es Casa ni Trabajo
    }

    @Test
    fun tooManyDropsTheOldestNamedButNeverHome() {
        var json = SavedPlaces.setTagged("", place("Casa", 40.0, -3.0), SavedPlaces.HOME)
        for (i in 1..SavedPlaces.MAX + 5) json = SavedPlaces.add(json, place("P$i", 40.0 + i * 0.01, -3.0), null, false, null)
        val list = SavedPlaces.parse(json)
        assertEquals(SavedPlaces.MAX, list.size)
        assertNotNull(SavedPlaces.tagged(list, SavedPlaces.HOME))
        assertEquals("P${SavedPlaces.MAX + 5}", list.last().name)
    }

    @Test
    fun savedPlacesAndTheRouteHomeNeverTravelInTheBackup() {
        assertFalse(SettingsBackup.travels(com.headqlink.link.Config.SAVED_PLACES))
        assertFalse(SettingsBackup.travels(com.headqlink.link.Config.HOME_ROUTE))
        assertFalse(SettingsBackup.travels("recent_places"))
        assertTrue(SettingsBackup.travels("charger_marks")) // cargadores públicos: sí viajan
        val ctx = RuntimeEnvironment.getApplication()
        Str.init(ctx)
        val cfg = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val car = ctx.getSharedPreferences(CarCloudStore.PREFS, Context.MODE_PRIVATE)
        cfg.edit().clear()
            .putString(com.headqlink.link.Config.SAVED_PLACES, SavedPlaces.setTagged("", place("Casa", 40.123, -3.456), SavedPlaces.HOME))
            .putString(com.headqlink.link.Config.HOME_ROUTE, HomeReach.routeJson(40.321, -3.654, 40.123, -3.456, 12.0, 2.0, 0L))
            .putInt("dest_charge_pct", 25)
            .commit()
        car.edit().clear().commit()
        val text = SettingsBackup.export(cfg, car, "0.0.0", 0L)
        for (bad in listOf(com.headqlink.link.Config.SAVED_PLACES, com.headqlink.link.Config.HOME_ROUTE, "40.123", "40.321", "Calle Ejemplo")) assertFalse(bad, text.contains(bad))
        assertTrue(text.contains("dest_charge_pct"))
    }
}
