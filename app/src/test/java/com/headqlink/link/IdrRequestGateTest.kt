package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Antirrebote de las peticiones de IDR al encoder propio (ráfagas de KEY_FRAME_REQ del coche). */
class IdrRequestGateTest {
    private val g = IdrRequestGate()

    @Test
    fun debounceIsAtLeast600msLikeThePassthroughPolicy() {
        assertTrue(IdrRequestGate.DEBOUNCE_MS >= 600)
        assertTrue(KeyframePolicy.DEBOUNCE_MS >= 600)
    }

    @Test
    fun theFirstIdrOfTheSessionIsImmediate() {
        assertEquals(IdrRequestGate.NOW, g.onRequest(0, true))
        // Aunque acabe de pedirse otro: STREAM_START y OVERSIZED no esperan.
        assertEquals(IdrRequestGate.NOW, g.onRequest(10, true))
        assertEquals(IdrRequestGate.NOW, IdrRequestGate().onRequest(1_000_000, false))
    }

    @Test
    fun aStormIsServedByThePendingIdr() {
        assertEquals(IdrRequestGate.NOW, g.onRequest(1_000, false))
        // El IDR pedido aún no ha salido: las peticiones siguientes se sirven con él.
        for (t in 1_050L..1_550L step 100) assertEquals(IdrRequestGate.SKIP, g.onRequest(t, false))
        assertTrue(g.pending())
        // Pasada la ventana, una nueva sí sale.
        assertEquals(IdrRequestGate.NOW, g.onRequest(1_600, false))
    }

    @Test
    fun aRequestAfterTheIdrWentOutIsDeferredOnceToTheEndOfTheWindow() {
        assertEquals(IdrRequestGate.NOW, g.onRequest(0, false))
        g.onIdr()
        assertFalse(g.pending())
        assertEquals(400, g.onRequest(200, false))
        // Las demás de la ventana se suman al aplazamiento.
        assertEquals(IdrRequestGate.SKIP, g.onRequest(300, false))
        assertEquals(IdrRequestGate.SKIP, g.onRequest(500, false))
        assertTrue(g.onDue(600))
        assertTrue(g.pending())
        assertFalse("ya servida", g.onDue(600))
        val line = g.takeWindowLine()
        assertTrue(line, line!!.contains("aplazados 1") && line.contains("servidos con otro 2"))
        assertNull("sin antirrebote no hay línea", g.takeWindowLine())
    }

    @Test
    fun anUrgentRequestCancelsTheDeferredOne() {
        g.onRequest(0, false)
        g.onIdr()
        assertTrue(g.onRequest(100, false) > 0)
        assertEquals(IdrRequestGate.NOW, g.onRequest(200, true))
        assertFalse(g.onDue(600))
        assertEquals("IDR pedidos 2 · aplazados 1 · servidos con otro 0", g.summary())
    }
}
