package com.headqlink.link

import com.headqlink.link.GpsWatch.LockProbe
import com.headqlink.link.GpsWatch.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** GPS parado: estado de los paneles, huecos que se suman al viaje y la sonda del móvil bloqueado. */
class GpsWatchTest {
    @Test
    fun freshFixIsOkWhateverTheLock() {
        assertEquals(State.OK, GpsWatch.state(true, 0, false, false))
        assertEquals(State.OK, GpsWatch.state(true, GpsWatch.STALE_MS, true, false))
        assertEquals(State.OK, GpsWatch.state(true, 1200, true, true))
    }

    @Test
    fun staleWithThePhoneLockedAndOnlyWhileInUseIsPaused() {
        assertEquals(State.PAUSED_LOCKED, GpsWatch.state(true, GpsWatch.STALE_MS + 1, true, false))
        assertEquals(State.PAUSED_LOCKED, GpsWatch.state(true, 60_000, true, false))
        // Aún sin ninguna posición y bloqueado: también es la pausa (Android no se la da).
        assertEquals(State.PAUSED_LOCKED, GpsWatch.state(false, -1, true, false))
    }

    @Test
    fun staleOtherwiseIsNoGps() {
        // Desbloqueado (túnel, garaje…) o con «todo el tiempo»: no es la pausa del bloqueo.
        assertEquals(State.LOST, GpsWatch.state(true, 6000, false, false))
        assertEquals(State.LOST, GpsWatch.state(true, 6000, true, true))
        assertEquals(State.WAITING, GpsWatch.state(false, -1, false, false))
        assertEquals(State.WAITING, GpsWatch.state(false, -1, true, true))
    }

    @Test
    fun gapsAreLongerThanFiveSeconds() {
        assertFalse(GpsWatch.isGap(1000))
        assertFalse(GpsWatch.isGap(GpsWatch.STALE_MS))
        assertTrue(GpsWatch.isGap(GpsWatch.STALE_MS + 1))
    }

    @Test
    fun aGapAddsTheStraightLineAtAPlausibleSpeed() {
        // 60 s bloqueado a ~90 km/h: 1,5 km en línea recta.
        assertEquals(1.5, GpsWatch.bridgeKm(1500.0, 60_000), 1e-9)
        assertEquals(90.0, GpsWatch.bridgeKmh(1.5, 60_000), 1e-9)
        // Parado: unos metros de deriva, se suman (no cambian nada) y la media es ~0.
        assertEquals(0.02, GpsWatch.bridgeKm(20.0, 600_000), 1e-9)
        assertTrue(GpsWatch.bridgeKmh(0.02, 600_000) < 2)
    }

    @Test
    fun anImplausibleGapAddsNothing() {
        // 10 km en 60 s (600 km/h): un salto de la posición.
        assertEquals(0.0, GpsWatch.bridgeKm(10_000.0, 60_000), 0.0)
        // Más de 3 h: no se sabe qué pasó.
        assertEquals(0.0, GpsWatch.bridgeKm(100_000.0, GpsWatch.MAX_BRIDGE_MS + 1), 0.0)
        assertEquals(0.0, GpsWatch.bridgeKm(0.0, 60_000), 0.0)
        assertEquals(0.0, GpsWatch.bridgeKm(Double.NaN, 60_000), 0.0)
        assertEquals(0.0, GpsWatch.bridgeKm(500.0, 0), 0.0)
        // Por debajo del límite (198 km/h) sí.
        assertEquals(3.3, GpsWatch.bridgeKm(3300.0, 60_000), 1e-9)
    }

    @Test
    fun probeSaysItArrivesWhenFixesKeepComingAfterTheLock() {
        val p = LockProbe()
        assertNull(p.tick(0, false, 0))
        assertNull(p.tick(1000, true, 900)) // bloquea con el GPS al día
        // Lo que llega en los primeros segundos no cuenta (Android tarda en quitar el acceso).
        assertNull(p.tick(5000, true, 4900))
        assertEquals(LockProbe.Result.ARRIVES, p.tick(10_000, true, 9_500))
        // Decide una sola vez.
        assertNull(p.tick(40_000, true, 9_500))
        assertEquals(LockProbe.Result.ARRIVES, p.result())
    }

    @Test
    fun probeSaysItStopsWhenNothingArrivesWhileLocked() {
        val p = LockProbe()
        p.tick(1000, true, 800)
        var r: LockProbe.Result? = null
        var t = 2000L
        while (r == null && t <= 60_000) {
            r = p.tick(t, true, 6000) // la última, dentro del margen tras bloquear
            t += 1000
        }
        assertEquals(LockProbe.Result.STOPS, r)
        assertEquals(1000 + LockProbe.DECIDE_MS, t - 1000)
    }

    @Test
    fun unlockingBeforeDecidingWaitsForTheNextLock() {
        val p = LockProbe()
        p.tick(0, true, 0)
        assertNull(p.tick(10_000, true, 0))
        assertNull(p.tick(11_000, false, 10_500)) // desbloquea: la pausa no cuenta
        assertEquals(-1L, p.lockedAt())
        assertNull(p.tick(12_000, true, 11_800)) // bloquea otra vez
        assertNull(p.tick(30_000, true, 11_800))
        assertEquals(LockProbe.Result.STOPS, p.tick(37_000, true, 11_800))
    }

    @Test
    fun lockWithoutFreshGpsOnlyDecidesIfAFixArrives() {
        val p = LockProbe()
        // Bloqueado desde el principio, sin ninguna posición: no se puede saber si es Android o el GPS arrancando.
        p.tick(0, true, -1)
        assertNull(p.tick(60_000, true, -1))
        assertEquals(LockProbe.Result.PENDING, p.result())
        // Llega una: con el móvil bloqueado sí llegan.
        assertEquals(LockProbe.Result.ARRIVES, p.tick(61_000, true, 60_500))
    }
}
