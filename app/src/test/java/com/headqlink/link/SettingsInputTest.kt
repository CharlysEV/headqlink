package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Test

/** Campos numéricos de Ajustes › Avanzado: un número de 11 cifras cerraba la app. */
class SettingsInputTest {
    @Test
    fun numbersOutOfRangeAreRejected() {
        assertEquals(0, HomeActivity.parseSetting("", 120))
        assertEquals(0, HomeActivity.parseSetting("  ", 120))
        assertEquals(0, HomeActivity.parseSetting(null, 4096))
        assertEquals(60, HomeActivity.parseSetting(" 60 ", 120))
        assertEquals(120, HomeActivity.parseSetting("120", 120))
        assertEquals(-1, HomeActivity.parseSetting("121", 120))
        assertEquals(-1, HomeActivity.parseSetting("99999999999", 4096))
        assertEquals(-1, HomeActivity.parseSetting("-5", 4096))
        assertEquals(-1, HomeActivity.parseSetting("12a", 4096))
    }
}
