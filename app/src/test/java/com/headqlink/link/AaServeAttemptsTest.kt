package com.headqlink.link

import com.headqlink.link.AaServeAttempts.Kind
import com.headqlink.link.AaServeAttempts.Miss
import com.headqlink.link.AaServeAttempts.Phase
import com.headqlink.link.AaServeAttempts.Seen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Arranque manual del servidor de Android Auto: los intentos de verdad del Self-Mode (nunca un sondeo). Servido solo si
 * AA contesta; rechazado, cerrado, 6 s con el TCP abierto y sin respuesta o 30 s sin marcar es no servido: se cierra ese
 * intento, aviso (una vez) y reintento cada 5 s mientras la sesión lo necesite; el primer servido quita el aviso. Todo
 * con un reloj de mentira.
 */
class AaServeAttemptsTest {
    private var now = 1_000_000L
    private val attempts = AaServeAttempts { now }

    /** Contadores del móvil (solo crecen). */
    private var answers = 0L
    private var dials = 0L
    private var refusals = 0L

    private fun seen(wants: Boolean = true, tcp: Boolean = false, connecting: Boolean = false, handshake: Boolean = false) =
        Seen().wants(wants).tcp(tcp).connecting(connecting).handshake(handshake)
            .answers(answers).dials(dials).refusals(refusals)

    private fun at(ms: Long): AaServeAttemptsTest {
        now = ms
        return this
    }

    /** Lanza un intento en t y comprueba que es un LAUNCH con su número. */
    private fun launch(n: Int, why: String = "sesión") {
        val s = attempts.need(seen(), why)
        assertEquals(s.toString(), Kind.LAUNCH, s.kind)
        assertEquals(n, s.attempt)
        assertEquals(Phase.ATTEMPT, attempts.phase())
    }

    @Test
    fun servedOnTheFirstAttemptWithoutAnyNotice() {
        val t0 = now
        launch(1, "la sesión con el coche necesita Android Auto")
        // El Self-Mode marca y AA acepta el TCP: todavía no es «servido».
        at(t0 + 40)
        dials++
        val tcp = attempts.tick(seen(tcp = true))
        assertEquals(Kind.NONE, tcp.kind)
        assertTrue(tcp.reason, tcp.reason.contains("acepta el TCP"))
        assertFalse(attempts.waiting())
        // AA contesta (primeros bytes de la respuesta de versión): servido, sin aviso que quitar.
        at(t0 + 120)
        answers++
        val served = attempts.tick(seen(tcp = true))
        assertEquals(Kind.SERVED, served.kind)
        assertEquals(1, served.attempt)
        assertFalse(served.dismiss)
        assertTrue(served.reason, served.reason.contains("intento 1: servido"))
        assertEquals(Phase.IDLE, attempts.phase())
        assertFalse(attempts.active())
        assertEquals(Kind.NONE, attempts.tick(seen(tcp = true)).kind)
    }

    @Test
    fun refusedAsksOnceAndRetriesEveryFiveSecondsUntilAnAttemptIsServed() {
        val t0 = now
        launch(1)
        // Servidor apagado: el Self-Mode cuenta la conexión rechazada.
        at(t0 + 15)
        refusals++
        val first = attempts.tick(seen())
        assertEquals(Kind.MISSED, first.kind)
        assertEquals(Miss.REFUSED, first.miss)
        assertTrue("primer fallo: aviso", first.notice)
        assertTrue(first.retry)
        assertFalse("nada abierto que cerrar", first.tearDown)
        assertTrue(attempts.waiting())
        assertEquals(t0 + 15 + AaServeAttempts.RETRY_MS, attempts.retryAtMs())
        // Antes de los 5 s no se reintenta.
        assertEquals(Kind.NONE, at(t0 + 15 + AaServeAttempts.RETRY_MS - 1).let { attempts.tick(seen()) }.kind)
        // A los 5 s, el intento 2 (de verdad, otra marcación).
        val second = at(t0 + 15 + AaServeAttempts.RETRY_MS).let { attempts.tick(seen()) }
        assertEquals(Kind.LAUNCH, second.kind)
        assertEquals(2, second.attempt)
        refusals++
        val again = at(now + 10).let { attempts.tick(seen()) }
        assertEquals(Kind.MISSED, again.kind)
        assertFalse("el aviso ya está puesto: no se repite", again.notice)
        assertTrue(attempts.waiting())
        // El usuario arranca el servidor: el intento 3 es servido y quita el aviso.
        val third = at(now + AaServeAttempts.RETRY_MS).let { attempts.tick(seen()) }
        assertEquals(Kind.LAUNCH, third.kind)
        assertEquals(3, third.attempt)
        dials++
        assertEquals(Kind.NONE, at(now + 30).let { attempts.tick(seen(tcp = true)) }.kind)
        answers++
        val served = at(now + 60).let { attempts.tick(seen(tcp = true)) }
        assertEquals(Kind.SERVED, served.kind)
        assertEquals(3, served.attempt)
        assertTrue("servido: fuera el aviso", served.dismiss)
        assertFalse(attempts.waiting())
        assertEquals(Phase.IDLE, attempts.phase())
    }

