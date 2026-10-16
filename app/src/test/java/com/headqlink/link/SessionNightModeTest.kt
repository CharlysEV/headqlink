package com.headqlink.link

import com.andrerinas.openheadunit.utils.Settings
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Día o noche de la sesión: el ajuste «Tema» si es fijo; si no, el tema del coche si lo dijo; si no, el sensor de luz del
 * móvil (túneles, garajes).
 */
class SessionNightModeTest {
    @Test
    fun theCarThemeWinsThenTheLightSensor() {
        assertEquals(Settings.NightMode.NIGHT, AaPassthroughSource.sessionNightMode(0, 1, true))
        assertEquals(Settings.NightMode.DAY, AaPassthroughSource.sessionNightMode(0, 0, true))
        assertEquals(Settings.NightMode.LIGHT_SENSOR, AaPassthroughSource.sessionNightMode(0, -1, true))
        assertEquals(Settings.NightMode.AUTO, AaPassthroughSource.sessionNightMode(0, -1, false))
    }

    @Test
    fun aFixedThemeWinsOverTheCarAndTheSensor() {
        assertEquals(Settings.NightMode.DAY, AaPassthroughSource.sessionNightMode(1, 1, true))
        assertEquals(Settings.NightMode.DAY, AaPassthroughSource.sessionNightMode(1, -1, true))
        assertEquals(Settings.NightMode.NIGHT, AaPassthroughSource.sessionNightMode(2, 0, true))
        assertEquals(Settings.NightMode.NIGHT, AaPassthroughSource.sessionNightMode(2, -1, false))
    }
}
