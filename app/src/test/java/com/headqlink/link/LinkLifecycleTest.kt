package com.headqlink.link

import com.headqlink.link.LinkLifecycle.Action
import com.headqlink.link.LinkLifecycle.Env
import com.headqlink.link.LinkLifecycle.Phase
import com.headqlink.link.LinkLifecycle.ShutdownPlan
import com.headqlink.link.LinkLifecycle.Trigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ciclo de vida del enlace y de Android Auto: vídeo vivo, AA en pausa esperando al coche, reanudar al instante, cierre
 * al vencer «Esperar al coche», móvil bloqueado o no, apagado pendiente y llegada por Bluetooth.
 */
class LinkLifecycleTest {
    private val sec = 1_000L
    private val min = 60_000L
    private val grace = 30 * sec
    private val wait = 5 * min
    private val life = LinkLifecycle()

    /** AA conectado y proyectando, móvil desbloqueado, servidor encendido. */
    private fun aaLive() = Env().aa(true).connected(true).serverOn(true)

    /** En marcha con una sesión: arranque en t=0 y sesión en t=10 s. */
    private fun connectedAt10s() {
        life.start(0, Trigger.USER, aaLive(), wait)
        life.carConnected(10 * sec, aaLive())
        assertEquals(Phase.CONNECTED, life.phase())
        assertEquals(-1, life.deadlineMs())
    }

    @Test
    fun carBackWithinTheVideoGraceReconnectsWithoutTouchingAa() {
        connectedAt10s()
        val lost = life.carLost(100 * sec, aaLive(), grace, wait)
        assertEquals(Phase.GRACE, life.phase())
        assertTrue(lost.actions().isEmpty())
        assertEquals(130 * sec, life.deadlineMs())

        assertTrue(life.carSeen(110 * sec, aaLive()).actions().isEmpty())
        val back = life.carConnected(111 * sec, aaLive())
        assertEquals(Phase.CONNECTED, life.phase())
        assertTrue("sin pausa no hay nada que reanudar", back.actions().isEmpty())
        assertTrue(back.reasons().any { it.contains("vídeo seguía vivo") })
        assertEquals(-1, life.deadlineMs())
    }

    @Test
    fun afterTheGraceAaIsParkedBeforeTheVideoStopsAndTheLinkKeepsListening() {
        connectedAt10s()
        life.carLost(100 * sec, aaLive(), grace, wait)
        assertTrue("antes de tiempo no pasa nada", life.timer(129 * sec, aaLive()).isEmpty)

        val d = life.timer(130 * sec, aaLive())
        assertEquals(listOf(Action.PARK_AA, Action.STOP_VIDEO), d.actions())
        assertFalse(d.has(Action.SHUTDOWN))
        assertEquals(Phase.PARKED, life.phase())
        // La espera cuenta desde que se perdió al coche, no desde el fin del vídeo vivo.
        assertEquals(100 * sec + wait, life.deadlineMs())
    }

    @Test
    fun carBackWhileParkedResumesAaInstantlyWithoutStartingTheServer() {
        connectedAt10s()
        life.carLost(100 * sec, aaLive(), grace, wait)
        life.timer(130 * sec, aaLive())
        val parked = aaLive().parked(true)

        val seen = life.carSeen(200 * sec, parked)
        assertFalse(seen.has(Action.START_SERVER))
        assertFalse(seen.has(Action.START_SERVER_ON_UNLOCK))
        assertFalse("se reanuda con la sesión, no con el broadcast", seen.has(Action.RESUME_AA))

        val back = life.carConnected(201 * sec, parked.locked(true))
        assertEquals(listOf(Action.RESUME_AA), back.actions())
        assertTrue(back.reasons().any { it.contains("al instante") })
        assertEquals(Phase.CONNECTED, life.phase())
        assertEquals(-1, life.deadlineMs())
    }