    @Test
    fun tcpAcceptedWithoutAnswerIsNotServedAfterSixSecondsAndIsClosed() {
        // El caso real del 2026-10-06 (docs §15, C): una conexión cortada a medias bloqueó el servidor; el núcleo acepta el
        // TCP y nadie contesta hasta pararlo y volver a iniciarlo.
        val t0 = now
        launch(1)
        dials++
        at(t0 + 50).let { attempts.tick(seen(tcp = true)) }
        assertEquals(Kind.NONE, at(t0 + 50 + AaServeAttempts.SERVE_TIMEOUT_MS - 1).let { attempts.tick(seen(tcp = true)) }.kind)
        val miss = at(t0 + 50 + AaServeAttempts.SERVE_TIMEOUT_MS).let { attempts.tick(seen(tcp = true)) }
        assertEquals(Kind.MISSED, miss.kind)
        assertEquals(Miss.NO_ANSWER, miss.miss)
        assertTrue("se cierra la conexión que AA no atiende", miss.tearDown)
        assertEquals("para comprobar otra vez antes de cerrar", answers, miss.answersAtDecision)
        assertTrue(miss.notice)
        // Lo que de verdad pasa (bloqueado por una conexión cortada a medias), no «atiende una por arranque».
        assertTrue(miss.reason, miss.reason.contains("su servidor está bloqueado (pasa si una conexión se cortó a medias)"))
        assertTrue(miss.reason, miss.reason.contains("páralo y vuelve a iniciarlo"))
        assertFalse(miss.reason, miss.reason.contains("por arranque"))
        assertEquals(Phase.RETRY_WAIT, attempts.phase())
    }

    @Test
    fun anAnswerJustBeforeTheDeadlineIsServedAndNeverClosed() {
        val t0 = now
        launch(1)
        dials++
        at(t0 + 50).let { attempts.tick(seen(tcp = true)) }
        answers++
        val served = at(t0 + 50 + AaServeAttempts.SERVE_TIMEOUT_MS - 100).let { attempts.tick(seen(tcp = true)) }
        assertEquals(Kind.SERVED, served.kind)
        // Pasado el plazo no pasa nada: no hay intento que cerrar.
        val after = at(t0 + 50 + AaServeAttempts.SERVE_TIMEOUT_MS + 1_000).let { attempts.tick(seen(tcp = true)) }
        assertEquals(Kind.NONE, after.kind)
        assertFalse(after.tearDown)
    }

    @Test
    fun theFinishedHandshakeCountsAsServedEvenIfTheAnswerWasNotSeen() {
        launch(1)
        dials++
        val served = at(now + 300).let { attempts.tick(seen(tcp = true, handshake = true)) }
        assertEquals(Kind.SERVED, served.kind)
    }

