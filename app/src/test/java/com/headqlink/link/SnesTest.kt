package com.headqlink.link

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Super Nintendo: archivos, mando Bluetooth por posición, botones hacia libretro y tamaño lógico de la imagen. */
class SnesTest {
    @Test
    fun filesOfTheSnes() {
        assertEquals(GameConsole.SNES, GameConsole.of("Super Mario World.sfc"))
        assertEquals(GameConsole.SNES, GameConsole.of("Zelda.SMC"))
        assertEquals("Super Mario World", GameConsole.title("Super Mario World.sfc"))
        // Las demás siguen igual.
        assertEquals(GameConsole.NES, GameConsole.of("a.nes"))
        assertEquals(GameConsole.MD, GameConsole.of("a.md"))
        assertNull(GameConsole.of("a.txt"))
    }

    @Test
    fun bluetoothPadByPosition() {
        val s = GameConsole.SNES
        assertEquals(RetroPad.Btn.B, s.map(KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(RetroPad.Btn.A, s.map(KeyEvent.KEYCODE_BUTTON_B))
        assertEquals(RetroPad.Btn.Y, s.map(KeyEvent.KEYCODE_BUTTON_X))
        assertEquals(RetroPad.Btn.X, s.map(KeyEvent.KEYCODE_BUTTON_Y))
        assertEquals(RetroPad.Btn.L, s.map(KeyEvent.KEYCODE_BUTTON_L1))
        assertEquals(RetroPad.Btn.R, s.map(KeyEvent.KEYCODE_BUTTON_R1))
        assertEquals(RetroPad.Btn.START, s.map(KeyEvent.KEYCODE_BUTTON_START))
        assertEquals(RetroPad.Btn.UP, s.map(KeyEvent.KEYCODE_DPAD_UP))
    }

    @Test
    fun buttonsToLibretro() {
        fun m(b: RetroPad.Btn) = RetroPad.mask(b)
        assertEquals(1 shl SnesPad.J_B, SnesPad.toLibretro(m(RetroPad.Btn.B)))
        assertEquals(1 shl SnesPad.J_A, SnesPad.toLibretro(m(RetroPad.Btn.A)))
        assertEquals(1 shl SnesPad.J_L or (1 shl SnesPad.J_R), SnesPad.toLibretro(m(RetroPad.Btn.L) or m(RetroPad.Btn.R)))
        assertEquals(1 shl SnesPad.J_START or (1 shl SnesPad.J_UP), SnesPad.toLibretro(m(RetroPad.Btn.START) or m(RetroPad.Btn.UP)))
        // C y Z (solo de la Mega Drive) no llegan a la SNES.
        assertEquals(0, SnesPad.toLibretro(m(RetroPad.Btn.C) or m(RetroPad.Btn.Z)))
        // Los doce primeros bits siguen en el orden de ClownMDEmu.
        assertEquals(0, RetroPad.Btn.UP.ordinal)
        assertEquals(11, RetroPad.Btn.SELECT.ordinal)
    }

    @Test
    fun hiResKeepsTheSameSize() {
        assertEquals(256, NesScreen.NesView.logicalW(256))
        assertEquals(256, NesScreen.NesView.logicalW(512))
        assertEquals(320, NesScreen.NesView.logicalW(320))
        assertEquals(224, NesScreen.NesView.logicalH(224))
        assertEquals(224, NesScreen.NesView.logicalH(448))
        // A ×4 en el C10 (1824x882 de juego), como la NES.
        assertEquals(4, NesScreen.NesView.scale(1824, 882, 256, 224))
    }
}
