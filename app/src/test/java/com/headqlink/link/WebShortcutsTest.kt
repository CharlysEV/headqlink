package com.headqlink.link

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RoboConfig
import org.robolectric.annotation.ConscryptMode

/** Marcadores de la pantalla Web: los de serie si no hay nada guardado, y el JSON guardado de ida y vuelta (org.json: Robolectric). */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@RoboConfig(sdk = [35], application = android.app.Application::class)
class WebShortcutsTest {
    @Test
    fun defaultsWhenNothingSavedOrBroken() {
        assertArrayEquals(WebScreen.DEFAULT_SHORTCUTS, Config.webShortcutsFrom(null))
        assertArrayEquals(WebScreen.DEFAULT_SHORTCUTS, Config.webShortcutsFrom("no es json"))
    }

    @Test
    fun savedListRoundTripsAndFillsInWhatIsMissing() {
        val saved = arrayOf(arrayOf("Mi web", "https://ejemplo.org/a"), arrayOf("", "ejemplo.net/b"))
        val back = Config.webShortcutsFrom(Config.webShortcutsJson(saved))
        assertEquals(2, back.size)
        assertEquals("Mi web", back[0][0])
        assertEquals("https://ejemplo.org/a", back[0][1])
        // Sin nombre: el dominio; sin «https://»: se añade.
        assertEquals("ejemplo.net", back[1][0])
        assertEquals("https://ejemplo.net/b", back[1][1])
        // Sin URL, fuera; y una lista vacía es válida (ningún marcador).
        assertEquals(0, Config.webShortcutsFrom("[[\"x\",\"\"]]").size)
        assertEquals(0, Config.webShortcutsFrom("[]").size)
    }
}