    @Test
    fun aConnectionClosedWithoutAnswerIsNotServed() {
        val t0 = now
        launch(1)
        dials++
        at(t0 + 40).let { attempts.tick(seen(tcp = true)) }
        // El usuario para el servidor: la conexión pendiente se cierra sin respuesta.
        val dropped = at(t0 + 2_000).let { attempts.tick(seen()) }
        assertEquals(Kind.MISSED, dropped.kind)
        assertEquals(Miss.DROPPED, dropped.miss)
        assertFalse("ya está cerrada", dropped.tearDown)

        // También si el TCP se abrió y se cerró entre dos vistazos.
        val other = AaServeAttempts { now }
        assertEquals(Kind.LAUNCH, other.need(seen(), "sesión").kind)
        dials++
        val quick = at(now + 250).let { other.tick(seen()) }
        assertEquals(Miss.DROPPED, quick.miss)
    }

    @Test
    fun noDialWithinThirtySecondsIsNotServed() {
        val t0 = now
        launch(1)
        assertEquals(Kind.NONE, at(t0 + AaServeAttempts.DIAL_TIMEOUT_MS - 1).let { attempts.tick(seen()) }.kind)
        val miss = at(t0 + AaServeAttempts.DIAL_TIMEOUT_MS).let { attempts.tick(seen()) }
        assertEquals(Miss.NO_DIAL, miss.miss)
        assertFalse(miss.tearDown)
        // Atascado conectando: también se cierra.
        val other = AaServeAttempts { now }
        other.need(seen(), "sesión")
        val stuck = at(now + AaServeAttempts.DIAL_TIMEOUT_MS).let { other.tick(seen(connecting = true)) }
        assertEquals(Miss.NO_DIAL, stuck.miss)
        assertTrue(stuck.tearDown)
    }

    @Test
    fun neverTwoLaunchesAtOnceNorWithAndroidAutoAlreadyConnected() {
        // AA ya conectado (servido antes): nada.
        assertEquals(Kind.NONE, attempts.need(seen(tcp = true), "sesión").kind)
        assertNull(attempts.need(seen(tcp = true), "sesión").reason)
        assertEquals(Phase.IDLE, attempts.phase())
        launch(1)
        val busy = attempts.need(seen(), "vuelve el coche")
        assertEquals(Kind.NONE, busy.kind)
        assertTrue(busy.reason, busy.reason.contains("sigue en curso"))
        refusals++
        attempts.tick(seen())
        val waiting = attempts.need(seen(), "vuelve el coche")
        assertEquals(Kind.NONE, waiting.kind)
        assertTrue(waiting.reason, waiting.reason.contains("ya lo reintento"))
        assertEquals(Phase.RETRY_WAIT, attempts.phase())
    }

    @Test
    fun retriesStopAndTheNoticeGoesWhenTheSessionNoLongerNeedsAndroidAuto() {
        launch(1)
        refusals++
        attempts.tick(seen())
        assertTrue(attempts.waiting())
        val t = now + 1_000
        // Un parpadeo (el vídeo se rehace) no corta los reintentos.
        assertEquals(Kind.NONE, at(t).let { attempts.tick(seen(wants = false)) }.kind)
        assertEquals(Kind.NONE, at(t + 1_000).let { attempts.tick(seen()) }.kind)
        assertEquals(Phase.RETRY_WAIT, attempts.phase())
        // Sin sesión que necesite AA más de 3 s: se deja de intentar y fuera el aviso.
        at(t + 2_000).let { attempts.tick(seen(wants = false)) }
        assertEquals(Kind.NONE, at(t + 2_000 + AaServeAttempts.STOP_GRACE_MS - 1).let { attempts.tick(seen(wants = false)) }.kind)
        val stop = at(t + 2_000 + AaServeAttempts.STOP_GRACE_MS).let { attempts.tick(seen(wants = false)) }
        assertEquals(Kind.STOP, stop.kind)
        assertTrue(stop.dismiss)
        assertFalse(attempts.waiting())
        assertEquals(Phase.IDLE, attempts.phase())
        // Y ya no se lanza nada solo.
        assertEquals(Kind.NONE, at(now + 60_000).let { attempts.tick(seen()) }.kind)
    }

