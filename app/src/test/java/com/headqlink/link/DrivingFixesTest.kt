package com.headqlink.link

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RoboConfig
import org.robolectric.annotation.ConscryptMode

/**
 * Lo de los viajes largos: radares solo de nuestra vía y sentido, la pausa de Overpass, la nube que no se espacia en
 * marcha, la batería estimada entre lecturas y el plan que sigue la ruta de Google.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@RoboConfig(sdk = [35], application = android.app.Application::class)
class DrivingFixesTest {
    private val lat = 40.0
    private val lon = -3.0
    private val mLat = 1 / 111195.0
    private val mLon = 1 / (111195.0 * Math.cos(Math.toRadians(40.0)))

    @Test
    fun cameraOnAParallelRoadIsNotOurs() {
        // Hacia el norte; radar 800 m delante pero 300 m a la derecha (otra carretera): antes valía (±25°), ahora no.
        assertEquals(-1.0, RoadInfo.aheadOnRoad(lat, lon, 0.0, lat + 800 * mLat, lon + 300 * mLon, Double.NaN), 0.0)
        // En nuestra vía (10 m de lado): sí, a unos 500 m.
        assertEquals(500.0, RoadInfo.aheadOnRoad(lat, lon, 0.0, lat + 500 * mLat, lon + 10 * mLon, Double.NaN), 2.0)
        // Ya pasado: no.
        assertEquals(-1.0, RoadInfo.aheadOnRoad(lat, lon, 0.0, lat - 200 * mLat, lon, Double.NaN), 0.0)
        // Más allá de 1,5 km: todavía no.
        assertEquals(-1.0, RoadInfo.aheadOnRoad(lat, lon, 0.0, lat + 1600 * mLat, lon, Double.NaN), 0.0)
    }

    @Test
    fun cameraForTheOtherCarriagewayIsSkipped() {
        val ahead = lat + 400 * mLat
        assertEquals(-1.0, RoadInfo.aheadOnRoad(lat, lon, 0.0, ahead, lon + 15 * mLon, 180.0), 0.0)
        assertEquals(400.0, RoadInfo.aheadOnRoad(lat, lon, 0.0, ahead, lon - 15 * mLon, 10.0), 2.0)
    }

    @Test
    fun cameraDirectionFromTagsAndWay() {
        assertEquals(90.0, RoadInfo.cameraDirection("", 90.0, 1), 1e-9)
        assertEquals(270.0, RoadInfo.cameraDirection("", 90.0, -1), 1e-9)
        assertEquals(270.0, RoadInfo.cameraDirection("backward", 90.0, 0), 1e-9)
        assertEquals(90.0, RoadInfo.cameraDirection("forward", 90.0, 0), 1e-9)
        assertEquals(45.0, RoadInfo.cameraDirection("NE", Double.NaN, 0), 1e-9)
        assertEquals(135.0, RoadInfo.cameraDirection("135", Double.NaN, 0), 1e-9)
        // Vía de doble sentido sin etiqueta: vale para los dos.
        assertTrue(RoadInfo.cameraDirection("", 90.0, 0).isNaN())
        assertEquals(1, RoadInfo.oneway(JSONObject("{\"highway\":\"motorway\"}")))
        assertEquals(-1, RoadInfo.oneway(JSONObject("{\"highway\":\"primary\",\"oneway\":\"-1\"}")))
        assertEquals(0, RoadInfo.oneway(JSONObject("{\"highway\":\"primary\"}")))
    }

    @Test
    fun overpassPausesInsteadOfWaitingMinutes() {
        assertEquals(30_000L, Http.OverpassGate.pause(1))
        assertEquals(60_000L, Http.OverpassGate.pause(2))
        assertEquals(240_000L, Http.OverpassGate.pause(4))
        assertEquals(300_000L, Http.OverpassGate.pause(9))
        val g = Http.OverpassGate()
        assertTrue(g.open(1_000))
        g.failed(1_000)
        assertFalse(g.open(30_000))
        assertTrue(g.open(31_000))
        g.failed(31_000)
        assertFalse(g.open(90_000))
        g.ok()
        assertTrue(g.open(90_000))
    }

    @Test
    fun cloudKeepsReadingWhileDriving() {
        // Aparcado y sin dato nuevo: se espacia (15 min). En marcha, no: 2 min.
        assertEquals(900_000L, CarCloud.Policy.delayMs(0, false, 3, false))
        assertEquals(120_000L, CarCloud.Policy.delayMs(0, false, 3, true))
        // Tras dos fallos (sin cobertura): 5 min aparcado, 2 min en marcha.
        assertEquals(300_000L, CarCloud.Policy.delayMs(2, false, 0, false))
        assertEquals(120_000L, CarCloud.Policy.delayMs(2, false, 0, true))
    }

    @Test
    fun batteryKeepsGoingDownBetweenCloudReadings() {
        assertEquals(70.0, RoutePlanner.estimatedSoc(80.0, 67.0, 6.7), 1e-9)
        assertEquals(80.0, RoutePlanner.estimatedSoc(80.0, 67.0, 0.0), 1e-9)
        assertEquals(80.0, RoutePlanner.estimatedSoc(80.0, 67.0, -1.0), 1e-9)
        assertEquals(0.0, RoutePlanner.estimatedSoc(5.0, 67.0, 20.0), 1e-9)
    }

    @Test
    fun theRouteThatMatchesGoogleWins() {
        // Google dice 98 km: entre una de 105 y otra de 98,5, la segunda.
        assertTrue(RoutePlanner.matchScore(98_500.0, 98_000.0, 0) < RoutePlanner.matchScore(105_000.0, 98_000.0, 0))
        // Con la misma distancia, la que pasa por las carreteras que anuncia Google.
        assertTrue(RoutePlanner.matchScore(98_000.0, 98_000.0, 2) < RoutePlanner.matchScore(98_000.0, 98_000.0, 0))
        assertEquals("a2", RoutePlanner.norm("A-2"))
        assertEquals("autoviadelnordeste", RoutePlanner.norm("Autovía del Nordeste"))
        val route = JSONObject("{\"legs\":[{\"steps\":[{\"name\":\"Autovía del Nordeste\",\"ref\":\"A-2;E-90\"},{\"name\":\"\",\"ref\":\"\"}]}]}")
        assertEquals(2, RoutePlanner.roadHits(route, setOf("a2", "autoviadelnordeste", "m40")))
    }

    @Test
    fun timesFollowGoogleAndReplanWhenItChanges() {
        assertEquals(1.2, RoutePlanner.timeFactor(3600, 3000), 1e-9)
        assertEquals(0.6, RoutePlanner.timeFactor(100, 3000), 1e-9)
        assertEquals(1.0, RoutePlanner.timeFactor(0, 3000), 1e-9)
        // Quedan 45 km según Google: 50 en el plan aún vale; 60, no (ha cambiado de ruta).
        assertFalse(RoutePlanner.googleDisagrees(50.0, 45.0))
        assertTrue(RoutePlanner.googleDisagrees(60.0, 45.0))
        assertFalse(RoutePlanner.googleDisagrees(10.0, 8.0))
    }

    @Test
    fun googleMapsNotificationGivesRemainingKmAndArrival() {
        val c = java.util.Calendar.getInstance()
        c.set(2026, 9, 11, 20, 47, 0)
        c.set(java.util.Calendar.MILLISECOND, 0)
        val now = c.timeInMillis
        val i = MapsNavListener.parse(36246, 1246, "Llegada: 21:19", "Dirígete hacia una calle", now)
        assertEquals(35000, i.remainingM)
        assertEquals(36246, i.totalM)
        assertEquals(32L, (i.etaMs - now) / 60_000)
        // Pasada la medianoche: 00:10 es de mañana.
        assertTrue(MapsNavListener.eta("Llegada: 0:10", now) > now)
        assertEquals(21 * 60 + 19L, minutesOfDay(MapsNavListener.eta("Arrival: 9:19 PM", now)))
        assertEquals(-1L, MapsNavListener.eta("sin hora", now))
        // Sin barra de progreso: no se sabe lo que falta.
        assertEquals(-1, MapsNavListener.parse(0, 0, "Llegada: 21:19", null, now).remainingM)
    }

    private fun minutesOfDay(ms: Long): Long {
        val c = java.util.Calendar.getInstance()
        c.timeInMillis = ms
        return c.get(java.util.Calendar.HOUR_OF_DAY) * 60L + c.get(java.util.Calendar.MINUTE)
    }
}
