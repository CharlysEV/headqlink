package com.headqlink.link

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Modo T03: el coche del perfil, su batería y carga, su modelo de consumo y los textos con su nombre. */
class CarSpecTest {
    @After
    fun back() {
        CarSpec.use(CarSpec.C10)
    }

    @Test
    fun profileChoosesTheCar() {
        assertTrue(CarSpec.forProfile(CarCloudStore.PROFILE_T03).isT03())
        assertEquals(CarSpec.C10, CarSpec.forProfile(""))
        assertEquals(CarSpec.C10, CarSpec.forProfile(CarCloudStore.PROFILE_C10_REEV))
        assertEquals(37.3, CarCloudStore.capacityFor(CarCloudStore.PROFILE_T03, 0.0), 0.0)
        // La cuenta de Leapmotor dice el modelo.
        assertTrue(CarCloudStore.isT03("T03"))
        assertTrue(CarCloudStore.isT03(" t03 "))
        assertFalse(CarCloudStore.isT03("C10"))
        assertFalse(CarCloudStore.isT03(null))
    }

    @Test
    fun t03ChargesSlowerWithItsOwnCurve() {
        assertEquals(48.0, RoutePlanner.carPeakKw(37.3), 0.0)
        assertEquals(84.0, RoutePlanner.carPeakKw(69.9), 0.0)
        assertEquals(400.0, RoutePlanner.carVolts(37.3), 0.0)
        // Su curva: el máximo solo hasta el 20 %, ~70 % al 50 % y ~50 % al 80 %.
        assertEquals(48.0, ChargePlanner.carPowerKw(15.0, 48.0), 0.01)
        assertEquals(48.0 * 0.70, ChargePlanner.carPowerKw(50.0, 48.0), 0.01)
        assertEquals(48.0 * 0.50, ChargePlanner.carPowerKw(80.0, 48.0), 0.01)
        // La del C10 sigue igual (máximo hasta el 50 %).
        assertEquals(84.0, ChargePlanner.carPowerKw(50.0, 84.0), 0.01)
        // Del 30 al 80 % en un cargador de 50 kW: ~36 min según su ficha.
        val min = ChargePlanner.minutes(30.0, 80.0, 50.0, 48.0, 37.3)
        assertTrue("$min min", min in 28.0..42.0)
    }

    @Test
    fun energyModelUsesTheCar() {
        val c10 = EnergyModel().compute(110.0, 0.0, 0.0, 20.0, 0.0)
        CarSpec.use(CarSpec.T03)
        assertEquals(36.0, EnergyModel.usableKwh(), 0.0)
        val t03 = EnergyModel().compute(110.0, 0.0, 0.0, 20.0, 0.0)
        // Más ligero y más estrecho: gasta menos a la misma velocidad.
        assertTrue("$t03 < $c10", t03 < c10)
        // Sin bomba de calor, la calefacción gasta más con frío.
        val heatT03 = EnergyModel.hvac(0.0)
        CarSpec.use(CarSpec.C10)
        assertTrue(heatT03 > EnergyModel.hvac(0.0))
    }

    @Test
    fun textsSayTheCarName() {
        assertEquals("Juega contra el T03", Str.carText("Juega contra el C10", CarSpec.T03))
        assertEquals("¡265 km! Más que la autonomía WLTP del T03",
            Str.carText("¡420 km! Más que la autonomía WLTP del C10", CarSpec.T03))
    }
}
