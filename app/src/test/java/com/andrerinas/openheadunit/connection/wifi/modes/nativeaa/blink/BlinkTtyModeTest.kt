package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.blink

import org.junit.Assert.assertTrue
import org.junit.Test

class BlinkTtyModeTest {

    @Test
    fun `tty setup disables the transformations found in the FYT capture`() {
        val command = BlinkAutoSerialChannel.TTY_MODE_COMMAND
        for (flag in listOf("-iuclc", "-icrnl", "-inlcr", "-igncr", "-ixon", "-ixoff", "-opost")) {
            assertTrue("missing $flag in: $command", command.split(' ').contains(flag))
        }
    }
}
