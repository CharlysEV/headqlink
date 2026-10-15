package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RoboConfig
import org.robolectric.annotation.ConscryptMode
import java.util.TimeZone

/** Galería del coche: álbumes, días y meses, marcas de «seguir» de 48 h y subtítulos. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@RoboConfig(sdk = [35], application = android.app.Application::class)
class MediaLibraryTest {
    private val tz = TimeZone.getTimeZone("Europe/Madrid")
    private val day = 86_400_000L
    private val now = 1_760_520_000_000L // 15-10-2025 hacia el mediodía

    private fun item(bucket: String?, date: Long, fav: Boolean = false, source: String = "ms") =
        MediaLibrary.Item(null, "x.jpg", bucket, date, 0, fav, source, null, null)

    @Test
    fun albumsComeInAFixedOrderThenByCount() {
        val items = listOf(
            item("Camera", now), item("Camera", now), item("WhatsApp Images", now), item("Download", now, fav = true),
            item("Screenshots", now), item("Viajes", now), item("Viajes", now), item("Recetas", now),
            item(null, now, source = MediaLibrary.USB),
        )
        val a = MediaLibrary.albums(items)
        assertEquals(listOf("all", "camera", "whatsapp", "download", "fav", "screenshots", "usb", "b:Viajes", "b:Recetas"),
            a.map { it.key })
        assertEquals(9, a[0].count)
        assertEquals(2, a[1].count)
        assertEquals("Viajes", a[7].name)
        // Filtrar.
        assertTrue(MediaLibrary.inAlbum(items[2], MediaLibrary.WHATSAPP))
        assertTrue(MediaLibrary.inAlbum(items[3], MediaLibrary.FAV))
        assertFalse(MediaLibrary.inAlbum(items[0], MediaLibrary.FAV))
        assertTrue(MediaLibrary.inAlbum(items[8], MediaLibrary.USB))
        assertFalse(MediaLibrary.inAlbum(items[8], MediaLibrary.CAMERA))
        assertEquals("b:Viajes", MediaLibrary.bucketKey("Viajes"))
        assertEquals(MediaLibrary.WHATSAPP, MediaLibrary.bucketKey("WhatsApp Video"))
    }

    @Test
    fun theGridHasAHeaderPerDayAndTheBarAMarkPerMonth() {
        val items = listOf(item("Camera", now), item("Camera", now - 3600_000), item("Camera", now - day),
            item("Camera", now - 40 * day))
        val rows = MediaLibrary.sections(items, tz)
        // hoy (2), ayer (1), septiembre (1): 3 encabezados + 4 fotos.
        assertEquals(7, rows.size)
        assertEquals(listOf(0, 3, 5), rows.indices.filter { rows[it][0] >= 0 })
        assertEquals(0, MediaLibrary.relativeDay(rows[0][0], now, tz))
        assertEquals(1, MediaLibrary.relativeDay(rows[3][0], now, tz))
        assertEquals(2, MediaLibrary.relativeDay(rows[5][0], now, tz))
        val months = MediaLibrary.months(rows)
        assertEquals(2, months.size)
        assertEquals(202510, months[0][0])
        assertEquals(5, months[1][1])
    }

    @Test
    fun resumeMarksLastFortyEightHours() {
        val fresh = MediaResume.Mark(754_000, 2_700_000, now - 3600_000)
        val old = MediaResume.Mark(10_000, 30_000, now - 49 * 3600_000L)
        val json = MediaResume.encode(mapOf("a" to fresh, "b" to old))
        val back = MediaResume.decode(json, now)
        assertEquals(setOf("a"), back.keys)
        assertTrue(back["a"]!!.resumable())
        assertEquals("12:34", MediaResume.clock(754_000))
        assertEquals("1:02:03", MediaResume.clock(3_723_000))
        // Un vídeo de 30 s también se puede seguir; visto entero, no.
        assertTrue(MediaResume.Mark(12_000, 30_000, now).resumable())
        assertTrue(MediaResume.Mark(29_500, 30_000, now).done())
        assertFalse(MediaResume.Mark(1_000, 30_000, now).resumable())
        assertTrue(MediaResume.decode("basura", now).isEmpty())
    }

    @Test
    fun subtitlesNextToTheVideo() {
        assertTrue(MediaLibrary.subtitleFor("Peli.mkv", "Peli.srt"))
        assertTrue(MediaLibrary.subtitleFor("Peli.mkv", "peli.es.srt"))
        assertFalse(MediaLibrary.subtitleFor("Peli.mkv", "Otra.srt"))
        assertFalse(MediaLibrary.subtitleFor("Peli.mkv", "Peli.txt"))
        assertTrue(MediaLibrary.isVideoName("viaje.MP4"))
        assertFalse(MediaLibrary.isVideoName("viaje.jpg"))
        assertNull(null)
    }
}
