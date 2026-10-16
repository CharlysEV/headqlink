package com.headqlink.link

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Un viaje cortado por un reinicio de la conexión sigue si vuelve en menos de 10 minutos. */
class TripCarryTest {
    @Test
    fun aTripContinuesWithinTenMinutes() {
        val t = 1_760_000_000_000L
        assertTrue(TripCarry.fresh(t, t + 30_000))
        assertTrue(TripCarry.fresh(t, t + 10 * 60_000))
        assertFalse(TripCarry.fresh(t, t + 10 * 60_000 + 1))
        assertFalse(TripCarry.fresh(0, t))
        // Un reloj que va hacia atrás más de un minuto: no se fía.
        assertFalse(TripCarry.fresh(t, t - 2 * 60_000))
    }

    @Test
    fun theEnergyBreakdownSurvivesTheCut() {
        val a = EnergyBreakdown()
        a.fromArray(doubleArrayOf(1.0, 2.0, 0.5, 0.3, 0.2, 0.1, 0.7))
        val b = EnergyBreakdown()
        b.fromArray(a.toArray())
        assertArrayEquals(a.toArray(), b.toArray(), 1e-9)
        assertTrue(Math.abs(b.net() - (4.1 - 0.7)) < 1e-9)
    }
}
