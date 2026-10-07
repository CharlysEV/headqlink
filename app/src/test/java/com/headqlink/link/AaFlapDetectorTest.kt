package com.headqlink.link

import com.headqlink.link.AaFlapDetector.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El síntoma de Open Headunit #985 (AA 17.8): AA conecta y se desconecta solo a los pocos segundos. Dos cortes seguidos
 * en menos de 10 s: diagnóstico (aviso solo la primera vez); con racha, relanzamientos como mucho cada 10 s.
 */
class AaFlapDetectorTest {
    @Test
    fun twoShortSessionsInARowAreDiagnosedOnce() {
        val d = AaFlapDetector()
        d.onConnected(0)
        assertEquals(Result.SHORT, d.onDisconnected(1_500, false))
        assertFalse("una sola no avisa", d.takeNotice())
        d.onConnected(5_000)
        assertEquals(Result.FLAPPING, d.onDisconnected(6_200, false))
        assertEquals(1_200L, d.lastDurationMs())
        assertEquals(2, d.streak())
        assertTrue(d.takeNotice())
        // La tercera sigue diagnosticada (línea en el log) pero sin aviso nuevo.
        d.onConnected(20_000)
        assertEquals(Result.FLAPPING, d.onDisconnected(21_000, false))
        assertFalse(d.takeNotice())
    }

    @Test
    fun aLongSessionBreaksTheStreak() {
        val d = AaFlapDetector()
        d.onConnected(0)
        assertEquals(Result.SHORT, d.onDisconnected(2_000, false))
        d.onConnected(3_000)
        assertEquals(Result.LONG, d.onDisconnected(3_000 + AaFlapDetector.SHORT_MS, false))
        assertEquals(0, d.streak())
        assertEquals(0L, d.relaunchHoldMs(13_000))
        d.onConnected(20_000)
        assertEquals("vuelve a empezar", Result.SHORT, d.onDisconnected(21_000, false))
    }

    @Test
    fun ourOwnClosesNeitherCountNorBreakTheStreak() {
        val d = AaFlapDetector()
        d.onConnected(0)
        assertEquals(Result.SHORT, d.onDisconnected(1_000, false))
        // Desconectar / cambio de perfil / apagado del servidor a los 2 s: no cuenta.
        d.onConnected(5_000)
        assertEquals(Result.NONE, d.onDisconnected(7_000, true))
        assertEquals(1, d.streak())
        d.onConnected(10_000)
        assertEquals(Result.FLAPPING, d.onDisconnected(11_000, false))
    }

    @Test
    fun disconnectWithoutASessionIsNothing() {
        val d = AaFlapDetector()
        // Intento rechazado o cortado antes del handshake (lo llevan los intentos), o Error + Disconnected seguidos.
        assertEquals(Result.NONE, d.onDisconnected(1_000, false))
        d.onConnected(2_000)
        d.onConnected(2_500) // HandshakeComplete y luego TransportStarted: la misma sesión
        assertTrue(d.connected())
        assertEquals(Result.SHORT, d.onDisconnected(3_000, false))
        assertEquals(1_000L, d.lastDurationMs())
        assertEquals(Result.NONE, d.onDisconnected(3_001, false))
        assertEquals(1, d.streak())
    }

    @Test
    fun relaunchesAreNeverFasterThanEvery10sWhileFlapping() {
        val d = AaFlapDetector()
        assertEquals("sin cortes, ya", 0L, d.relaunchHoldMs(0))
        d.onConnected(0)
        d.onDisconnected(1_500, false)
        assertEquals(AaFlapDetector.MIN_RELAUNCH_GAP_MS, d.relaunchHoldMs(1_500))
        assertEquals(7_000L, d.relaunchHoldMs(4_500))
        assertEquals(0L, d.relaunchHoldMs(11_500))
        assertEquals(0L, d.relaunchHoldMs(60_000))
        assertEquals(10_000L, AaFlapDetector.MIN_RELAUNCH_GAP_MS)
        assertEquals(10_000L, AaFlapDetector.SHORT_MS)
        assertEquals(2, AaFlapDetector.STREAK)
    }
}
