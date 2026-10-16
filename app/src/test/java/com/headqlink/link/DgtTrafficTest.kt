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

/** Incidencias de tráfico de la DGT (DATEX II 3.7): lectura, tipo, y cuáles caen sobre la ruta y en nuestro sentido. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class)
class DgtTrafficTest {
    private fun situation(id: String, type: String, details: String, road: String, km: String, dir: String, coords: String, status: String = "active") =
        """<sit:situation id="$id"><sit:overallSeverity>high</sit:overallSeverity>
          <sit:situationRecord xsi:type="sit:$type" id="r$id" version="1">
            <sit:validity><com:validityStatus>$status</com:validityStatus></sit:validity>
            $details
            <sit:locationReference><loc:supplementaryPositionalDescription><loc:roadInformation><loc:roadName>$road</loc:roadName>
            </loc:roadInformation></loc:supplementaryPositionalDescription>
            <loc:tpegLinearLocation><loc:tpegDirection>$dir</loc:tpegDirection>$coords
            <lse:kilometerPoint>$km</lse:kilometerPoint></loc:tpegLinearLocation></sit:locationReference>
          </sit:situationRecord></sit:situation>"""

    private fun point(lat: Double, lon: Double) =
        "<loc:point><loc:pointCoordinates><loc:latitude>$lat</loc:latitude><loc:longitude>$lon</loc:longitude></loc:pointCoordinates></loc:point>"

    private val xml = """<?xml version="1.0" encoding="UTF-8"?>
        <d2:payload xmlns:d2="a" xmlns:sit="b" xmlns:loc="c" xmlns:com="d" xmlns:lse="e" xmlns:xsi="f">""" +
        situation("1", "GenericSituationRecord",
            "<sit:cause><sit:causeType>roadMaintenance</sit:causeType><sit:detailedCauseType><sit:roadMaintenanceType>roadworks</sit:roadMaintenanceType></sit:detailedCauseType></sit:cause><sit:roadOrCarriagewayOrLaneManagementType>laneClosures</sit:roadOrCarriagewayOrLaneManagementType>",
            "A-4", "243.5", "southBound", point(40.30, -3.70) + point(40.29, -3.70)) +
        situation("2", "VehicleObstruction",
            "<sit:cause><sit:causeType>vehicleObstruction</sit:causeType><sit:detailedCauseType><sit:vehicleObstructionType>vehicleStuck</sit:vehicleObstructionType></sit:detailedCauseType></sit:cause>",
            "A-4", "250", "northBound", point(40.25, -3.70)) +
        situation("3", "Accident",
            "<sit:cause><sit:causeType>accident</sit:causeType></sit:cause>",
            "A-4", "260", "southBound", point(40.20, -3.70)) +
        situation("4", "AbnormalTraffic", "<sit:abnormalTrafficType>slowTraffic</sit:abnormalTrafficType>",
            "M-40", "10", "eastBound", point(40.40, -3.60)) +
        situation("5", "Accident", "<sit:cause><sit:causeType>accident</sit:causeType></sit:cause>",
            "A-4", "270", "southBound", point(40.15, -3.70), status = "suspended") +
        "</d2:payload>"

    @Test
    fun parsesKindsRoadsAndDirections() {
        val all = DgtTraffic.parse(xml.byteInputStream())
        assertEquals("la suspendida no cuenta", 4, all.size)
        val works = all.first { it.id == "1" }
        assertEquals(DgtTraffic.Kind.ROADWORKS, works.kind)
        assertTrue(works.laneClosed)
        assertEquals("A-4", works.road)
        assertEquals(243.5, works.km, 0.01)
        assertEquals(180.0, works.heading, 0.0)
        assertEquals(40.30, works.lat1, 1e-9)
        assertEquals(40.29, works.lat2, 1e-9)
        assertEquals(DgtTraffic.Kind.VEHICLE_STOPPED, all.first { it.id == "2" }.kind)
        assertEquals(DgtTraffic.Kind.ACCIDENT, all.first { it.id == "3" }.kind)
        assertEquals(DgtTraffic.Kind.CONGESTION, all.first { it.id == "4" }.kind)
        assertTrue(all.first { it.id == "3" }.serious())
        assertFalse(works.serious())
    }

    @Test
    fun onlyThoseOnTheRouteAndInOurDirection() {
        val all = DgtTraffic.parse(xml.byteInputStream())
        // Ruta hacia el sur por el meridiano -3.70, de 40.32 a 40.10 (unos 24 km).
        val n = 23
        val lat = DoubleArray(n) { 40.32 - it * 0.01 }
        val lon = DoubleArray(n) { -3.70 }
        val km = DoubleArray(n) { it * 1.1054 }
        val on = DgtTraffic.onRoute(all, lat, lon, km, n)
        // Las obras y el accidente (hacia el sur); no el vehículo de la calzada de enfrente (hacia el norte) ni la M-40.
        assertEquals(listOf("1", "3"), on.map { it.inc.id })
        assertEquals(2.2, on[0].kmAlong, 0.05)
        assertEquals(13.3, on[1].kmAlong, 0.05)
        // Pasado el km 5, solo queda el accidente, a 8,3 km.
        val ahead = DgtTraffic.ahead(on, 5.0)
        assertEquals(1, ahead.size)
        assertEquals(8.3, ahead[0].km, 0.05)
    }

    @Test
    fun headings() {
        assertEquals(90.0, DgtTraffic.heading("eastBound"), 0.0)
        assertEquals(315.0, DgtTraffic.heading("northWestBound"), 0.0)
        assertTrue(DgtTraffic.heading("unknown").isNaN())
    }
}