    @Test
    fun theWaitRunningOutClosesEverything() {
        connectedAt10s()
        life.carLost(100 * sec, aaLive(), grace, wait)
        life.timer(130 * sec, aaLive())
        val parked = aaLive().parked(true)
        assertTrue(life.timer(100 * sec + wait - 1, parked).isEmpty)

        val d = life.timer(100 * sec + wait, parked)
        assertEquals(listOf(Action.SHUTDOWN), d.actions())
        assertEquals(Phase.CLOSED, life.phase())
        assertEquals(-1, life.deadlineMs())
        // Cerrado: lo que llegue después no hace nada.
        assertTrue(life.carConnected(100 * sec + wait + 1, parked).actions().isEmpty())
        assertTrue(life.timer(100 * sec + wait + 10 * sec, parked).isEmpty)
    }

    @Test
    fun aWaitExpiringWithAnAttemptInProgressIsRecheckedNotClosed() {
        connectedAt10s()
        life.carLost(100 * sec, aaLive(), grace, wait)
        life.timer(130 * sec, aaLive())
        val end = 100 * sec + wait

        val busy = life.timer(end, aaLive().parked(true).busy(true))
        assertTrue(busy.actions().isEmpty())
        assertEquals(end + LinkLifecycle.RECHECK_MS, life.deadlineMs())
        assertEquals(Phase.PARKED, life.phase())

        val d = life.timer(end + LinkLifecycle.RECHECK_MS, aaLive().parked(true))
        assertEquals(listOf(Action.SHUTDOWN), d.actions())
    }

    @Test
    fun aGraceLongerThanTheWaitClosesWhenTheGraceEnds() {
        connectedAt10s()
        life.carLost(0, aaLive(), 10 * min, 1 * min)
        assertEquals(Phase.GRACE, life.phase())
        assertEquals(10 * min, life.deadlineMs())
        assertEquals(listOf(Action.SHUTDOWN), life.timer(10 * min, aaLive()).actions())
    }

    @Test
    fun withoutVideoToKeepAaIsParkedRightAway() {
        // Motor original o «mantener Android Auto» desactivado: la sesión se lleva el vídeo.
        connectedAt10s()
        val d = life.carLost(100 * sec, aaLive(), 0, wait)
        assertEquals(listOf(Action.PARK_AA, Action.STOP_VIDEO), d.actions())
        assertEquals(Phase.PARKED, life.phase())
        assertEquals(100 * sec + wait, life.deadlineMs())
    }

    @Test
    fun onlyAConnectedAaIsParked() {
        connectedAt10s()
        life.carLost(0, aaLive(), grace, wait)
        assertEquals(listOf(Action.STOP_VIDEO), life.timer(grace, aaLive().connected(false)).actions())

        val pattern = LinkLifecycle()
        pattern.start(0, Trigger.USER, Env(), wait)
        pattern.carConnected(sec, Env())
        assertEquals(listOf(Action.STOP_VIDEO), pattern.carLost(2 * sec, Env(), 0, wait).actions())
    }

    @Test
    fun anotherLossDuringTheWaitDoesNotExtendIt() {
        connectedAt10s()
        life.carLost(100 * sec, aaLive(), grace, wait)
        assertTrue(life.carLost(120 * sec, aaLive(), grace, wait).isEmpty)
        assertEquals(130 * sec, life.deadlineMs())
        assertEquals(100 * sec + wait, life.closeAtMs())
    }

    @Test
    fun aPendingServerStopIsCancelledAsSoonAsTheCarIsSeen() {
        connectedAt10s()
        life.carLost(100 * sec, aaLive(), grace, wait)
        life.timer(130 * sec, aaLive())
        val pending = aaLive().parked(true).stopPending(true)

        val seen = life.carSeen(150 * sec, pending)
        assertEquals(listOf(Action.CANCEL_PENDING_STOP), seen.actions())
        val back = life.carConnected(151 * sec, pending)
        assertEquals(listOf(Action.CANCEL_PENDING_STOP, Action.RESUME_AA), back.actions())
    }

