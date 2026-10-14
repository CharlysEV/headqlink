package com.headqlink.link

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * Viajes de varios tramos (paradas intermedias, ida y vuelta), cargar en el destino, sitios para comer junto a las
 * paradas y el «¿llego a Casa?» del widget. Coordenadas inventadas.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class)
class TripPlanTest {
    private fun place(name: String, lat: Double, lon: Double) = RoutePlanner.Place().apply {
        this.name = name
        this.lat = lat
        this.lon = lon
    }

    // ------------------------------------------------------------------ tramos

    @Test
    fun aRoundTripGoesThroughTheStopsToTheDestinationAndBack() {
        val origin = place("Salida", 40.0, -3.0)
        val v1 = place("Uno", 40.2, -3.2)
        val dest = place("Destino", 40.5, -3.5)
        assertEquals(listOf(v1, dest, origin), RoutePlanner.tripPoints(origin, listOf(v1), dest, true))
        assertEquals(listOf(v1, dest), RoutePlanner.tripPoints(origin, listOf(v1), dest, false))
        assertEquals(listOf(dest), RoutePlanner.tripPoints(origin, null, dest, false))
        assertEquals("-3.000000,40.000000;-3.200000,40.200000;-3.500000,40.500000;-3.000000,40.000000",
            RoutePlanner.osrmCoords(40.0, -3.0, RoutePlanner.tripPoints(origin, listOf(v1), dest, true)))
    }

    @Test
    fun legEndsAndWhatIsLeftAfterPassingAStop() {
        assertArrayEquals(doubleArrayOf(1.0, 3.5, 6.0), RoutePlanner.legEndsKm(doubleArrayOf(1000.0, 2500.0, 2500.0)), 1e-9)
        val a = place("A", 40.1, -3.0)
        val b = place("B", 40.2, -3.0)
        val c = place("C", 40.0, -3.0)
        val km = doubleArrayOf(50.0, 100.0, 150.0)
        assertEquals(listOf(a, b, c), RoutePlanner.remaining(listOf(a, b, c), km, 0.0))
        assertEquals(listOf(b, c), RoutePlanner.remaining(listOf(a, b, c), km, 60.0))
        // Al final solo queda la vuelta (aunque ya se esté casi allí: el último siempre).
        assertEquals(listOf(c), RoutePlanner.remaining(listOf(a, b, c), km, 149.9))
    }

    @Test
    fun theSegmentsOfAllLegsGoTogether() {
        // Dos tramos de OSRM: el trazado tiene un punto más que los tramos de los dos juntos.
        val coords = JSONArray("[[-3.0,40.0],[-3.0,40.001],[-3.0,40.002],[-3.0,40.003]]")
        val route = JSONObject("""{"duration":60,"legs":[
            {"annotation":{"distance":[100,200],"duration":[10,20]}},
            {"annotation":{"distance":[300],"duration":[30]}}]}""")
        val a = RoutePlanner.annotations(route, coords)
        assertArrayEquals(doubleArrayOf(100.0, 200.0, 300.0), a[0], 1e-9)
        assertArrayEquals(doubleArrayOf(10.0, 20.0, 30.0), a[1], 1e-9)
        // Si no cuadran con el trazado: la línea recta entre los puntos y el tiempo repartido.
        val odd = JSONObject("""{"duration":60,"legs":[{"annotation":{"distance":[100],"duration":[10]}}]}""")
        val b = RoutePlanner.annotations(odd, coords)
        assertEquals(3, b[0].size)
        assertEquals(111.2, b[0][0], 1.0)
        assertEquals(60.0, b[1].sum(), 1e-6)
    }

