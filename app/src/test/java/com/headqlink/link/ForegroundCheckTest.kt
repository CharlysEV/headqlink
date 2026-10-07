package com.headqlink.link

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cuándo LinkService puede ahorrarse el startForeground de una orden (y cuándo cerraría la app no hacerlo). */
class ForegroundCheckTest {
    private val connectedDevice = 0x10
    private val location = 0x08

    @Test
    fun notYetForegroundInThisInstanceAlwaysCalls() {
        assertFalse(ForegroundCheck.alreadyForeground(false, 0, 36))
        assertFalse("aunque el sistema diga otra cosa, una instancia nueva lo hace", ForegroundCheck.alreadyForeground(false, connectedDevice, 36))
        assertFalse(ForegroundCheck.alreadyForeground(false, 0, 26))
    }

    @Test
    fun theSystemHasTheLastWord() {
        assertTrue(ForegroundCheck.alreadyForeground(true, connectedDevice or location, 36))
        assertTrue(ForegroundCheck.alreadyForeground(true, connectedDevice, 29))
        // El servicio cree que está en primer plano pero Android no (salió o no llegó a entrar): hay que llamar.
        assertFalse(ForegroundCheck.alreadyForeground(true, 0, 36))
    }

    @Test
    fun beforeAndroid10TheFlagIsAllThereIs() {
        assertTrue(ForegroundCheck.alreadyForeground(true, 0, 28))
    }
}
