package com.headqlink.link

import com.andrerinas.openheadunit.connection.CommManager.ConnectionState
import com.headqlink.link.AaClose.Link
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cierres de nuestra head unit con Android Auto, siempre limpios (prueba real del 2026-10-06, docs §15): con la sesión
 * hecha, ByeBye ya; con el handshake a medias o un Self-Mode recién pedido, se espera a que termine (como mucho 6 s),
 * porque cortarlo a medias bloquea el servidor de head unit de AA hasta pararlo y volver a iniciarlo.
 */
class AaCloseTest {
    @Test
    fun eachConnectionStateMapsToWhatMattersForClosing() {
        assertEquals(Link.NONE, AaClose.linkOf(ConnectionState.Disconnected()))
        assertEquals(Link.NONE, AaClose.linkOf(ConnectionState.Disconnected(isClean = true, isUserExit = true)))
        assertEquals(Link.NONE, AaClose.linkOf(ConnectionState.Error("x")))
        assertEquals(Link.NONE, AaClose.linkOf(null))
        assertEquals(Link.DIALING, AaClose.linkOf(ConnectionState.Connecting))
        // TCP abierto: antes del SSL no se puede mandar el ByeBye (va cifrado).
        assertEquals(Link.HANDSHAKE, AaClose.linkOf(ConnectionState.Connected))
        assertEquals(Link.HANDSHAKE, AaClose.linkOf(ConnectionState.StartingTransport))
        assertEquals(Link.SESSION, AaClose.linkOf(ConnectionState.HandshakeComplete))
        assertEquals(Link.SESSION, AaClose.linkOf(ConnectionState.TransportStarted))
    }

    @Test
    fun aSessionOrNothingClosesRightAway() {
        // Sesión hecha: el ByeBye de siempre, sin esperar. Sin conexión (ni Self-Mode recién pedido): nada que esperar.
        for (recent in listOf(false, true)) assertFalse(AaClose.mustWait(Link.SESSION, recent, 0))
        assertFalse(AaClose.mustWait(Link.NONE, false, 0))
    }

    @Test
    fun aHandshakeHalfwayIsLetFinishFirst() {
        for (link in listOf(Link.DIALING, Link.HANDSHAKE)) for (recent in listOf(false, true)) {
            assertTrue("$link", AaClose.mustWait(link, recent, 0))
            assertTrue("$link", AaClose.mustWait(link, recent, AaClose.HANDSHAKE_WAIT_MS - 1))
        }
        // Un Self-Mode recién pedido aún puede marcar: se le deja, para cerrar después su sesión con orden.
        assertTrue(AaClose.mustWait(Link.NONE, true, 0))
    }

    @Test
    fun theWaitNeverOutlastsTheTimeAfterWhichAnAttemptCountsAsNotServed() {
        // Si AA no ha dicho nada en 6 s, su servidor ya estaba bloqueado: cerrar no cambia nada.
        assertEquals(AaServeAttempts.SERVE_TIMEOUT_MS, AaClose.HANDSHAKE_WAIT_MS)
        for (link in Link.values()) for (recent in listOf(false, true)) {
            assertFalse("$link", AaClose.mustWait(link, recent, AaClose.HANDSHAKE_WAIT_MS))
        }
        assertTrue(AaClose.LAUNCH_GRACE_MS < AaClose.HANDSHAKE_WAIT_MS)
    }
}
