package com.headqlink.link

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Plan de carga: dónde parar y cuánto cargar, y cómo se rehace durante el viaje si se gasta más (o menos) de lo previsto. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class)
class ChargePlannerTest {
    // Ruta llana de 500 km a 20 kWh/100 km y un C10 de 70 kWh.
    private val km = DoubleArray(501) { it.toDouble() }
    private val kwh = DoubleArray(501) { it * 0.2 }

    private fun settings() = ChargePlanner.Settings().apply { capacityKwh = 70.0 }

    private fun charger(at: Double, kw: Double) = RoutePlanner.Charger().apply {
        name = "km $at"
        kmAlong = at
        maxKw = kw
    }

    private val c100 = charger(100.0, 50.0)
    private val c180 = charger(180.0, 150.0)
    private val c200 = charger(200.0, 22.0)
    private val c260 = charger(260.0, 150.0)
    private val c350 = charger(350.0, 100.0)
    private val c420 = charger(420.0, 50.0)
    private val all = listOf(c100, c180, c200, c260, c350, c420)

    @Test
    fun aShortTripNeedsNoStops() {
        val r = ChargePlanner.plan(km.copyOf(201), kwh.copyOf(201), 0.0, 90.0, all, settings())
        assertEquals(ChargePlanner.Outcome.NO_STOPS, r.outcome)
        assertEquals(90 - 40 / 70.0 * 100, r.arrivalPct, 1e-6)
    }

    @Test
    fun aLongTripStopsLateAtTheFastestChargerAndOnlyChargesWhatItNeeds() {
        val r = ChargePlanner.plan(km, kwh, 0.0, 90.0, all, settings())
        assertEquals(ChargePlanner.Outcome.PLANNED, r.outcome)
        assertEquals(listOf(c260, c420), r.stops.map { it.charger })
        // Al primero se llega con ~16 % y se carga hasta el máximo (80 %); en el último, solo lo justo para llegar con 15 + 2.
        assertEquals(90 - 52 / 70.0 * 100, r.stops[0].arrivePct, 1e-6)
        assertEquals(80.0, r.stops[0].departPct, 1e-6)
        assertEquals(16 / 70.0 * 100 + 17, r.stops[1].departPct, 1e-6)
        assertEquals(17.0, r.arrivalPct, 1e-6)
        assertTrue(r.arrivalPct >= 15)
        // La línea del % sube en la parada.
        assertEquals(r.stops[0].arrivePct, r.pctAt(260.0 - 1e-9), 1e-3)
        assertEquals(80 - 0.2 / 70 * 100, r.pctAt(261.0), 1e-6)
    }

    @Test
    fun aFastChargerALittleEarlierBeatsOnlySlowOnesAtTheEndOfTheRange() {
        val fast = charger(100.0, 150.0)
        val r = ChargePlanner.plan(km, kwh, 0.0, 90.0, listOf(fast, charger(250.0, 22.0), charger(270.0, 11.0)), settings())
        assertSame(fast, r.stops[0].charger)
    }

    @Test
    fun withoutAReachableChargerThePlanSaysSo() {
        val r = ChargePlanner.plan(km, kwh, 0.0, 90.0, listOf(charger(400.0, 150.0)), settings())
        assertEquals(ChargePlanner.Outcome.NO_CHARGER, r.outcome)
        assertTrue(r.stops.isEmpty())
        assertTrue(r.arrivalPct < 0)
    }

    @Test
    fun theChargingCurveSlowsDownAboveHalfAndOnSlowChargers() {
        val s = settings()
        val fast = ChargePlanner.chargeMinutes(10.0, 80.0, 150.0, s)
        assertTrue("10→80 % a 150 kW: $fast min", fast in 35.0..45.0)
        // De 10 a 50 % va a tope (84 kW): 28 kWh en 20 min.
        assertEquals(28.0 / 84 * 60, ChargePlanner.chargeMinutes(10.0, 50.0, 150.0, s), 0.01)
        assertTrue(ChargePlanner.chargeMinutes(10.0, 80.0, 22.0, s) > 120)
        assertTrue(ChargePlanner.chargeMinutes(80.0, 100.0, 150.0, s) > ChargePlanner.chargeMinutes(30.0, 50.0, 150.0, s))
        assertEquals(0.0, ChargePlanner.chargeMinutes(60.0, 50.0, 150.0, s), 0.0)
    }

    @Test
    fun theTrendOnlyScalesWhatIsLeft() {
        val k = ChargePlanner.scaled(km, kwh, 100.0, 1.5)
        assertEquals(10.0, k[50], 1e-9)
        assertEquals(20.0, k[100], 1e-9)
        assertEquals(20 + 20 * 1.5, k[200], 1e-9)
        assertSame(kwh, ChargePlanner.scaled(km, kwh, 100.0, 1.0))
    }

