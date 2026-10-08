package com.headqlink.link

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Día y noche de la interfaz del coche: cambia la paleta entera y vuelve a la de siempre. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = android.app.Application::class)
class CarThemeTest {
    @After
    fun backToNight() {
        CarTheme.apply(true)
    }

    @Test
    fun dayIsLightAndNightIsTheUsualPalette() {
        CarTheme.apply(true)
        val nightBg = CarKit.BG
        val nightText = CarKit.TEXT
        assertTrue(CarTheme.apply(false))
        assertFalse(CarTheme.night())
        // De día: fondo claro y texto oscuro (luminancia al revés que de noche).
        assertTrue(lum(CarKit.BG) > 0.8 && lum(CarKit.TEXT) < 0.2)
        assertTrue(lum(CarStyle.BG) > 0.8 && lum(CarStyle.TEXT) < 0.2)
        assertEquals(0xFFE9EDF1.toInt(), CarTheme.panelColor(0xFF1E1E20.toInt()))
        assertFalse(CarTheme.apply(false))
        assertTrue(CarTheme.apply(true))
        assertEquals(nightBg, CarKit.BG)
        assertEquals(nightText, CarKit.TEXT)
        assertEquals(0xFF1E1E20.toInt(), CarTheme.panelColor(0xFF1E1E20.toInt()))
    }

    private fun lum(c: Int): Double = (0.2126 * ((c shr 16) and 255) + 0.7152 * ((c shr 8) and 255) + 0.0722 * (c and 255)) / 255
}
