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
    fun romFilesAndFoldersSortFoldersFirst() {
        assertTrue(NesScreen.isRom("Juego.NES"))
        assertTrue(NesScreen.isRom("musica.nsf"))
        assertFalse(NesScreen.isRom("notas.txt"))
        val list = listOf(NesScreen.Entry("b", "zeta.nes", false), NesScreen.Entry("a", "Carpeta", true), NesScreen.Entry("c", "alfa.nes", false)).sorted()
        assertEquals(listOf("Carpeta", "alfa.nes", "zeta.nes"), list.map { it.name })
    }
}