    @Test
    fun bluetoothArrivalWithAaParkedByTheGuardAdoptsItAndResumesWithoutUnlocking() {
        // Todo se cerró con el móvil bloqueado: AaGuardService tiene AA aparcado y el apagado pendiente.
        val guarded = Env().aa(true).locked(true).connected(true).parked(true).guard(true).stopPending(true).serverOn(true)
        val start = life.start(0, Trigger.BLUETOOTH, guarded, wait)
        assertEquals(listOf(Action.ADOPT_PARK, Action.CANCEL_PENDING_STOP), start.actions())
        assertEquals(Phase.SEARCHING, life.phase())

        val adopted = Env().aa(true).locked(true).connected(true).parked(true).serverOn(true)
        assertTrue(life.carSeen(5 * sec, adopted).actions().isEmpty())
        assertEquals(listOf(Action.RESUME_AA), life.carConnected(6 * sec, adopted).actions())
    }

    @Test
    fun bluetoothArrivalWithTheServerOffStartsItNowOrOnUnlock() {
        val off = Env().aa(true).serverOn(false)

        val unlocked = LinkLifecycle().start(0, Trigger.BLUETOOTH, off, wait)
        assertEquals(listOf(Action.START_SERVER), unlocked.actions())

        val locked = LinkLifecycle().start(0, Trigger.BLUETOOTH, Env().aa(true).locked(true), wait)
        assertEquals(listOf(Action.START_SERVER_ON_UNLOCK), locked.actions())
        assertTrue(locked.reasons().any { it.contains("bloqueado") })

        // Servidor encendido o AA conectado: nada que arrancar.
        assertTrue(LinkLifecycle().start(0, Trigger.BLUETOOTH, Env().aa(true).serverOn(true), wait).actions().isEmpty())
        assertTrue(LinkLifecycle().start(0, Trigger.BLUETOOTH, Env().aa(true).connected(true), wait).actions().isEmpty())
        // Sin Android Auto, o sin accesibilidad para arrancarlo: tampoco.
        assertTrue(LinkLifecycle().start(0, Trigger.BLUETOOTH, Env(), wait).actions().isEmpty())
        val noA11y = LinkLifecycle().start(0, Trigger.BLUETOOTH, Env().aa(true).automate(false), wait)
        assertTrue(noA11y.actions().isEmpty())
        assertTrue(noA11y.reasons().any { it.contains("accesibilidad") })
        // Conectar desde la app: HomeActivity ya arrancó el servidor con el móvil desbloqueado.
        assertTrue(LinkLifecycle().start(0, Trigger.USER, off, wait).actions().isEmpty())
    }

    @Test
    fun aCarSeenWhileLockedAsksToUnlockOncePerSearch() {
        life.start(0, Trigger.USER, Env().aa(true), wait)
        val locked = Env().aa(true).locked(true)
        assertEquals(listOf(Action.START_SERVER_ON_UNLOCK), life.carSeen(sec, locked).actions())
        assertTrue("un aviso, no uno por broadcast", life.carSeen(2 * sec, locked).actions().isEmpty())
    }

    @Test
    fun bluetoothGoneBeforeAnySessionCloses() {
        life.start(0, Trigger.BLUETOOTH, aaLive(), wait)
        assertEquals(listOf(Action.SHUTDOWN), life.btGone(sec, aaLive()).actions())
        assertEquals(Phase.CLOSED, life.phase())
    }

