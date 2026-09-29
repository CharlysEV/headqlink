package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.blink

import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.blink.StockCarLink.Info
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.blink.StockCarLink.State
import org.junit.Assert.assertEquals
import org.junit.Test

class StockCarLinkTest {

    @Test
    fun `the package manager's answer maps to a state`() {
        assertEquals(State.ENABLED, StockCarLink.state { Info(enabled = true, persistent = false) })
        assertEquals(State.ENABLED, StockCarLink.state { Info(enabled = true, persistent = true) })
        assertEquals(State.DISABLED, StockCarLink.state { Info(enabled = false, persistent = false) })
        assertEquals(State.DISABLED_PERSISTENT, StockCarLink.state { Info(enabled = false, persistent = true) })
        assertEquals(State.ABSENT, StockCarLink.state { null })
        assertEquals(State.UNKNOWN, StockCarLink.state { throw IllegalArgumentException() })
    }

    @Test
    fun `only a disabled, non-persistent stock client lets this app open the port without the root guard`() {
        // A persistent app survives the force-stop that disabling does, and absence is refused
        // by the root guard too; neither can be checked further without root.
        assertEquals(listOf(State.DISABLED), State.values().filter { it.allowsDirectOpen })
    }
}
