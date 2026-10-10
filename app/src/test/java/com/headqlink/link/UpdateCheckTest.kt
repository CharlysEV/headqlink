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

/** Aviso de versión nueva: etiquetas de GitHub comparadas número a número, las notas en texto llano y el APK adjunto. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@RoboConfig(sdk = [35], application = android.app.Application::class)
class UpdateCheckTest {
    @Test
    fun releaseJsonGivesTheApkAsset() {
        val json = org.json.JSONObject(
            """{"tag_name":"v0.2.36-qdauto","html_url":"https://example.org/r","body":"## x",
               "assets":[{"name":"notas.txt","browser_download_url":"https://example.org/n"},
                         {"name":"HeadQLink-QDAuto-v0.2.36.apk","browser_download_url":"https://example.org/a.apk"}]}"""
        )
        val r = UpdateCheck.fromJson(json)
        assertEquals("0.2.36", r.version)
        assertEquals("https://example.org/a.apk", r.apkUrl)
        assertTrue(r.hasApk())
        // Sin adjuntos: sin APK (queda el enlace a GitHub).
        assertFalse(UpdateCheck.fromJson(org.json.JSONObject("""{"tag_name":"v0.2.1"}""")).hasApk())
    }

    @Test
    fun tagsParseNumberByNumber() {
        assertEquals(listOf(0, 2, 33), UpdateCheck.parse("v0.2.33-qdauto")!!.toList())
        assertEquals(listOf(0, 2, 9), UpdateCheck.parse("0.2.9-qdauto")!!.toList())
        assertEquals(listOf(1, 0, 0), UpdateCheck.parse("v1.0")!!.toList())
        assertNull(UpdateCheck.parse("latest"))
        assertEquals("0.2.33", UpdateCheck.clean("v0.2.33-qdauto"))
    }

    @Test
    fun thirtyIsNewerThanNine() {
        assertTrue(UpdateCheck.newer("v0.2.30-qdauto", "0.2.9-qdauto"))
        assertTrue(UpdateCheck.newer("v0.3.0-qdauto", "0.2.33-qdauto"))
        assertFalse(UpdateCheck.newer("v0.2.33-qdauto", "0.2.33-qdauto"))
        assertFalse(UpdateCheck.newer("v0.2.3-qdauto", "0.2.33-qdauto"))
        assertFalse(UpdateCheck.newer("latest", "0.2.33-qdauto"))
    }

    @Test
    fun notesBecomePlainTextWithoutTheLicenceBlock() {
        val md = """
            ## 0.2.33: color fijo del panel lateral

            ### Color del panel lateral (Auto extendido)
            - En **«Ajustes»** del coche:
              - **«Automático»:** como hasta ahora.
            - Se aplica al momento.

            ## Aviso
            Software experimental, **sin garantía**.

            SHA-256 del APK: `abc`
        """.trimIndent()
        val plain = UpdateCheck.plainNotes(md)
        assertEquals("COLOR DEL PANEL LATERAL (AUTO EXTENDIDO)\n• En «Ajustes» del coche:\n    – «Automático»: como hasta ahora.\n• Se aplica al momento.", plain)
        assertFalse(plain.contains("Aviso"))
        assertFalse(plain.contains("SHA"))
    }
}
