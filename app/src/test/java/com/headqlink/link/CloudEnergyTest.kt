package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Consumo real con los datos de la nube: bajada del % × capacidad entre los km del cuentakilómetros. */
class CloudEnergyTest {
    @Test
    fun realConsumptionFromSocDropAndOdometer() {
        // 84 % → 79,5 %: 4,5 % de 69,9 kWh = 3,1455 kWh en 15 km = 20,97 kWh/100 km.
        val r = CloudEnergy.between(84.0, 12480.0, 79.5, 12495.0, 69.9, false)
        assertTrue(r.ok())
        assertEquals(4.5, r.socDrop, 1e-9)
        assertEquals(15.0, r.km, 1e-9)
        assertEquals(3.1455, r.kwh, 1e-9)
        assertEquals(20.97, r.kwhPer100, 1e-9)
        // La misma bajada con el ProMax son más kWh.
        assertEquals(81.9 * 0.045 / 15 * 100, CloudEnergy.between(84.0, 0.0, 79.5, 15.0, 81.9, false).kwhPer100, 1e-9)
    }

    @Test
    fun tooLittleToMeasure() {
        // Menos de un 2 % de bajada: aún no se mide (el redondeo del % sería enorme).
        val little = CloudEnergy.between(84.0, 0.0, 82.1, 9.0, 69.9, false)
        assertEquals(CloudEnergy.Kind.LITTLE, little.kind)
        assertTrue(little.kwhPer100.isNaN())
        assertEquals(CloudEnergy.Kind.OK, CloudEnergy.between(84.0, 0.0, 82.0, 9.0, 69.9, false).kind)
        // Pocos km de cuentakilómetros (va en km enteros).
        assertEquals(CloudEnergy.Kind.LITTLE, CloudEnergy.between(84.0, 0.0, 81.0, 2.0, 69.9, false).kind)
        // Batería que sube (bajada larga recuperando): tampoco hay consumo que medir.
        assertEquals(CloudEnergy.Kind.LITTLE, CloudEnergy.between(80.0, 0.0, 81.0, 10.0, 69.9, false).kind)
    }

    @Test
    fun chargedOrImplausibleOrMissing() {
        assertEquals(CloudEnergy.Kind.CHARGED, CloudEnergy.between(84.0, 0.0, 60.0, 50.0, 69.9, true).kind)
        // 30 % en 10 km = 300 %/100 km: no es un consumo, es un dato malo.
        assertEquals(CloudEnergy.Kind.IMPLAUSIBLE, CloudEnergy.between(84.0, 0.0, 54.0, 10.0, 69.9, false).kind)
        // 2 % en 100 km = 2 %/100 km: tampoco.
        assertEquals(CloudEnergy.Kind.IMPLAUSIBLE, CloudEnergy.between(84.0, 0.0, 82.0, 100.0, 69.9, false).kind)
        assertEquals(CloudEnergy.Kind.NONE, CloudEnergy.between(Double.NaN, 0.0, 80.0, 10.0, 69.9, false).kind)
        assertEquals(CloudEnergy.Kind.NONE, CloudEnergy.between(84.0, Double.NaN, 80.0, 10.0, 69.9, false).kind)
        assertEquals(CloudEnergy.Kind.NONE, CloudEnergy.between(84.0, 0.0, 80.0, 10.0, 0.0, false).kind)
        assertFalse(CloudEnergy.NONE.ok())
    }

    @Test
    fun arrivalFromRealSocAndCapacity() {
        // 84 % y faltan 20 kWh: con 69,9 kWh se llega con 55,4 %; con 81,9 kWh, con 59,6 %.
        assertEquals(84 - 20 / 69.9 * 100, CloudEnergy.arrivalPct(84.0, 20.0, 69.9), 1e-9)
        assertEquals(84 - 20 / 81.9 * 100, CloudEnergy.arrivalPct(84.0, 20.0, 81.9), 1e-9)
        assertTrue(CloudEnergy.arrivalPct(Double.NaN, 20.0, 69.9).isNaN())
        assertTrue(CloudEnergy.arrivalPct(84.0, Double.NaN, 69.9).isNaN())
        assertEquals(6.0, CloudEnergy.kwh(10.0, 60.0), 1e-9)
    }
}