    @Test
    fun theChargingPlanCarriesTheBatteryFromLegToLeg() {
        // Ida y vuelta de 150 + 150 km, llano, a 20 kWh/100 km, 70 kWh, salida con el 90 %, sin cargadores.
        val km = DoubleArray(301) { it.toDouble() }
        val kwh = DoubleArray(301) { it * 0.2 }
        val s = ChargePlanner.Settings().apply { capacityKwh = 70.0 }
        val r = ChargePlanner.plan(km, kwh, 0.0, 90.0, emptyList(), s)
        val at = ChargePlanner.arrivals(r, doubleArrayOf(150.0, 300.0))
        assertEquals(90 - 30 / 70.0 * 100, at[0], 1e-6)
        assertEquals(90 - 60 / 70.0 * 100, at[1], 1e-6)
        // Volver pide lo de la vuelta más el mínimo al llegar.
        assertEquals(30 / 70.0 * 100 + 15, ChargePlanner.neededFrom(r, 150.0, 15.0), 1e-6)
        // Con un cargador en la vuelta, el plan para en él y se llega con margen.
        val ch = RoutePlanner.Charger().apply { name = "vuelta"; kmAlong = 200.0; maxKw = 150.0 }
        val r2 = ChargePlanner.plan(km, kwh, 0.0, 90.0, listOf(ch), s)
        assertEquals(ChargePlanner.Outcome.PLANNED, r2.outcome)
        assertTrue(ChargePlanner.arrivals(r2, doubleArrayOf(300.0))[0] >= 15 - 1e-6)
    }

    // ------------------------------------------------------------------ cargador en el destino

    @Test
    fun chargeAtTheDestinationWhenArrivingLowOrWhenTheWayBackNeedsIt() {
        assertFalse(ChargePlanner.destChargeAdvised(47.0, 20.0, Double.NaN))
        assertTrue(ChargePlanner.destChargeAdvised(12.0, 20.0, Double.NaN))
        assertFalse(ChargePlanner.destChargeAdvised(12.0, 0.0, Double.NaN)) // umbral apagado
        // De ida y vuelta: con 47 % no basta si volver pide 58 %, aunque esté por encima del umbral.
        assertTrue(ChargePlanner.destChargeAdvised(47.0, 20.0, 58.0))
        assertTrue(ChargePlanner.destChargeAdvised(47.0, 0.0, 58.0))
        assertFalse(ChargePlanner.destChargeAdvised(60.0, 20.0, 58.0))
        assertFalse(ChargePlanner.destChargeAdvised(Double.NaN, 20.0, 58.0))
    }

    // ------------------------------------------------------------------ navegar con las paradas

    @Test
    fun mapsGetsTheNextPointsInRouteOrder() {
        val pts = listOf(10.0, 50.0, 30.0, 80.0, 120.0, 5.0).map { doubleArrayOf(40.0, -3.0, it) }
        assertEquals(listOf(10.0, 30.0, 50.0, 80.0), NavApps.ahead(pts, 8.0, 3).map { it[2] })
        assertEquals(listOf(120.0), NavApps.ahead(pts, 100.0, 3).map { it[2] })
        assertTrue(NavApps.ahead(pts, 200.0, 3).isEmpty())
    }

    // ------------------------------------------------------------------ comer junto a las paradas

    @Test
    fun foodWithin300MetresOfEachStop() {
        val a = RoutePlanner.Charger().apply { id = "osm:node1"; lat = 40.0; lon = -3.0 }
        val b = RoutePlanner.Charger().apply { id = "dgt:2"; lat = 40.1; lon = -3.1 }
        val q = FoodNearby.query(listOf(a, b))
        assertTrue(q.contains("around:300,40.00000,-3.00000"))
        assertTrue(q.contains("restaurant|cafe|fast_food"))
        val els = JSONArray("""[
            {"type":"node","lat":40.0010,"lon":-3.0,"tags":{"amenity":"cafe","name":"Café Ejemplo"}},
            {"type":"way","center":{"lat":40.0020,"lon":-3.0},"tags":{"amenity":"restaurant"}},
            {"type":"node","lat":40.0050,"lon":-3.0,"tags":{"amenity":"restaurant","name":"Lejos"}}]""")
        val got = FoodNearby.assign(els, listOf(a, b), FoodNearby.RADIUS_M, 1000L)
        assertEquals(2, got["osm:node1"]!!.count)
        assertEquals("Café Ejemplo", got["osm:node1"]!!.name)
        assertEquals(0, got["dgt:2"]!!.count) // también se guarda que no hay
        assertEquals(1000L, got["dgt:2"]!!.atMs)
        val st = ChargePlanner.Stop(a, 100.0, 10.0, 60.0, 25.0, 30.0)
        assertTrue(ChargePlanner.longStop(st))
        assertFalse(ChargePlanner.longStop(ChargePlanner.Stop(a, 100.0, 10.0, 20.0, 8.0, 5.0)))
    }

