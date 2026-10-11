package com.headqlink.link

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RoboConfig
import org.robolectric.annotation.ConscryptMode

/** Home Assistant: dirección, lectura de estados y qué hace un toque en cada tipo de elemento. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@RoboConfig(sdk = [35], application = android.app.Application::class)
class HomeAssistantTest {
    private fun e(json: String) = HomeAssistant.parse(JSONObject(json))

    @Test
    fun addressIsCleanedUp() {
        assertEquals("https://casa.example.org:8123", HomeAssistant.normalizeUrl(" casa.example.org:8123/ "))
        assertEquals("http://192.168.1.10:8123", HomeAssistant.normalizeUrl("http://192.168.1.10:8123//"))
        assertEquals("", HomeAssistant.normalizeUrl(""))
    }

    @Test
    fun statesAreParsed() {
        val t = e("""{"entity_id":"sensor.salon_temp","state":"21.46","attributes":{"friendly_name":"Salón","unit_of_measurement":"°C","device_class":"temperature"}}""")
        assertEquals("sensor", t.domain)
        assertEquals("Salón", t.name)
        assertTrue(t.value().startsWith("21") && t.value().endsWith("°C"))
        val bare = e("""{"entity_id":"light.x","state":"on"}""")
        assertEquals("light.x", bare.name)
    }

    @Test
    fun aTapDoesTheRightThing() {
        assertArrayEquals(arrayOf("light", "toggle"), HomeAssistant.action(e("""{"entity_id":"light.a","state":"off"}""")))
        assertArrayEquals(arrayOf("cover", "close_cover"), HomeAssistant.action(e("""{"entity_id":"cover.garaje","state":"open"}""")))
        assertArrayEquals(arrayOf("cover", "open_cover"), HomeAssistant.action(e("""{"entity_id":"cover.garaje","state":"closed"}""")))
        assertArrayEquals(arrayOf("lock", "unlock"), HomeAssistant.action(e("""{"entity_id":"lock.puerta","state":"locked"}""")))
        assertArrayEquals(arrayOf("scene", "turn_on"), HomeAssistant.action(e("""{"entity_id":"scene.llegada","state":"scening"}""")))
        assertArrayEquals(arrayOf("button", "press"), HomeAssistant.action(e("""{"entity_id":"button.timbre","state":"unknown"}""")))
        // Sensores y clima: solo se miran.
        assertNull(HomeAssistant.action(e("""{"entity_id":"sensor.t","state":"20"}""")))
        assertNull(HomeAssistant.action(e("""{"entity_id":"climate.casa","state":"heat"}""")))
    }

    @Test
    fun locksAndDoorsAskFirst() {
        assertTrue(HomeAssistant.needsConfirm(e("""{"entity_id":"lock.puerta","state":"locked"}""")))
        assertTrue(HomeAssistant.needsConfirm(e("""{"entity_id":"cover.entrada","state":"closed","attributes":{"device_class":"door"}}""")))
        assertFalse(HomeAssistant.needsConfirm(e("""{"entity_id":"cover.garaje","state":"closed","attributes":{"device_class":"garage"}}""")))
        assertTrue(HomeAssistant.active(e("""{"entity_id":"cover.garaje","state":"opening"}""")))
        assertFalse(HomeAssistant.active(e("""{"entity_id":"light.a","state":"off"}""")))
    }
}
