package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Adaptación térmica: sube en el acto, baja tras 60 s por debajo (viaje del 2026-10-05). */
class ThermalPolicyTest {
    private val s = 1_000L

    @Test
    fun noneAndLightChangeNothing() {
        val p = ThermalPolicy()
        assertFalse(p.update(0, 0))
        assertFalse(p.update(1, 10 * s))
        assertEquals(ThermalPolicy.NORMAL, p.level())
        assertEquals(-1L, p.msUntilCheck(10 * s))
    }

    @Test
    fun goesUpAtOnce() {
        val p = ThermalPolicy()
        assertTrue(p.update(2, 0))
        assertEquals(ThermalPolicy.MODERATE, p.level())
        assertTrue(p.update(3, 1 * s))
        assertEquals(ThermalPolicy.SEVERE, p.level())
        // Crítico y más: el mismo nivel que grave.
        assertFalse(p.update(4, 2 * s))
        assertEquals(ThermalPolicy.SEVERE, p.level())
    }

    @Test
    fun backToNormalOnlyAfter60sAtLightOrLess() {
        val p = ThermalPolicy()
        p.update(3, 0)
        assertFalse(p.update(1, 10 * s))
        assertEquals(50 * s, p.msUntilCheck(20 * s))
        assertFalse(p.update(1, 69 * s))
        assertEquals(ThermalPolicy.SEVERE, p.level())
        assertTrue(p.update(1, 70 * s))
        assertEquals(ThermalPolicy.NORMAL, p.level())
        assertEquals(-1L, p.msUntilCheck(70 * s))
    }

    @Test
    fun aRelapseRestartsTheWait() {
        val p = ThermalPolicy()
        p.update(2, 0)
        p.update(1, 10 * s)
        // Vuelve a moderado a los 40 s: la espera empieza de nuevo cuando baje otra vez.
        assertFalse(p.update(2, 40 * s))
        assertFalse(p.update(0, 41 * s))
        assertFalse(p.update(0, 100 * s))
        assertEquals(ThermalPolicy.MODERATE, p.level())
        assertTrue(p.update(0, 101 * s))
        assertEquals(ThermalPolicy.NORMAL, p.level())
    }

    @Test
    fun severeStepsDownToModerateWhileStillModerate() {
        val p = ThermalPolicy()
        p.update(3, 0)
        p.update(2, 5 * s)
        assertTrue(p.update(2, 65 * s))
        assertEquals(ThermalPolicy.MODERATE, p.level())
        // De moderado a normal, otros 60 s con el estado en 0-1.
        p.update(1, 70 * s)
        assertFalse(p.update(1, 129 * s))
        assertTrue(p.update(1, 130 * s))
        assertEquals(ThermalPolicy.NORMAL, p.level())
    }

    @Test
    fun aDipToLightWithinASevereSpellStopsAtTheHighestSeen() {
        val p = ThermalPolicy()
        p.update(3, 0)
        p.update(1, 10 * s)
        p.update(2, 30 * s)
        p.update(1, 50 * s)
        // A los 60 s de la primera bajada: el más alto visto en ese rato fue moderado.
        assertTrue(p.update(1, 70 * s))
        assertEquals(ThermalPolicy.MODERATE, p.level())
        assertEquals(60 * s, p.msUntilCheck(70 * s))
        assertTrue(p.update(1, 130 * s))
        assertEquals(ThermalPolicy.NORMAL, p.level())
    }

    @Test
    fun capsForTheC10() {
        // C10: 30 fps y 5080320 bps.
        assertEquals(30, ThermalPolicy.fpsCap(ThermalPolicy.NORMAL, 30))
        assertEquals(24, ThermalPolicy.fpsCap(ThermalPolicy.MODERATE, 30))
        assertEquals(20, ThermalPolicy.fpsCap(ThermalPolicy.SEVERE, 30))
        assertEquals(5_080_320, ThermalPolicy.bitrateCap(ThermalPolicy.NORMAL, 5_080_320))
        assertEquals(3_556_224, ThermalPolicy.bitrateCap(ThermalPolicy.MODERATE, 5_080_320))
        assertEquals(3_000_000, ThermalPolicy.bitrateCap(ThermalPolicy.SEVERE, 5_080_320))
    }

    @Test
    fun capsNeverRaiseTheSession() {
        // Una sesión ya por debajo (Muy bajo: 20 fps, 2,5 Mbps) no sube, y grave nunca deja más que moderado.
        assertEquals(20, ThermalPolicy.fpsCap(ThermalPolicy.MODERATE, 20))
        assertEquals(15, ThermalPolicy.fpsCap(ThermalPolicy.SEVERE, 15))
        assertEquals(1_750_000, ThermalPolicy.bitrateCap(ThermalPolicy.MODERATE, 2_500_000))
        assertEquals(1_750_000, ThermalPolicy.bitrateCap(ThermalPolicy.SEVERE, 2_500_000))
        // Muy alto (60 fps, 10 Mbps al empezar): a 60 el moderado es 30, no 24.
        assertEquals(30, ThermalPolicy.fpsCap(ThermalPolicy.MODERATE, 60))
        assertEquals(7_000_000, ThermalPolicy.bitrateCap(ThermalPolicy.MODERATE, 10_000_000))
        assertEquals(3_000_000, ThermalPolicy.bitrateCap(ThermalPolicy.SEVERE, 10_000_000))
    }

    @Test
    fun capsWithFluidity60() {
        // Coche con Fluidez 60: 60 fps y 8 Mbps. Moderado → 30 fps (lo que pide el coche) y ×0,7; grave → 20 fps y 3 Mbit/s.
        assertEquals(60, ThermalPolicy.fpsCap(ThermalPolicy.NORMAL, 60))
        assertEquals(30, ThermalPolicy.fpsCap(ThermalPolicy.MODERATE, 60))
        assertEquals(20, ThermalPolicy.fpsCap(ThermalPolicy.SEVERE, 60))
        assertEquals(5_600_000, ThermalPolicy.bitrateCap(ThermalPolicy.MODERATE, 8_000_000))
        assertEquals(3_000_000, ThermalPolicy.bitrateCap(ThermalPolicy.SEVERE, 8_000_000))
        // El umbral es la sesión a 60: a 45 (Medio) sigue siendo 24.
        assertEquals(30, ThermalPolicy.moderateFps(60))
        assertEquals(24, ThermalPolicy.moderateFps(45))
        assertEquals(24, ThermalPolicy.moderateFps(30))
        assertTrue(ThermalPolicy.describe(ThermalPolicy.MODERATE, 60).startsWith("30 fps"))
        assertTrue(ThermalPolicy.describe(ThermalPolicy.MODERATE, 30).startsWith("24 fps"))
        assertTrue(ThermalPolicy.describe(ThermalPolicy.MODERATE).contains("24"))
        assertTrue(ThermalPolicy.describe(ThermalPolicy.MODERATE).contains("30"))
    }

    @Test
    fun namesForTheLog() {
        assertEquals("normal", ThermalPolicy.name(ThermalPolicy.NORMAL))
        assertEquals("moderado", ThermalPolicy.name(ThermalPolicy.MODERATE))
        assertEquals("grave", ThermalPolicy.name(ThermalPolicy.SEVERE))
        assertTrue(ThermalPolicy.describe(ThermalPolicy.SEVERE), ThermalPolicy.describe(ThermalPolicy.SEVERE).contains("20 fps"))
    }
}
