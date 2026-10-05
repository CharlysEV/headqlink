package dev.qdauto.core.session

import dev.qdauto.core.TestSupport.waitUntil
import dev.qdauto.core.wire.CarMessages
import dev.qdauto.core.wire.Direction
import dev.qdauto.core.wire.TraceEvent
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** hql (C3): acceso al socket, configurador, TOS, `shutdown` antes de `close`, ganchos de hilo y `closeLocal`. */
class SocketHooksTest {
    private val closeables = ArrayList<AutoCloseable>()

    @AfterTest
    fun cleanup() = closeables.reversed().forEach { runCatching { it.close() } }

    /** Socket espía: cuenta las llamadas y delega en el original. */
    private class SpySocket : Socket() {
        val calls: MutableList<String> = Collections.synchronizedList(ArrayList())
        override fun shutdownInput() {
            calls += "shutdownInput"
            super.shutdownInput()
        }

        override fun shutdownOutput() {
            calls += "shutdownOutput"
            super.shutdownOutput()
        }

        override fun close() {
            calls += "close"
            super.close()
        }
    }

    /** Servidor de test cuyo `accept()` entrega un [SpySocket]. */
    private class SpyServer : ServerSocket(0) {
        override fun accept(): Socket = SpySocket().also { implAccept(it) }
    }

    @Test
    fun configuratorRunsOnceBeforeTheAppStatusAndTheSocketIsExposed() {
        val server = MirrorServer(0).also { closeables += it }
        val car = FakeCar(server.port).also { closeables += it }
        val accepted = server.accept(5_000)
        val order: MutableList<String> = Collections.synchronizedList(ArrayList())
        val configured = java.util.concurrent.atomic.AtomicInteger()
        val listener = object : SessionListener {
            override fun onTrace(event: TraceEvent) {
                if (event.direction == Direction.OUT && event.kind == "!BIN AppStatus") order += "appStatus"
            }
        }
        val s = PhoneSession(
            accepted,
            SessionConfig(
                heartbeatInitialDelayMs = 60_000,
                trafficClass = 0xA0,
                socketConfigurator = { sock ->
                    configured.incrementAndGet()
                    assertSame(accepted, sock)
                    order += "configurator"
                },
            ),
            listener,
        ).also { closeables += it }
        assertSame(accepted, s.socket)
        s.start()
        car.next()
        assertTrue(waitUntil(2_000) { order.size == 2 })
        assertEquals(listOf("configurator", "appStatus"), order.toList())
        assertEquals(1, configured.get())
    }

    @Test
    fun closeShutsDownBothDirectionsBeforeClosing() {
        val server = SpyServer().also { closeables += it }
        val car = FakeCar(server.localPort).also { closeables += it }
        val spy = server.accept() as SpySocket
        val listener = RecordingListener()
        val s = PhoneSession(spy, SessionConfig(heartbeatInitialDelayMs = 60_000), listener).start()
        car.next()
        s.closeLocal("aplicar ajustes")
        assertTrue(listener.awaitClosed(5_000))
        assertEquals(CloseReason(CloseReason.Kind.LOCAL, "aplicar ajustes"), listener.closeReason)
        val calls = spy.calls.toList()
        assertTrue(calls.indexOf("shutdownInput") in 0 until calls.indexOf("close"), calls.toString())
        assertTrue(calls.indexOf("shutdownOutput") in 0 until calls.indexOf("close"), calls.toString())
        assertTrue(car.awaitEof(5_000))
        assertTrue(s.awaitTermination(5_000))
    }

    @Test
    fun everySessionThreadRunsTheHookFirst() {
        val server = MirrorServer(0).also { closeables += it }
        val car = FakeCar(server.port).also { closeables += it }
        val roles = ConcurrentHashMap<ThreadRole, String>()
        val s = PhoneSession(
            server.accept(5_000),
            SessionConfig(
                heartbeatInitialDelayMs = 20,
                heartbeatPeriodMs = 1_000,
                onThreadStart = { role -> roles[role] = Thread.currentThread().name },
            ),
            RecordingListener(),
        ).also { closeables += it }.start()
        car.send(CarMessages.heartbeat()) // hace trabajar al lector y al hilo de eventos
        assertTrue(waitUntil(3_000) { roles.size == 4 }, roles.toString())
        val n = s.id
        assertEquals("qd-s$n-reader", roles[ThreadRole.READER])
        assertEquals("qd-s$n-writer", roles[ThreadRole.WRITER])
        assertEquals("qd-s$n-timer", roles[ThreadRole.TIMER])
        assertEquals("qd-s$n-events", roles[ThreadRole.EVENTS])
    }

    @Test
    fun aFailingHookOrConfiguratorDoesNotBreakTheSession() {
        val server = MirrorServer(0).also { closeables += it }
        val car = FakeCar(server.port).also { closeables += it }
        val listener = RecordingListener()
        val s = PhoneSession(
            server.accept(5_000),
            SessionConfig(
                heartbeatInitialDelayMs = 60_000,
                socketConfigurator = { throw IllegalStateException("prueba") },
                onThreadStart = { throw IllegalStateException("prueba") },
            ),
            listener,
        ).also { closeables += it }.start()
        car.next()
        car.send(CarMessages.landModeReq(2))
        assertTrue(car.nextJson().contains("LAND_MODE_RSP"))
        assertTrue(!s.isClosed)
    }
}
