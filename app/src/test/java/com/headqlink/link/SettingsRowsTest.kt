package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Lo que no es vista de la pantalla de Ajustes del móvil (SettingsRows): opciones, textos de las filas y validación. */
class SettingsRowsTest {
    @Test
    fun pricesAcceptCommaPointAndUnit() {
        assertEquals(0.25, SettingsRows.parsePrice("0,25", 1.0), 1e-9)
        assertEquals(0.25, SettingsRows.parsePrice(" 0.25 ", 1.0), 1e-9)
        assertEquals(0.25, SettingsRows.parsePrice("0,25 €", 1.0), 1e-9)
        assertEquals(0.25, SettingsRows.parsePrice("0.25 €/kWh", 1.0), 1e-9)
        assertEquals(0.5, SettingsRows.parsePrice(".5", 1.0), 1e-9)
        assertEquals(1.0, SettingsRows.parsePrice("1", 1.0), 1e-9)
        assertEquals(0.0, SettingsRows.parsePrice("0", 1.0), 1e-9)
        assertEquals(1.5, SettingsRows.parsePrice("1,5", SettingsRows.PRICE_FAST_MAX), 1e-9)
        // Redondeado al céntimo, como Config.
        assertEquals(0.26, SettingsRows.parsePrice("0,256", 1.0), 1e-9)
    }

    @Test
    fun oddPricesAreRejectedWithoutThrowing() {
        for (s in listOf(null, "", "  ", ".", "€", "abc", "-0.1", "1.2.3", "0,2a", "99999999999", "1e3", "NaN", "0,1,2")) {
            assertEquals("«$s»", -1.0, SettingsRows.parsePrice(s, 1.0), 0.0)
        }
        // Por encima del máximo: en casa hasta 1 €, en carga rápida hasta 1,5 €.
        assertEquals(-1.0, SettingsRows.parsePrice("1,01", SettingsRows.PRICE_HOME_MAX), 0.0)
        assertEquals(-1.0, SettingsRows.parsePrice("1,6", SettingsRows.PRICE_FAST_MAX), 0.0)
        assertEquals(-1.0, SettingsRows.parsePrice("12", SettingsRows.PRICE_FAST_MAX), 0.0)
    }

    @Test
    fun priceTextFollowsTheLanguage() {
        assertEquals("0,20 €/kWh", SettingsRows.priceText(0.2, Locale("es", "ES")))
        assertEquals("0.49 €/kWh", SettingsRows.priceText(0.49, Locale.US))
        assertEquals("0,20", SettingsRows.priceField(0.2, Locale("es", "ES")))
        // Lo que se pone en el campo se vuelve a leer igual.
        for (loc in listOf(Locale("es", "ES"), Locale.US, Locale.GERMANY, Locale.FRANCE)) {
            assertEquals(0.37, SettingsRows.parsePrice(SettingsRows.priceField(0.37, loc), 1.0), 1e-9)
        }
    }

    @Test
    fun uiScaleChoices() {
        assertEquals(0, SettingsRows.UI_SCALE_CHOICES[0])
        for (p in SettingsRows.UI_SCALE_CHOICES.drop(1)) {
            // Cada porcentaje elegido es el que usa UiScale tal cual.
            assertEquals(p / 100f, UiScale.forCar(1920, 882, p), 1e-6f)
        }
        val t03 = SettingsRows.uiScaleAutoT03()
        assertEquals(Math.round(UiScale.forCar(1024, 600, 0) * 100), t03)
        assertTrue(t03 in 50..100)
        assertEquals(0, SettingsRows.indexOf(SettingsRows.UI_SCALE_CHOICES, 0))
        assertEquals(-1, SettingsRows.indexOf(SettingsRows.UI_SCALE_CHOICES, 75))
    }

    @Test
    fun choiceIndexes() {
        assertEquals(1, SettingsRows.indexOf(AaSize.CHOICES, 100))
        assertEquals(-1, SettingsRows.indexOf(AaSize.CHOICES, 55))
        assertEquals(2, SettingsRows.indexOf(SettingsRows.H264, "high", 0))
        assertEquals(0, SettingsRows.indexOf(SettingsRows.H264, "raro", 0))
        assertEquals(1, SettingsRows.indexOf(SettingsRows.THERMAL, ThermalPolicy.MODE_SOFT, 0))
        for (m in SettingsRows.THERMAL) assertEquals(m, ThermalPolicy.mode(m))
        val ids = SettingsRows.profileIds()
        assertEquals("", ids[0])
        assertEquals(VideoProfile.ALL.size + 1, ids.size)
        assertEquals(-1, SettingsRows.indexOf(ids, "perfil_viejo", -1))
    }

    @Test
    fun numberSummary() {
        assertEquals("Automático", SettingsRows.numberSummary(0, "Automático"))
        assertEquals("60", SettingsRows.numberSummary(60, "Automático"))
    }

    @Test
    fun renegotiateOnlyWhenAaNeedsIt() {
        assertFalse(SettingsRows.renegotiate("car", 30, "car", 30, false, false))
        assertTrue(SettingsRows.renegotiate("car", 30, "medium", 30, false, false))
        assertTrue(SettingsRows.renegotiate("car", 30, "car", 60, false, false))
        assertTrue(SettingsRows.renegotiate("car", 30, "car", 30, true, false))
        assertTrue(SettingsRows.renegotiate("car", 30, "car", 30, false, true))
    }
}
