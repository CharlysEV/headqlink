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
        CarTheme.setFixedPanel(0)
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

    @Test
    fun aFixedPanelColorStaysDayAndNight() {
        val blue = 0xFF1967D2.toInt()
        CarTheme.setFixedPanel(blue)
        CarTheme.apply(true)
        assertEquals(blue, CarTheme.panelColor(0xFF1E1E20.toInt()))
        CarTheme.apply(false)
        assertEquals(blue, CarTheme.panelColor(0xFF1E1E20.toInt()))
        // Sobre azul oscuro, texto claro aunque sea de día.
        assertTrue(lum(CarTheme.navText()) > 0.8)
        CarTheme.setFixedPanel(0)
        assertEquals(0xFFE9EDF1.toInt(), CarTheme.panelColor(0xFF1E1E20.toInt()))
    }

    @Test
    fun panelTextFollowsTheChosenColor() {
        // De noche, sobre blanco o amarillo, texto oscuro; sobre negro o rojo, claro.
        CarTheme.apply(true)
        for (light in intArrayOf(0xFFF1F3F4.toInt(), 0xFFBDC1C6.toInt(), 0xFFF9AB00.toInt(), 0xFFE8710A.toInt())) {
            CarTheme.setFixedPanel(light)
            assertFalse(CarTheme.isDark(light))
            assertTrue(lum(CarTheme.navText()) < 0.2)
            assertTrue(lum(CarTheme.navTextDim()) < 0.5)
        }
        for (dark in intArrayOf(0xFF000000.toInt(), 0xFF5F6368.toInt(), 0xFFC5221F.toInt(), 0xFF00695C.toInt())) {
            CarTheme.setFixedPanel(dark)
            assertTrue(CarTheme.isDark(dark))
            assertTrue(lum(CarTheme.navText()) > 0.8)
        }
    }

    @Test
    fun sixteenDistinctOpaqueColors() {
        val colors = CarTheme.PANEL_COLORS
        assertEquals(16, colors.size)
        assertEquals(16, colors.toSet().size)
        // 0 es «automático»: ninguno puede serlo, y todos opacos.
        assertTrue(colors.all { it != 0 && (it ushr 24) == 0xFF })
    }

    private fun lum(c: Int): Double = (0.2126 * ((c shr 16) and 255) + 0.7152 * ((c shr 8) and 255) + 0.0722 * (c and 255)) / 255
}
