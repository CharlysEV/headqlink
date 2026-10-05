package dev.qdauto.core.discovery

import dev.qdauto.core.util.Hex
import dev.qdauto.core.util.QdLog
import dev.qdauto.core.util.e
import dev.qdauto.core.util.i
import dev.qdauto.core.util.w
import dev.qdauto.core.wire.UdpCodec
import dev.qdauto.core.wire.UdpMessage
import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Escucha los `Connect_Broadcast` del coche y envía el `Broadcast_ACK` **desde el mismo socket** (puerto origen
 * 18463), como QDLink (WF/a.java:486-489; IU/e.java:163-165; WF/d.java:80).
 *
 * Hilos: uno de recepción (`qd-discovery-rx`) y, si hay reintentos de ACK, uno programado (`qd-discovery-ack`).
 * Los callbacks se llaman desde esos hilos: no bloquearlos.
 */
class DiscoveryListener(
    val config: DiscoveryConfig = DiscoveryConfig(),
    private val callback: Callback = object : Callback {},
    private val log: QdLog = QdLog.NONE,
) : Closeable {

    interface Callback {
        /** Primer broadcast de un coche (clave `DeviceUUID` + IP). */
        fun onCarFound(car: CarAnnouncement) {}

        /** Cada broadcast repetido de un coche ya conocido (con [CarAnnouncement.count] y `lastSeen` al día). */
        fun onCarSeen(car: CarAnnouncement) {}

        /** Cualquier datagrama que no sea un `Connect_Broadcast` con JSON legible. */
        fun onOtherDatagram(from: InetSocketAddress, bytes: ByteArray, parsed: UdpMessage) {}

        /** Cada envío del ACK (el primero es `attempt` = 1). */
        fun onAckSent(target: InetSocketAddress, mirrorPort: Int, attempt: Int, bytes: ByteArray) {}

        fun onError(error: Throwable) {}
    }

    private val closed = AtomicBoolean(false)
    private val cars = ConcurrentHashMap<String, CarAnnouncement>()

    @Volatile
    private var socket: DatagramSocket? = null
    private var rxThread: Thread? = null
    private var ackScheduler: ScheduledExecutorService? = null

    @Synchronized
    private fun ackScheduler(): ScheduledExecutorService? {
        if (closed.get()) return null
        return ackScheduler ?: Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "qd-discovery-ack").apply { isDaemon = true }
        }.also { ackScheduler = it }
    }

    /** Puerto local real (útil si se configuró 0). -1 antes de [start]. */
    val localPort: Int get() = socket?.localPort ?: -1

    /** Abre el socket (lanza si no se puede) y arranca el hilo de recepción. */
    @Synchronized
    @Throws(IOException::class)
    fun start(): DiscoveryListener {
        check(socket == null && !closed.get()) { "DiscoveryListener ya arrancado o cerrado" }
        val s = DatagramSocket(null as InetSocketAddress?)
        try {
            s.reuseAddress = config.reuseAddress
            s.broadcast = config.broadcast
            s.bind(InetSocketAddress(config.bindAddress, config.port))
        } catch (e: IOException) {
            s.close()
            throw e
        }
        socket = s
        log.i(TAG, "escuchando UDP en ${s.localSocketAddress}")
        rxThread = Thread({ receiveLoop(s) }, "qd-discovery-rx").apply {
            isDaemon = true
            start()
        }
        return this
    }

    /** Coches vistos hasta ahora (orden: el más reciente primero). */
    fun cars(): List<CarAnnouncement> = cars.values.sortedByDescending { it.lastSeenMillis }

    fun forgetAll() = cars.clear()

    /** Envía el ACK al coche ([CarAnnouncement.address]:[DiscoveryConfig.ackPort]) con la política indicada. */
    fun sendAck(car: CarAnnouncement, mirrorPort: Int, policy: AckPolicy = config.ackPolicy): AckHandle =
        sendAck(car.address, mirrorPort, policy)

    /** Igual, para una IP elegida a mano. El primer envío es síncrono; los reintentos van en otro hilo. */
    fun sendAck(address: InetAddress, mirrorPort: Int, policy: AckPolicy = config.ackPolicy): AckHandle {
        val s = socket ?: throw IllegalStateException("DiscoveryListener no arrancado")
        val bytes = UdpCodec.buildBroadcastAck(mirrorPort, config.deviceName, config.deviceUuid, config.passistMobileNum)
        val target = InetSocketAddress(address, config.ackPort)
        val handle = AckSender(s, bytes, target, mirrorPort)
        handle.sendOnce()
        if (policy.retries > 0) handle.schedule(policy)
        return handle
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        socket?.close()
        rxThread?.interrupt()
        synchronized(this) { ackScheduler?.shutdownNow() }
        log.i(TAG, "descubrimiento cerrado")
    }

    /** Espera a que terminen los hilos (tras [close]). */
    fun awaitTermination(timeoutMs: Long): Boolean {
        rxThread?.join(timeoutMs)
        val rxDone = rxThread?.isAlive != true
        val sched = synchronized(this) { ackScheduler }
        val ackDone = sched == null || sched.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)
        return rxDone && ackDone
    }

    private fun receiveLoop(s: DatagramSocket) {
        val buf = ByteArray(config.receiveBufferSize)
        while (!closed.get()) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                s.receive(packet)
            } catch (e: IOException) {
                if (closed.get()) break
                log.e(TAG, "error al recibir UDP", e)
                safe { callback.onError(e) }
                try {
                    Thread.sleep(100)
                } catch (_: InterruptedException) {
                    break
                }
                continue
            }
            val bytes = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
            try {
                handleDatagram(packet.address, packet.port, bytes)
            } catch (e: Exception) {
                log.e(TAG, "error procesando datagrama", e)
                safe { callback.onError(e) }
            }
        }
    }

    private fun handleDatagram(address: InetAddress, port: Int, bytes: ByteArray) {
        val msg = UdpCodec.parse(bytes)
        val from = InetSocketAddress(address, port)
        log.i(TAG, "UDP ${bytes.size} B de $from: ${String(bytes, Charsets.UTF_8).take(512)}")
        if (msg.warnings.isNotEmpty()) log.w(TAG, "avisos del datagrama: ${msg.warnings}")
        val json = msg.json
        if (msg.type != UdpCodec.CONNECT_BROADCAST || json == null) {
            log.w(TAG, "datagrama ignorado (tipo=${msg.type}):\n${Hex.dump(bytes, maxBytes = 256)}")
            safe { callback.onOtherDatagram(from, bytes, msg) }
            return
        }
        val uuid = json.string("DeviceUUID") ?: ""
        val name = json.string("DeviceName") ?: ""
        val now = System.currentTimeMillis()
        val key = CarAnnouncement.keyOf(uuid, address)
        // hql (C1): sin ConcurrentHashMap.compute (API 24); el lector es único y las lecturas de cars() siguen
        // siendo seguras porque el mapa sigue siendo concurrente.
        val (car, isNew) = synchronized(cars) {
            val prev = cars[key]
            val next = CarAnnouncement(
                address = address,
                sourcePort = port,
                uuid = uuid,
                name = name,
                rawJson = msg.jsonText ?: "",
                rawBytes = bytes,
                json = json,
                qdlinkCompatible = msg.qdlinkCompatible,
                warnings = msg.warnings,
                firstSeenMillis = prev?.firstSeenMillis ?: now,
                lastSeenMillis = now,
                count = (prev?.count ?: 0) + 1,
            )
            cars[key] = next
            next to (prev == null)
        }
        if (isNew) {
            log.i(TAG, "coche nuevo: $car qdlink=${car.qdlinkCompatible}")
            safe { callback.onCarFound(car) }
        } else {
            safe { callback.onCarSeen(car) }
        }
    }

    private inline fun safe(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            log.e(TAG, "excepción en un callback de descubrimiento", e)
        }
    }

    private inner class AckSender(
        private val s: DatagramSocket,
        override val bytes: ByteArray,
        private val target: InetSocketAddress,
        private val mirrorPort: Int,
    ) : AckHandle {
        private val sent = AtomicInteger(0)
        private val cancelled = AtomicBoolean(false)

        @Volatile
        private var future: ScheduledFuture<*>? = null
        private var remaining = 0

        override val attempts: Int get() = sent.get()

        /** Envío y [cancel] se excluyen: cuando [cancel] vuelve, no hay ningún envío en curso ni habrá más. */
        fun sendOnce() {
            var error: IOException? = null
            val n = synchronized(this) {
                if (cancelled.get() || closed.get()) return
                try {
                    s.send(DatagramPacket(bytes, bytes.size, target))
                    sent.incrementAndGet()
                } catch (e: IOException) {
                    error = e
                    -1
                }
            }
            val err = error
            if (err != null) {
                log.e(TAG, "no se pudo enviar el ACK a $target", err)
                safe { callback.onError(err) }
                return
            }
            log.i(TAG, "ACK #$n a $target (MirrorPort=$mirrorPort, ${bytes.size} B): ${String(bytes, Charsets.UTF_8)}")
            safe { callback.onAckSent(target, mirrorPort, n, bytes) }
        }

        fun schedule(policy: AckPolicy) {
            remaining = policy.retries
            val scheduler = ackScheduler() ?: return
            future = try {
                scheduler.scheduleWithFixedDelay({
                    if (cancelled.get() || closed.get() || remaining <= 0) {
                        future?.cancel(false)
                    } else {
                        remaining--
                        sendOnce()
                        if (remaining <= 0) future?.cancel(false)
                    }
                }, policy.firstRetryDelayMs, policy.retryIntervalMs, TimeUnit.MILLISECONDS)
            } catch (_: RejectedExecutionException) {
                null // cerrado mientras tanto
            }
        }

        override fun cancel() {
            synchronized(this) {
                if (!cancelled.compareAndSet(false, true)) return
            }
            future?.cancel(false)
        }
    }

    private companion object {
        const val TAG = "QD/Discovery"
    }
}
