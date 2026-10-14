package com.headqlink.link

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** Importes con el formato de cada idioma: siempre euros, el símbolo y los decimales donde los pone cada uno. */
class MoneyTest {
    /** Los espacios de los importes (normal, duro o estrecho según el idioma y la versión de Java) como espacio normal. */
    private fun plain(s: String) = s.replace(' ', ' ').replace(' ', ' ')

    private fun fmt(v: Double, lang: String, decimals: Int = 2) = plain(Money.format(v, decimals, Locale.forLanguageTag(lang)))

    @Test
    fun symbolAndDecimalsFollowTheLanguage() {
        assertEquals("1,23 €", fmt(1.23, "es"))
        assertEquals("1,23 €", fmt(1.23, "es-ES"))
        assertEquals("€1.23", fmt(1.23, "en"))
        assertEquals("€1.23", fmt(1.23, "en-GB"))
        assertEquals("1,23 €", fmt(1.23, "de"))
        assertEquals("1,23 €", fmt(1.23, "fr"))
        assertEquals("1,23 €", fmt(1.23, "it"))
        assertEquals("1,23 €", fmt(1.23, "pt-PT"))
        assertEquals("€ 1,23", fmt(1.23, "nl"))
        // Con un país de otra moneda, sigue siendo en euros (no se convierte: solo cambia cómo se escribe).
        assertEquals("€1.23", fmt(1.23, "en-US"))
    }

    @Test
    fun decimalsAndRounding() {
        assertEquals("≈ 12 €", "≈ " + fmt(12.4, "es", 0))
        assertEquals("€12", fmt(11.5, "en", 0))
        // Como String.format: la mitad sube.
        assertEquals("0,13 €", fmt(0.125, "es"))
        assertEquals("€1,234.50", fmt(1234.5, "en"))
        assertEquals("1.234,50 €", fmt(1234.5, "de"))
    }

    @Test
    fun pricePerUnit() {
        assertEquals("0,25 €/kWh", plain(Money.per(0.25, "kWh", Locale.forLanguageTag("es"))))
        assertEquals("€0.25/kWh", plain(Money.per(0.25, "kWh", Locale.ENGLISH)))
        assertEquals("1,65 €/l", plain(Money.per(1.649, "l", Locale.FRENCH)))
    }

    @Test
    fun partsForTheBigNumbers() {
        // Símbolo detrás: cifra grande y «€» pequeño detrás, como antes.
        assertArrayEquals(arrayOf("3,40", "€"), Money.parts(3.4, 2, Locale.forLanguageTag("es")))
        assertArrayEquals(arrayOf("3,40", "€"), Money.parts(3.4, 2, Locale.GERMAN))
        // Símbolo delante: todo en la cifra, sin unidad detrás («€3.40», no «3.40 €»).
        assertArrayEquals(arrayOf("€3.40", ""), Money.parts(3.4, 2, Locale.ENGLISH))
        val nl = Money.parts(3.4, 2, Locale.forLanguageTag("nl"))
        assertEquals("€ 3,40", plain(nl[0]))
        assertEquals("", nl[1])
    }
}
