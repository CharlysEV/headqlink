package com.headqlink.link

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config as RoboConfig
import org.robolectric.annotation.ConscryptMode
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/** Guía de programación de la TV y copia de los ajustes. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@RoboConfig(sdk = [35], application = android.app.Application::class)
class EpgBackupTest {
    private val xml = """
        <?xml version='1.0' encoding='utf-8'?>
        <tv>
          <channel id="La1.TV"><display-name>La1.TV</display-name></channel>
          <channel id="Antena3.TV"><display-name>Antena 3</display-name></channel>
          <programme channel="La1.TV" start="20261013125500 +0000" stop="20261013133000 +0000">
            <title>Telediario 1</title><desc>Noticias</desc>
          </programme>
          <programme channel="La1.TV" start="20261013133000 +0000" stop="20261013150000 +0000">
            <title>Corazón</title>
          </programme>
          <programme channel="Antena3.TV" start="20261013150000 +0200" stop="20261013160000 +0200">
            <title>Noticias &amp; más</title>
          </programme>
          <programme channel="La1.TV" start="20261012100000 +0000" stop="20261012110000 +0000">
            <title>Ayer</title>
          </programme>
        </tv>
    """.trimIndent()

    private fun utc(s: String) = Epg.parseTime("$s +0000")

    @Test
    fun xmltvTimes() {
        assertEquals(utc("20261013130000"), Epg.parseTime("20261013150000 +0200"))
        assertEquals(utc("20261013170000"), Epg.parseTime("20261013120000 -0500"))
        assertEquals(utc("20261013120000"), Epg.parseTime("20261013120000"))
        assertEquals(-1L, Epg.parseTime("ayer"))
        assertEquals(-1L, Epg.parseTime(null))
    }

    @Test
    fun whatIsOnNow() {
        val now = utc("20261013131000")
        val e = Epg.parse(xml.byteInputStream(), now)
        assertEquals("Telediario 1", e.now("La1.TV", "La 1", now)!!.title)
        assertEquals("Corazón", e.next("La1.TV", "La 1", now)!!.title)
        // Sin id en la lista: por el nombre (sin espacios, mayúsculas ni «HD»).
        assertEquals("Telediario 1", e.now(null, "LA 1 HD", now)!!.title)
        // Antena 3 empieza a las 13:00 UTC (15:00 +0200), y se busca por su display-name.
        assertEquals("Noticias & más", e.now(null, "Antena 3", utc("20261013133000"))!!.title)
        assertNull(e.now(null, "Antena 3", utc("20261013125000")))
        assertNull(e.now("Otro.TV", "Otro", now))
        // Lo de ayer no se guarda (fuera del rato que interesa).
        assertNull(e.now("La1.TV", null, utc("20261012103000")))
    }

    @Test
    fun gzippedGuide() {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(xml.toByteArray()) }
        val now = utc("20261013140000")
        val e = Epg.parse(bos.toByteArray().inputStream(), now)
        assertEquals(2, e.channels())
        assertEquals("Corazón", e.now("La1.TV", null, now)!!.title)
    }

    @Test
    fun listsSayWhereTheGuideIs() {
        val m3u = "#EXTM3U @LaQuay https://github.com/LaQuay/TDTChannels\n" +
                "#EXTM3U url-tvg=\"https://www.tdtchannels.com/epg/TV.xml.gz\"\n" +
                "#EXTINF:-1 tvg-id=\"La1.TV\" group-title=\"Generalistas\",La 1\nhttps://a.org/la1.m3u8\n"
        val url = arrayOfNulls<String>(1)
        val l = IptvScreen.read(m3u.byteInputStream(), url)
        assertEquals("https://www.tdtchannels.com/epg/TV.xml.gz", url[0])
        assertEquals("La1.TV", l[0].epg)
        assertNull(IptvScreen.m3uEpgUrl("#EXTM3U\n#EXTINF:-1,La 1\nhttps://a.org/x.m3u8"))
        assertEquals("https://x.org/g.xml", IptvScreen.m3uEpgUrl("#EXTM3U x-tvg-url=\"https://x.org/g.xml,https://y.org/h.xml\""))

        val json = """{"epg":{"xml":"https://t.org/TV.xml","xml.gz":"https://t.org/TV.xml.gz"},
            "countries":[{"ambits":[{"name":"G","channels":[{"name":"La 1","epg_id":"La1.TV",
            "options":[{"format":"m3u8","url":"https://a.org/la1.m3u8"}]}]}]}]}"""
        val l2 = IptvScreen.read(json.byteInputStream(), url)
        assertEquals("https://t.org/TV.xml.gz", url[0])
        assertEquals("La1.TV", l2[0].epg)
    }

    @Test
    fun backupCarriesSettingsButNothingPersonal() {
        val ctx = RuntimeEnvironment.getApplication()
        Str.init(ctx)
        val cfg = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val car = ctx.getSharedPreferences(CarCloudStore.PREFS, Context.MODE_PRIVATE)
        cfg.edit().clear()
            .putString("iptv_lists", "[[\"TDT\",\"https://t.org/tv.json\"],[\"Mía\",\"content://docs/1\"]]")
            .putInt("iptv_list_sel", 1)
            .putString("tv_favs", "[{\"name\":\"La 1\",\"url\":\"https://a.org/la1.m3u8\"}]")
            .putString(Config.WEB_SHORTCUTS, "[[\"YouTube\",\"https://m.youtube.com\"]]")
            .putFloat("price_fast_kwh", 0.39f)
            .putBoolean("trip_notice", false)
            .putString("recent_places", "[{\"lat\":40.1,\"lon\":-3.2}]")
            .putString("route_calib", "[1,2]")
            .putString("uuid", "abc")
            .putString(Config.NES_TREE, "content://tree/roms")
            .putBoolean("responsibility_accepted", true)
            .commit()
        car.edit().clear().putString("profile", "c10_promax").putString("car_type", "2D4")
            .putString("email_masked", "x***@y").commit()

        val text = SettingsBackup.export(cfg, car, "0.2.63", 0L)
        for (bad in listOf("recent_places", "40.1", "route_calib", "uuid", "content://", "email", "car_type", "responsibility")) {
            assertFalse(bad, text.contains(bad))
        }
        val root = SettingsBackup.parse(text)
        val s = SettingsBackup.summary(root)
        assertEquals(1, s.tvLists)
        assertEquals(1, s.favorites)
        assertEquals(1, s.bookmarks)

        // Otro móvil: lo importa encima de lo suyo.
        cfg.edit().clear().putString("recent_places", "[]").putFloat("price_fast_kwh", 0.49f).commit()
        car.edit().clear().commit()
        val r = SettingsBackup.apply(cfg, car, root)
        assertTrue(r.settings >= 5)
        val lists = org.json.JSONArray(cfg.getString("iptv_lists", null))
        assertEquals(1, lists.length())
        assertEquals("https://t.org/tv.json", lists.getJSONArray(0).getString(1))
        assertEquals(0.39f, cfg.getFloat("price_fast_kwh", 0f), 1e-6f)
        assertFalse(cfg.getBoolean("trip_notice", true))
        assertEquals(1, cfg.getInt("iptv_list_sel", 0))
        assertEquals("[]", cfg.getString("recent_places", null))
        assertEquals("c10_promax", car.getString("profile", null))

        // Un archivo tocado no cuela lo que no viaja.
        val evil = SettingsBackup.parse(
            """{"app":"qonecta-settings","format":1,"cfg":{"uuid":["s","x"],"nes_tree":["s","content://a"],
               "update_url":["s","https://evil"],"price_kwh":["f",0.1]},"car":{"car_type":["s","2D6"]}}"""
        )
        SettingsBackup.apply(cfg, car, evil)
        assertNull(cfg.getString("uuid", null))
        assertNull(cfg.getString("update_url", null))
        assertNull(car.getString("car_type", null))
        assertEquals(0.1f, cfg.getFloat("price_kwh", 0f), 1e-6f)
    }

    @Test(expected = org.json.JSONException::class)
    fun notABackup() {
        SettingsBackup.parse("{\"countries\":[]}")
    }
}
