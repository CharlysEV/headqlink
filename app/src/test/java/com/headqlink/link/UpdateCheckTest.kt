package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Aviso de versión nueva: etiquetas de GitHub comparadas número a número, y las notas en texto llano. */
class UpdateCheckTest {
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
