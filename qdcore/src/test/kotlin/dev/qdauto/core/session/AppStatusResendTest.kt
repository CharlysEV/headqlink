package dev.qdauto.core.session

import dev.qdauto.core.TestSupport.waitUntil
import dev.qdauto.core.json.JsonObject
import dev.qdauto.core.wire.BinBlock
import dev.qdauto.core.wire.CarMessages
import dev.qdauto.core.wire.WireMessage
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * hql: por cable, el C10 a veces no ve el primer AppStatus (empieza a leer tarde) y manda heartbeats sin `CAR_INFO`
 * hasta rendirse. El teléfono repite el AppStatus ([SessionConfig.appStatusResendMax]) y, si sigue sin `CAR_INFO`,
 * cierra a los [SessionConfig.handshakeTimeoutMs] para volver a abrir.
 */
class AppStatusResendTest {
    private val closeables = ArrayList<AutoCloseable>()

    @AfterTest
    fun cleanup() = closeables.reversed().forEach { runCatching { it.close() } }

    private class Rig(val car: FakeCar, val session: PhoneSession, val listener: RecordingListener, val startedNanos: Long)

    private fun rig(config: SessionConfig): Rig {
        val server = MirrorServer(0).also { closeables += it }
        val car = FakeCar(server.port).also { closeables += it }
        val listener = RecordingListener()
        val started = System.nanoTime()
        val s = PhoneSession(server.accept(5_000), config, listener).also { closeables += it }.start()
        return Rig(car, s, listener, started)
    }

    // Sin heartbeats del teléfono: al coche solo le llega lo que se prueba.
    private fun config(resends: Int, intervalMs: Long, handshakeTimeoutMs: Long = 0) = SessionConfig(
        heartbeatInitialDelayMs = 60_000,
        appStatusResendMax = resends,
        appStatusResendIntervalMs = intervalMs,
        handshakeTimeoutMs = handshakeTimeoutMs,
        watchdogCheckIntervalMs = 25,
    )

    private fun isAppStatus(m: WireMessage) = m is WireMessage.Bin && m.block.cmd == BinBlock.CMD_APP_STATUS

    /** El coche manda un heartbeat cada [periodMs] mientras [go] (como mucho [maxMs]) y sin saludar. */
    private fun beat(car: FakeCar, periodMs: Long, maxMs: Long, go: () -> Boolean) {
        val deadline = System.currentTimeMillis() + maxMs
        while (go() && System.currentTimeMillis() < deadline) {
            try {
                car.send(CarMessages.heartbeat())
            } catch (_: IOException) {
                return // el teléfono cerró
            }
            Thread.sleep(periodMs)
        }
    }

    private fun carInfo() = CarMessages.carInfo(
        JsonObject.of(
            "Version" to "1", "CarType" to "2D4", "Platform" to 0, "PlatformVersion" to "1", "CarWidth" to 1920,
            "CarHeight" to 1080, "CarFactory" to "018", "HUFactory" to "119", "MirrorTypeReq" to 0,
        ),
    )

    @Test
    fun theAppStatusIsRepeatedWhenTheCarBeatsWithoutGreeting() {
        val r = rig(config(resends = 3, intervalMs = 500))
        val first = r.car.next()
        assertTrue(isAppStatus(first), "$first")
        // Un heartbeat enseguida: el coche puede tardar un poco en mandar CAR_INFO, aún no se repite.
        r.car.send(CarMessages.heartbeat())
        assertTrue(waitUntil(2_000) { r.session.stats().carHeartbeats == 1L })
        assertEquals(0, r.session.appStatusResent)
        // Sigue latiendo sin saludar: pasado el intervalo, el mismo AppStatus otra vez.
        beat(r.car, 50, 3_000) { r.session.appStatusResent == 0 }
        val second = r.car.next()
        assertTrue(isAppStatus(second), "$second")
        assertContentEquals((first as WireMessage.Bin).block.bytes, (second as WireMessage.Bin).block.bytes)
        val ms = (System.nanoTime() - r.startedNanos) / 1_000_000
        assertTrue(ms >= 500, "repetido a los $ms ms")
        // Ahora sí lo ve y saluda: la sesión empieza y ya no se repite, lata lo que lata.
        r.car.send(carInfo())
        assertTrue(waitUntil(2_000) { r.listener.carInfo != null })
        beat(r.car, 50, 1_200) { true }
        assertEquals(1, r.session.appStatusResent)
        assertEquals(0, r.car.drain().count { isAppStatus(it) })
        assertFalse(r.session.isClosed)
    }

    @Test
    fun atMostTheConfiguredRepeats() {
        val r = rig(config(resends = 2, intervalMs = 100))
        assertTrue(isAppStatus(r.car.next()))
        beat(r.car, 30, 1_000) { true }
        assertEquals(2, r.session.appStatusResent)
        assertEquals(2, r.car.drain().count { isAppStatus(it) })
        assertFalse(r.session.isClosed) // sin límite para el saludo (handshakeTimeoutMs = 0), como QDLink
    }

    @Test
    fun byDefaultItIsSentOnceLikeQdLink() {
        val r = rig(SessionConfig(heartbeatInitialDelayMs = 60_000, appStatusResendIntervalMs = 50))
        assertTrue(isAppStatus(r.car.next()))
        beat(r.car, 30, 600) { true }
        assertEquals(0, r.session.appStatusResent)
        assertEquals(0, r.car.drain().count { isAppStatus(it) })
    }

    @Test
    fun withoutCarInfoTheSessionClosesAtTheHandshakeTimeout() {
        val r = rig(config(resends = 3, intervalMs = 100, handshakeTimeoutMs = 700))
        assertTrue(isAppStatus(r.car.next()))
        // El coche late sin parar (el watchdog de recepción no cortaría nunca), pero no saluda.
        beat(r.car, 40, 5_000) { !r.session.isClosed }
        assertTrue(r.listener.awaitClosed(3_000))
        val reason = r.listener.closeReason!!
        assertEquals(CloseReason.Kind.WATCHDOG, reason.kind)
        assertTrue("sin CAR_INFO" in reason.message, reason.message)
        assertTrue("repetido 3 veces" in reason.message, reason.message)
        val ms = (System.nanoTime() - r.startedNanos) / 1_000_000
        assertTrue(ms >= 700, "cerró a los $ms ms")
        assertEquals(3, r.session.appStatusResent)
    }

    @Test
    fun theHandshakeTimeoutDoesNotApplyOnceTheCarGreets() {
        val r = rig(config(resends = 3, intervalMs = 100, handshakeTimeoutMs = 400))
        assertTrue(isAppStatus(r.car.next()))
        r.car.send(carInfo())
        assertTrue(waitUntil(2_000) { r.listener.carInfo != null })
        beat(r.car, 40, 1_000) { true }
        assertFalse(r.session.isClosed)
        assertEquals(0, r.session.appStatusResent)
    }
}
