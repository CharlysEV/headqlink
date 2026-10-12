package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RoboConfig
import org.robolectric.annotation.ConscryptMode

/** Listas de TV: repetidos juntos con su fuente de reserva, la JSON de TDTChannels y los reproductores incrustados. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@RoboConfig(sdk = [35], application = android.app.Application::class)
class IptvListTest {
    private fun read(s: String) = IptvScreen.read(s.byteInputStream())

    @Test
    fun repeatedChannelsBecomeOneWithBackups() {
        val l = read(
            """
            #EXTM3U
            #EXTINF:-1 tvg-logo="https://a.org/la1.png" group-title="Generalistas",La 1
            https://a.org/la1.m3u8
            #EXTINF:-1 group-title="Generalistas",La 1
            https://b.org/la1.m3u8
            #EXTINF:-1 group-title="Generalistas",La 2
            https://a.org/la2.m3u8
            #EXTINF:-1 group-title="Otros",La 1
            https://c.org/la1.m3u8
            """.trimIndent()
        )
        assertEquals(3, l.size)
        assertEquals("La 1", l[0].name)
        assertEquals(listOf("https://a.org/la1.m3u8", "https://b.org/la1.m3u8"), l[0].urls)
        assertEquals("https://a.org/la1.png", l[0].logo)
        // Mismo nombre en otro grupo: otro canal.
        assertEquals("Otros", l[2].group)
    }

    @Test
    fun tdtChannelsJson() {
        val json = """
            {"countries":[{"name":"Spain","ambits":[{"name":"Generalistas","channels":[
              {"name":"La 1","logo":"https://a.org/l.png","options":[{"format":"m3u8","url":"https://a.org/la1.m3u8"},
                {"format":"m3u8","url":"https://b.org/la1.m3u8"}]},
              {"name":"Cuatro","logo":null,"options":[]},
              {"name":"Telecinco","web":"https://www.mitele.es/directo/telecinco","options":[]}]},
             {"name":"Informativos","channels":[
              {"name":"EFE","options":[{"format":"youtube","url":"https://www.youtube.com/channel/UCabc_12/live"}]},
              {"name":"Mixto","options":[{"format":"youtube","url":"https://www.youtube.com/live/VID123"},
                {"format":"stream","url":"https://x.org/directo/playlist.m3u8?t=1"}]}]}]}]}
        """
        val l = read(json)
        // Cuatro sin emisión abierta ni web: fuera; Telecinco, con la web de su directo.
        assertEquals(listOf("La 1", "Telecinco", "EFE", "Mixto"), l.map { it.name })
        assertEquals(2, l[0].urls.size)
        assertEquals(listOf(IptvScreen.WEB + "https://www.mitele.es/directo/telecinco"), l[1].urls)
        assertEquals("Informativos", l[2].group)
        assertEquals(IptvScreen.WEB + "https://www.youtube.com/channel/UCabc_12/live", l[2].urls[0])
        // El vídeo directo primero, aunque venga después.
        assertEquals("https://x.org/directo/playlist.m3u8?t=1", l[3].urls[0])
        assertTrue(l[3].urls[1].startsWith(IptvScreen.WEB))
    }

    @Test
    fun embeddedPlayers() {
        assertEquals("https://www.youtube.com/embed/live_stream?channel=UCabc_12&autoplay=1&playsinline=1",
            IptvScreen.embedFor("https://www.youtube.com/channel/UCabc_12/live"))
        assertEquals("https://www.youtube.com/embed/VID123?autoplay=1&playsinline=1",
            IptvScreen.embedFor("https://www.youtube.com/live/VID123"))
        assertEquals("https://player.twitch.tv/?channel=canal&parent=charlysev.github.io&autoplay=true&muted=false",
            IptvScreen.embedFor("https://player.twitch.tv/?channel=canal&parent=www.tdtchannels.com"))
        // Un @canal o una web: la página tal cual.
        assertNull(IptvScreen.embedFor("https://www.youtube.com/@canal/live"))
        assertNull(IptvScreen.embedFor("https://laxarxames.cat/player/8036"))
        assertTrue(IptvScreen.embedHtml("https://a.org/?x=1&y=2").contains("src=\"https://a.org/?x=1&amp;y=2\""))
    }
}
