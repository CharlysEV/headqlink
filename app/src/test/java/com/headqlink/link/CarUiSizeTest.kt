package com.headqlink.link

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil

/**
 * Tamaños mínimos de la interfaz del modo extendido en una pantalla pequeña (T03): lo que se toca y el texto no bajan de
 * un mínimo en px de la pantalla del coche; con escala 1 (C10), todo igual que siempre.
 */
class CarUiSizeTest {
    @After
    fun reset() {
        CarStyle.setUiScale(1f, false)
    }

    @Test
    fun scaleOneLeavesEverySizeAsItWas() {
        for (px in listOf(0, 14, 19, 24, 40, 56, 72, 84, 120)) {
            assertEquals(px, CarStyle.touch(1f, px))
            assertEquals(px, CarStyle.textPx(1f, px))
            assertEquals(px.toFloat(), CarStyle.textPx(1f, px.toFloat()), 0f)
        }
        // La de ahora, por defecto 1: igual.
        assertFalse(CarStyle.scaled())
        assertEquals(24, CarStyle.textPx(24))
        assertEquals(72, CarStyle.touch(72))
    }

    @Test
    fun t03ScaleGrowsTouchTargetsAndSmallText() {
        val s = 0.6f
        for (px in listOf(0, 24, 40, 56, 72)) {
            val t = CarStyle.touch(s, px)
            assertTrue("touch($px) = $t", t >= 48 / 0.6 - 0.01)
            assertTrue(t >= px)
            // En la pantalla del coche, al menos 48 px.
            assertTrue(UiScale.toCar(t, s) >= CarStyle.MIN_TOUCH_PX)
            val x = CarStyle.textPx(s, px)
            assertTrue("textPx($px) = $x", x >= 16 / 0.6 - 0.01)
            assertTrue(CarStyle.textPx(s, px.toFloat()) * s >= CarStyle.MIN_TEXT_PX - 0.01f)
        }
        // Exacto: 48 / 0,6 = 80 (no 81 por el redondeo del float) y 16 / 0,6 = 26,7 → 27.
        assertEquals(80, CarStyle.touch(s, 56))
        assertEquals(27, CarStyle.textPx(s, 19))
        // Lo que ya es grande se queda como está.
        assertEquals(96, CarStyle.touch(s, 96))
        assertEquals(38, CarStyle.textPx(s, 38))
        // Al 80 %: 60 y 20.
        assertEquals(60, CarStyle.touch(0.8f, 40))
        assertEquals(ceil(16 / 0.8 - 1e-3).toInt(), CarStyle.textPx(0.8f, 12))
    }

    @Test
    fun currentScaleComesFromCarUi() {
        CarStyle.setUiScale(0.6f, true)
        assertTrue(CarStyle.scaled())
        assertTrue(CarStyle.smallScreen())
        assertEquals(80, CarStyle.touch(56))
        assertEquals(27, CarStyle.textPx(24))
        // Valores raros: como escala 1.
        CarStyle.setUiScale(0f, false)
        assertFalse(CarStyle.scaled())
        CarStyle.setUiScale(1.3f, false)
        assertEquals(1f, CarStyle.uiScale(), 0f)
    }

    @Test
    fun gridColumns() {
        // C10: siempre las de siempre (también con la pantalla partida, más estrecha).
        assertEquals(4, CarStyle.gridColumns(4, 1600, 1f, CarStyle.MIN_CELL_SCREEN_PX))
        assertEquals(4, CarStyle.gridColumns(4, 500, 1f, CarStyle.MIN_CELL_SCREEN_PX))
        // T03: lienzo de 1707 al 60 % menos el panel de 320 → 1387 de ancho: 3 columnas (la TV y la radio).
        val w = UiScale.canvas(1024, 0.6f) - 320
        assertEquals(1387, w)
        assertEquals(3, CarStyle.gridColumns(4, w, 0.6f, CarStyle.MIN_CELL_SCREEN_PX))
        assertEquals(3, CarStyle.gridColumns(4, w - 36, 0.6f, CarStyle.MIN_CELL_SCREEN_PX))
        // Muy estrecho: al menos 1; nunca más de las de siempre.
        assertEquals(1, CarStyle.gridColumns(4, 100, 0.6f, CarStyle.MIN_CELL_SCREEN_PX))
        assertEquals(4, CarStyle.gridColumns(4, 4000, 0.6f, CarStyle.MIN_CELL_SCREEN_PX))
    }

    @Test
    fun panelButtons() {
        // C10, como siempre: panel completo 72; barra de iconos entre 40 y 72 según quepan.
        assertEquals(72, CarUi.navButtonHeight(1f, false, 300, 10))
        assertEquals(72, CarUi.navButtonHeight(1f, true, 2000, 10))
        assertEquals(60, CarUi.navButtonHeight(1f, true, 660, 10))
        assertEquals(40, CarUi.navButtonHeight(1f, true, 100, 10))
        // T03: hasta el mínimo táctil (80 en el lienzo = 48 en la pantalla) si cabe; si no, lo que cabe (≥ 40).
        assertEquals(80, CarUi.navButtonHeight(0.6f, false, 2000, 6))
        assertEquals(80, CarUi.navButtonHeight(0.6f, true, 2000, 6))
        assertEquals(66, CarUi.navButtonHeight(0.6f, false, 720, 10))
        assertEquals(40, CarUi.navButtonHeight(0.6f, true, 100, 10))
    }

    @Test
    fun uiScaleSettingRow() {
        // Solo en una pantalla pequeña o con un tamaño elegido a mano.
        assertFalse(SettingsScreen.showUiScale(false, 0))
        assertTrue(SettingsScreen.showUiScale(true, 0))
        assertTrue(SettingsScreen.showUiScale(false, 80))
        assertEquals("Auto", SettingsScreen.uiScaleLabel(0, "Auto"))
        assertEquals("70 %", SettingsScreen.uiScaleLabel(70, "Auto"))
        // Las opciones son las que acepta UiScale (0 o 50-100).
        for (p in SettingsScreen.UI_SCALE_CHOICES) assertTrue(p == 0 || p in 50..100)
        assertEquals(0.7f, UiScale.forCar(1024, 498, 70), 0.001f)
    }

    @Test
    fun t03ScreenIsSmall() {
        // El T03 (1024x498): 60 %, lienzo 1707x830. El C10 no.
        val s = UiScale.forCar(1024, 498, 0)
        assertEquals(0.6f, s, 0f)
        assertEquals(1707, UiScale.canvas(1024, s))
        assertEquals(830, UiScale.canvas(498, s))
        assertEquals(1f, UiScale.forCar(1920, 882, 0), 0f)
    }
}
