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
        assertEquals(CarSpec.C10_REEV, CarSpec.forProfile(CarCloudStore.PROFILE_C10_REEV))
        assertEquals(37.3, CarCloudStore.capacityFor(CarCloudStore.PROFILE_T03, 0.0), 0.0)
        // La cuenta de Leapmotor dice el modelo.
        assertTrue(CarCloudStore.isT03("T03"))
        assertTrue(CarCloudStore.isT03(" t03 "))
        assertFalse(CarCloudStore.isT03("C10"))
        assertFalse(CarCloudStore.isT03(null))
    }

    @Test
    fun t03ChargesSlowerWithItsOwnCurve() {
        // Manda el coche elegido, no la capacidad.
        assertEquals(84.0, RoutePlanner.carPeakKw(69.9), 0.0)
        assertEquals(180.0, RoutePlanner.carPeakKw(81.9), 0.0)
        CarSpec.use(CarSpec.T03)
        assertEquals(45.0, RoutePlanner.carPeakKw(37.3), 0.0)
        CarSpec.use(CarSpec.C10)
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
    fun fastChargeTimesMatchLeapmotorSpecSheets() {
        // Del 30 al 80 % en un cargador de sobra, como las fichas de Leapmotor España (2026-10).
        fun min(spec: CarSpec) = ChargePlanner.minutes(30.0, 80.0, 400.0, spec.peakDcKw, spec.grossKwh)
        assertEquals(180.0, CarSpec.C10_PROMAX.peakDcKw, 0.0)
        assertEquals(84.0, CarSpec.C10.peakDcKw, 0.0)
        assertEquals(64.0, CarSpec.C10_REEV.peakDcKw, 0.0)
        assertEquals(45.0, CarSpec.T03.peakDcKw, 0.0)
        assertEquals(22.0, min(CarSpec.C10_PROMAX), 2.0) // ProMax y AWD: 22 min
        assertEquals(30.0, min(CarSpec.C10), 2.0) // Pro: 30 min
        assertEquals(36.0, min(CarSpec.T03), 2.0) // T03: 36 min
        // El ProMax de 800 V da su pico solo hasta el 30 %.
        assertEquals(180.0, ChargePlanner.carPowerKw(30.0, 180.0), 0.01)
        assertTrue(ChargePlanner.carPowerKw(50.0, 180.0) < 130.0)
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
    fun reevIsItsOwnCar() {
        val reev = CarSpec.forProfile(CarCloudStore.PROFILE_C10_REEV)
        assertTrue(reev.reev)
        assertFalse(reev.isT03())
        CarSpec.use(reev)
        // 28,4 kWh no es un T03: su carga rápida (~65 kW) con la curva del C10.
        assertEquals(64.0, RoutePlanner.carPeakKw(28.4), 0.0)
        assertEquals(64.0, ChargePlanner.carPowerKw(40.0, 64.0), 0.01)
        assertFalse(CarSpec.forProfile(CarCloudStore.PROFILE_C10_LIFE).reev)
    }

    @Test
    fun textsSayTheCarName() {
        assertEquals("Juega contra el T03", Str.carText("Juega contra el C10", CarSpec.T03))
        assertEquals("¡265 km! Más que la autonomía WLTP del T03",
            Str.carText("¡420 km! Más que la autonomía WLTP del C10", CarSpec.T03))
    }
}
