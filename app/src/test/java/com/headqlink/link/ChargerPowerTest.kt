package com.headqlink.link

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * Potencia de los cargadores sin el dato en OpenStreetMap (casi todos: en una ruta de 312 km, 94 de 99) y el filtro de
 * potencia mínima: los de las redes de solo carga rápida cuentan con su potencia típica; el resto, si se incluyen.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class)
class ChargerPowerTest {
    private fun item(net: String, name: String, kw: Double = 0.0, sockets: Int = 0) = ChargerCache.Item().apply {
        network = net
        this.name = name
        this.kw = kw
        this.sockets = sockets
    }

    @Test
    fun socketsSayDirectOrAlternating() {
        assertEquals(ChargerFilter.SOCKET_DC, ChargerFilter.socketKind("type2_combo"))
        assertEquals(ChargerFilter.SOCKET_DC, ChargerFilter.socketKind("chademo:output"))
        assertEquals(ChargerFilter.SOCKET_AC, ChargerFilter.socketKind("type2"))
        assertEquals(ChargerFilter.SOCKET_AC, ChargerFilter.socketKind("schuko"))
        assertEquals(0, ChargerFilter.socketKind("type2_combo_foo"))
    }

    @Test
    fun withoutPowerTheNetworkOrTheSocketsGiveAnEstimate() {
        // OpenStreetMap manda: con potencia, esa.
        val tagged = item("iberdrola", "Iberdrola", kw = 350.0).toCharger()
        assertEquals(350.0, tagged.maxKw, 0.0)
        assertEquals(ChargerFilter.KW_TAGGED, tagged.kwSource)
        // Redes de solo carga rápida: su potencia típica.
        val sc = item("tesla", "Tesla Supercharger Lerma").toCharger()
        assertEquals(150.0, sc.maxKw, 0.0)
        assertEquals(ChargerFilter.KW_NETWORK, sc.kwSource)
        assertEquals(180.0, item("zunder", "Zunder").toCharger().maxKw, 0.0)
        assertEquals(350.0, item("ionity", "Ionity").toCharger().maxKw, 0.0)
        // Un Tesla Destination (alterna) no es un Supercharger.
        assertEquals(11.0, item("tesla", "Tesla Destination Hotel").toCharger().maxKw, 0.0)
        assertEquals(11.0, item("tesla", "Tesla", sockets = ChargerFilter.SOCKET_AC).toCharger().maxKw, 0.0)
        // Redes mixtas: por los enchufes, y sin enchufes, desconocida.
        val dc = item("iberdrola", "Iberdrola", sockets = ChargerFilter.SOCKET_DC or ChargerFilter.SOCKET_AC).toCharger()
        assertEquals(50.0, dc.maxKw, 0.0)
        assertEquals(ChargerFilter.KW_SOCKETS, dc.kwSource)
        val ac = item("iberdrola", "Iberdrola", sockets = ChargerFilter.SOCKET_AC).toCharger()
        assertEquals(22.0, ac.maxKw, 0.0)
        assertTrue(ac.acOnly)
        val none = item("wenea", "Wenea").toCharger()
        assertEquals(0.0, none.maxKw, 0.0)
        assertEquals(ChargerFilter.KW_UNKNOWN, none.kwSource)
    }

    @Test
    fun theMinimumPowerFilterTrustsTheNetworkAndIncludesTheUnknownOnlyIfAsked() {
        val nets = setOf("tesla", "zunder", "iberdrola", "wenea")
        val sc = item("tesla", "Tesla Supercharger").toCharger()
        val zunder = item("zunder", "Zunder").toCharger()
        val iberUnknown = item("iberdrola", "Iberdrola").toCharger()
        val iberDc = item("iberdrola", "Iberdrola", sockets = ChargerFilter.SOCKET_DC).toCharger()
        val iberAc = item("iberdrola", "Iberdrola", sockets = ChargerFilter.SOCKET_AC).toCharger()
        val iber50 = item("iberdrola", "Iberdrola", kw = 50.0).toCharger()
        val repsol = item("repsol", "Repsol", kw = 150.0).toCharger()
        // Con los que no dicen su potencia: pasan los rápidos por su red y los dudosos; no los de alterna ni los de 50 kW.
        for (c in listOf(sc, zunder, iberUnknown, iberDc)) assertTrue(c.name, ChargerFilter.accepts(c, 150, nets, true))
        for (c in listOf(iberAc, iber50, repsol)) assertFalse(c.name, ChargerFilter.accepts(c, 150, nets, true))
        // Solo confirmados: los de red rápida siguen, los dudosos no.
        assertTrue(ChargerFilter.accepts(sc, 150, nets, false))
        assertTrue(ChargerFilter.accepts(zunder, 150, nets, false))
        assertFalse(ChargerFilter.accepts(iberUnknown, 150, nets, false))
        assertFalse(ChargerFilter.accepts(iberDc, 150, nets, false))
        // Sin potencia mínima, todos los de las redes elegidas.
        assertTrue(ChargerFilter.accepts(iberAc, 0, nets, false))
        assertFalse(ChargerFilter.accepts(repsol, 0, nets, true))
    }

    @Test
    fun theSocketsSurviveTheCache() {
        val it = item("iberdrola", "Iberdrola", sockets = ChargerFilter.SOCKET_DC).apply {
            id = "node9"
            lat = 41.0
            lon = -3.0
        }
        val json = ChargerCache.toJson(mapOf("205_-15" to 1L), mapOf("205_-15" to listOf(it)))
        val at = HashMap<String, Long>()
        val items = HashMap<String, List<ChargerCache.Item>>()
        ChargerCache.fromJson(json, at, items)
        assertEquals(ChargerFilter.SOCKET_DC, items["205_-15"]!![0].sockets)
    }
}
