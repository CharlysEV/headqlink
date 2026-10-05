package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ritmo del relay GL: como mucho los fps del vídeo, también con dos fuentes a la vez. */
class FramePacerTest {
    private val ms = 1_000_000L

    /**
     * Simula el relay con un reloj de 1 ms durante [seconds]: cada llegada deja un frame nuevo (el último pisa al
     * anterior) y se dibuja en cuanto el ritmo lo permite. Devuelve cuántos se dibujaron.
     */
    private fun simulate(pacer: FramePacer, grid: Boolean, seconds: Int, arrivalsMs: List<Long>): Int {
        val arrivals = arrivalsMs.toHashSet()
        var hasNew = false
        var drawn = 0
        for (t in 0L until seconds * 1000L) {
            if (t in arrivals) hasNew = true
            val now = 1_000_000_000L + t * ms
            if (hasNew && pacer.waitNs(now, grid) <= 0) {
                pacer.onDraw(now, grid)
                hasNew = false
                drawn++
            }
        }
        return drawn
    }

    private fun every(periodMs: Double, offsetMs: Double, seconds: Int): List<Long> =
        generateSequence(offsetMs) { it + periodMs }.takeWhile { it < seconds * 1000 }.map { it.toLong() }.toList()

    @Test
    fun aaAt30IsDrawnFrameByFrame() {
        val drawn = simulate(FramePacer(30), grid = false, seconds = 10, arrivalsMs = every(1000.0 / 30, 0.0, 10))
        assertTrue("dibujados $drawn", drawn in 298..301)
    }

    @Test
    fun twoSourcesAt30DoNotMake60() {
        // AA a 30 y nuestra capa a 30, desfasadas medio periodo: antes salían ~60 dibujos por segundo.
        val arrivals = every(1000.0 / 30, 0.0, 10) + every(1000.0 / 30, 1000.0 / 60, 10)
        val drawn = simulate(FramePacer(30), grid = false, seconds = 10, arrivalsMs = arrivals)
        assertTrue("dibujados $drawn", drawn in 290..301)
    }

    @Test
    fun jitteryAaIsNotThinnedOut() {
        // AA a 30 con ±8 ms de irregularidad: se dibujan todos (la tolerancia es medio periodo).
        val arrivals = every(1000.0 / 30, 0.0, 10).mapIndexed { i, t -> t + if (i % 2 == 0) 8 else -8 }.filter { it >= 0 }
        val drawn = simulate(FramePacer(30), grid = false, seconds = 10, arrivalsMs = arrivals)
        assertTrue("dibujados $drawn de ${arrivals.size}", drawn >= arrivals.size - 2)
    }

    @Test
    fun gridBelowTheSourceRateCapsIt() {
        // Tope térmico: 24 o 20 fps con AA a 30, en rejilla.
        val aa = every(1000.0 / 30, 0.0, 10)
        val p24 = FramePacer(24)
        p24.setFps(24, true)
        val d24 = simulate(p24, grid = true, seconds = 10, arrivalsMs = aa)
        assertTrue("dibujados $d24", d24 in 230..241)
        val p20 = FramePacer(30)
        p20.setFps(20, true)
        val d20 = simulate(p20, grid = true, seconds = 10, arrivalsMs = aa)
        assertTrue("dibujados $d20", d20 in 190..201)
    }

    @Test
    fun aGapLeavesNoBurstCredit() {
        val p = FramePacer(30)
        val t0 = 5_000_000_000L
        p.onDraw(t0, false)
        // Dos segundos sin frames y luego una ráfaga: solo el primero sale en el acto.
        val t1 = t0 + 2_000 * ms
        assertTrue(p.waitNs(t1, false) <= 0)
        p.onDraw(t1, false)
        assertTrue(p.waitNs(t1 + 1 * ms, false) > 0)
        assertEquals(0L, FramePacer(0).waitNs(t1, false))
    }
}