    // ------------------------------------------------------------------ ¿llego a Casa? (widget)

    @Test
    fun homeEstimateFromAStraightLineIsMarkedApproximate() {
        // 50 km en línea recta → ≈65 km por carretera a 20 kWh/100: 13 kWh de 70 → 18,6 %.
        val e = HomeReach.estimate(60.0, 70.0, 20.0, 50.0, Double.NaN, Double.NaN, HomeReach.RESERVE_PCT)!!
        assertTrue(e.approx)
        assertEquals(65.0, e.km, 1e-9)
        assertEquals(60 - 13 / 70.0 * 100, e.arrivePct, 1e-9)
        assertFalse(e.needCharge)
        // Con la ruta calculada: su distancia y su energía, sin «≈».
        val r = HomeReach.estimate(60.0, 70.0, 20.0, 50.0, 70.0, 15.0, HomeReach.RESERVE_PCT)!!
        assertFalse(r.approx)
        assertEquals(60 - 15 / 70.0 * 100, r.arrivePct, 1e-9)
        // Sin consumo del coche: el supuesto.
        val d = HomeReach.estimate(60.0, 70.0, Double.NaN, 50.0, Double.NaN, Double.NaN, 10.0)!!
        assertEquals(60 - 65 * HomeReach.DEFAULT_KWH_100 / 100 / 70 * 100, d.arrivePct, 1e-9)
    }

    @Test
    fun homeEstimateSaysChargeWhenItWouldArriveBelowTheReserve() {
        val e = HomeReach.estimate(15.0, 70.0, 20.0, 50.0, Double.NaN, Double.NaN, HomeReach.RESERVE_PCT)!!
        assertTrue(e.needCharge)
        // Ya en Casa, o sin el % del coche: nada.
        assertNull(HomeReach.estimate(60.0, 70.0, 20.0, 0.5, Double.NaN, Double.NaN, 10.0))
        assertNull(HomeReach.estimate(Double.NaN, 70.0, 20.0, 50.0, Double.NaN, Double.NaN, 10.0))
    }

    @Test
    fun theRouteHomeIsUsedOnlyFromNearWhereItStarted() {
        val now = 10L * 86_400_000L
        // Salió a ~1 km de aquí hacia Casa, hace un día: vale.
        assertTrue(HomeReach.routeFits(40.0, -3.0, 40.5, -3.5, now - 86_400_000L, 40.009, -3.0, 40.5, -3.5, now))
        // Desde a ~5,5 km: no.
        assertFalse(HomeReach.routeFits(40.0, -3.0, 40.5, -3.5, now - 86_400_000L, 40.05, -3.0, 40.5, -3.5, now))
        // A otro sitio (Casa ha cambiado): no.
        assertFalse(HomeReach.routeFits(40.0, -3.0, 40.5, -3.5, now - 86_400_000L, 40.0, -3.0, 40.6, -3.5, now))
        // Demasiado vieja: no.
        assertFalse(HomeReach.routeFits(40.0, -3.0, 40.5, -3.5, now - HomeReach.ROUTE_MAX_AGE_MS - 1, 40.0, -3.0, 40.5, -3.5, now))
        val j = JSONObject(HomeReach.routeJson(40.0, -3.0, 40.5, -3.5, 70.0, 15.0, 5L))
        assertEquals(70.0, j.getDouble("km"), 1e-9)
        assertEquals(5L, j.getLong("at"))
    }

    @Test
    fun theWidgetRepaintsWhenTheHomeLineChanges() {
        val a = LinkGlance.Input()
        val b = LinkGlance.Input().apply { home = "Casa: llegas con ~34 %" }
        val ga = LinkGlance.of(a)
        val gb = LinkGlance.of(b)
        assertEquals("", ga.home)
        assertNotEquals(ga.signature(), gb.signature())
        assertFalse(gb.sameButVideo(ga))
    }
}
