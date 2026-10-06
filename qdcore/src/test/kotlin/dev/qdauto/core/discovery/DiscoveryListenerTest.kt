package dev.qdauto.core.discovery

import dev.qdauto.core.TestSupport
import dev.qdauto.core.wire.UdpCodec
import dev.qdauto.core.wire.UdpMessage
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscoveryListenerTest {
    private val loopback = InetAddress.getLoopbackAddress()

    private class Recorder : DiscoveryListener.Callback {
        val found = LinkedBlockingQueue<CarAnnouncement>()
        val seen = LinkedBlockingQueue<CarAnnouncement>()
        val other = LinkedBlockingQueue<UdpMessage>()
        val acks: MutableList<Int> = Collections.synchronizedList(ArrayList())
        override fun onCarFound(car: CarAnnouncement) = found.put(car)
        override fun onCarSeen(car: CarAnnouncement) = seen.put(car)
        override fun onOtherDatagram(from: InetSocketAddress, bytes: ByteArray, parsed: UdpMessage) = other.put(parsed)
        override fun onAckSent(target: InetSocketAddress, mirrorPort: Int, attempt: Int, bytes: ByteArray) {
            acks += attempt
        }
    }

    private fun receive(s: DatagramSocket, timeoutMs: Int): DatagramPacket? {
        s.soTimeout = timeoutMs
        val p = DatagramPacket(ByteArray(2048), 2048)
        return try {
            s.receive(p)
            p
        } catch (_: SocketTimeoutException) {
            null
        }
    }

    @Test
    fun announcesDeduplicatesAndAcksFromTheListeningPort() {
        val car = DatagramSocket(0, loopback)
        val rec = Recorder()
        val listener = DiscoveryListener(DiscoveryConfig(port = 0, ackPort = car.localPort), rec).start()
        try {
            val phone = InetSocketAddress(loopback, listener.localPort)
            val broadcast = UdpCodec.buildConnectBroadcast("uuid-1", "C10")
            repeat(2) { car.send(DatagramPacket(broadcast, broadcast.size, phone)) }
            car.send(DatagramPacket("ruido".toByteArray(), 5, phone))

            val first = assertNotNull(rec.found.poll(3, TimeUnit.SECONDS))
            assertEquals("uuid-1", first.uuid)
            assertEquals("C10", first.name)
            assertEquals(car.localPort, first.sourcePort)
            assertContentEquals(broadcast, first.rawBytes)
            assertEquals("""{"DeviceUUID":"uuid-1","DeviceName":"C10"}""", first.rawJson)
            val again = assertNotNull(rec.seen.poll(3, TimeUnit.SECONDS))
            assertEquals(2, again.count)
            assertTrue(again.lastSeenMillis >= first.lastSeenMillis)
            assertEquals(first.firstSeenMillis, again.firstSeenMillis)
            assertNull(assertNotNull(rec.other.poll(3, TimeUnit.SECONDS)).type)
            assertEquals(1, listener.cars().size)

            // El ACK sale del mismo socket (puerto origen = puerto de escucha) hacia car:ackPort.
            val handle = listener.sendAck(first, 34567, AckPolicy.QDLINK)
            val p = assertNotNull(receive(car, 3_000))
            assertEquals(listener.localPort, p.port)
            assertContentEquals(UdpCodec.buildBroadcastAck(34567), p.data.copyOf(p.length))
            assertEquals(1, handle.attempts)
            // Con la política de QDLink no hay reenvíos.
            assertNull(receive(car, 300))
            assertEquals(1L, listener.solicitedAcksSent)
            assertEquals(0L, listener.unsolicitedAcksSent)
            assertEquals(3L, listener.datagramsReceived)
        } finally {
            listener.close()
            car.close()
        }
        assertTrue(listener.awaitTermination(3_000))
    }

    @Test
    fun ackRetriesUntilCancelled() {
        val car = DatagramSocket(0, loopback)
        val rec = Recorder()
        val listener = DiscoveryListener(DiscoveryConfig(port = 0, ackPort = car.localPort), rec).start()
        try {
            val handle = listener.sendAck(loopback, 40000, AckPolicy(retries = 5, firstRetryDelayMs = 50, retryIntervalMs = 50))
            repeat(3) { assertNotNull(receive(car, 2_000), "ACK #${it + 1}") }
            handle.cancel()
            val attempts = handle.attempts
            Thread.sleep(300)
            assertEquals(attempts, handle.attempts)
            assertTrue(attempts in 3..6)
            // Todos los reenvíos son el mismo datagrama.
            assertTrue(TestSupport.waitUntil(1_000) { rec.acks.size == attempts })
            assertEquals((1..attempts).toList(), rec.acks.toList())
        } finally {
            listener.close()
            car.close()
        }
        assertTrue(listener.awaitTermination(3_000))
    }

    @Test
    fun retriesStopByThemselves() {
        val car = DatagramSocket(0, loopback)
        val listener = DiscoveryListener(DiscoveryConfig(port = 0, ackPort = car.localPort)).start()
        try {
            val handle = listener.sendAck(loopback, 40000, AckPolicy(retries = 2, firstRetryDelayMs = 10, retryIntervalMs = 10))
            assertTrue(TestSupport.waitUntil(2_000) { handle.attempts == 3 })
            Thread.sleep(200)
            assertEquals(3, handle.attempts)
        } finally {
            listener.close()
            car.close()
        }
    }

    @Test
    fun cadenceSlowsDownAfterAWhileAndUnsolicitedAcksAreCountedApart() {
        val car = DatagramSocket(0, loopback)
        val rec = Recorder()
        val listener = DiscoveryListener(DiscoveryConfig(port = 0, ackPort = car.localPort), rec).start()
        try {
            // 50 ms los primeros 300 ms y después cada 200 ms.
            val policy = AckPolicy(retries = 100, firstRetryDelayMs = 50, retryIntervalMs = 50, slowAfterMs = 300, slowIntervalMs = 200)
            val handle = listener.sendAck(loopback, 40001, policy, unsolicited = true)
            val at = ArrayList<Long>()
            val deadline = System.nanoTime() + 1_300_000_000L
            while (System.nanoTime() < deadline) {
                val p = receive(car, 400) ?: break
                assertEquals(40001, assertNotNull(UdpCodec.parseBroadcastAck(p.data, 0, p.length)).mirrorPort)
                at += System.nanoTime()
            }
            handle.cancel()
            val t = at.map { (it - at[0]) / 1_000_000 }
            val fast = t.zipWithNext { a, b -> b - a }.filterIndexed { i, _ -> t[i + 1] <= 250 }
            val slow = t.zipWithNext { a, b -> b - a }.filterIndexed { i, _ -> t[i] >= 350 }
            assertTrue(fast.size >= 3 && fast.all { it in 20..150 }, "rápidos: $t")
            assertTrue(slow.size >= 2 && slow.all { it in 150..350 }, "lentos: $t")
            assertTrue(TestSupport.waitUntil(1_000) { listener.unsolicitedAcksSent == handle.attempts.toLong() })
            assertEquals(0L, listener.solicitedAcksSent)
            assertTrue(handle.lastSentAtNanos > 0)
        } finally {
            listener.close()
            car.close()
        }
        assertTrue(listener.awaitTermination(3_000))
    }

    @Test
    fun reopenKeepsThePortAndPendingAcksGoOutThroughTheNewSocket() {
        val car = DatagramSocket(0, loopback)
        val rec = Recorder()
        val reopened = LinkedBlockingQueue<String>()
        val listener = DiscoveryListener(DiscoveryConfig(port = 0, ackPort = car.localPort), object : DiscoveryListener.Callback by rec {
            override fun onReopened(reason: String) = reopened.put(reason)
        }).start()
        try {
            val port = listener.localPort
            val handle = listener.sendAck(loopback, 40002, AckPolicy(retries = 100, firstRetryDelayMs = 100, retryIntervalMs = 100))
            assertNotNull(receive(car, 2_000))
            assertTrue(listener.reopen("prueba"))
            assertEquals("prueba", reopened.poll(2, TimeUnit.SECONDS))
            assertEquals(1, listener.reopenCount)
            assertEquals(port, listener.localPort)
            assertTrue(listener.isOpen)
            // Los reenvíos siguen saliendo, desde el mismo puerto (el socket nuevo).
            repeat(3) {
                val p = assertNotNull(receive(car, 2_000), "ACK tras reabrir #$it")
                assertEquals(port, p.port)
            }
            handle.cancel()
            // El socket nuevo recibe: un broadcast llega como siempre.
            val broadcast = UdpCodec.buildConnectBroadcast("uuid-r", "C10")
            car.send(DatagramPacket(broadcast, broadcast.size, InetSocketAddress(loopback, port)))
            assertEquals("uuid-r", assertNotNull(rec.found.poll(3, TimeUnit.SECONDS)).uuid)
            assertTrue(listener.lastDatagramAtNanos > 0)
            // Un solo hilo de recepción vivo (el del socket viejo terminó).
            assertTrue(TestSupport.waitUntil(2_000) { TestSupport.liveThreads("qd-discovery-rx").size == 1 }, TestSupport.liveThreads("qd-discovery-rx").toString())
        } finally {
            listener.close()
            car.close()
        }
        assertTrue(listener.awaitTermination(3_000))
        assertTrue(!listener.reopen("cerrado"))
    }

    @Test
    fun policySchedules() {
        // ACK no pedidos: cada 2 s el primer minuto y después cada 5 s.
        val unsolicited = AckPolicy.UNSOLICITED.schedule(36)
        assertEquals((0..30).map { it * 2_000L } + listOf(65_000L, 70_000L, 75_000L, 80_000L, 85_000L), unsolicited)
        // Hasta conectar: cada 2 s, con tope de 5 min.
        val untilConnected = AckPolicy.UNTIL_CONNECTED.schedule(1_000)
        assertEquals(151, untilConnected.size)
        assertEquals(listOf(0L, 2_000L, 4_000L), untilConnected.take(3))
        assertEquals(300_000L, untilConnected.last())
        assertEquals(listOf(0L), AckPolicy.QDLINK.schedule(10))
        assertEquals(AckPolicy.UNTIL_CONNECTED, DiscoveryConfig().ackPolicy)
    }
}