    @Test
    fun bluetoothGoneWhileWaitingForTheCarKeepsWaiting() {
        // Apagar el coche se lleva el Bluetooth y la sesión: justo lo que cubre la espera.
        connectedAt10s()
        assertTrue(life.btGone(50 * sec, aaLive()).actions().isEmpty())
        life.carLost(100 * sec, aaLive(), grace, wait)
        val gone = life.btGone(101 * sec, aaLive())
        assertTrue(gone.actions().isEmpty())
        assertTrue(gone.reasons().any { it.contains("sigo esperando") })
        assertEquals(Phase.GRACE, life.phase())
        life.timer(130 * sec, aaLive())
        assertTrue(life.btGone(140 * sec, aaLive().parked(true)).actions().isEmpty())
        assertEquals(Phase.PARKED, life.phase())
    }

    @Test
    fun searchingWithoutACarClosesAfterAtLeastFiveMinutesOrTheWait() {
        life.start(0, Trigger.USER, aaLive(), 1 * min)
        assertEquals(LinkLifecycle.SEARCH_MIN_MS, life.deadlineMs())
        assertEquals(listOf(Action.SHUTDOWN), life.timer(LinkLifecycle.SEARCH_MIN_MS, aaLive()).actions())

        val long = LinkLifecycle()
        long.start(0, Trigger.USER, aaLive(), 15 * min)
        assertEquals(15 * min, long.deadlineMs())
    }

    @Test
    fun theServiceStartsOnlyOnce() {
        life.start(0, Trigger.USER, aaLive(), wait)
        assertTrue(life.start(sec, Trigger.BLUETOOTH, Env().aa(true), wait).isEmpty)
        assertEquals(LinkLifecycle.SEARCH_MIN_MS, life.deadlineMs())
    }

    @Test
    fun shutdownParksAaUntilUnlockOnlyWhenLockedAndConnected() {
        val locked = Env().aa(true).locked(true).connected(true)
        assertEquals(ShutdownPlan.PARK_UNTIL_UNLOCK, LinkLifecycle.shutdownPlan(locked))
        assertEquals(ShutdownPlan.STOP_SERVER, LinkLifecycle.shutdownPlan(Env().aa(true).connected(true)))
        assertEquals(ShutdownPlan.STOP_SERVER, LinkLifecycle.shutdownPlan(Env().aa(true).locked(true)))
        // Con el botón «Detener» de la notificación del servidor se apaga ya, también bloqueado.
        assertEquals(ShutdownPlan.STOP_SERVER, LinkLifecycle.shutdownPlan(Env().aa(true).locked(true).connected(true).stopWithoutUi(true)))
        // Sin accesibilidad no hay quien lo apague al desbloquear: se para AA y queda pendiente.
        assertEquals(ShutdownPlan.STOP_SERVER, LinkLifecycle.shutdownPlan(Env().aa(true).locked(true).connected(true).automate(false)))
        assertEquals(ShutdownPlan.STOP_SERVER_IF_UNLOCKED, LinkLifecycle.shutdownPlan(Env().aa(true).stopOnExit(false)))
        assertEquals(ShutdownPlan.LINK_ONLY, LinkLifecycle.shutdownPlan(Env().locked(true)))
    }

    @Test
    fun waitForTheCarSettingAcceptsOnlyItsChoices() {
        assertEquals(5, Config.DEFAULT_CAR_WAIT_MIN)
        assertEquals(Config.DEFAULT_CAR_WAIT_MIN, Config.carWaitMinFor(0))
        for (c in Config.CAR_WAIT_CHOICES) assertEquals(c, Config.carWaitMinFor(c))
        assertEquals(Config.DEFAULT_CAR_WAIT_MIN, Config.carWaitMinFor(7))
        assertEquals(Config.DEFAULT_CAR_WAIT_MIN, Config.carWaitMinFor(-1))
    }

    @Test
    fun durationsReadNaturallyInTheLog() {
        assertEquals("30 s", LinkLifecycle.dur(30 * sec))
        assertEquals("90 s", LinkLifecycle.dur(90 * sec))
        assertEquals("5 min", LinkLifecycle.dur(5 * min))
        assertEquals("15 min", LinkLifecycle.dur(15 * min))
    }
}
