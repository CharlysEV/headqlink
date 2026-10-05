package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** El ack retenido de AA se ejecuta exactamente una vez, en cualquier orden (qdauto §4.8, §9.1). */
class AckSlotTest {
    @Test
    fun holdThenReleaseRunsTheAckOnce() {
        val runs = AtomicInteger()
        val s = AckSlot(null)
        assertTrue(s.hold { runs.incrementAndGet() })
        assertTrue(s.isHeld)
        assertEquals(0, runs.get())
        assertTrue(s.release() >= 0)
        assertEquals(1, runs.get())
        assertEquals(-1, s.release()) // doble finalización: nada
        assertEquals(1, runs.get())
        assertTrue(s.isDone)
    }

    @Test
    fun releaseBeforeHoldMeansAaAcksItself() {
        val runs = AtomicInteger()
        val s = AckSlot(null)
        assertEquals(-1, s.release()) // el frame terminó (descartado) antes de que AA llamara a hold
        assertFalse(s.hold { runs.incrementAndGet() })
        assertEquals(0, runs.get()) // la confirmación la hace AA en el acto, no la ranura
    }

    @Test
    fun aSecondHoldIsRefused() {
        val s = AckSlot(null)
        assertTrue(s.hold {})
        assertFalse(s.hold {})
    }

    @Test
    fun aThrowingAckDoesNotEscape() {
        val s = AckSlot(null)
        assertTrue(s.hold { throw IllegalStateException("AA desconectado") })
        s.release()
        assertTrue(s.isDone)
    }

    @Test
    fun concurrentFinishersRunTheAckExactlyOnce() {
        val pool = Executors.newFixedThreadPool(4)
        try {
            repeat(2_000) {
                val runs = AtomicInteger()
                val s = AckSlot(null)
                val start = CountDownLatch(1)
                val done = CountDownLatch(4)
                // Uno retiene (hilo de vídeo de AA); tres terminan a la vez (escritor, cierre, drenado).
                pool.execute {
                    start.await()
                    s.hold { runs.incrementAndGet() }
                    done.countDown()
                }
                repeat(3) {
                    pool.execute {
                        start.await()
                        s.release()
                        done.countDown()
                    }
                }
                start.countDown()
                assertTrue(done.await(5, TimeUnit.SECONDS))
                // Si hold ganó, el ack corrió una vez; si llegó tarde, AA confirmó él mismo (0 aquí).
                assertTrue(runs.get() <= 1)
                assertTrue(s.isDone)
                // Nunca queda un ack colgado: si se retuvo, se ejecutó.
                if (runs.get() == 0) assertFalse(s.hold { runs.incrementAndGet() })
            }
        } finally {
            pool.shutdownNow()
        }
    }
}
