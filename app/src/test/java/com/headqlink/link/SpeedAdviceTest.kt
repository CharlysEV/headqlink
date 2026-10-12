package com.headqlink.link

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Consejo de velocidad (más despacio, menos paradas o menos tiempo) y lo que cuesta el plan de carga. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class)
class SpeedAdviceTest {
    /** Ruta llana de totalKm a kmh, sin viento, a 20 °C, con la energía del modelo (como la de RoutePlanner). */
    private fun route(totalKm: Int, kmh: Double): RoutePlanner.Plan {
        val n = totalKm + 1
        val p = RoutePlanner.Plan()
        p.n = n
        p.km = DoubleArray(n) { it.toDouble() }
        p.elev = DoubleArray(n)
        p.headwind = DoubleArray(n)
        p.temp = DoubleArray(n) { 20.0 }
        p.segS = DoubleArray(n) { if (it == 0) 0.0 else 3600.0 / kmh }
        p.kwhCum = DoubleArray(n)
        val m = EnergyModel()
        for (i in 1 until n) p.kwhCum[i] = p.kwhCum[i - 1] + m.compute(kmh, 0.0, 0.0, 20.0, 0.0) / kmh
        return p
    }

    private fun charger(at: Double, kw: Double) = RoutePlanner.Charger().apply {
        name = "km $at"
        kmAlong = at
        maxKw = kw
    }

    private fun settings() = ChargePlanner.Settings().apply { capacityKwh = 67.0 }

    @Test
    fun slowerSavesEnergyAndAddsDrivingTime() {
        val p = route(200, 130.0)
        val extra = DoubleArray(1)
        val capped = SpeedAdvice.cappedKwhCum(p, 110.0, 0.0, extra)
        assertTrue(capped[200] < p.kwhCum[200] - 3)
        // 200 km a 110 en vez de 130: ~16,8 min más.
        assertEquals(200 / 110.0 * 60 - 200 / 130.0 * 60, extra[0] / 60, 0.2)
        // Desde el km 150 solo cambia lo que falta.
        val late = SpeedAdvice.cappedKwhCum(p, 110.0, 150.0, null)
        assertEquals(p.kwhCum[150], late[150], 1e-9)
        assertTrue(late[200] < p.kwhCum[200])
    }

    @Test
    fun adviceWhenItSavesAStop() {
        // Justo: a 130 falta un 2 % para llegar con el 15 % (una parada corta); a 110 se llega sin parar.
        val p = route(200, 130.0)
        val list = listOf(charger(100.0, 150.0))
        val s = settings()
        val soc = p.kwhCum[200] / 67.0 * 100 + s.arriveMinPct - 2
        val now = ChargePlanner.plan(p.km, p.kwhCum, 0.0, soc, list, s)
        assertEquals(ChargePlanner.Outcome.PLANNED, now.outcome)
        val a = SpeedAdvice.best(p, now, list, s)
        assertNotNull(a)
        assertEquals(110, a!!.capKmh)
        assertEquals(0, a.stopsAfter)
        // ~16,8 min más conduciendo; la parada que se quita, menos.
        assertEquals(16.8, a.extraDriveMin, 0.3)
        assertTrue(a.chargeSavedMin > 0 && a.chargeSavedMin < a.extraDriveMin)
    }

    @Test
    fun rescueWhenTheDestinationIsOutOfReachAtRouteSpeed() {
        // Sin cargadores y un 3 % corto a 130: no se llega; a 110 sí (consejo de rescate, sin paradas).
        val p = route(150, 130.0)
        val s = settings()
        val soc = p.kwhCum[150] / 67.0 * 100 + s.arriveMinPct - 3
        val now = ChargePlanner.plan(p.km, p.kwhCum, 0.0, soc, emptyList(), s)
        assertEquals(ChargePlanner.Outcome.NO_CHARGER, now.outcome)
        assertTrue(now.stops.isEmpty())
        val a = SpeedAdvice.best(p, now, emptyList(), s)
        assertNotNull(a)
        assertTrue(a!!.rescue)
        assertEquals(110, a.capKmh)
        assertEquals(0, a.stopsAfter)
    }

    @Test
    fun noAdviceWithoutStopsOrWhenAlreadySlow() {
        val s = settings()
        val list = listOf(charger(200.0, 150.0))
        val short = route(100, 130.0)
        assertNull(SpeedAdvice.best(short, ChargePlanner.plan(short.km, short.kwhCum, 0.0, 90.0, list, s), list, s))
        // Ya a 90: no hay tramos más rápidos.
        val slow = route(600, 90.0)
        val r = ChargePlanner.plan(slow.km, slow.kwhCum, 0.0, 90.0, listOf(charger(300.0, 150.0)), s)
        assertNull(SpeedAdvice.best(slow, r, listOf(charger(300.0, 150.0)), s))
    }

    @Test
    fun planCost() {
        val p = route(300, 120.0)
        val list = listOf(charger(150.0, 150.0))
        val r = ChargePlanner.plan(p.km, p.kwhCum, 0.0, 90.0, list, settings())
        assertEquals(ChargePlanner.Outcome.PLANNED, r.outcome)
        val kwh = r.stops.sumOf { it.kwh }
        assertTrue(kwh > 0)
        assertEquals(kwh / ChargePlanner.CHARGE_EFF * 0.5, ChargePlanner.cost(r, 0.5), 1e-9)
    }
}
