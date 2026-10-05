package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Test

/** Conexión con el coche: la zona Wi-Fi del móvil por defecto en una instalación nueva; lo ya elegido se respeta. */
class LinkModeDefaultTest {
    @Test
    fun newInstallUsesThePhoneHotspot() {
        assertEquals(Config.LINK_HOTSPOT, Config.DEFAULT_LINK)
        assertEquals(Config.LINK_HOTSPOT, Config.resolveLinkMode(null, false))
    }

    @Test
    fun aSavedChoiceIsKept() {
        for (setupDone in listOf(false, true)) {
            assertEquals(Config.LINK_P2P, Config.resolveLinkMode(Config.LINK_P2P, setupDone))
            assertEquals(Config.LINK_HOTSPOT, Config.resolveLinkMode(Config.LINK_HOTSPOT, setupDone))
        }
    }

    @Test
    fun configuredWithoutAChoiceKeepsWifiDirect() {
        // Configurada con una versión sin esta elección (solo Wi-Fi Direct): no se le cambia la conexión.
        assertEquals(Config.LINK_P2P, Config.resolveLinkMode(null, true))
    }

    @Test
    fun unknownValuesFallBackToWifiDirectAsBefore() {
        assertEquals(Config.LINK_P2P, Config.resolveLinkMode("", false))
        assertEquals(Config.LINK_P2P, Config.resolveLinkMode("otra", true))
    }
}
