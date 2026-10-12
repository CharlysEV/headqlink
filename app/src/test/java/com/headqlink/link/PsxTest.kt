package com.headqlink.link

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PlayStation: archivos, mando, botones hacia libretro, el disco (pistas del .cue, .m3u, .zip, BIOS) y las copias. */
class PsxTest {
    @Test
    fun filesOfThePlayStation() {
        assertEquals(GameConsole.PSX, GameConsole.of("Crash Bandicoot (Europe).cue"))
        assertEquals(GameConsole.PSX, GameConsole.of("Tekken 3.CHD"))
        assertEquals(GameConsole.PSX, GameConsole.of("FF7.m3u"))
        assertEquals(GameConsole.PSX, GameConsole.of("game.pbp"))
        assertEquals("Crash Bandicoot (Europe)", GameConsole.title("Crash Bandicoot (Europe).cue"))
        // Un .bin suelto sigue siendo de la Mega Drive (las pistas grandes las aparta el navegador por tamaño).
        assertEquals(GameConsole.MD, GameConsole.of("Sonic.bin"))
        assertTrue(GameConsole.MD_MAX_BYTES < 300L * 1024 * 1024)
    }

    @Test
    fun bluetoothPadByPositionWithTriggers() {
        val p = GameConsole.PSX
        assertEquals(RetroPad.Btn.B, p.map(KeyEvent.KEYCODE_BUTTON_A))  // ✕
        assertEquals(RetroPad.Btn.A, p.map(KeyEvent.KEYCODE_BUTTON_B))  // ○
        assertEquals(RetroPad.Btn.Y, p.map(KeyEvent.KEYCODE_BUTTON_X))  // □
        assertEquals(RetroPad.Btn.X, p.map(KeyEvent.KEYCODE_BUTTON_Y))  // △
        assertEquals(RetroPad.Btn.L, p.map(KeyEvent.KEYCODE_BUTTON_L1))
        assertEquals(RetroPad.Btn.L2, p.map(KeyEvent.KEYCODE_BUTTON_L2))
        assertEquals(RetroPad.Btn.R2, p.map(KeyEvent.KEYCODE_BUTTON_R2))
        assertEquals(RetroPad.Btn.SELECT, p.map(KeyEvent.KEYCODE_BUTTON_SELECT))
        // En la SNES los gatillos no hacen nada.
        assertNull(GameConsole.SNES.map(KeyEvent.KEYCODE_BUTTON_L2))
    }

    @Test
    fun buttonsToLibretro() {
        fun m(b: RetroPad.Btn) = RetroPad.mask(b)
        assertEquals(1 shl SnesPad.J_B, PsxPad.toLibretro(m(RetroPad.Btn.B)))
        assertEquals(1 shl PsxPad.J_L2 or (1 shl PsxPad.J_R2), PsxPad.toLibretro(m(RetroPad.Btn.L2) or m(RetroPad.Btn.R2)))
        assertEquals(1 shl SnesPad.J_L or (1 shl SnesPad.J_START), PsxPad.toLibretro(m(RetroPad.Btn.L) or m(RetroPad.Btn.START)))
        // L2 y R2 no llegan a la SNES.
        assertEquals(0, SnesPad.toLibretro(m(RetroPad.Btn.L2) or m(RetroPad.Btn.R2)))
    }

    @Test
    fun cueTracks() {
        val cue = """
            FILE "Game (Europe) (Track 1).bin" BINARY
              TRACK 01 MODE2/2352
                INDEX 01 00:00:00
            FILE "Game (Europe) (Track 2).bin" BINARY
              TRACK 02 AUDIO
                INDEX 00 00:00:00
            file sub\Extra.bin BINARY
        """.trimIndent()
        assertEquals(listOf("Game (Europe) (Track 1).bin", "Game (Europe) (Track 2).bin", "Extra.bin"), PsxDisc.cueFiles(cue))
        assertEquals(emptyList<String>(), PsxDisc.cueFiles("REM nada"))
    }

    @Test
    fun m3uDiscs() {
        val m3u = "#EXTM3U\r\nFF7 (Disc 1).cue\r\n\r\ndiscos/FF7 (Disc 2).chd\n# fin\n"
        assertEquals(listOf("FF7 (Disc 1).cue", "FF7 (Disc 2).chd"), PsxDisc.m3uFiles(m3u))
        assertTrue(PsxDisc.isDiscList(m3u))
        // Una lista de canales no es un juego.
        assertFalse(PsxDisc.isDiscList("#EXTM3U\n#EXTINF:-1,Canal\nhttp://example.com/live.m3u8\n"))
        assertFalse(PsxDisc.isDiscList("# vacía\n"))
    }

    @Test
    fun whichFileOfAZipOpens() {
        assertEquals("a.cue", PsxDisc.mainFile(listOf("a (Track 1).bin", "a.cue")))
        assertEquals("all.m3u", PsxDisc.mainFile(listOf("d1.cue", "all.m3u", "d1.bin")))
        assertEquals("x.chd", PsxDisc.mainFile(listOf("readme.txt", "x.chd")))
        assertNull(PsxDisc.mainFile(listOf("a.bin", "readme.txt")))
    }

    @Test
    fun biosAndNames() {
        assertTrue(PsxDisc.isBios("scph1001.bin"))
        assertTrue(PsxDisc.isBios("SCPH5501.BIN"))
        assertTrue(PsxDisc.isBios("psxonpsp660.bin"))
        assertFalse(PsxDisc.isBios("Crash (Track 1).bin"))
        assertEquals("a_b_c", PsxDisc.folder("a/b:c"))
        assertEquals("disco", PsxDisc.folder("  "))
        assertEquals("x.bin", PsxDisc.baseName("dir\\sub/x.bin"))
    }

    @Test
    fun keepsTheNewestCopies() {
        val names = listOf("saves", "A", "B", "bios", "C", "D")
        val used = listOf(0L, 40L, 10L, 0L, 30L, 20L)
        // Quedan A, C y D (las tres más recientes); B se borra. saves y bios nunca.
        assertEquals(listOf("B"), PsxDisc.evict(names, used, 3))
        assertEquals(emptyList<String>(), PsxDisc.evict(listOf("A", "saves"), listOf(1L, 2L), 3))
    }

    @Test
    fun playStationFramesScale() {
        // 640x480 cuenta como 320x240; en el C10 (1824x882 de juego), ×3.
        assertEquals(320, NesScreen.NesView.logicalW(640))
        assertEquals(240, NesScreen.NesView.logicalH(480))
        assertEquals(368, NesScreen.NesView.logicalW(368))
        assertEquals(3, NesScreen.NesView.scale(1824, 882, 320, 240))
        // Pero la PlayStation va a todo el alto en 4:3: 1176x882 en el C10.
        assertEquals(listOf(1176, 882), NesScreen.NesView.fit43(1824, 882).toList())
        // En una pantalla estrecha, manda el ancho.
        assertEquals(listOf(800, 600), NesScreen.NesView.fit43(800, 900).toList())
    }
}
