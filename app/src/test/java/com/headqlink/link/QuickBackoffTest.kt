package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Espera entre intentos: inmediata tras una sesión buena, 0,5 → 5 s tras fallos, y pausas (qdauto §4.4). */
class QuickBackoffTest {
    private var now = 1_000_000L
    private val b = QuickBackoff { now }

    @Test
    fun aGoodSessionMeansReconnectRightAway() {
        assertTrue(b.canAttempt())
        b.onSessionEnded(reachedStreaming = true)
        assertTrue(b.canAttempt())
        assertEquals(0, b.waitMs())
    }

    @Test
    fun failedSessionsBackOffExponentiallyUpTo5s() {
        val waits = ArrayList<Long>()
        repeat(6) {
            b.onSessionEnded(reachedStreaming = false)
            waits += b.waitMs()
            assertFalse(b.canAttempt())
            now += b.waitMs()
            assertTrue(b.canAttempt())
        }
        assertEquals(listOf(500L, 1_000L, 2_000L, 4_000L, 5_000L, 5_000L), waits)
        assertEquals(6, b.consecutiveFailures)
        // Una sesión buena lo reinicia.
        b.onSessionEnded(reachedStreaming = true)
        assertEquals(0, b.consecutiveFailures)
        b.onSessionEnded(reachedStreaming = false)
        assertEquals(500, b.waitMs())
    }

    @Test
    fun pauseHoldsEvenIfTheSessionEndsWellAfterwards() {
        b.pause(3_000)
        b.onSessionEnded(reachedStreaming = true) // la sesión cerrada por «aplicar ajustes» termina después
        assertFalse(b.canAttempt())
        now += 2_999
        assertFalse(b.canAttempt())
        now += 1
        assertTrue(b.canAttempt())
    }

    @Test
    fun acceptTimeoutRetriesWithTheNextBroadcast() {
        b.onSessionEnded(reachedStreaming = false)
        b.onAcceptTimeout()
        assertTrue(b.canAttempt())
        b.pause(1_000)
        b.onAcceptTimeout()
        assertFalse(b.canAttempt()) // la pausa se respeta
    }
}
