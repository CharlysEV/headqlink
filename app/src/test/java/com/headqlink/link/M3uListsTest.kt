package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RoboConfig
import org.robolectric.annotation.ConscryptMode

/** Varias listas M3U de TV y radio: la lista única de antes entra como la primera, el JSON de ida y vuelta y los nombres. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@RoboConfig(sdk = [35], application = android.app.Application::class)
class M3uListsTest {
    @Test
    fun oldSingleListBecomesTheFirst() {
        val l = Config.m3uListsFrom(null, "https://www.ejemplo.org/tv/lista.m3u")
        assertEquals(1, l.size)
        assertEquals("ejemplo.org", l[0][0])
        assertEquals("https://www.ejemplo.org/tv/lista.m3u", l[0][1])
        assertEquals(0, Config.m3uListsFrom(null, "").size)
        assertEquals(0, Config.m3uListsFrom("no es json", "https://ejemplo.org/a.m3u").size)
    }

    @Test
    fun savedListsRoundTrip() {
        val saved = arrayOf(arrayOf("Deportes", "https://ejemplo.org/d.m3u"), arrayOf("", "content://doc/primary%3ADownload%2Fcanales.m3u8"),
            arrayOf("Sin URL", ""))
        val back = Config.m3uListsFrom(Config.webShortcutsJson(saved), "https://antigua.org/x.m3u")
        assertEquals(2, back.size)
        assertEquals("Deportes", back[0][0])
        // Archivo sin nombre: el del archivo, sin «.m3u8».
        assertEquals("canales", back[1][0])
        // Guardadas: la de antes ya no cuenta.
        assertEquals(0, Config.m3uListsFrom("[]", "https://antigua.org/x.m3u").size)
    }

    @Test
    fun selectionStaysInRange() {
        assertEquals(0, Config.clampSel(3, 0))
        assertEquals(1, Config.clampSel(5, 2))
        assertEquals(0, Config.clampSel(-1, 2))
    }
}
