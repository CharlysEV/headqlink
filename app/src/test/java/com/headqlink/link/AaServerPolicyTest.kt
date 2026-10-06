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
 * Servidor de head unit de Android Auto: modo de arranque (automático / manual) × lo que se sabe × móvil bloqueado ×
 * motivo. El manual nunca automatiza ni sondea (cada conexión gasta el servidor: lo dice el intento real con el coche) y
 * no cierra Android Auto sin Desconectar; el automático sigue igual que antes.
 */
class AaServerPolicyTest {
    private fun state(manual: Boolean, server: Server, locked: Boolean, automate: Boolean = true, connected: Boolean = false) =
        State().manual(manual).server(server).locked(locked).automate(automate).connected(connected)

    private val allNeeds = Need.values().toList()
    private val bools = listOf(false, true)

    @Test
    fun manualNeverAutomatesNorProbesWhateverTheTriggerOrLock() {
        for (need in allNeeds) for (locked in bools) for (automate in bools) for (server in Server.values()) {
            val ctx = "$need $server bloqueado=$locked accesibilidad=$automate"
            // Ni arrancar, ni comprobar el puerto: lo dirá el intento real del Self-Mode con el coche.
            assertEquals(ctx, Action.AT_SESSION, AaServerPolicy.onNeed(need, state(true, server, locked, automate)))
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
        for (need in allNeeds) {
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
    }

    @Test
    fun exhaustiveMatrixKeepsEachModeInItsLane() {
        for (manual in bools) for (server in Server.values()) for (locked in bools) for (automate in bools) for (need in allNeeds) {
            for (connected in bools) {
                val a = AaServerPolicy.onNeed(need, state(manual, server, locked, automate, connected))
                val ctx = "manual=$manual $server bloqueado=$locked accesibilidad=$automate conectado=$connected $need → $a"
                if (manual) {
                    assertTrue(ctx, a == Action.NONE || a == Action.AT_SESSION)
                    // El bloqueo no cambia nada en el manual: nunca hace falta desbloquear para nada.
                    assertEquals(ctx, AaServerPolicy.onNeed(need, state(true, server, !locked, automate, connected)), a)
                } else {
                    assertFalse(ctx, a == Action.AT_SESSION)
                }
            }
        }
    }

    @Test
    fun manualEndKeepsAndroidAutoUnlessTheUserDisconnects() {
        val ready = State().manual(true).connected(true).ready(true)
        // Sin Desconectar (espera vencida, Bluetooth fuera, el sistema): AA se queda en pausa para el próximo viaje.
        for (locked in bools) assertEquals(End.KEEP_AA, AaServerPolicy.onEnd(true, ready.locked(locked), false))
        // Desconectar: se cierra, con el aviso de que la próxima vez hay que reiniciar el servidor.
        assertEquals(End.CLOSE_AA_RESTART, AaServerPolicy.onEnd(true, ready, true))
        // Un intento con el TCP abierto, o los intentos fallando: el servidor ya está gastado (o no atiende): aviso.
        assertEquals(End.CLOSE_AA_RESTART, AaServerPolicy.onEnd(true, State().manual(true).connected(true), true))
        assertEquals(End.CLOSE_AA_RESTART, AaServerPolicy.onEnd(true, State().manual(true).used(true), false))
        assertEquals(End.CLOSE_AA_RESTART, AaServerPolicy.onEnd(true, State().manual(true).used(true), true))
        // Sin haber tocado el servidor: se cierra sin aviso (sigue sin estrenar).
        for (user in bools) assertEquals(End.CLOSE_AA, AaServerPolicy.onEnd(true, State().manual(true), user))
    }

    @Test
    fun endOfTheLink() {
        for (manual in bools) for (server in Server.values()) for (user in bools) {
            assertEquals(End.NOTHING, AaServerPolicy.onEnd(false, state(manual, server, false), user))
        }
        // Automático: el cierre de siempre (apagarlo), sepa lo que sepa del servidor y sea quien sea quien cierra.
        for (server in Server.values()) for (user in bools) {
            assertEquals(End.STOP_SERVER, AaServerPolicy.onEnd(true, state(false, server, true).ready(true), user))
        }
    }
}
