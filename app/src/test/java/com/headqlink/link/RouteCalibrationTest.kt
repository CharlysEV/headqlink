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

/** Ajuste de la previsión de las rutas a lo que gasta el coche, y la energía de arrancar y frenar en ciudad. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class)
class RouteCalibrationTest {
    @Test
    fun withoutArrivalsTheModelStaysAsIs() {
        assertEquals(1.0, RouteCalibration.factor(emptyList()), 0.0)
        assertEquals(1.0, RouteCalibration.factor(null), 0.0)
    }

    @Test
    fun theEvening7OctoberRoundTripPullsTheForecastUp() {
        // Ida: previsto 0,4 kWh y real 0,74; vuelta: previsto 1,4 y real 1,64 (nube, 7 km cada una).
        var s = RouteCalibration.add(emptyList(), RouteCalibration.Sample(0.4, 0.74, 7.0, 1))
        s = RouteCalibration.add(s, RouteCalibration.Sample(1.4, 1.64, 7.0, 2))
        val f = RouteCalibration.factor(s)
        // (2,38 + 1) / (1,8 + 1): prudente con solo dos rutas.
        assertEquals(3.38 / 2.8, f, 1e-9)
        assertEquals(2L, s[0].atMs) // la más reciente primero
    }

    @Test
    fun theFactorIsBounded() {
        val wild = (1..12).map { RouteCalibration.Sample(1.0, 2.4, 10.0, it.toLong()) }
        assertEquals(RouteCalibration.MAX_FACTOR, RouteCalibration.factor(wild), 0.0)
        val low = (1..12).map { RouteCalibration.Sample(2.0, 0.85, 10.0, it.toLong()) }
        assertEquals(RouteCalibration.MIN_FACTOR, RouteCalibration.factor(low), 0.0)
    }

    @Test
    fun onlyMeaningfulArrivalsCount() {
        assertTrue(RouteCalibration.usable(1.4, 1.64, 7.0))
        assertFalse(RouteCalibration.usable(1.4, 1.64, 1.5)) // muy corta
        assertFalse(RouteCalibration.usable(0.2, 0.1, 5.0)) // poco gasto: la resolución del % manda
        assertFalse(RouteCalibration.usable(1.0, 3.0, 10.0)) // absurda
        assertFalse(RouteCalibration.usable(Double.NaN, 1.0, 10.0))
    }

    @Test
    fun keepsTheLastOnesAndSurvivesJson() {
        var s: List<RouteCalibration.Sample> = emptyList()
        for (i in 1..20) s = RouteCalibration.add(s, RouteCalibration.Sample(1.0, 1.1, 8.0, i.toLong()))
        assertEquals(RouteCalibration.KEEP, s.size)
        val back = RouteCalibration.fromJson(RouteCalibration.toJson(s))
        assertEquals(s.size, back.size)
        assertEquals(20L, back[0].atMs)
        assertEquals(1.1, back[0].realKwh, 1e-9)
        assertTrue(RouteCalibration.fromJson("basura").isEmpty())
        assertTrue(RouteCalibration.fromJson("").isEmpty())
    }

    @Test
    fun stopAndGoOnlyInTown() {
        assertEquals(0.0, RoutePlanner.stopAndGoKwh(110.0, 10.0), 0.0)
        val town = RoutePlanner.stopAndGoKwh(40.0, 10.0) / 10 * 100 // kWh/100 km
        assertTrue("$town", town > 1.0 && town < 4.0)
        assertTrue(RoutePlanner.stopAndGoKwh(30.0, 1.0) > 0)
    }

    @Test
    fun theCarsHistorySeedsTheFactor() {
        // 30 días de viajes según el coche, a 30 km/h de media y 20 kWh/100 km: el modelo en llano da bastante menos.
        val t0 = 1791297600000L
        val trips = (0 until 10).map { i ->
            CloudHistory.Trip(t0 + i * 86_400_000L, t0 + i * 86_400_000L + 20 * 60_000L, 10.0, 2.0, Double.NaN, 70.0)
        }
        val h = RouteCalibration.historyFactor(trips, 15.0)
        assertTrue("$h", h > 1.2 && h <= RouteCalibration.MAX_FACTOR)
        // Sin rutas medidas, manda el historial; con muchas, mandan ellas.
        assertEquals(h, RouteCalibration.factor(emptyList(), h), 1e-9)
        val many = (1..12).map { RouteCalibration.Sample(2.0, 2.2, 10.0, it.toLong()) }
        assertEquals((26.4 + h) / 25.0, RouteCalibration.factor(many, h), 1e-9)
        // Poco recorrido (o viajes de un momento): sin factor.
        assertTrue(RouteCalibration.historyFactor(trips.take(1), 15.0).isNaN())
        assertTrue(RouteCalibration.historyFactor(listOf(CloudHistory.Trip(t0, t0 + 60_000, 30.0, 5.0, Double.NaN, 90.0)), 15.0).isNaN())
        assertTrue(RouteCalibration.historyFactor(null, 15.0).isNaN())
        assertEquals(1.0, RouteCalibration.factor(emptyList(), Double.NaN), 0.0)
    }
}
