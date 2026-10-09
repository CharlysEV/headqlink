package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pantalla partida: AA junto a la barra y Web, Vídeos o TV al lado. */
class SplitScreenTest {
    @Test
    fun onlyWebVideosAndTvCanSitNextToAndroidAuto() {
        assertTrue(CarUi.splitCapable("web"))
        assertTrue(CarUi.splitCapable("videos"))
        assertTrue(CarUi.splitCapable("tv"))
        assertFalse(CarUi.splitCapable("car"))
        assertFalse(CarUi.splitCapable("aa"))
        assertFalse(CarUi.splitCapable("photos"))
    }

    @Test
    fun androidAutoKeepsTheWidthWhereMapsStillWorks() {
        // C10: 1920x882 a 200 dpi y la barra de 96. La mitad (912) deja Maps sin toques; medido: 950 ya va bien.
        val aa = CarUi.splitAaWidth(1920, 882, 96, 200)
        assertEquals(1014, aa)
        assertTrue(aa >= 950)
        assertEquals(810, 1920 - 96 - aa)
    }

    @Test
    fun aWideScreenStaysHalfAndHalf() {
        // Con sitio de sobra, mitad y mitad.
        assertEquals(1452, CarUi.splitAaWidth(3000, 882, 96, 200))
    }

    @Test
    fun ourScreenKeepsAMinimumWidth() {
        // Pantalla estrecha: AA no se come la nuestra (al menos 560 px), salvo que ni la mitad llegue.
        assertEquals(1280 - 96 - CarUi.SPLIT_MIN_SCREEN_W, CarUi.splitAaWidth(1280, 882, 96, 200))
        assertEquals((1000 - 96) / 2, CarUi.splitAaWidth(1000, 882, 96, 200))
    }
}