    @Test
    fun noRetryWithoutTheSessionWhenAnAttemptFails() {
        launch(1)
        dials++
        at(now + 50).let { attempts.tick(seen(tcp = true)) }
        val miss = at(now + AaServeAttempts.SERVE_TIMEOUT_MS).let { attempts.tick(seen(wants = false, tcp = true)) }
        assertEquals(Kind.MISSED, miss.kind)
        assertTrue("el intento abierto se cierra igual", miss.tearDown)
        assertFalse(miss.retry)
        assertFalse("sin sesión no se avisa", miss.notice)
        assertEquals(Phase.IDLE, attempts.phase())
        assertFalse(attempts.waiting())
    }

    @Test
    fun backInTheAppBringsTheRetryForward() {
        assertEquals(Kind.NONE, attempts.soon().kind)
        launch(1)
        refusals++
        val failedAt = now + 10
        at(failedAt).let { attempts.tick(seen()) }
        // De vuelta de los ajustes de AA un segundo después: el reintento, en medio segundo (no a los 5 s).
        val soon = at(failedAt + 1_000).let { attempts.soon() }
        assertNotNull(soon.reason)
        assertEquals(failedAt + 1_000 + AaServeAttempts.SOON_MS, attempts.retryAtMs())
        assertEquals(Kind.LAUNCH, at(failedAt + 1_000 + AaServeAttempts.SOON_MS).let { attempts.tick(seen()) }.kind)
        // Con el reintento ya a punto, no se mueve.
        refusals++
        at(now + 10).let { attempts.tick(seen()) }
        val retryAt = attempts.retryAtMs()
        at(retryAt - AaServeAttempts.SOON_MS + 1)
        assertNull(attempts.soon().reason)
        assertEquals(retryAt, attempts.retryAtMs())
    }

    @Test
    fun resetDismissesTheNoticeAndLeavesNothingPending() {
        assertNull("sin nada pendiente, nada que contar", attempts.reset("el enlace se cierra").reason)
        launch(1)
        refusals++
        attempts.tick(seen())
        val stop = attempts.reset("el enlace se cierra (Desconectar)")
        assertEquals(Kind.STOP, stop.kind)
        assertTrue(stop.dismiss)
        assertFalse(attempts.active())
        assertFalse(attempts.waiting())
        assertNull(attempts.reset("otra vez").reason)
        // Lo siguiente vuelve a contar desde el intento 1.
        launch(1)
    }

    @Test
    fun aLateAnswerWhileWaitingToRetryCountsAsServed() {
        // Se dio por perdido a los 6 s, pero AA contestó justo al ir a cerrarlo (no se cerró): servido.
        launch(1)
        dials++
        at(now + 50).let { attempts.tick(seen(tcp = true)) }
        val miss = at(now + AaServeAttempts.SERVE_TIMEOUT_MS).let { attempts.tick(seen(tcp = true)) }
        assertTrue(miss.tearDown)
        answers++
        val late = at(now + 300).let { attempts.tick(seen(tcp = true)) }
        assertEquals(Kind.SERVED, late.kind)
        assertTrue(late.dismiss)
        assertFalse(attempts.waiting())
    }

    @Test
    fun everyAttemptAndOutcomeIsLoggedWithItsNumber() {
        val log = mutableListOf<String>()
        fun keep(s: AaServeAttempts.Step) = s.reason?.let { log.add(it) }
        keep(attempts.need(seen(), "sesión"))
        refusals++
        keep(attempts.tick(seen()))
        keep(at(now + AaServeAttempts.RETRY_MS).let { attempts.tick(seen()) })
        dials++
        keep(at(now + 20).let { attempts.tick(seen(tcp = true)) })
        answers++
        keep(at(now + 20).let { attempts.tick(seen(tcp = true)) })
        assertTrue(log.toString(), log[0].startsWith("intento 1 (sesión): lanzo Android Auto"))
        assertTrue(log.toString(), log[1].startsWith("intento 1: no servido") && log[1].contains("rechaza") && log[1].contains("reintento en 5 s"))
        assertTrue(log.toString(), log[2].startsWith("intento 2 (reintento): lanzo"))
        assertTrue(log.toString(), log[3].startsWith("intento 2: 127.0.0.1:5277 acepta el TCP"))
        assertTrue(log.toString(), log[4].startsWith("intento 2: servido") && log[4].contains("quito el aviso"))
    }

