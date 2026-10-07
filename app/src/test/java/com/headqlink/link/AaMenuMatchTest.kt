package com.headqlink.link

import com.headqlink.link.AaMenuMatch.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lo que pulsa la automatización en los ajustes de Android Auto, por su texto en todos los idiomas de HeadQLink y sus
 * variantes conocidas (AA cambia los textos entre versiones: 17.8, 17.9), sin mayúsculas, tildes ni signos.
 */
class AaMenuMatchTest {
    @Test
    fun everyKnownStartTextIsTheStartItem() {
        for (t in AaMenuMatch.KNOWN_START) assertEquals(t, Kind.START, AaMenuMatch.serverItem(t))
    }

    @Test
    fun everyKnownStopTextIsTheStopItem() {
        for (t in AaMenuMatch.KNOWN_STOP) assertEquals(t, Kind.STOP, AaMenuMatch.serverItem(t))
    }

    @Test
    fun variantsInEveryShippedLanguage() {
        // es: con y sin «de la», «Detener».
        assertEquals(Kind.START, AaMenuMatch.serverItem("Iniciar servidor de la unidad principal"))
        assertEquals(Kind.STOP, AaMenuMatch.serverItem("Parar servidor unidad principal"))
        assertEquals(Kind.STOP, AaMenuMatch.serverItem("Detener el servidor de la unidad principal"))
        // en
        assertEquals(Kind.START, AaMenuMatch.serverItem("Start head unit server"))
        assertEquals(Kind.STOP, AaMenuMatch.serverItem("Stop head unit server"))
        assertEquals(Kind.START, AaMenuMatch.serverItem("Start Headunit Server"))
        // pt-PT / pt-BR
        assertEquals(Kind.START, AaMenuMatch.serverItem("Iniciar servidor da unidade principal"))
        assertEquals(Kind.STOP, AaMenuMatch.serverItem("Parar servidor da unidade principal"))
        assertEquals(Kind.STOP, AaMenuMatch.serverItem("Interromper o servidor da unidade principal"))
    }

    @Test
    fun caseDiacriticsAndPunctuationDoNotMatter() {
        assertEquals(Kind.START, AaMenuMatch.serverItem("INICIAR SERVIDOR DE LA UNIDAD PRINCIPAL"))
        assertEquals(Kind.STOP, AaMenuMatch.serverItem("  Parar   servidor unidad principal… "))
        assertEquals(Kind.START, AaMenuMatch.serverItem("Iniciar servidor da unidade principal."))
        assertEquals(Kind.START, AaMenuMatch.serverItem("Start head-unit server"))
        // Tildes compuestas o precompuestas, igual.
        assertEquals("mas opciones", AaMenuMatch.normalize("Más opciones"))
        assertEquals("mas opciones", AaMenuMatch.normalize("Más opciones"))
        assertEquals("mais opcoes", AaMenuMatch.normalize("Mais opções"))
    }

    @Test
    fun textOrContentDescription() {
        assertEquals(Kind.STOP, AaMenuMatch.serverItem(null, "Stop head unit server"))
        assertEquals(Kind.START, AaMenuMatch.serverItem("Start head unit server", null))
        assertEquals(Kind.NONE, AaMenuMatch.serverItem(null, null))
    }

    @Test
    fun otherMenuItemsAreNotTheServer() {
        for (t in listOf(
            "Configuración de desarrollador", "Salir del modo de desarrollador", "Ayuda y comentarios", "Developer settings",
            "Quit developer mode", "Definições de programador", "Configurações do desenvolvedor", "", "Parar", "Servidor",
        )) {
            assertEquals(t, Kind.NONE, AaMenuMatch.serverItem(t))
        }
        // «Stop» dentro de otra palabra no es parar.
        assertEquals(Kind.START, AaMenuMatch.serverItem("Iniciar servidor de la unidad principal (stopwatch)"))
    }

    @Test
    fun developerItemsInEveryLanguage() {
        assertTrue(AaMenuMatch.isDeveloperItem("Configuración de desarrollador"))
        assertTrue(AaMenuMatch.isDeveloperItem("Salir del modo de desarrollador"))
        assertTrue(AaMenuMatch.isDeveloperItem("Developer settings"))
        assertTrue(AaMenuMatch.isDeveloperItem("Definições de programador"))
        assertTrue(AaMenuMatch.isDeveloperItem("Sair do modo de desenvolvedor"))
        assertFalse(AaMenuMatch.isDeveloperItem("Ayuda y comentarios"))
        assertFalse(AaMenuMatch.isDeveloperItem(null))
    }

    @Test
    fun overflowButtonByIdClassOrDescription() {
        // Lo estable primero: resource-id o el botón estándar de desbordamiento.
        assertTrue(AaMenuMatch.isOverflow(null, "com.google.android.projection.gearhead:id/overflow_menu", "android.widget.ImageView"))
        assertTrue(AaMenuMatch.isOverflow(null, "com.google.android.projection.gearhead:id/action_overflow", null))
        assertTrue(AaMenuMatch.isOverflow(null, null, "androidx.appcompat.widget.ActionMenuPresenter\$OverflowMenuButton"))
        // Descripción en cada idioma.
        assertTrue(AaMenuMatch.isOverflow("Más opciones", null, null))
        assertTrue(AaMenuMatch.isOverflow("More options", null, null))
        assertTrue(AaMenuMatch.isOverflow("Mais opções", null, null))
        assertTrue(AaMenuMatch.isOverflow("MÁS OPCIONES", null, null))
        assertTrue(AaMenuMatch.isOverflow("Opciones", null, null))
        // Otros botones, no.
        assertFalse(AaMenuMatch.isOverflow("Navegar hacia arriba", null, "android.widget.ImageButton"))
        assertFalse(AaMenuMatch.isOverflow("Buscar", "com.google.android.projection.gearhead:id/search", null))
        assertFalse(AaMenuMatch.isOverflow(null, null, null))
        assertFalse(AaMenuMatch.isOverflow("Opciones de la pantalla", null, null))
    }

    @Test
    fun dumpIsCompactAndWithoutPersonalData() {
        assertEquals(
            "TextView «Iniciar servidor de la unidad principal»",
            AaMenuMatch.describe("android.widget.TextView", "Iniciar servidor de la unidad principal", null, null, true),
        )
        assertEquals(
            "ImageView desc=«Más opciones» id=overflow_menu",
            AaMenuMatch.describe("android.widget.ImageView", null, "Más opciones", "com.google.android.projection.gearhead:id/overflow_menu", true),
        )
        // Pantalla de ajustes: sin texto.
        assertEquals("TextView id=title", AaMenuMatch.describe("android.widget.TextView", "Mi coche", null, "x:id/title", false))
        // Correos y números largos tapados; textos recortados a 40.
        assertEquals("…@… #", AaMenuMatch.scrub("someone@example.com 123456789"))
        val long = AaMenuMatch.scrub("x".repeat(80))
        assertEquals(40, long.length)
        assertTrue(long.endsWith("…"))
    }
}
