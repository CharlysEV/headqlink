package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** Nota de suavidad (tirón): sin cambios, 100; cuanto más brusco, menos; parado no cuenta. */
class SmoothnessTest {
    private fun drive(seconds: Double, dt: Double = 0.1, moving: Boolean = true, f: (Double) -> Pair<Double, Double>): Smoothness {
        val s = Smoothness()
        var t = 0.0
        while (t < seconds) {
            val (lon, lat) = f(t)
            s.add(lon, lat, dt, moving)
            t += dt
        }
        return s
    }

    @Test
    fun steadyDrivingIsPerfect() {
        val s = drive(60.0) { 0.1 to 0.0 }
        assertEquals(100, s.score())
        assertEquals(100, s.recentScore())
    }

    @Test
    fun harsherIsWorse() {
        // Oscilaciones de la misma forma: suaves (±0,05 g cada 10 s) y bruscas (±0,3 g cada 2 s).
        val gentle = drive(120.0) { t -> 0.05 * sin(2 * PI * t / 10) to 0.0 }
        val harsh = drive(120.0) { t -> 0.3 * sin(2 * PI * t / 2) to 0.25 * sin(2 * PI * t / 3) }
        assertTrue("suave ${gentle.score()}", gentle.score() >= 90)
        assertTrue("brusca ${harsh.score()}", harsh.score() < 40)
        assertTrue(gentle.score() > harsh.score())
    }

    @Test
    fun noScoreUntilEnoughTimeMovingAndStopsDoNotCount() {
        assertEquals(-1, drive(10.0) { 0.0 to 0.0 }.score())
        val parked = drive(120.0, moving = false) { t -> 0.3 * sin(2 * PI * t) to 0.0 }
        assertEquals(-1, parked.score())
    }

    @Test
    fun sampleRateDoesNotChangeTheScoreMuch() {
        val f: (Double) -> Pair<Double, Double> = { t -> 0.15 * sin(2 * PI * t / 4) to 0.1 * sin(2 * PI * t / 6) }
        val at10 = drive(120.0, 0.1, f = f).score()
        val at5 = drive(120.0, 0.2, f = f).score()
        assertTrue("$at10 vs $at5", kotlin.math.abs(at10 - at5) <= 6)
    }

    @Test
    fun gapsResetTheFilterInsteadOfMakingAJerk() {
        val s = Smoothness()
        repeat(300) { s.add(0.0, 0.0, 0.1, true) }
        // Un hueco de 5 s (sin GPS) y la aceleración ya es otra: no es un tirón.
        s.add(0.3, 0.0, 5.0, true)
        repeat(10) { s.add(0.3, 0.0, 0.1, true) }
        assertEquals(100, s.score())
    }

    @Test
    fun theDemoDriveGetsAGoodButNotPerfectScore() {
        val d = DemoDrive()
        val s = Smoothness()
        for (k in 1 until d.n) s.add(d.longG[k].toDouble(), d.latG[k].toDouble(), DemoDrive.DT, d.speedKmh[k] > 5)
        assertTrue("demostración ${s.score()}", s.score() in 60..92)
    }
}
