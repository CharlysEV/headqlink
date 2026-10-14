package com.headqlink.link

import android.view.KeyEvent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config as RoboConfig
import org.robolectric.annotation.ConscryptMode

/** Partidas a dos: qué mando es cada jugador (PadSlots) y que cada tecla llega al RetroPad de su jugador. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@RoboConfig(sdk = [35], application = android.app.Application::class)
class TwoPlayersTest {
    private val a = RetroPad.mask(RetroPad.Btn.A)
    private val p1 = RetroPad()
    private val p2 = RetroPad()

    @Before
    fun setUp() {
        Str.init(RuntimeEnvironment.getApplication())
    }

    @After
    fun tearDown() {
        NesInput.endGame()
    }

    private fun down(key: Int, dev: String, id: Int, gamepad: Boolean = true) =
        NesInput.key(key, KeyEvent.ACTION_DOWN, dev, id, gamepad, "Mando de prueba")

    private fun up(key: Int, dev: String, id: Int, gamepad: Boolean = true) =
        NesInput.key(key, KeyEvent.ACTION_UP, dev, id, gamepad, "Mando de prueba")

    @Test
    fun firstPadToPressIsPlayerOneSecondIsPlayerTwoThirdIsIgnored() {
        val s = PadSlots()
        assertEquals(0, s.claim("mando-b", 12))
        assertEquals(1, s.claim("mando-a", 11))
        assertEquals(PadSlots.NONE, s.claim("mando-c", 13))
        // Cada uno conserva el suyo.
        assertEquals(0, s.claim("mando-b", 12))
        assertEquals(1, s.claim("mando-a", 11))
        assertEquals(2, s.count())
        assertEquals(PadSlots.NONE, s.seatOf("mando-c"))
    }

    @Test
    fun aPadThatDisconnectsGetsItsSeatBackWithANewId() {
        val s = PadSlots()
        s.claim("mando-a", 11)
        s.claim("mando-b", 12)
        assertEquals(PadSlots.NONE, s.removed(99))
        assertEquals(1, s.removed(12))
        assertFalse(s.connected(1))
        assertTrue(s.taken(1))
        // Vuelve con otro deviceId (Android le da uno nuevo): mismo descriptor, mismo puesto.
        assertEquals(1, s.claim("mando-b", 20))
        assertTrue(s.connected(1))
        // El aviso de desconexión trae el id nuevo.
        assertEquals(1, s.removed(20))
    }

    @Test
    fun aNewPadTakesTheSeatOfADisconnectedOneOnlyIfNoneIsFree() {
        val s = PadSlots()
        s.claim("mando-a", 11)
        assertEquals(0, s.removed(11))
        // Queda un puesto libre: el nuevo va ahí y el del desconectado se guarda.
        assertEquals(1, s.claim("mando-b", 12))
        assertEquals(0, s.claim("mando-a", 21))
        s.removed(21)
        // Sin puestos libres: un tercero ocupa el del desconectado, y el que vuelve ya no tiene sitio.
        assertEquals(0, s.claim("mando-c", 13))
        assertEquals(PadSlots.NONE, s.claim("mando-a", 22))
    }

    @Test
    fun endOfGameClearsTheSeats() {
        val s = PadSlots()
        s.claim("mando-a", 11)
        s.claim("mando-b", 12)
        s.clear()
        assertEquals(0, s.count())
        assertEquals(0, s.claim("mando-b", 12))
    }

    @Test
    fun secondPadKeysGoToPlayerTwoOnly() {
        NesInput.newGame(p1, p2, GameConsole.NES)
        assertTrue(down(KeyEvent.KEYCODE_BUTTON_A, "mando-a", 11))
        assertEquals(a, p1.bits())
        assertEquals(0, p2.bits())
        assertTrue(down(KeyEvent.KEYCODE_BUTTON_A, "mando-b", 12))
        assertEquals(a, p2.bits())
        assertTrue(up(KeyEvent.KEYCODE_BUTTON_A, "mando-a", 11))
        assertEquals(0, p1.bits())
        assertEquals(a, p2.bits())
        // Un tercer mando: la tecla se consume (no va a la app de delante) pero no mueve a nadie.
        assertTrue(down(KeyEvent.KEYCODE_DPAD_UP, "mando-c", 13))
        assertEquals(0, p1.bits())
        assertEquals(a, p2.bits())
        // Teclado, botones táctiles o un evento sin dispositivo: jugador 1.
        assertTrue(down(KeyEvent.KEYCODE_DPAD_LEFT, "teclado", 5, gamepad = false))
        assertTrue(NesInput.key(KeyEvent.KEYCODE_BUTTON_START, KeyEvent.ACTION_DOWN))
        NesInput.press(RetroPad.Btn.B)
        assertEquals(RetroPad.mask(RetroPad.Btn.LEFT) or RetroPad.mask(RetroPad.Btn.START) or RetroPad.mask(RetroPad.Btn.B), p1.bits())
        assertEquals(a, p2.bits())
        // Lo que no es del mando sigue sin consumirse.
        assertFalse(down(KeyEvent.KEYCODE_VOLUME_UP, "mando-b", 12))
    }

    @Test
    fun withAOnePlayerCoreEveryPadIsPlayerOneAsBefore() {
        NesInput.newGame(p1, GameConsole.MD)
        assertTrue(down(KeyEvent.KEYCODE_BUTTON_X, "mando-a", 11))
        assertTrue(down(KeyEvent.KEYCODE_BUTTON_B, "mando-b", 12))
        assertTrue(down(KeyEvent.KEYCODE_BUTTON_Y, "mando-c", 13))
        assertEquals(RetroPad.mask(RetroPad.Btn.A) or RetroPad.mask(RetroPad.Btn.C) or RetroPad.mask(RetroPad.Btn.Y), p1.bits())
        assertEquals(0, NesInput.slots.count())
    }

    @Test
    fun aDisconnectedPadReleasesItsButtonsAndComesBackAsTheSamePlayer() {
        NesInput.newGame(p1, p2, GameConsole.SNES)
        down(KeyEvent.KEYCODE_BUTTON_A, "mando-a", 11)
        down(KeyEvent.KEYCODE_DPAD_RIGHT, "mando-b", 12)
        assertEquals(RetroPad.mask(RetroPad.Btn.RIGHT), p2.bits())
        NesInput.deviceRemoved(12)
        assertEquals(0, p2.bits())
        assertEquals(RetroPad.mask(RetroPad.Btn.B), p1.bits())
        assertEquals("Gamepad 1 · Gamepad 2 (disconnected)", NesInput.padsLabel())
        down(KeyEvent.KEYCODE_DPAD_DOWN, "mando-b", 30)
        assertEquals(RetroPad.mask(RetroPad.Btn.DOWN), p2.bits())
        assertEquals(RetroPad.mask(RetroPad.Btn.B), p1.bits())
        assertEquals("Gamepad 1 · Gamepad 2", NesInput.padsLabel())
    }

    @Test
    fun axesGoToThePlayerOfTheirPad() {
        NesInput.newGame(p1, p2, GameConsole.MD)
        down(KeyEvent.KEYCODE_BUTTON_START, "mando-a", 11)
        up(KeyEvent.KEYCODE_BUTTON_START, "mando-a", 11)
        p1.read()
        // Un mando sin puesto con el stick en el centro no se lo queda.
        assertTrue(NesInput.axes(0f, 0f, "mando-b", 12, true, "Mando de prueba"))
        assertEquals(1, NesInput.slots.count())
        // Moverlo sí: es el jugador 2, y la cruceta del 1 no se entera.
        NesInput.axes(-1f, 0f, "mando-b", 12, true, "Mando de prueba")
        assertEquals(RetroPad.mask(RetroPad.Btn.LEFT), p2.bits())
        assertEquals(0, p1.bits())
        NesInput.axes(0f, 1f, "mando-a", 11, true, "Mando de prueba")
        assertEquals(RetroPad.mask(RetroPad.Btn.DOWN), p1.bits())
        assertEquals(RetroPad.mask(RetroPad.Btn.LEFT), p2.bits())
        NesInput.axes(0f, 0f, "mando-b", 12, true, "Mando de prueba")
        assertEquals(0, p2.bits())
    }

    @Test
    fun aNewGameStartsWithNoSeats() {
        NesInput.newGame(p1, p2, GameConsole.NES)
        down(KeyEvent.KEYCODE_BUTTON_A, "mando-a", 11)
        down(KeyEvent.KEYCODE_BUTTON_A, "mando-b", 12)
        assertEquals("Gamepad 1 · Gamepad 2", NesInput.padsLabel())
        NesInput.endGame()
        assertEquals(0, NesInput.slots.count())
        assertEquals("", NesInput.padsLabel())
        val q1 = RetroPad()
        val q2 = RetroPad()
        NesInput.newGame(q1, q2, GameConsole.NES)
        // Ahora el mando B pulsa primero: es el jugador 1.
        down(KeyEvent.KEYCODE_BUTTON_A, "mando-b", 12)
        assertEquals(a, q1.bits())
        assertEquals(0, q2.bits())
    }

    @Test
    fun bothPadsTravelInOneIntToTheCores() {
        // Jugador 1 abajo, jugador 2 arriba; la PlayStation usa 14 bits y cabe.
        assertEquals(0x00030081, NativeCore.twoPads(0x81, 0x03))
        val psx = PsxPad.toLibretro(RetroPad.mask(RetroPad.Btn.R2) or RetroPad.mask(RetroPad.Btn.UP))
        val both = NativeCore.twoPads(0, psx)
        assertEquals(0, both and 0xFFFF)
        assertEquals(psx, both ushr 16)
        assertEquals(0x0000FFFF, NativeCore.twoPads(-1, 0))
    }

    @Test
    fun padNamesInTheLogNeverCarryAMacAddress() {
        assertEquals("Xbox Wireless Controller", NesInput.safeName("Xbox Wireless Controller"))
        assertEquals("Mando", NesInput.safeName("Mando 00:11:22:AA:bb:cc"))
        assertEquals("?", NesInput.safeName(null))
        assertEquals("?", NesInput.safeName("00-11-22-33-44-55"))
    }
}
