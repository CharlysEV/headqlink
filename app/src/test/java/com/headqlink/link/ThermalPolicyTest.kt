package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Adaptación térmica: sube en el acto, baja tras 30 s por debajo (viaje del 2026-10-05). Niveles: moderado (2), grave
 * (3) y crítico (4 o más); protección Normal, Suave o Apagada.
 */
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
    fun goesUpAtOnceWithCriticalAsItsOwnLevel() {
        val p = ThermalPolicy()
        assertTrue(p.update(2, 0))
        assertEquals(ThermalPolicy.MODERATE, p.level())
        assertTrue(p.update(3, 1 * s))
        assertEquals(ThermalPolicy.SEVERE, p.level())
        assertTrue(p.update(4, 2 * s))
        assertEquals(ThermalPolicy.CRITICAL, p.level())
        // Emergencia y apagado: el mismo nivel que crítico.
        assertFalse(p.update(5, 3 * s))
        assertEquals(ThermalPolicy.CRITICAL, p.level())
        assertEquals(ThermalPolicy.CRITICAL, ThermalPolicy.levelFor(6))
    }

    @Test
    fun backToNormalOnlyAfter30sAtLightOrLess() {
        val p = ThermalPolicy()
        p.update(3, 0)
        assertFalse(p.update(1, 10 * s))
        assertEquals(20 * s, p.msUntilCheck(20 * s))
        assertFalse(p.update(1, 39 * s))
        assertEquals(ThermalPolicy.SEVERE, p.level())
        assertTrue(p.update(1, 40 * s))
        assertEquals(ThermalPolicy.NORMAL, p.level())
        assertEquals(-1L, p.msUntilCheck(40 * s))
    }

    @Test
    fun aRelapseRestartsTheWait() {
        val p = ThermalPolicy()
        p.update(2, 0)
        p.update(1, 10 * s)
        // Vuelve a moderado a los 20 s: la espera empieza de nuevo cuando baje otra vez.
        assertFalse(p.update(2, 20 * s))
        assertFalse(p.update(0, 21 * s))
        assertFalse(p.update(0, 50 * s))
        assertEquals(ThermalPolicy.MODERATE, p.level())
        assertTrue(p.update(0, 51 * s))
        assertEquals(ThermalPolicy.NORMAL, p.level())
    }

    @Test
    fun severeStepsDownToModerateWhileStillModerate() {
        val p = ThermalPolicy()
        p.update(3, 0)
        p.update(2, 5 * s)
        assertTrue(p.update(2, 35 * s))
        assertEquals(ThermalPolicy.MODERATE, p.level())
        // De moderado a normal, otros 30 s con el estado en 0-1.
        p.update(1, 40 * s)
        assertFalse(p.update(1, 69 * s))
        assertTrue(p.update(1, 70 * s))
        assertEquals(ThermalPolicy.NORMAL, p.level())
    }

    @Test
    fun aDipToLightWithinASevereSpellStopsAtTheHighestSeen() {
        val p = ThermalPolicy()
        p.update(3, 0)
        p.update(1, 10 * s)
        p.update(2, 20 * s)
        p.update(1, 30 * s)
        // A los 30 s de la primera bajada: el más alto visto en ese rato fue moderado.
        assertTrue(p.update(1, 40 * s))
        assertEquals(ThermalPolicy.MODERATE, p.level())
        assertEquals(30 * s, p.msUntilCheck(40 * s))
        assertTrue(p.update(1, 70 * s))
        assertEquals(ThermalPolicy.NORMAL, p.level())
    }

    @Test
    fun normalProtectionCapsForTheC10() {
        // C10: 30 fps y 5080320 bps. Moderado no toca los fps de una sesión a 30; grave 24; crítico 20.
        assertEquals(30, ThermalPolicy.fpsCap(ThermalPolicy.NORMAL, 30))
        assertEquals(30, ThermalPolicy.fpsCap(ThermalPolicy.MODERATE, 30))
        assertEquals(24, ThermalPolicy.fpsCap(ThermalPolicy.SEVERE, 30))
        assertEquals(20, ThermalPolicy.fpsCap(ThermalPolicy.CRITICAL, 30))
        assertEquals(5_080_320, ThermalPolicy.bitrateCap(ThermalPolicy.NORMAL, 5_080_320))
        assertEquals(4_064_256, ThermalPolicy.bitrateCap(ThermalPolicy.MODERATE, 5_080_320))
        assertEquals(3_500_000, ThermalPolicy.bitrateCap(ThermalPolicy.SEVERE, 5_080_320))
        assertEquals(3_000_000, ThermalPolicy.bitrateCap(ThermalPolicy.CRITICAL, 5_080_320))
    }

    @Test
    fun capsNeverRaiseTheSession() {
        // Una sesión ya por debajo (Muy bajo: 20 fps, 2,5 Mbps) no sube, y un nivel más alto nunca deja más que el anterior.
        assertEquals(20, ThermalPolicy.fpsCap(ThermalPolicy.MODERATE, 20))
        assertEquals(15, ThermalPolicy.fpsCap(ThermalPolicy.SEVERE, 15))
        assertEquals(15, ThermalPolicy.fpsCap(ThermalPolicy.CRITICAL, 15))
        assertEquals(2_000_000, ThermalPolicy.bitrateCap(ThermalPolicy.MODERATE, 2_500_000))
        assertEquals(2_000_000, ThermalPolicy.bitrateCap(ThermalPolicy.SEVERE, 2_500_000))
        assertEquals(2_000_000, ThermalPolicy.bitrateCap(ThermalPolicy.CRITICAL, 2_500_000))
        // Muy alto (60 fps, 10 Mbps al empezar): a 60 el moderado es 30.
        assertEquals(30, ThermalPolicy.fpsCap(ThermalPolicy.MODERATE, 60))
        assertEquals(8_000_000, ThermalPolicy.bitrateCap(ThermalPolicy.MODERATE, 10_000_000))
        assertEquals(3_500_000, ThermalPolicy.bitrateCap(ThermalPolicy.SEVERE, 10_000_000))
        assertEquals(3_000_000, ThermalPolicy.bitrateCap(ThermalPolicy.CRITICAL, 10_000_000))
    }

    @Test
    fun capsWithFluidity60() {
        // Coche con Fluidez 60: 60 fps y 8 Mbps. Moderado → 30 fps y ×0,8; grave → 24 fps y 3,5 Mbit/s; crítico → 20 y 3.
        assertEquals(60, ThermalPolicy.fpsCap(ThermalPolicy.NORMAL, 60))
        assertEquals(30, ThermalPolicy.fpsCap(ThermalPolicy.MODERATE, 60))
        assertEquals(24, ThermalPolicy.fpsCap(ThermalPolicy.SEVERE, 60))
        assertEquals(20, ThermalPolicy.fpsCap(ThermalPolicy.CRITICAL, 60))
        assertEquals(6_400_000, ThermalPolicy.bitrateCap(ThermalPolicy.MODERATE, 8_000_000))
        assertEquals(3_500_000, ThermalPolicy.bitrateCap(ThermalPolicy.SEVERE, 8_000_000))
        assertEquals(3_000_000, ThermalPolicy.bitrateCap(ThermalPolicy.CRITICAL, 8_000_000))
        // El umbral es la sesión a 60: a 45 (Medio) y a 30 el moderado deja los fps.
        assertEquals(30, ThermalPolicy.moderateFps(60))
        assertEquals(45, ThermalPolicy.moderateFps(45))
        assertEquals(30, ThermalPolicy.moderateFps(30))
        assertTrue(ThermalPolicy.describe(ThermalPolicy.MODERATE, 60).startsWith("30 fps"))
        assertTrue(ThermalPolicy.describe(ThermalPolicy.MODERATE, 30).startsWith("los fps de la sesión"))
        assertTrue(ThermalPolicy.describe(ThermalPolicy.MODERATE).contains("30 fps con la sesión a 60"))
    }

    @Test
    fun softProtectionOnlyLowersTheBitrateAndNeverGoesBelow30FpsUnlessCritical() {
        val soft = ThermalPolicy.MODE_SOFT
        // Sesión a 30: los fps no se tocan hasta crítico.
        assertEquals(30, ThermalPolicy.fpsCap(soft, ThermalPolicy.MODERATE, 30))
        assertEquals(30, ThermalPolicy.fpsCap(soft, ThermalPolicy.SEVERE, 30))
        assertEquals(20, ThermalPolicy.fpsCap(soft, ThermalPolicy.CRITICAL, 30))
        // Sesión a 60: moderado deja los 60; grave, 30 como mucho; crítico, 20.
        assertEquals(60, ThermalPolicy.fpsCap(soft, ThermalPolicy.MODERATE, 60))
        assertEquals(30, ThermalPolicy.fpsCap(soft, ThermalPolicy.SEVERE, 60))
        assertEquals(20, ThermalPolicy.fpsCap(soft, ThermalPolicy.CRITICAL, 60))
        // El bitrate baja igual que en Normal.
        assertEquals(4_064_256, ThermalPolicy.bitrateCap(soft, ThermalPolicy.MODERATE, 5_080_320))
        assertEquals(3_500_000, ThermalPolicy.bitrateCap(soft, ThermalPolicy.SEVERE, 5_080_320))
        assertEquals(3_000_000, ThermalPolicy.bitrateCap(soft, ThermalPolicy.CRITICAL, 5_080_320))
        assertTrue(ThermalPolicy.describe(soft, ThermalPolicy.MODERATE, 30).startsWith("solo el bitrate"))
        assertTrue(ThermalPolicy.describe(soft, ThermalPolicy.SEVERE, 60).startsWith("como mucho 30 fps"))
    }

    @Test
    fun offProtectionChangesNothingAndOnlyLogs() {
        val off = ThermalPolicy.MODE_OFF
        for (level in listOf(ThermalPolicy.MODERATE, ThermalPolicy.SEVERE, ThermalPolicy.CRITICAL)) {
            assertEquals(60, ThermalPolicy.fpsCap(off, level, 60))
            assertEquals(30, ThermalPolicy.fpsCap(off, level, 30))
            assertEquals(8_000_000, ThermalPolicy.bitrateCap(off, level, 8_000_000))
        }
        assertTrue(ThermalPolicy.describe(off, ThermalPolicy.SEVERE, 30).contains("apagada"))
        // Un ajuste desconocido o vacío es Normal.
        assertEquals(ThermalPolicy.MODE_NORMAL, ThermalPolicy.mode(null))
        assertEquals(ThermalPolicy.MODE_NORMAL, ThermalPolicy.mode("lo que sea"))
        assertEquals(ThermalPolicy.MODE_SOFT, ThermalPolicy.mode("suave"))
        assertEquals(24, ThermalPolicy.fpsCap("lo que sea", ThermalPolicy.SEVERE, 30))
    }

    @Test
    fun namesForTheLog() {
        assertEquals("normal", ThermalPolicy.name(ThermalPolicy.NORMAL))
        assertEquals("moderado", ThermalPolicy.name(ThermalPolicy.MODERATE))
        assertEquals("grave", ThermalPolicy.name(ThermalPolicy.SEVERE))
        assertEquals("crítico", ThermalPolicy.name(ThermalPolicy.CRITICAL))
        assertEquals("suave", ThermalPolicy.modeName(ThermalPolicy.MODE_SOFT))
        assertEquals("apagada", ThermalPolicy.modeName(ThermalPolicy.MODE_OFF))
        assertEquals("normal", ThermalPolicy.modeName(""))
        assertTrue(ThermalPolicy.describe(ThermalPolicy.SEVERE), ThermalPolicy.describe(ThermalPolicy.SEVERE).contains("24 fps"))
        assertTrue(ThermalPolicy.describe(ThermalPolicy.CRITICAL), ThermalPolicy.describe(ThermalPolicy.CRITICAL).contains("20 fps"))
    }
}
