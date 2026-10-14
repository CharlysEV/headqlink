package com.headqlink.link

import android.view.Display
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pantalla del móvil apagada → HeadQLink dibuja él la interfaz; y la conexión recomendada según el modo. */
class PhoneOffRendererTest {
    @Test
    fun theUiIsDrawnByHandOnlyWithThePhoneDisplayOff() {
        assertTrue(PhoneOffRenderer.phoneOff(Display.STATE_OFF))
        assertTrue(PhoneOffRenderer.phoneOff(Display.STATE_DOZE_SUSPEND))
        // Encendida, o en reposo con el AOD (lo que hace Samsung cargando): Android sigue componiendo.
        assertFalse(PhoneOffRenderer.phoneOff(Display.STATE_ON))
        assertFalse(PhoneOffRenderer.phoneOff(Display.STATE_DOZE))
        assertFalse(PhoneOffRenderer.phoneOff(Display.STATE_UNKNOWN))
    }

    @Test
    fun theDozeFlappingWhileChargingDoesNotSwitchBackToAndroid() {
        // Ya dibujando a mano: el AOD de la carga (DOZE ↔ DOZE_SUSPEND) no lo corta; solo la pantalla encendida.
        assertTrue(PhoneOffRenderer.stayOff(Display.STATE_DOZE))
        assertTrue(PhoneOffRenderer.stayOff(Display.STATE_DOZE_SUSPEND))
        assertTrue(PhoneOffRenderer.stayOff(Display.STATE_OFF))
        assertFalse(PhoneOffRenderer.stayOff(Display.STATE_ON))
        assertFalse(PhoneOffRenderer.stayOff(Display.STATE_UNKNOWN))
    }

    @Test
    fun theRecommendedConnectionDependsOnTheMode() {
        assertEquals(Config.LINK_USB, Config.recommendedLink(Config.MODE_AA_EXT))
        assertEquals(Config.LINK_HOTSPOT, Config.recommendedLink(Config.MODE_AA))
    }
}
