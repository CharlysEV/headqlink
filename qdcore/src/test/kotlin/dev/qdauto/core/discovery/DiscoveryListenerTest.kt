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
            val handle = listener.sendAck(first, 34567)
            val p = assertNotNull(receive(car, 3_000))
            assertEquals(listener.localPort, p.port)
            assertContentEquals(UdpCodec.buildBroadcastAck(34567), p.data.copyOf(p.length))
            assertEquals(1, handle.attempts)
            // Por defecto (QDLink) no hay reenvíos.
            assertNull(receive(car, 300))
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
}
