package com.headqlink.link

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RoboConfig
import org.robolectric.annotation.ConscryptMode

/** Navegador del coche: dirección o búsqueda, dominio, visitadas hace poco y color de la inicial (Uri y org.json: Robolectric). */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@RoboConfig(sdk = [35], application = android.app.Application::class)
class WebBrowserTest {
    @Test
    fun addressOrSearch() {
        assertNull(WebScreen.toUrl("  "))
        assertEquals("https://ejemplo.org/a", WebScreen.toUrl("https://ejemplo.org/a"))
        assertEquals("http://ejemplo.org", WebScreen.toUrl("http://ejemplo.org"))
        assertEquals("https://ejemplo.org", WebScreen.toUrl("ejemplo.org"))
        assertEquals("https://m.ejemplo.co.uk/b?c=1", WebScreen.toUrl("m.ejemplo.co.uk/b?c=1"))
        // Lo dicho en voz alta o con espacios es una búsqueda, aunque lleve un punto.
        assertEquals("https://www.google.com/search?q=tiempo%20ma%C3%B1ana", WebScreen.toUrl("tiempo mañana"))
        assertTrue(WebScreen.toUrl("versión 2.0 novedades")!!.startsWith("https://www.google.com/search?q="))
        // Un número con punto no es una web.
        assertTrue(WebScreen.toUrl("3.5")!!.startsWith("https://www.google.com/search?q="))
    }

    @Test
    fun hostWithoutWww() {
        assertEquals("ejemplo.org", WebScreen.host("https://www.ejemplo.org/a/b"))
        assertEquals("m.ejemplo.org", WebScreen.host("https://m.ejemplo.org"))
        assertEquals("", WebScreen.host("no es una dirección"))
    }

    @Test
    fun recentKeepsOnePerSiteNewestFirstAndCaps() {
        var json = "[]"
        json = WebScreen.recentAdd(json, "https://a.org/1", "A1", 3)
        json = WebScreen.recentAdd(json, "https://b.org/1", "B1", 3)
        json = WebScreen.recentAdd(json, "https://www.a.org/2", "A2", 3)
        var a = JSONArray(json)
        assertEquals(2, a.length())
        assertEquals("https://www.a.org/2", a.getJSONObject(0).getString("u"))
        assertEquals("https://b.org/1", a.getJSONObject(1).getString("u"))
        json = WebScreen.recentAdd(json, "https://c.org", "C", 3)
        json = WebScreen.recentAdd(json, "https://d.org", "D", 3)
        a = JSONArray(json)
        assertEquals(3, a.length())
        assertEquals("https://d.org", a.getJSONObject(0).getString("u"))
        // Lo guardado roto no tumba nada: queda solo la visita nueva.
        assertEquals(1, JSONArray(WebScreen.recentAdd("roto", "https://e.org", "", 3)).length())
    }

    @Test
    fun desktopUserAgentKeepsChromeVersion() {
        val mobile = "Mozilla/5.0 (Linux; Android 16; SM-S938B Build/BP2A; wv) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Version/4.0 Chrome/141.0.7390.122 Mobile Safari/537.36"
        val pc = WebScreen.desktopUa(mobile)
        assertEquals(
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.7390.122 Safari/537.36",
            pc,
        )
        // Sin versión de Chrome reconocible, sigue siendo de ordenador.
        assertTrue(!WebScreen.desktopUa("raro").contains("Android"))
    }

    @Test
    fun textSizeSteps() {
        assertEquals(115, WebScreen.nextZoom(100))
        assertEquals(150, WebScreen.nextZoom(130))
        assertEquals(85, WebScreen.nextZoom(150))
        assertEquals(85, WebScreen.nextZoom(70))
    }

    @Test
    fun googleSuggestions() {
        val json = """["mario",["mario kart","mario bros","","super mario"],[],{}]"""
        assertEquals(listOf("mario kart", "mario bros", "super mario"), WebScreen.parseSuggestions(json, 6))
        assertEquals(listOf("mario kart"), WebScreen.parseSuggestions(json, 1))
        assertTrue(WebScreen.parseSuggestions("roto", 6).isEmpty())
    }

    @Test
    fun adBlockKeepsVideosAndSites() {
        assertTrue(AdBlock.isAd("securepubads.g.doubleclick.net"))
        assertTrue(AdBlock.isAd("www.googletagmanager.com"))
        assertTrue(AdBlock.isAd("cdn.taboola.com"))
        // Los vídeos y las webs, no.
        assertTrue(!AdBlock.isAd("rr3---sn-h5q7kn7z.googlevideo.com"))
        assertTrue(!AdBlock.isAd("www.youtube.com"))
        assertTrue(!AdBlock.isAd("www.disneyplus.com"))
        assertTrue(!AdBlock.isAd("notdoubleclick.net"))
        assertTrue(!AdBlock.isAd(null))
    }

    @Test
    fun letterColourIsStable() {
        assertEquals(WebScreen.colorFor("ejemplo.org"), WebScreen.colorFor("ejemplo.org"))
        assertEquals(0xFF shl 24, WebScreen.colorFor("") and (0xFF shl 24))
    }
}
