package com.headqlink.link

import com.headqlink.link.AaServerPolicy.Action
import com.headqlink.link.AaServerPolicy.End
import com.headqlink.link.AaServerPolicy.Need
import com.headqlink.link.AaServerPolicy.Server
import com.headqlink.link.AaServerPolicy.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Servidor de head unit de Android Auto: modo de arranque (automático / manual) × servidor (encendido, apagado, sin
 * saber) × móvil bloqueado × motivo. El manual nunca automatiza nada y el automático sigue igual que antes.
 */
class AaServerPolicyTest {
    private fun state(manual: Boolean, server: Server, locked: Boolean, automate: Boolean = true, connected: Boolean = false) =
        State().manual(manual).server(server).locked(locked).automate(automate).connected(connected)

    private val allNeeds = Need.values().toList()
    private val bools = listOf(false, true)

    @Test
    fun manualNeverAutomatesWhateverTheTriggerOrLock() {
        for (need in allNeeds) for (locked in bools) for (automate in bools) {
            val ctx = "$need bloqueado=$locked accesibilidad=$automate"
            assertEquals(ctx, Action.NONE, AaServerPolicy.onNeed(need, state(true, Server.UP, locked, automate)))
            assertEquals(ctx, Action.ASK_USER, AaServerPolicy.onNeed(need, state(true, Server.DOWN, locked, automate)))
            assertEquals(ctx, Action.PROBE, AaServerPolicy.onNeed(need, state(true, Server.UNKNOWN, locked, automate)))
        }
    }

    @Test
    fun anyModeWithAndroidAutoConnectedNeedsNothing() {
        for (manual in bools) for (server in Server.values()) for (need in allNeeds) for (locked in bools) {
            assertEquals(
                "manual=$manual $server $need bloqueado=$locked",
                Action.NONE,
                AaServerPolicy.onNeed(need, state(manual, server, locked, connected = true)),
            )
        }
    }

    @Test
    fun automaticIsTheBehaviourOfAlways() {
        for (need in allNeeds.filter { it != Need.WATCH }) {
            // Sin dato o apagado: se arranca ya, o al desbloquear con el aviso.
            for (server in listOf(Server.DOWN, Server.UNKNOWN)) {
                assertEquals("$need $server", Action.AUTOMATE, AaServerPolicy.onNeed(need, state(false, server, false)))
                assertEquals("$need $server", Action.AUTOMATE_ON_UNLOCK, AaServerPolicy.onNeed(need, state(false, server, true)))
                // Sin accesibilidad no se puede, bloqueado o no.
                for (locked in bools) {
                    assertEquals(
                        "$need $server bloqueado=$locked",
                        Action.NO_ACCESSIBILITY,
                        AaServerPolicy.onNeed(need, state(false, server, locked, automate = false)),
                    )
                }
            }
            // Consta encendido: nada (lo de siempre: el arranque anticipado no se repite).
            for (locked in bools) assertEquals(Action.NONE, AaServerPolicy.onNeed(need, state(false, Server.UP, locked)))
        }
        // La vigilancia periódica es solo del manual.
        for (server in Server.values()) for (locked in bools) {
            assertEquals(Action.NONE, AaServerPolicy.onNeed(Need.WATCH, state(false, server, locked)))
        }
    }

    @Test
    fun exhaustiveMatrixKeepsEachModeInItsLane() {
        for (manual in bools) for (server in Server.values()) for (locked in bools) for (automate in bools) for (need in allNeeds) {
            val a = AaServerPolicy.onNeed(need, state(manual, server, locked, automate))
            val ctx = "manual=$manual $server bloqueado=$locked accesibilidad=$automate $need → $a"
            if (manual) {
                assertTrue(ctx, a == Action.NONE || a == Action.PROBE || a == Action.ASK_USER)
                // El bloqueo no cambia nada en el manual: la notificación se ve en la pantalla de bloqueo.
                assertEquals(ctx, AaServerPolicy.onNeed(need, state(true, server, !locked, automate)), a)
            } else {
                assertFalse(ctx, a == Action.PROBE || a == Action.ASK_USER)
            }
        }
    }

    @Test
    fun bluetoothOrCableWithThePhoneLockedAndTheServerAlreadyUpNeedNoUnlock() {
        for (need in listOf(Need.BLUETOOTH, Need.USB, Need.CAR_SEEN, Need.SESSION)) {
            assertEquals(Action.NONE, AaServerPolicy.onNeed(need, state(true, Server.UP, true, automate = false)))
        }
    }

    @Test
    fun endOfTheLink() {
        for (manual in bools) for (server in Server.values()) {
            assertEquals(End.NOTHING, AaServerPolicy.onEnd(false, state(manual, server, false)))
        }
        // Automático: el cierre de siempre (apagarlo), sepa lo que sepa del servidor.
        for (server in Server.values()) assertEquals(End.STOP_SERVER, AaServerPolicy.onEnd(true, state(false, server, true)))
        // Manual: nunca se para; se avisa si sigue encendido (o si no se pudo mirar).
        assertEquals(End.LEAVE_ON_NOTICE, AaServerPolicy.onEnd(true, state(true, Server.UP, false)))
        assertEquals(End.LEAVE_ON_NOTICE, AaServerPolicy.onEnd(true, state(true, Server.UNKNOWN, true)))
        assertEquals(End.NOTHING, AaServerPolicy.onEnd(true, state(true, Server.DOWN, false)))
    }

    @Test
    fun checkCadence() {
        assertEquals(2_000L, AaServerPolicy.nextCheckMs(true, true, true, true))
        assertEquals(60_000L, AaServerPolicy.nextCheckMs(true, true, true, false))
        assertEquals(-1L, AaServerPolicy.nextCheckMs(false, true, true, true))
        assertEquals(-1L, AaServerPolicy.nextCheckMs(true, false, true, true))
        assertEquals(-1L, AaServerPolicy.nextCheckMs(true, true, false, true))
    }
}