    @Test
    fun theTrendComparesTheRealDropWithTheForecastOfTheSameKm() {
        val t = ChargePlanner.Trend()
        val route = Any()
        t.observe(route, km, kwh, 10.0, 80.0, 1000.0, 70.0, false, 1)
        // 5 km: aún se fía de la previsión.
        t.observe(route, km, kwh, 15.0, 80 - 1.3 / 70 * 100, 1005.0, 70.0, false, 2)
        assertEquals(1.0, t.factor(), 0.0)
        // 20 km: previstos 4 kWh, gastados 5 → (5 + 3) / (4 + 3).
        t.observe(route, km, kwh, 30.0, 80 - 5.0 / 70 * 100, 1020.0, 70.0, false, 3)
        assertEquals(8.0 / 7, t.factor(), 1e-9)
        // La misma lectura otra vez no cuenta.
        t.observe(route, km, kwh, 31.0, 50.0, 1021.0, 70.0, false, 3)
        assertEquals(8.0 / 7, t.factor(), 1e-9)
        // Una carga cierra el tramo; el siguiente (4 previstos, 4 reales) se suma.
        t.observe(route, km, kwh, 30.0, 85.0, 1020.0, 70.0, true, 4)
        t.observe(route, km, kwh, 30.0, 90.0, 1020.0, 70.0, false, 5)
        t.observe(route, km, kwh, 50.0, 90 - 4.0 / 70 * 100, 1040.0, 70.0, false, 6)
        assertEquals(12.0 / 11, t.factor(), 1e-9)
        assertEquals(8.0, t.predictedKwh(), 1e-9)
        // Otra ruta: de cero.
        t.observe(Any(), km, kwh, 0.0, 70.0, 1040.0, 70.0, false, 7)
        assertEquals(1.0, t.factor(), 0.0)
    }

    @Test
    fun theTrendIsBounded() {
        val t = ChargePlanner.Trend()
        val route = Any()
        t.observe(route, km, kwh, 0.0, 90.0, 0.0, 70.0, false, 1)
        t.observe(route, km, kwh, 50.0, 20.0, 50.0, 70.0, false, 2)
        assertEquals(ChargePlanner.Trend.MAX, t.factor(), 0.0)
    }

    @Test
    fun aChosenStopIsKeptWhileItIsReachableAndChangesWhenTheBatteryRunsShort() {
        val c270 = charger(270.0, 150.0)
        val chargers = all + c270
        val s = settings()
        // Sin plan anterior, desde el km 0 elige el rápido más lejano (270).
        assertSame(c270, ChargePlanner.plan(km, kwh, 0.0, 90.0, chargers, s).stops[0].charger)
        // Con el 260 ya elegido y gastando lo previsto, se mantiene (no baila con cada km).
        val soc100 = 90 - 20 / 70.0 * 100
        val before = ChargePlanner.plan(km, kwh, 100.0, soc100, chargers, s, listOf(c260, c420))
        assertSame(c260, before.stops[0].charger)
        assertEquals(ChargePlanner.Change.NONE, ChargePlanner.change(before, ChargePlanner.plan(km, kwh, 100.0, soc100, chargers, s,
                before.stops.map { it.charger })))
        // Gastando un 30 % más, al 260 se llegaría con un 2 %: parada nueva antes (la rápida del 180) y aviso.
        val now = ChargePlanner.plan(km, ChargePlanner.scaled(km, kwh, 100.0, 1.3), 100.0, soc100, chargers, s,
                before.stops.map { it.charger })
        assertSame(c180, now.stops[0].charger)
        assertEquals(ChargePlanner.Change.CHANGED, ChargePlanner.change(before, now))
        assertSame(c180, ChargePlanner.added(before, now)!!.charger)
    }

    private fun result(outcome: ChargePlanner.Outcome, from: Double, vararg stops: RoutePlanner.Charger) =
        ChargePlanner.Result(outcome, stops.map { ChargePlanner.Stop(it, it.kmAlong, 15.0, 80.0, 20.0, 40.0) }, 20.0, km, kwh, from, 60.0, 70.0)

    @Test
    fun whatChangedBetweenTwoPlans() {
        val two = result(ChargePlanner.Outcome.PLANNED, 0.0, c260, c420)
        // Sobra una parada (se gasta menos): FEWER y cuál.
        val one = result(ChargePlanner.Outcome.PLANNED, 0.0, c420)
        assertEquals(ChargePlanner.Change.FEWER, ChargePlanner.change(two, one))
        assertSame(c260, ChargePlanner.dropped(two, one)!!.charger)
        val none = result(ChargePlanner.Outcome.NO_STOPS, 0.0)
        assertEquals(ChargePlanner.Change.FEWER, ChargePlanner.change(two, none))
        assertNull(none.next())
        // Ya no se llega: NO_CHARGER una vez, no en cada recálculo.
        val fail = result(ChargePlanner.Outcome.NO_CHARGER, 0.0)
        assertEquals(ChargePlanner.Change.NO_CHARGER, ChargePlanner.change(two, fail))
        assertEquals(ChargePlanner.Change.NONE, ChargePlanner.change(fail, fail))
        // Las paradas ya hechas (o en la que se está cargando) no cuentan como cambio.
        val passed = result(ChargePlanner.Outcome.PLANNED, 0.0, c100, c260)
        assertEquals(ChargePlanner.Change.NONE, ChargePlanner.change(passed, result(ChargePlanner.Outcome.PLANNED, 101.0, c260)))
        assertEquals(ChargePlanner.Change.NONE, ChargePlanner.change(passed, result(ChargePlanner.Outcome.PLANNED, 99.0, c260)))
        assertEquals(ChargePlanner.Change.NONE, ChargePlanner.change(null, two))
    }

    @Test
    fun kwhAtInterpolatesAndClamps() {
        assertEquals(0.0, ChargePlanner.kwhAt(km, kwh, -5.0), 0.0)
        assertEquals(25.1, ChargePlanner.kwhAt(km, kwh, 125.5), 1e-9)
        assertEquals(100.0, ChargePlanner.kwhAt(km, kwh, 900.0), 1e-9)
    }
}
