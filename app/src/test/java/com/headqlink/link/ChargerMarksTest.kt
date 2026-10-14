package com.headqlink.link

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Cargadores favoritos y vetados: cómo se guardan y cómo cambian el plan de carga. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class)
class ChargerMarksTest {
    private fun charger(id: String, lat: Double, lon: Double, at: Double = 0.0, kw: Double = 150.0) = RoutePlanner.Charger().apply {
        this.id = id
        name = "Cargador $id"
        this.lat = lat
        this.lon = lon
        kmAlong = at
        maxKw = kw
    }

    @Test
    fun theKeyIsTheSourceIdOrThePosition() {
        assertEquals("dgt:123", ChargerMarks.key(charger("dgt:123", 40.0, -3.0)))
        assertEquals("@40.0000,-3.0000", ChargerMarks.key(charger("", 40.0, -3.0)))
    }

    @Test
    fun tappingCyclesFavouriteBannedAndNothing() {
        assertEquals(ChargerMarks.FAV, ChargerMarks.next(ChargerMarks.NONE))
        assertEquals(ChargerMarks.BAN, ChargerMarks.next(ChargerMarks.FAV))
        assertEquals(ChargerMarks.NONE, ChargerMarks.next(ChargerMarks.BAN))
        val c = charger("osm:node7", 40.0, -3.0)
        var json = ChargerMarks.set("", c, ChargerMarks.FAV)
        assertEquals(ChargerMarks.FAV, ChargerMarks.kindOf(ChargerMarks.parse(json), c))
        json = ChargerMarks.set(json, c, ChargerMarks.BAN)
        assertEquals(1, ChargerMarks.parse(json).size)
        assertEquals(ChargerMarks.BAN, ChargerMarks.kindOf(ChargerMarks.parse(json), c))
        json = ChargerMarks.set(json, c, ChargerMarks.NONE)
        assertTrue(ChargerMarks.parse(json).isEmpty())
    }

    @Test
    fun aMarkFollowsTheSameSiteFromAnotherSource() {
        // Marcado cuando venía de OpenStreetMap; luego llega de la DGT (otro id) a unos 50 m: es el mismo sitio.
        val json = ChargerMarks.set("", charger("osm:node9", 40.0, -3.0), ChargerMarks.FAV)
        val marks = ChargerMarks.parse(json)
        assertEquals(ChargerMarks.FAV, ChargerMarks.kindOf(marks, charger("dgt:55", 40.00045, -3.0)))
        assertEquals(ChargerMarks.NONE, ChargerMarks.kindOf(marks, charger("dgt:56", 40.01, -3.0)))
    }

    @Test
    fun removeAndBrokenJson() {
        var json = ChargerMarks.set("", charger("a", 40.0, -3.0), ChargerMarks.FAV)
        json = ChargerMarks.set(json, charger("b", 41.0, -3.0), ChargerMarks.BAN)
        json = ChargerMarks.remove(json, 0)
        assertEquals(listOf("b"), ChargerMarks.parse(json).map { it.id })
        assertTrue(ChargerMarks.parse("{roto").isEmpty())
        assertTrue(ChargerMarks.parse("""[{"i":"x","k":0}]""").isEmpty()) // sin marca: fuera
    }

    // Ruta llana de 500 km a 20 kWh/100 km y 70 kWh, como en ChargePlannerTest: sin marcas para en el km 260.
    private val km = DoubleArray(501) { it.toDouble() }
    private val kwh = DoubleArray(501) { it * 0.2 }
    private val c100 = charger("c100", 40.1, -3.0, 100.0, 50.0)
    private val c180 = charger("c180", 40.2, -3.0, 180.0, 150.0)
    private val c200 = charger("c200", 40.3, -3.0, 200.0, 22.0)
    private val c260 = charger("c260", 40.4, -3.0, 260.0, 150.0)
    private val c350 = charger("c350", 40.5, -3.0, 350.0, 100.0)
    private val c420 = charger("c420", 40.6, -3.0, 420.0, 50.0)
    private val all = listOf(c100, c180, c200, c260, c350, c420)

    private fun settings() = ChargePlanner.Settings().apply { capacityKwh = 70.0 }

    @Test
    fun aFavouriteInTheLastStretchWinsOverAFasterOne() {
        assertSame(c260, ChargePlanner.plan(km, kwh, 0.0, 90.0, all, settings()).stops[0].charger)
        val marks = ChargerMarks.parse(ChargerMarks.set("", c180, ChargerMarks.FAV))
        val s = settings().apply { favorites = ChargerMarks.favorites(all, marks) }
        assertEquals(setOf(c180), s.favorites)
        assertSame(c180, ChargePlanner.plan(km, kwh, 0.0, 90.0, all, s).stops[0].charger)
        assertTrue(ChargePlanner.score(c180, s) > ChargePlanner.score(c260, s))
    }

    @Test
    fun aFavouriteTooEarlyIsNotWorthAnExtraStop() {
        // km 100 de 260 alcanzables: fuera incluso del tramo de los favoritos (desde el 40 %).
        val s = settings().apply { favorites = setOf(c100) }
        assertSame(c260, ChargePlanner.plan(km, kwh, 0.0, 90.0, all, s).stops[0].charger)
    }

    @Test
    fun aBannedChargerIsNeverUsed() {
        val marks = ChargerMarks.parse(ChargerMarks.set("", c260, ChargerMarks.BAN))
        val list = ChargerMarks.withoutBanned(all, marks)
        assertFalse(list.contains(c260))
        assertEquals(5, list.size)
        val r = ChargePlanner.plan(km, kwh, 0.0, 90.0, list, settings())
        assertFalse(r.stops.any { it.charger === c260 })
        assertEquals(ChargePlanner.Outcome.PLANNED, r.outcome)
        // Sin vetados, la misma lista.
        assertSame(all, ChargerMarks.withoutBanned(all, ChargerMarks.parse("")))
    }

    @Test
    fun withFoodPreferredAChargerWithFoodGetsTheBonus() {
        val s = settings().apply { food = setOf(c180) }
        assertSame(c180, ChargePlanner.plan(km, kwh, 0.0, 90.0, all, s).stops[0].charger)
    }
}
