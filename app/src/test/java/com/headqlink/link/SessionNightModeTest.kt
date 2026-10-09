package com.headqlink.link

import com.andrerinas.openheadunit.utils.Settings
import org.junit.Assert.assertEquals
import org.junit.Test

/** Día o noche de la sesión: el tema del coche si lo dijo; si no, el sensor de luz del móvil (túneles, garajes). */
class SessionNightModeTest {
    @Test
    fun theCarThemeWinsThenTheLightSensor() {
        assertEquals(Settings.NightMode.NIGHT, AaPassthroughSource.sessionNightMode(1, true))
        assertEquals(Settings.NightMode.DAY, AaPassthroughSource.sessionNightMode(0, true))
        assertEquals(Settings.NightMode.LIGHT_SENSOR, AaPassthroughSource.sessionNightMode(-1, true))
        assertEquals(Settings.NightMode.AUTO, AaPassthroughSource.sessionNightMode(-1, false))
    }
}