    @Test
    fun androidAutoDroppingMidSessionIsTriedAgainAfterAShortGrace() {
        // Nada que mirar en reposo sin sesión.
        assertEquals(-1L, attempts.tickDelayMs())
        launch(1)
        assertEquals(AaServeAttempts.TICK_MS, attempts.tickDelayMs())
        dials++
        at(now + 20).let { attempts.tick(seen(tcp = true)) }
        answers++
        assertEquals(Kind.SERVED, at(now + 20).let { attempts.tick(seen(tcp = true)) }.kind)
        // Servido y con la sesión: se vigila que AA siga, sin prisa.
        assertEquals(AaServeAttempts.WATCH_MS, attempts.tickDelayMs())
        assertEquals(Kind.NONE, at(now + AaServeAttempts.WATCH_MS).let { attempts.tick(seen(tcp = true)) }.kind)
        // Un hueco corto (un cambio de ajustes, la conexión que se rehace) no lanza nada.
        val gone = now + AaServeAttempts.WATCH_MS
        assertEquals(Kind.NONE, at(gone).let { attempts.tick(seen()) }.kind)
        assertEquals(Kind.NONE, at(gone + 1_000).let { attempts.tick(seen(connecting = true)) }.kind)
        assertEquals(Kind.NONE, at(gone + 2_000).let { attempts.tick(seen()) }.kind)
        // AA desaparece de verdad (servidor parado, AA actualizado): intento nuevo, que avisará si no atiende.
        val relaunch = at(gone + 2_000 + AaServeAttempts.DROP_GRACE_MS).let { attempts.tick(seen()) }
        assertEquals(Kind.LAUNCH, relaunch.kind)
        assertEquals(1, relaunch.attempt)
        assertTrue(relaunch.reason, relaunch.reason.contains("se desconectó con la sesión en marcha"))
        dials++
        at(now + 30).let { attempts.tick(seen(tcp = true)) }
        val miss = at(now + AaServeAttempts.SERVE_TIMEOUT_MS).let { attempts.tick(seen(tcp = true)) }
        assertEquals(Miss.NO_ANSWER, miss.miss)
        assertTrue(miss.notice)
    }

    @Test
    fun theWatchStartsWithAndroidAutoAlreadyConnectedAndEndsWithTheSession() {
        // Vuelve el vídeo con AA conectado (en pausa hasta ahora): nada que lanzar, pero se vigila.
        assertNull(attempts.need(seen(tcp = true), "la sesión con el coche usa Android Auto").reason)
        assertEquals(AaServeAttempts.WATCH_MS, attempts.tickDelayMs())
        // Sin sesión que use AA (AA en pausa otra vez): se deja de mirar, sin lanzar nada.
        assertEquals(Kind.NONE, attempts.tick(seen(wants = false)).kind)
        assertEquals(-1L, attempts.tickDelayMs())
        assertEquals(Kind.NONE, at(now + 60_000).let { attempts.tick(seen()) }.kind)
        // Y al cerrar el enlace, tampoco queda nada.
        attempts.need(seen(tcp = true), "sesión")
        assertNull(attempts.reset("el enlace se cierra").reason)
        assertEquals(-1L, attempts.tickDelayMs())
    }

    @Test
    fun theTimingsAreTheOnesTheFixPromises() {
        assertEquals(6_000L, AaServeAttempts.SERVE_TIMEOUT_MS)
        assertEquals(5_000L, AaServeAttempts.RETRY_MS)
        assertTrue(AaServeAttempts.TICK_MS < 1_000L)
        assertTrue(AaServeAttempts.DIAL_TIMEOUT_MS > AaServeAttempts.SERVE_TIMEOUT_MS)
    }
}
