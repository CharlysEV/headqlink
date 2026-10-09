package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pantalla partida: AA junto a la barra y Web, Vídeos o TV al lado, mitad y mitad. */
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
    fun androidAutoGetsHalfOfWhatTheRailLeaves() {
        // C10: 1920 de ancho y la barra de iconos de 96: AA 912 y nuestra pantalla 912.
        assertEquals(912, CarUi.splitAaWidth(1920, 96))
        assertEquals(1920 - 96 - 912, 912)
    }
}
