package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Filtro de cargadores de la Ruta: reconocer la red por las etiquetas de OpenStreetMap, potencia mínima y redes. */
class ChargerFilterTest {
    @Test
    fun networksFromOsmTags() {
        assertEquals("tesla", ChargerFilter.classify("Tesla, Inc.", "", "Tesla", "Tesla Supercharger Bailén"))
        assertEquals("tesla", ChargerFilter.classify("", "", "", "Supercharger Alcalá"))
        assertEquals("zunder", ChargerFilter.classify("", "Zunder", "Easycharger S.A.", "Zunder Écija"))
        assertEquals("ionity", ChargerFilter.classify("IONITY", "", "", ""))
        assertEquals("endesa", ChargerFilter.classify("", "", "Endesa X Way", ""))
        assertEquals("iberdrola", ChargerFilter.classify("", "", "Iberdrola | bp pulse", ""))
        assertEquals("moeve", ChargerFilter.classify("", "", "Cepsa", ""))
        assertEquals("bp", ChargerFilter.classify("bp pulse", "", "", ""))
        // «bp» suelto dentro de otra palabra no es bp pulse.
        assertEquals(ChargerFilter.OTHER, ChargerFilter.classify("", "", "Ayuntamiento", "Punto de recarga"))
        assertEquals(ChargerFilter.OTHER, ChargerFilter.classify(null, null, null, null))
    }

    @Test
    fun acceptsByPowerAndNetwork() {
        val none = emptySet<String>()
        assertTrue(ChargerFilter.accepts(22.0, "other", 0, none))
        assertTrue(ChargerFilter.accepts(0.0, "other", 0, none)) // sin potencia: solo con «cualquiera»
        assertFalse(ChargerFilter.accepts(0.0, "other", 50, none))
        assertFalse(ChargerFilter.accepts(49.0, "zunder", 50, none))
        assertTrue(ChargerFilter.accepts(150.0, "zunder", 50, none))
        val nets = setOf("tesla", "zunder")
        assertTrue(ChargerFilter.accepts(250.0, "tesla", 100, nets))
        assertFalse(ChargerFilter.accepts(300.0, "ionity", 100, nets))
        assertTrue(ChargerFilter.accepts(50.0, "", 0, setOf(ChargerFilter.OTHER)))
    }

    @Test
    fun countsMostFirstOtherLast() {
        val c = ChargerFilter.counts(listOf("other", "zunder", "tesla", "zunder", "other", "other", "ionity", "zunder"))
        assertEquals(listOf("zunder", "tesla", "ionity", "other"), c.map { it.key })
        assertEquals("zunder", c[0].key)
        assertEquals(3, c[0].value)
        assertEquals(ChargerFilter.OTHER, c.last().key)
    }

    @Test
    fun savedChoices() {
        assertEquals("tesla,zunder", ChargerFilter.formatNetworks(linkedSetOf("zunder", "tesla")))
        assertEquals(setOf("tesla", "zunder"), ChargerFilter.parseNetworks(" tesla, zunder ,"))
        assertTrue(ChargerFilter.parseNetworks("").isEmpty())
        assertEquals(50, ChargerFilter.snapMinKw(60))
        assertEquals(150, ChargerFilter.snapMinKw(400))
        assertEquals(0, ChargerFilter.snapMinKw(-5))
        assertEquals("Endesa X", ChargerFilter.label("endesa"))
    }
}
