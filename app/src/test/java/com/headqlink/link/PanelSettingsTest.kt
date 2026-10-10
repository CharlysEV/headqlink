package com.headqlink.link

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RoboConfig
import org.robolectric.annotation.ConscryptMode

/** Panel a medida: botones elegidos y en orden, transparencia (fundido con negro) y color desde el tono. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@RoboConfig(sdk = [35], application = android.app.Application::class)
class PanelSettingsTest {
    @After
    fun reset() {
        CarTheme.setPanelAlpha(0)
        CarTheme.setFixedPanel(0)
        CarTheme.apply(true)
    }

    @Test
    fun buttonsDefaultToAllAndKeepTheSavedOrder() {
        assertEquals(Config.PANEL_BUTTONS_ALL.toList(), Config.panelButtonsFrom(null))
        assertEquals(listOf("web", "car", "radio"), Config.panelButtonsFrom("web, car,radio"))
        // Lo que no existe o se repite, fuera; vacío = ningún botón (solo Auto y Ajustes).
        assertEquals(listOf("tv"), Config.panelButtonsFrom("tv,nada,tv"))
        assertEquals(emptyList<String>(), Config.panelButtonsFrom(""))
    }

    @Test
    fun transparencyFadesTheColourToBlack() {
        assertEquals(0xFF1967D2.toInt(), CarTheme.dim(0xFF1967D2.toInt(), 0))
        assertEquals(0xFF000000.toInt(), CarTheme.dim(0xFF1967D2.toInt(), 100))
        assertEquals(0xFF0D3469.toInt(), CarTheme.dim(0xFF1967D2.toInt(), 50))
        CarTheme.setFixedPanel(0xFFF1F3F4.toInt())
        CarTheme.setPanelAlpha(80)
        // Blanco casi transparente: ya es oscuro, y el texto del panel pasa a claro.
        assertTrue(CarTheme.isDark(CarTheme.panelColor(0)))
        assertTrue(lum(CarTheme.navText()) > 0.8)
    }

    @Test
    fun hueStripGivesOpaqueColoursAcrossTheWheel() {
        val seen = HashSet<Int>()
        for (h in 0 until 360 step 30) {
            val c = CarTheme.fromHue(h.toFloat(), 0.55f)
            assertEquals(0xFF, c ushr 24)
            seen.add(c)
        }
        assertEquals(12, seen.size)
        // Claridad al mínimo: casi negro; al máximo: vivo.
        assertTrue(CarTheme.isDark(CarTheme.fromHue(200f, 0.12f)))
    }

    private fun lum(c: Int): Double = (0.2126 * ((c shr 16) and 255) + 0.7152 * ((c shr 8) and 255) + 0.0722 * (c and 255)) / 255
}
