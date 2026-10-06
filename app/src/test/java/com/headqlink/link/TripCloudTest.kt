package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Viajes con datos reales del coche (nube de Leapmotor): consumo real por viaje, el mejor dato y los totales reales. */
class TripCloudTest {
    private fun trip(start: Long, km: Double, kwh: Double, soc0: Double = Double.NaN, soc1: Double = Double.NaN,
                     odo0: Double = Double.NaN, odo1: Double = Double.NaN, cap: Double = 69.9, charged: Boolean = false) =
        TripLog.Trip().also {
            it.startMs = start
            it.km = km
            it.kwh = kwh
            it.minutes = 30
            it.socStart = soc0
            it.socEnd = soc1
            it.odoStart = odo0
            it.odoEnd = odo1
            it.capKwh = if (soc0.isNaN()) Double.NaN else cap
            it.charged = charged
        }

    @Test
    fun realPerTripWhenItHasCarData() {
        // 61 km de cuentakilómetros y un 16,4 % de 69,9 kWh = 11,4636 kWh → 18,79 kWh/100 km reales.
        val t = trip(1, 61.3, 11.0, 80.0, 63.6, 12000.0, 12061.0)
        assertEquals(16.4 * 0.699 / 61 * 100, TripStats.realKwhPer100(t), 1e-9)
        assertEquals(TripStats.realKwhPer100(t), TripStats.bestKwhPer100(t), 0.0)
        // Sin datos del coche: el estimado de siempre.
        val est = trip(2, 20.0, 3.0)
        assertTrue(TripStats.realKwhPer100(est).isNaN())
        assertEquals(15.0, TripStats.bestKwhPer100(est), 1e-9)
        // Con carga por el camino o poca bajada: no hay real, se queda el estimado.
        assertTrue(TripStats.realKwhPer100(trip(3, 61.0, 11.0, 80.0, 60.0, 0.0, 61.0, charged = true)).isNaN())
        assertTrue(TripStats.realKwhPer100(trip(4, 5.0, 0.8, 80.0, 79.0, 0.0, 5.0)).isNaN())
        assertEquals(16.0, TripStats.bestKwhPer100(trip(4, 5.0, 0.8, 80.0, 79.0, 0.0, 5.0)), 1e-9)
    }

    @Test
    fun totalsAddOnlyTheRealTrips() {
        val trips = listOf(
            trip(10, 61.3, 11.0, 80.0, 63.6, 12000.0, 12061.0),
            trip(20, 18.5, 2.8, 63.6, 59.3, 12061.0, 12080.0),
            trip(30, 30.0, 5.0), // sin datos del coche
            trip(40, 40.0, 6.0, 80.0, 50.0, 0.0, 40.0, charged = true), // cargó por el camino
        )
        val t = TripStats.totals(trips, 0, 100)
        assertEquals(4, t.trips)
        assertEquals(149.8, t.km, 1e-9)
        assertEquals(2, t.realTrips)
        assertEquals(61.0 + 19.0, t.realKm, 1e-9)
        val kwh = (16.4 + 4.3) / 100 * 69.9
        assertEquals(kwh, t.realKwh, 1e-9)
        assertEquals(kwh / 80.0 * 100, t.realKwhPer100(), 1e-9)
        // El estimado de siempre no cambia.
        assertEquals(24.8 / 149.8 * 100, t.kwhPer100(), 1e-9)
        assertTrue(TripStats.totals(trips.subList(2, 3), 0, 100).realKwhPer100().isNaN())
    }

    @Test
    fun theMostEfficientRecordUsesTheRealFigure() {
        // Estimado 14 kWh/100 km, pero el real es 20,41: gana el otro (estimado 15, sin datos del coche).
        val a = trip(1, 50.0, 7.0, 80.0, 65.4, 0.0, 50.0)
        val b = trip(2, 50.0, 7.5)
        assertEquals(14.6 * 0.699 / 50 * 100, TripStats.bestKwhPer100(a), 1e-9)
        assertEquals(1, TripStats.mostEfficient(listOf(a, b)))
    }
}
