package com.headqlink.link

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * Historial oficial de la nube: leer los viajes (sin los registros vacíos), el consumo semanal, juntar lo guardado con
 * lo nuevo, cruzarlo con los viajes de HeadQLink y la caché.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class)
class CloudHistoryTest {
    private val t0 = 1791297600000L // 2026-10-07 12:00 UTC

    private fun row(startMin: Int, endMin: Int, km: Any?, kwh: Any?, oil: Any? = null) = JSONObject().apply {
        put("routeStartTs", t0 + startMin * 60_000L)
        put("routeEndTs", t0 + endMin * 60_000L)
        put("totalMileage", km ?: JSONObject.NULL)
        put("totalEnergy", kwh ?: JSONObject.NULL)
        if (oil != null) put("driveReevOil", oil)
        put("maxSpeed", 87)
        put("vin", "VIN000000000000A1")
    }

    @Test
    fun parsesTripsAndDropsEmptyRecords() {
        val data = JSONObject().put("list", org.json.JSONArray()
            .put(row(0, 12, 7.0, 0.74))
            .put(row(30, 40, 7.1, "1.64", 0))
            .put(row(50, 51, 0, 0)) // vacío: fuera
            .put(row(60, 90, 0, 0, 0.21)) // el generador con el coche parado: se queda
            .put(row(100, 90, 3.0, 0.5)) // al revés: fuera
            .put(row(120, 130, -1, 0.5))) // negativo: fuera
        val trips = CloudHistory.parsePage(data)
        assertEquals(3, trips.size)
        assertEquals(7.0, trips[0].km, 1e-9)
        assertEquals(0.74, trips[0].kwh, 1e-9)
        assertTrue(trips[0].fuelL.isNaN())
        assertEquals(1.64, trips[1].kwh, 1e-9)
        assertEquals(0.0, trips[1].fuelL, 1e-9)
        assertEquals(0.21, trips[2].fuelL, 1e-9)
        assertEquals(87.0, trips[0].maxKmh, 1e-9)
    }

    @Test
    fun pagesFromTheFirstAnswer() {
        assertEquals(3, CloudHistory.pages(JSONObject().put("totalPage", 3), 5))
        assertEquals(5, CloudHistory.pages(JSONObject().put("totalPage", 12), 5))
        assertEquals(1, CloudHistory.pages(JSONObject().put("totalPage", 0), 5))
    }

    @Test
    fun weeklyConsumption() {
        val w = CloudHistory.parseWeekly(JSONObject("""{"rankResult":{"result":0,"rank":"12%","hundredKmEC":"16.8"},
            "weeklyEC":[{"hundredKmEC":15.9},{"hundredKmEC":"0"},{"hundredKmEC":17.4}]}"""))
        assertNotNull(w)
        assertEquals(16.8, w!!.avgKwhPer100, 1e-9)
        assertEquals(3, w.weeks.size)
        assertTrue(w.weeks[1].isNaN()) // 0 no es un consumo
        assertNull(CloudHistory.parseWeekly(JSONObject("""{"rankResult":{"hundredKmEC":null},"weeklyEC":[]}""")))
    }

    @Test
    fun mergeKeepsTheNewestAndDropsTheOld() {
        val old = listOf(CloudHistory.Trip(t0 - 40L * 86_400_000, t0, 1.0, 0.2, Double.NaN, 50.0),
            CloudHistory.Trip(t0, t0 + 600_000, 7.0, 1.0, Double.NaN, 50.0))
        val fresh = listOf(CloudHistory.Trip(t0, t0 + 600_000, 7.0, 1.2, Double.NaN, 50.0),
            CloudHistory.Trip(t0 + 3_600_000, t0 + 4_200_000, 5.0, 0.8, Double.NaN, 50.0))
        val m = CloudHistory.merge(old, fresh, t0 - CloudHistory.KEEP_MS)
        assertEquals(2, m.size)
        assertEquals(t0 + 3_600_000, m[0].startMs) // más reciente primero
        assertEquals(1.2, m[1].kwh, 1e-9) // lo nuevo manda
    }

    @Test
    fun matchSumsTheCarTripsInsideOneHeadQLinkTrip() {
        // La tarde del 7 de octubre: ida, parada de 15 min con el coche encendido, vuelta. HeadQLink lo vio como un viaje.
        val cloud = listOf(CloudHistory.Trip(t0 + 2 * 60_000, t0 + 12 * 60_000, 7.0, 0.74, Double.NaN, 60.0),
            CloudHistory.Trip(t0 + 28 * 60_000, t0 + 37 * 60_000, 7.0, 1.64, Double.NaN, 70.0),
            CloudHistory.Trip(t0 + 300 * 60_000, t0 + 320 * 60_000, 20.0, 3.0, Double.NaN, 90.0)) // otro viaje
        val m = CloudHistory.match(cloud, t0, t0 + 40 * 60_000)!!
        assertEquals(2, m.trips)
        assertEquals(14.0, m.km, 1e-9)
        assertEquals(2.38 / 14 * 100, m.kwhPer100(), 1e-9)
        assertTrue(m.litersPer100().isNaN())
        assertNull(CloudHistory.match(cloud, t0 + 100 * 60_000, t0 + 120 * 60_000))
    }

    @Test
    fun matchAddsUpPetrol() {
        val cloud = listOf(CloudHistory.Trip(t0, t0 + 60 * 60_000, 77.0, 14.0, 4.9, 120.0))
        val m = CloudHistory.match(cloud, t0 - 60_000, t0 + 61 * 60_000)!!
        assertEquals(4.9 / 77 * 100, m.litersPer100(), 1e-9)
    }

    @Test
    fun cacheRoundTrip() {
        val trips = listOf(CloudHistory.Trip(t0, t0 + 600_000, 7.0, 1.6, 0.3, 77.0),
            CloudHistory.Trip(t0 - 86_400_000, t0 - 86_000_000, 3.0, 0.5, Double.NaN, Double.NaN))
        val o = CloudHistory.toJson(trips, CloudHistory.Weekly(16.8, doubleArrayOf(15.9, Double.NaN)), t0, t0 - 5)
        val back = CloudHistory.tripsFromJson(JSONObject(o.toString()))
        assertEquals(2, back.size)
        assertEquals(0.3, back[0].fuelL, 1e-9)
        assertTrue(back[1].fuelL.isNaN())
        assertTrue(back[1].maxKmh.isNaN())
        val w = CloudHistory.weeklyFromJson(JSONObject(o.toString()))!!
        assertEquals(16.8, w.avgKwhPer100, 1e-9)
        assertTrue(w.weeks[1].isNaN())
    }

    @Test
    fun logLineIsASummary() {
        val trips = listOf(CloudHistory.Trip(t0, t0 + 600_000, 7.0, 1.6, Double.NaN, 77.0))
        assertEquals("1 viajes, 7 km, 1.6 kWh (22.9 kWh/100 km)", CloudHistory.logLine(trips, t0 - 1))
        assertEquals("0 viajes", CloudHistory.logLine(trips, t0 + 1))
    }

    @Test
    fun aBevHasNoPetrol() {
        val bev = CloudHistory.withoutFuel(listOf(CloudHistory.Trip(t0, t0 + 600_000, 7.0, 1.6, 0.0, 77.0)))
        assertTrue(bev[0].fuelL.isNaN())
        assertEquals(1.6, bev[0].kwh, 1e-9)
        assertTrue(CloudHistory.match(bev, t0 - 1, t0 + 700_000)!!.fuelL.isNaN())
    }
}
