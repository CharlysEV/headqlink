package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** Cuentas de la pestaña Viajes: km por día, totales, récords y medias. */
class TripStatsTest {
    private val tz: TimeZone = TimeZone.getTimeZone("Europe/Madrid")

    private fun at(daysAgo: Int, hour: Int, now: Long): Long {
        val c = Calendar.getInstance(tz)
        c.timeInMillis = now
        c.add(Calendar.DAY_OF_YEAR, -daysAgo)
        c.set(Calendar.HOUR_OF_DAY, hour)
        c.set(Calendar.MINUTE, 0)
        return c.timeInMillis
    }

    private fun trip(start: Long, km: Double, kwh: Double, min: Long, climb: Double = 0.0) = TripLog.Trip().also {
        it.startMs = start
        it.km = km
        it.kwh = kwh
        it.minutes = min
        it.climb = climb
    }

    @Test
    fun dailyKmGroupsByLocalDayWithTodayLast() {
        val now = DemoDrive.BASE_MS
        val trips = listOf(
            trip(at(0, 9, now), 10.0, 1.5, 15),
            trip(at(0, 13, now), 5.0, 0.8, 9),
            trip(at(1, 23, now), 20.0, 3.0, 25),
            trip(at(13, 8, now), 7.0, 1.0, 10),
            trip(at(20, 8, now), 99.0, 15.0, 60), // fuera de las dos semanas
        )
        val d = TripStats.dailyKm(trips, now, 14, tz)
        assertEquals(14, d.size)
        assertEquals(15.0, d[13], 1e-9)
        assertEquals(20.0, d[12], 1e-9)
        assertEquals(7.0, d[0], 1e-9)
        assertEquals(42.0, d.sum(), 1e-9)
    }

    @Test
    fun totalsCountOnlyTheWindow() {
        val now = DemoDrive.BASE_MS
        val trips = listOf(trip(at(1, 9, now), 10.0, 1.6, 15), trip(at(3, 9, now), 30.0, 4.4, 30), trip(at(9, 9, now), 50.0, 9.0, 40))
        val week = TripStats.totals(trips, now - 7 * 86_400_000L, now)
        assertEquals(2, week.trips)
        assertEquals(40.0, week.km, 1e-9)
        assertEquals(45, week.minutes)
        assertEquals(15.0, week.kwhPer100(), 1e-9)
        assertTrue(TripStats.totals(trips, now, now + 1).kwhPer100().isNaN())
    }

    @Test
    fun recordsIgnoreTooShortTripsForEfficiency() {
        val now = DemoDrive.BASE_MS
        val trips = listOf(
            trip(now - 1, 2.0, 0.1, 5, 10.0),      // 5 kWh/100 pero solo 2 km
            trip(now - 2, 40.0, 6.4, 35, 300.0),   // 16
            trip(now - 3, 120.0, 22.0, 80, 150.0), // 18,3
            trip(now - 4, 15.0, 2.1, 20, 40.0),    // 14
        )
        assertEquals(2, TripStats.longest(trips))
        assertEquals(3, TripStats.mostEfficient(trips))
        assertEquals(1, TripStats.mostClimb(trips))
        assertEquals((0.1 + 6.4 + 22.0 + 2.1) / 177.0 * 100, TripStats.avgKwhPer100(trips), 1e-9)
        assertEquals(-1, TripStats.longest(emptyList()))
        assertEquals(-1, TripStats.mostEfficient(listOf(trip(now, 1.0, 0.1, 2))))
    }

    @Test
    fun perTripFigures() {
        val t = trip(0, 30.0, 4.5, 30)
        assertEquals(15.0, TripStats.kwhPer100(t), 1e-9)
        assertEquals(60.0, TripStats.avgKmh(t), 1e-9)
        assertTrue(TripStats.avgKmh(trip(0, 1.0, 0.1, 0)).isNaN())
    }

    @Test
    fun demoTripsGiveSensibleWeeks() {
        val trips = DemoDrive.trips(DemoDrive.SEED)
        val d = TripStats.dailyKm(trips, DemoDrive.BASE_MS, 14, DemoDrive.TZ)
        assertEquals(trips.sumOf { it.km }, d.sum(), 1e-6)
        assertEquals(4, TripStats.longest(trips))
        assertEquals(3, TripStats.mostEfficient(trips))
    }

    @Test
    fun theCarsOwnFigureWinsAndSaysWhereItComesFrom() {
        val t = trip(DemoDrive.BASE_MS, 15.8, 2.4, 37)
        assertEquals(TripStats.Source.ESTIMATED, TripStats.source(t, emptyList()))
        // Con el % y el cuentakilómetros del coche: real.
        t.socStart = 81.8; t.socEnd = 78.4; t.odoStart = 4496.0; t.odoEnd = 4511.0; t.capKwh = 81.9
        assertEquals(TripStats.Source.REAL, TripStats.source(t, emptyList()))
        assertEquals(3.4 / 100 * 81.9 / 15 * 100, TripStats.bestKwhPer100(t, emptyList()), 1e-9)
        // Con el historial del coche: lo que dice el coche.
        val h = listOf(CloudHistory.Trip(t.startMs + 60_000, t.startMs + 12 * 60_000, 7.0, 0.74, Double.NaN, 60.0),
            CloudHistory.Trip(t.startMs + 28 * 60_000, t.startMs + 37 * 60_000, 7.0, 1.64, Double.NaN, 70.0))
        assertEquals(TripStats.Source.CAR, TripStats.source(t, h))
        assertEquals(2.38 / 14 * 100, TripStats.bestKwhPer100(t, h), 1e-9)
        assertTrue(TripStats.fuelL(t, h).isNaN())
    }

    @Test
    fun reevPetrolAndCost() {
        val t = trip(DemoDrive.BASE_MS, 77.0, 15.0, 60)
        // Del depósito.
        t.fuelStartL = 30.0; t.fuelEndL = 25.6
        assertEquals(4.4, TripStats.fuelL(t, emptyList()), 1e-9)
        // El del coche manda.
        val h = listOf(CloudHistory.Trip(t.startMs + 60_000, t.endMs() - 60_000, 77.0, 15.0, 4.9, 120.0))
        assertEquals(4.9, TripStats.fuelL(t, h), 1e-9)
        assertEquals(4.9 / 77 * 100, TripStats.litersPer100(t, h), 1e-9)
        // Coste: la luz que no dio el generador (15 − 4,9 × 3 kWh) y la gasolina.
        assertEquals((15.0 - 4.9 * TripStats.KWH_PER_LITER) * 0.2 + 4.9 * 1.6, TripStats.cost(t, h, 0.2, 1.6), 1e-9)
        // Un eléctrico: solo la luz.
        val e = trip(DemoDrive.BASE_MS, 10.0, 1.5, 15)
        assertEquals(1.5 * 0.2, TripStats.cost(e, emptyList(), 0.2, 1.6), 1e-9)
        // Repostar por el camino no es un consumo.
        t.fuelStartL = 10.0; t.fuelEndL = 40.0
        assertTrue(t.fuelUsedL().isNaN())
    }
}
