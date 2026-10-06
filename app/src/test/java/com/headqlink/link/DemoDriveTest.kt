package com.headqlink.link

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Trayecto, ruta y viajes del modo demostración: deterministas y verosímiles. */
class DemoDriveTest {
    @Test
    fun sameSeedGivesTheSameDrive() {
        val a = DemoDrive(DemoDrive.SEED)
        val b = DemoDrive(DemoDrive.SEED)
        assertArrayEquals(a.speedKmh, b.speedKmh, 0f)
        assertArrayEquals(a.latG, b.latG, 0f)
        assertArrayEquals(a.lat, b.lat, 0.0)
        assertArrayEquals(a.altM, b.altM, 0f)
        val c = DemoDrive(DemoDrive.SEED + 1)
        // Otra semilla: el mismo guion con otro ruido.
        assertNotEquals(a.speedKmh.toList(), c.speedKmh.toList())
    }

    @Test
    fun theScriptCoversTheInterestingCases() {
        val d = DemoDrive()
        val maxKmh = d.speedKmh.maxOrNull()!!
        assertTrue("llega a 120 km/h: $maxKmh", maxKmh in 115f..125f)
        assertEquals("empieza parado", 0f, d.speedKmh[0], 0.01f)
        assertTrue("frenazo de ~0,45 g", d.longG.minOrNull()!! < -0.4f)
        assertTrue("curvas a la izquierda y a la derecha", d.latG.maxOrNull()!! > 0.3f && d.latG.minOrNull()!! < -0.18f)
        val climb = (1 until d.n).sumOf { i -> maxOf(0.0, (d.altM[i] - d.altM[i - 1]).toDouble()) }
        assertTrue("sube más de 100 m: $climb", climb > 100)
        // Aceleraciones realistas: nunca un salto mayor que el tirón máximo (más el ruido).
        for (i in 1 until d.n) assertTrue(abs(d.longG[i] - d.longG[i - 1]) < 0.12f)
        assertTrue(d.maneuvers.size >= 4)
    }

    @Test
    fun navigationCountsDownToTheNextManeuver() {
        val d = DemoDrive()
        val k1 = d.index(560.0)
        val k2 = d.index(570.0)
        val n1 = d.nav(k1)
        val n2 = d.nav(k2)
        assertEquals("salida de autovía a la derecha", 45, n1.angle)
        assertTrue(n2.stepMeters < n1.stepMeters)
        assertTrue(n1.lanes!![2] && !n1.lanes!![0])
    }

    @Test
    fun routeAndTripsAreReproducible() {
        val p1 = DemoDrive.plan(DemoDrive.SEED)
        val p2 = DemoDrive.plan(DemoDrive.SEED)
        assertArrayEquals(p1.elev, p2.elev, 0.0)
        assertArrayEquals(p1.kwhCum, p2.kwhCum, 0.0)
        assertEquals(531.0, p1.totalKm, 0.001)
        // Consumo de la ruta en lo razonable para el C10 (≈ 15-24 kWh/100 km).
        val per100 = p1.kwhCum[p1.n - 1] / p1.totalKm * 100
        assertTrue("$per100", per100 in 13.0..24.0)
        assertTrue(p1.chargers.size >= 6)
        val t1 = DemoDrive.trips(DemoDrive.SEED)
        val t2 = DemoDrive.trips(DemoDrive.SEED)
        assertEquals(7, t1.size)
        for (i in t1.indices) {
            assertEquals(t1[i].startMs, t2[i].startMs)
            assertEquals(t1[i].kwh, t2[i].kwh, 0.0)
            assertEquals(t1[i].track.size, t2[i].track.size)
        }
        // Del más reciente al más antiguo, todos en las dos semanas anteriores.
        for (i in 1 until t1.size) assertTrue(t1[i].startMs < t1[i - 1].startMs)
        assertTrue(DemoDrive.BASE_MS - t1.last().startMs < 14L * 86_400_000)
    }
}
