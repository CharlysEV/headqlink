package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Test

/** Escala de la interfaz del modo extendido según la pantalla del coche. */
class UiScaleTest {
    @Test
    fun c10AsIs() {
        assertEquals(1f, UiScale.forCar(1920, 882, 0), 0f)
        assertEquals(1f, UiScale.forCar(1920, 1080, 0), 0f)
    }

    @Test
    fun smallerScreensGetAReducedCanvas() {
        // 1280x720 (10"): lienzo de 1600x900 al 80 %.
        val s = UiScale.forCar(1280, 720, 0)
        assertEquals(0.8f, s, 0.001f)
        assertEquals(1600, UiScale.canvas(1280, s))
        assertEquals(900, UiScale.canvas(720, s))
        assertEquals(256, UiScale.toCar(320, s))
        // Muy pequeña: no baja del 60 %.
        assertEquals(UiScale.MIN_SCALE, UiScale.forCar(800, 480, 0), 0f)
    }

    @Test
    fun manualChoiceWins() {
        assertEquals(0.9f, UiScale.forCar(1920, 882, 90), 0.001f)
        assertEquals(0.7f, UiScale.forCar(1280, 720, 70), 0.001f)
        // Fuera de rango: automático.
        assertEquals(1f, UiScale.forCar(1920, 882, 120), 0f)
    }
}
