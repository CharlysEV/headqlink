package com.headqlink.link

import android.view.KeyEvent
import com.grapeshot.halfnes.ui.PuppetController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RoboConfig
import org.robolectric.annotation.ConscryptMode

/** Mando del emulador NES: teclas de Android → botones NES, ejes de la cruceta, y el navegador de ROMs. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@RoboConfig(sdk = [35], application = android.app.Application::class)
class NesInputTest {
    @Test
    fun gamepadKeysMapToNesButtons() {
        NesInput.console = GameConsole.NES
        assertEquals(RetroPad.Btn.UP, NesInput.map(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(RetroPad.Btn.A, NesInput.map(KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(RetroPad.Btn.B, NesInput.map(KeyEvent.KEYCODE_BUTTON_X))
        assertEquals(RetroPad.Btn.START, NesInput.map(KeyEvent.KEYCODE_BUTTON_START))
        assertEquals(RetroPad.Btn.SELECT, NesInput.map(KeyEvent.KEYCODE_BUTTON_SELECT))
        assertNull(NesInput.map(KeyEvent.KEYCODE_VOLUME_UP))
        assertNull(NesInput.map(KeyEvent.KEYCODE_BUTTON_L1))
    }

    @Test
    fun megaDriveUsesTheThreeFaceButtonsInARow() {
        // X, A, B del mando (izquierda, abajo, derecha) → A, B, C; LB, Y, RB → X, Y, Z; Select → Mode.
        assertEquals(RetroPad.Btn.A, GameConsole.MD.map(KeyEvent.KEYCODE_BUTTON_X))
        assertEquals(RetroPad.Btn.B, GameConsole.MD.map(KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(RetroPad.Btn.C, GameConsole.MD.map(KeyEvent.KEYCODE_BUTTON_B))
        assertEquals(RetroPad.Btn.X, GameConsole.MD.map(KeyEvent.KEYCODE_BUTTON_L1))
        assertEquals(RetroPad.Btn.Y, GameConsole.MD.map(KeyEvent.KEYCODE_BUTTON_Y))
        assertEquals(RetroPad.Btn.Z, GameConsole.MD.map(KeyEvent.KEYCODE_BUTTON_R1))
        assertEquals(RetroPad.Btn.SELECT, GameConsole.MD.map(KeyEvent.KEYCODE_BUTTON_SELECT))
        // El orden de bits es el de ClownMDEmu: arriba 0 … A 4, B 5, C 6 … Start 10, Mode 11.
        assertEquals(1 shl 4, RetroPad.mask(RetroPad.Btn.A))
        assertEquals(1 shl 6, RetroPad.mask(RetroPad.Btn.C))
        assertEquals(1 shl 10, RetroPad.mask(RetroPad.Btn.START))
        assertEquals(1 shl 11, RetroPad.mask(RetroPad.Btn.SELECT))
    }

    @Test
    fun keysOnlyCountWhileAGameIsRunning() {
        NesInput.pad = null
        assertFalse(NesInput.key(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.ACTION_DOWN))
        val pad = RetroPad()
        NesInput.newGame(pad, GameConsole.NES)
        assertTrue(NesInput.key(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.ACTION_DOWN))
        assertEquals(RetroPad.mask(RetroPad.Btn.A), pad.bits())
        assertFalse(NesInput.key(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_DOWN))
        NesInput.pad = null
    }

    @Test
    fun nesByteKeepsNestopiasBitOrder() {
        // Nestopia lee el mando como un byte: A, B, Select, Start, arriba, abajo, izquierda, derecha (bit 0 → 7).
        val pad = RetroPad()
        pad.press(RetroPad.Btn.A)
        pad.press(RetroPad.Btn.RIGHT)
        assertEquals(0x81, RetroPad.nesBits(pad.bits()))
        pad.axes(0f, -1f)
        assertEquals(0x91, RetroPad.nesBits(pad.bits()))
        pad.axes(0f, 0f)
        pad.release(RetroPad.Btn.A)
        assertEquals(0x80, RetroPad.nesBits(pad.bits()))
        for (b in listOf(RetroPad.Btn.START, RetroPad.Btn.SELECT, RetroPad.Btn.B, RetroPad.Btn.DOWN, RetroPad.Btn.LEFT)) pad.press(b)
        assertEquals(0xEE, RetroPad.nesBits(pad.bits()))
        // C, X, Y, Z no existen en la NES.
        pad.press(RetroPad.Btn.C)
        assertEquals(0xEE, RetroPad.nesBits(pad.bits()))
        pad.releaseAll()
        assertEquals(0, pad.bits())
    }

    @Test
    fun aTapShorterThanAFrameStillCounts() {
        val pad = RetroPad()
        pad.press(RetroPad.Btn.START)
        pad.release(RetroPad.Btn.START)
        // Pulsado y soltado entre dos fotogramas: el siguiente lo ve una vez, y ya no.
        assertEquals(RetroPad.mask(RetroPad.Btn.START), pad.read())
        assertEquals(0, pad.read())
    }

    @Test
    fun halfNesPuppetFollowsThePad() {
        val puppet = PuppetController()
        val pad = RetroPad(puppet)
        pad.press(RetroPad.Btn.A)
        puppet.output(true)
        assertEquals(0x01, puppet.peekOutput() and 0xFF)
        pad.release(RetroPad.Btn.A)
        puppet.output(true)
        assertEquals(0, puppet.peekOutput() and 0xFF)
    }

    @Test
    fun gameIsScaledByAWholeFactor() {
        // C10 sin cabecera (1824 x 882): ×4 recortando 2 líneas arriba y abajo (1024 x 880); nunca un aumento no entero.
        assertEquals(4, NesScreen.NesView.scale(1824, 882))
        assertEquals(2, NesScreen.NesView.crop(4, 882))
        // Con el panel desplegado (1600) también cabe a ×4.
        assertEquals(4, NesScreen.NesView.scale(1600, 882))
        // Pantalla de 1080 de alto: ×4 sin recortar nada.
        assertEquals(4, NesScreen.NesView.scale(1920, 1080))
        assertEquals(0, NesScreen.NesView.crop(4, 1080))
        assertEquals(2, NesScreen.NesView.scale(800, 500))
        assertEquals(1, NesScreen.NesView.scale(100, 100))
    }

    @Test
    fun flickeringSpritesBlendInsteadOfVanishingAt30Fps() {
        val red = 0xFFE40058.toInt()
        val sky = 0xFF5C94FC.toInt()
        val ground = 0xFF884400.toInt()
        // Píxel 0: Mario tocado, pintado solo en los impares; los pares enseñan cielo en los dos → parpadeo: a medias.
        // Píxel 1: scroll, cada par distinto del anterior → limpio, sin estela del impar.
        // Píxel 2: quieto → igual.
        val lastEven = intArrayOf(sky, sky, sky)
        val odd = intArrayOf(red, red, sky)
        val cur = intArrayOf(sky, ground, sky)
        NativeCore.flickerBlend(odd, cur, lastEven, 3)
        assertEquals(0xFFA04AAA.toInt(), cur[0])
        assertEquals((0xE4 shr 1) + (0x5C shr 1), (cur[0] shr 16) and 0xFF)
        assertEquals(ground, cur[1])
        assertEquals(sky, cur[2])
        // El par se guarda tal cual, sin la mezcla.
        assertEquals(listOf(sky, ground, sky), lastEven.toList())
    }

    @Test
    fun megaDriveIsAlsoScaledByFour() {
        // 320x224 en el C10: ×4 recortando 2 líneas (1280 x 880); en PAL (240 líneas) no cabe a ×4 y va a ×3.
        assertEquals(4, NesScreen.NesView.scale(1824, 882, 320, 224))
        assertEquals(2, NesScreen.NesView.crop(4, 882, 224))
        assertEquals(3, NesScreen.NesView.scale(1824, 882, 320, 240))
        assertEquals(0, NesScreen.NesView.crop(3, 882, 240))
    }

    @Test
    fun romFilesAndFoldersSortFoldersFirst() {
        assertTrue(NesScreen.isRom("Juego.NES"))
        assertTrue(NesScreen.isRom("musica.nsf"))
        assertTrue(NesScreen.isRom("Sonic.md"))
        assertTrue(NesScreen.isRom("Super Pang.zip"))
        assertFalse(NesScreen.isRom("notas.txt"))
        assertEquals(GameConsole.NES, GameConsole.of("Contra (USA).nes"))
        assertEquals(GameConsole.MD, GameConsole.of("Sonic The Hedgehog (USA, Europe).GEN"))
        assertNull(GameConsole.of("juegos.zip"))
        assertEquals("Super Pang (Asia) (PAL) (Unl)", GameConsole.title("Super Pang (Asia) (PAL) (Unl).zip"))
        val list = listOf(NesScreen.Entry("b", "zeta.nes", false, -1), NesScreen.Entry("a", "Carpeta", true, -1), NesScreen.Entry("c", "alfa.nes", false, -1)).sorted()
        assertEquals(listOf("Carpeta", "alfa.nes", "zeta.nes"), list.map { it.name })
    }
}
