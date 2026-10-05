package com.headqlink.link

import android.app.UiModeManager
import org.junit.Assert.assertEquals
import org.junit.Test

/** Modo coche de Android: por defecto deja apagar la pantalla del móvil (sin el FULL_WAKE_LOCK de UiModeManager). */
class PhoneScreenTest {
    @Test
    fun byDefaultTheScreenMaySleep() {
        assertEquals(UiModeManager.ENABLE_CAR_MODE_ALLOW_SLEEP, PhoneScreen.flagsFor(false))
        assertEquals(2, PhoneScreen.flagsFor(false))
    }

    @Test
    fun keepScreenOnIsTheOldBehaviour() {
        assertEquals(0, PhoneScreen.flagsFor(true))
    }
}
