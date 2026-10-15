package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tarjetas encima de Android Auto: en la columna de la derecha, sin pisarse (lienzo de 1920 × 882). */
class CarUiFloatTest {
    private val h = 882
    private val x = 1420
    private val w = 480

    @Test
    fun rectanglesOverlapOnlyWhenTheyShareSpace() {
        assertTrue(CarUi.overlaps(intArrayOf(0, 0, 100, 100), intArrayOf(50, 50, 100, 100), 0))
        assertFalse(CarUi.overlaps(intArrayOf(0, 0, 100, 100), intArrayOf(0, 130, 100, 100), 20))
        // Con el margen, dos que casi se tocan cuentan como pisadas.
        assertTrue(CarUi.overlaps(intArrayOf(0, 0, 100, 100), intArrayOf(0, 110, 100, 100), 20))
        assertFalse(CarUi.overlaps(intArrayOf(0, 0, 100, 100), null, 20))
    }

    @Test
    fun thePlannerCardGoesInTheCenterWhenTheStopCardIsAtTheTop() {
        // La de la próxima parada, arriba a la derecha (de fábrica): la del planificador, en el centro.
        val stop = intArrayOf(1430, 20, 470, 112)
        assertEquals((h - 304) / 2, CarUi.stackY(x, w, 304, h, stop, 20))
        assertEquals((h - 304) / 2, CarUi.stackY(x, w, 304, h, null, 20))
    }

    @Test
    fun thePlannerCardMovesBelowOrAboveTheStopCard() {
        // La de la parada movida al centro: la del planificador, justo debajo.
        val middle = intArrayOf(1430, 300, 470, 112)
        assertEquals(300 + 112 + 20, CarUi.stackY(x, w, 304, h, middle, 20))
        // La de la parada más abajo del centro (no cabe debajo): encima de ella.
        val low = intArrayOf(1430, 560, 470, 112)
        assertEquals(560 - 20 - 304, CarUi.stackY(x, w, 304, h, low, 20))
        // Del todo abajo ya no pisa el centro: se queda en el centro.
        val bottom = intArrayOf(1430, 750, 470, 112)
        assertEquals((h - 304) / 2, CarUi.stackY(x, w, 304, h, bottom, 20))
        // En la otra mitad de la pantalla no estorba.
        val left = intArrayOf(200, 300, 470, 112)
        assertEquals((h - 304) / 2, CarUi.stackY(x, w, 304, h, left, 20))
    }
}
