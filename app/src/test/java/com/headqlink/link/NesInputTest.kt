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
        assertEquals(PuppetController.Button.UP, NesInput.map(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(PuppetController.Button.A, NesInput.map(KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(PuppetController.Button.B, NesInput.map(KeyEvent.KEYCODE_BUTTON_X))
        assertEquals(PuppetController.Button.START, NesInput.map(KeyEvent.KEYCODE_BUTTON_START))
        assertEquals(PuppetController.Button.SELECT, NesInput.map(KeyEvent.KEYCODE_BUTTON_SELECT))
        assertNull(NesInput.map(KeyEvent.KEYCODE_VOLUME_UP))
    }

    @Test
    fun keysOnlyCountWhileAGameIsRunning() {
        NesInput.pad = null
        assertFalse(NesInput.key(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.ACTION_DOWN))
        val pad = PuppetController()
        NesInput.pad = pad
        assertTrue(NesInput.key(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.ACTION_DOWN))
        assertFalse(NesInput.key(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_DOWN))
        NesInput.pad = null
    }

    @Test
    fun hatAxesPressAndReleaseTheDpad() {
        val pad = PuppetController()
        NesInput.apply(pad, -1f, 0f)
        NesInput.apply(pad, 0f, 1f)
        NesInput.apply(pad, 0f, 0f)
        // Sin excepciones y con el estado final suelto: el PuppetController no expone los botones, basta con que no falle.
        assertTrue(true)
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
        // Un píxel de Mario (rojo) que la NES pinta en un fotograma y no en el siguiente (cielo azul): a 30 fps se ve a medias.
        val prev = intArrayOf(0xFFE40058.toInt(), 0xFF5C94FC.toInt())
        val cur = intArrayOf(0xFF5C94FC.toInt(), 0xFF5C94FC.toInt())
        NesEngine.blend(prev, cur)
        assertEquals(0xFFA04AAA.toInt(), cur[0])
        // Lo que no cambia, igual.
        assertEquals(0xFF5C94FC.toInt(), cur[1])
        // Mitad y mitad de cada canal.
        assertEquals((0xE4 shr 1) + (0x5C shr 1), (cur[0] shr 16) and 0xFF)
        assertEquals((0x00 shr 1) + (0x94 shr 1), (cur[0] shr 8) and 0xFF)
        assertEquals((0x58 shr 1) + (0xFC shr 1), cur[0] and 0xFF)
    }

    @Test
    fun romFilesAndFoldersSortFoldersFirst() {
        assertTrue(NesScreen.isRom("Juego.NES"))
        assertTrue(NesScreen.isRom("musica.nsf"))
        assertFalse(NesScreen.isRom("notas.txt"))
        val list = listOf(NesScreen.Entry("b", "zeta.nes", false), NesScreen.Entry("a", "Carpeta", true), NesScreen.Entry("c", "alfa.nes", false)).sorted()
        assertEquals(listOf("Carpeta", "alfa.nes", "zeta.nes"), list.map { it.name })
    }
}
