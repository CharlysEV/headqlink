package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** IDR del reenvío directo de AA: antirrebote, reintento único, vigilante y la secuencia real del C10 (qdauto §4.9). */
class KeyframePolicyTest {
    @Test
    fun debounceSkipsRequestsWithin600ms() {
        val p = KeyframePolicy()
        assertTrue(p.onRequest(10_000))
        p.onIdrSeen(10_100)
        assertFalse(p.onRequest(10_500)) // 500 ms después del último ciclo
        assertTrue(p.onRequest(10_600))
    }

    @Test
    fun oneCycleInFlightAtATimeUntilTheWatchdogWindow() {
        val p = KeyframePolicy()
        assertTrue(p.onRequest(0))
        assertFalse(p.onRequest(700)) // en curso, sin IDR todavía
        assertFalse(p.onRequest(1_400))
        assertTrue(p.onRequest(1_500)) // el ciclo se perdió: se puede volver a pedir
    }

    @Test
    fun refusedLeverRetriesOnceAfter150ms() {
        val p = KeyframePolicy()
        assertTrue(p.onRequest(0))
        assertEquals(KeyframePolicy.RETRY_MS, p.onLeverRefused(5))
        p.onRetry(155)
        assertEquals(-1, p.onLeverRefused(160)) // el reintento también falla: no hay más
        assertFalse(p.inFlight())
        // Una petición nueva (fuera del antirrebote) vuelve a tener su reintento.
        assertTrue(p.onRequest(1_000))
        assertEquals(KeyframePolicy.RETRY_MS, p.onLeverRefused(1_001))
    }

    @Test
    fun watchdogFiresOnlyWhileWaitingAndAtMostEvery1500ms() {
        val p = KeyframePolicy()
        assertTrue(p.onRequest(0))
        assertFalse(p.watchdog(1_000, true)) // aún dentro de 1,5 s del último ciclo
        assertFalse(p.watchdog(2_000, false)) // no espera IDR: nada
        assertTrue(p.watchdog(2_000, true))
        assertFalse(p.watchdog(2_500, true))
        assertFalse(p.watchdog(3_400, true))
        assertTrue(p.watchdog(3_500, true))
        p.onIdrSeen(3_900)
        assertFalse(p.watchdog(6_000, false))
        assertEquals(3, p.cycles())
    }

    @Test
    fun c10SequenceStreamStartThenIdrThenCarRequestAt340ms() {
        // Al arrancar el reenvío ya hay un ciclo en curso; STREAM_START no lanza otro.
        val p = KeyframePolicy()
        p.noteRequested(0)
        assertFalse(p.onRequest(20)) // STREAM_START
        // AA tarda ~0,8 s en dar el IDR; el coche descarta el primero y pide otro a los ~340 ms del primer frame.
        p.onIdrSeen(800)
        assertTrue(p.onRequest(1_140)) // KEY_FRAME_REQ del coche: hay que servirlo
        p.onIdrSeen(1_600)
        // Una petición BACKLOG del núcleo 560 ms después del último ciclo: antirrebote.
        assertFalse(p.onRequest(1_700))
        assertTrue(p.summary().contains("servidos 2"))
    }

    @Test
    fun reattachedPipelineFiresImmediatelyOnStreamStart() {
        val p = KeyframePolicy()
        p.noteRequested(0)
        p.onIdrSeen(700)
        // Sesión nueva 60 s después: el STREAM_START lanza el ciclo al momento.
        assertTrue(p.onRequest(60_000))
    }
}
