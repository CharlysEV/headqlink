package com.headqlink.link

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regla de retención del freno a AA (qdauto §4.8): cola del kernel alta o frame retrasado con cola detrás. */
class AaAckBrakeTest {
    @Test
    fun holdsWhileTheKernelQueueIsAboveTheDrainLevel() {
        assertTrue(AaAckBrake.shouldHold(AaAckBrake.DRAIN_OUTQ + 1, 0, 0))
        assertFalse(AaAckBrake.shouldHold(AaAckBrake.DRAIN_OUTQ, 0, 0))
        assertFalse(AaAckBrake.shouldHold(0, 0, 0))
        // Sin NetStat (-1) la cola no cuenta.
        assertFalse(AaAckBrake.shouldHold(-1, 0, 0))
    }

    @Test
    fun aLateFrameHoldsUntilTheQueueIsEmpty() {
        // Esperó 300 ms en la cola del núcleo (viaje 5) y hay otro frame detrás: se retiene.
        assertTrue(AaAckBrake.shouldHold(0, 300, 1))
        assertTrue(AaAckBrake.shouldHold(-1, AaAckBrake.LAG_HOLD_MS + 1, 2))
        // Cola vacía: AA ya puede seguir.
        assertFalse(AaAckBrake.shouldHold(0, 300, 0))
        // Un retraso pequeño no retiene aunque haya cola.
        assertFalse(AaAckBrake.shouldHold(0, AaAckBrake.LAG_HOLD_MS, 3))
    }

    @Test
    fun theDeadlineIsShorterThanTheWatchdogSoAaNeverDeadlocks() {
        assertTrue(AaAckBrake.MAX_WAIT_MS in 1..1_000)
        assertTrue(AaAckBrake.LAG_HOLD_MS < 150)
    }
}
