package com.andrerinas.openheadunit.aap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceStopRacePolicyTest {

    @Test
    fun commandsAreHandledUntilAStopIsAccepted() {
        assertEquals(ServiceStopRacePolicy.Next.HANDLE, ServiceStopRacePolicy.afterStopAccepted(false, false))
        assertEquals(ServiceStopRacePolicy.Next.HANDLE, ServiceStopRacePolicy.afterStopAccepted(false, true))
    }

    @Test
    fun aCommandQueuedBehindAStopFinishesTheStopAfterStartForeground() {
        assertEquals(ServiceStopRacePolicy.Next.STOP_NOW, ServiceStopRacePolicy.afterStopAccepted(true, false))
    }

    @Test
    fun aRunningTeardownKeepsTheStopForItself() {
        // Stopping here would cancel serviceScope at the await and leave the network up.
        assertEquals(ServiceStopRacePolicy.Next.LEAVE_TO_TEARDOWN, ServiceStopRacePolicy.afterStopAccepted(true, true))
    }

    @Test
    fun noRestartFromTaskRemovalOnceStopping() {
        assertTrue(ServiceStopRacePolicy.restartOnTaskRemoved(false))
        assertFalse(ServiceStopRacePolicy.restartOnTaskRemoved(true))
    }
}
