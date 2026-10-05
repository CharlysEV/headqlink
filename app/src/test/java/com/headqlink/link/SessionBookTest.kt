package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit

/** Sesión actual y hueco de reconexión del motor QDAuto cuando el inicio de una y el fin de otra se cruzan. */
class SessionBookTest {
    private val posted: MutableList<String> = Collections.synchronizedList(ArrayList())
    private val book = SessionBook { r -> r.run() }

    private fun note(what: String) = Runnable { posted += what }

    private fun start(sid: Int, end: SessionBook.EndStamp? = SessionBook.EndStamp(sid), closed: Boolean = false): SessionBook.Start {
        val s = book.started(sid, end) { closed }
        if (s.current) book.confirm(sid, note("conectado S$sid"))
        return s
    }

    @Test
    fun lateEndOfTheOldSessionDoesNotClearTheNewOne() {
        start(1)
        start(2) // relevo: S2 empieza antes de que termine S1
        assertFalse(book.ended(1, note("perdido S1")))
        assertEquals(2, book.currentId)
        assertTrue(book.ended(2, note("perdido S2")))
        assertEquals(listOf("conectado S1", "conectado S2", "perdido S2"), posted)
        assertEquals(0, book.currentId)
    }

    @Test
    fun endBeforeTheNewStartKeepsTheOrder() {
        start(1)
        assertTrue(book.ended(1, note("perdido S1")))
        start(2)
        assertEquals(listOf("conectado S1", "perdido S1", "conectado S2"), posted)
        assertTrue(book.isCurrentOrNone(2))
        assertFalse(book.isCurrentOrNone(1))
    }

    @Test
    fun sessionClosedBeforeItStartedNeverBecomesCurrent() {
        start(1)
        assertTrue(book.ended(1, note("perdido S1")))
        assertFalse(book.ended(2, note("perdido S2"))) // S2 se cerró y su fin llegó antes que su inicio
        val s = start(2, closed = true)
        assertFalse(s.current)
        assertEquals(0, book.currentId)
        assertEquals(listOf("conectado S1", "perdido S1"), posted)
    }

    @Test
    fun confirmIsSkippedWhenTheSessionAlreadyEnded() {
        assertTrue(book.started(1, SessionBook.EndStamp(1)) { false }.current)
        assertTrue(book.ended(1, note("perdido S1"))) // se cierra mientras se prepara su puente
        assertFalse(book.confirm(1, note("conectado S1")))
        assertEquals(listOf("perdido S1"), posted)
    }

    @Test
    fun crossingStartAndEndNeverLeavesAStaleCurrentSession() {
        repeat(2_000) { i ->
            val b = SessionBook { r -> r.run() }
            val events: MutableList<String> = Collections.synchronizedList(ArrayList())
            val old = 2 * i + 1
            val new = old + 1
            b.started(old, SessionBook.EndStamp(old)) { false }
            b.confirm(old, Runnable { events += "c$old" })
            val barrier = CyclicBarrier(2)
            val ender = Thread {
                barrier.await(5, TimeUnit.SECONDS)
                b.ended(old, Runnable { events += "l$old" })
            }
            val starter = Thread {
                barrier.await(5, TimeUnit.SECONDS)
                if (b.started(new, SessionBook.EndStamp(new)) { false }.current) b.confirm(new, Runnable { events += "c$new" })
            }
            ender.start()
            starter.start()
            ender.join(5_000)
            starter.join(5_000)
            assertEquals("vuelta $i: $events", new, b.currentId)
            // Lo último que ve el hilo principal es «conectado» de la nueva; «perdido» de la vieja, si sale, va antes.
            assertEquals("vuelta $i: $events", "c$new", events.last())
            // Y su fin, cuando llegue, sí avisa.
            assertTrue(b.ended(new, Runnable { events += "l$new" }))
        }
    }

    @Test
    fun gapCarriesTheFirstBroadcastAndAckAndResolvesTheEndLazily() {
        val e1 = SessionBook.EndStamp(1)
        book.noteBroadcast(10) // antes de la primera sesión: no hay hueco
        assertNull(start(1, e1).gap)
        book.noteBroadcast(1_000)
        book.noteBroadcast(1_500) // solo cuenta el primero
        book.noteAck(1_001)
        val g = start(2).gap
        assertNotNull(g)
        g!!
        assertEquals(1, g.prevSid)
        assertEquals(1_000L, g.broadcastNanos)
        assertEquals(1_001L, g.ackNanos)
        assertEquals(0L, g.endNanos) // el onClosed de S1 aún no ha empezado
        e1.stamp(900)
        e1.stamp(950) // vale el primer sello
        assertEquals(900L, g.endNanos)
    }

    @Test
    fun supersedeStampsTheOldEndAndTheBroadcast() {
        val e1 = SessionBook.EndStamp(1)
        start(1, e1)
        book.superseded(e1, 5_000)
        book.noteAck(5_006)
        e1.stamp(5_400) // el onClosed de la vieja llega después: no cambia el fin
        val g = start(2).gap!!
        assertEquals(5_000L, g.endNanos)
        assertEquals(5_000L, g.broadcastNanos)
        assertEquals(5_006L, g.ackNanos)
    }
}
