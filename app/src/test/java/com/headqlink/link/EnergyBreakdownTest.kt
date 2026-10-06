package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reparto de la energía del viaje: cuadra con la energía neta del modelo y cada parte va a su sitio. */
class EnergyBreakdownTest {
    private fun run(steps: List<DoubleArray>): Pair<EnergyBreakdown, Double> {
        // Cada paso: km/h, pendiente %, viento de cara, temperatura, aceleración (g), segundos.
        val m = EnergyModel()
        val b = EnergyBreakdown()
        var net = 0.0
        for (s in steps) {
            val dtH = s[5] / 3600
            net += m.compute(s[0], s[1], s[2], s[3], s[4]) * dtH
            b.add(m, dtH)
        }
        return b to net
    }

    @Test
    fun consumedMinusRecoveredIsTheNetEnergy() {
        val steps = listOf(
            doubleArrayOf(50.0, 0.0, 0.0, 20.0, 0.2, 10.0),
            doubleArrayOf(120.0, 4.5, 15.0, 20.0, 0.0, 120.0),
            doubleArrayOf(115.0, -5.0, 10.0, 20.0, 0.0, 90.0),
            doubleArrayOf(90.0, 0.0, 0.0, 20.0, -0.45, 4.0),
            doubleArrayOf(60.0, -1.0, -8.0, 5.0, 0.05, 60.0),
        )
        val (b, net) = run(steps)
        assertEquals(net, b.consumed() - b.recovered(), 1e-9)
        assertEquals(b.net(), net, 1e-9)
        var shares = 0.0
        for (i in 0 until EnergyBreakdown.PARTS) shares += b.share(i)
        assertEquals(1.0, shares, 1e-9)
    }

    @Test
    fun flatCruiseIsAirAndRollingOnly() {
        val (b, _) = run(listOf(doubleArrayOf(120.0, 0.0, 0.0, 20.0, 0.0, 600.0)))
        assertEquals(0.0, b.part(EnergyBreakdown.SLOPE), 1e-12)
        assertEquals(0.0, b.part(EnergyBreakdown.ACCEL), 1e-12)
        assertEquals(0.0, b.recovered(), 1e-12)
        // A 120 km/h el aire pesa más que la rodadura.
        assertTrue(b.part(EnergyBreakdown.AIR) > 2 * b.part(EnergyBreakdown.ROLLING))
    }

    @Test
    fun climbingGoesToSlopeAndDescendingIsRecovered() {
        val (up, _) = run(listOf(doubleArrayOf(80.0, 6.0, 0.0, 20.0, 0.0, 300.0)))
        assertTrue(up.share(EnergyBreakdown.SLOPE) > 0.5)
        val (down, _) = run(listOf(doubleArrayOf(80.0, -7.0, 0.0, 20.0, 0.0, 300.0)))
        assertTrue(down.recovered() > 0.5)
        assertEquals(0.0, down.part(EnergyBreakdown.SLOPE), 1e-12)
        // Bajando, solo climatización y electrónica salen de la batería.
        assertEquals(down.part(EnergyBreakdown.CLIMATE) + down.part(EnergyBreakdown.AUX), down.consumed(), 1e-9)
    }

    @Test
    fun copyAndReset() {
        val (b, _) = run(listOf(doubleArrayOf(100.0, 2.0, 5.0, 10.0, 0.1, 60.0)))
        val c = EnergyBreakdown()
        c.copyFrom(b)
        assertEquals(b.consumed(), c.consumed(), 0.0)
        c.reset()
        assertEquals(0.0, c.consumed(), 0.0)
        assertEquals(0.0, c.share(EnergyBreakdown.AIR), 0.0)
    }
}
