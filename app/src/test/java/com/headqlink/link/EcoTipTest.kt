package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Consejo de eficiencia: el ahorro de ir 10 km/h más despacio (≈ 11 % a 120) y la elección según el viaje. */
class EcoTipTest {
    @Test
    fun slowerSavesAboutATenthOnTheMotorway() {
        val at120 = EcoTip.slowerSavingPct(120.0, 0.0, 20.0)
        val at50 = EcoTip.slowerSavingPct(50.0, 0.0, 20.0)
        assertTrue("a 120: $at120", at120 in 8.0..16.0)
        // En ciudad pesan más lo fijo (electrónica, clima) y la rodadura: ir más despacio ahorra menos.
        assertTrue("a 50: $at50", at50 < at120)
        assertEquals(0.0, EcoTip.slowerSavingPct(15.0, 0.0, 20.0), 0.0)
    }

    @Test
    fun picksByContext() {
        assertEquals(EcoTip.SLOWER, EcoTip.pick(118.0, 10.0, 24.0, null).kind)
        val m = EnergyModel()
        val accel = EnergyBreakdown()
        repeat(200) {
            m.compute(40.0, 0.0, 0.0, 20.0, 0.25)
            accel.add(m, 1.0 / 3600)
        }
        assertEquals(EcoTip.GENTLER, EcoTip.pick(40.0, 0.0, 20.0, accel).kind)
        val cold = EnergyBreakdown()
        repeat(600) {
            m.compute(30.0, 0.0, 0.0, -5.0, 0.0)
            cold.add(m, 1.0 / 3600)
        }
        assertEquals(EcoTip.CLIMATE, EcoTip.pick(30.0, 0.0, -5.0, cold).kind)
        val down = EnergyBreakdown()
        repeat(600) {
            m.compute(60.0, -6.0, 0.0, 20.0, 0.0)
            down.add(m, 1.0 / 3600)
            m.compute(60.0, 1.0, 0.0, 20.0, 0.0)
            down.add(m, 1.0 / 3600)
        }
        assertEquals(EcoTip.REGEN, EcoTip.pick(60.0, 0.0, 20.0, down).kind)
        assertEquals(EcoTip.STEADY, EcoTip.pick(50.0, 0.0, 20.0, EnergyBreakdown()).kind)
    }
}
