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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Escucha los `Connect_Broadcast` del coche y envía el `Broadcast_ACK` **desde el mismo socket** (puerto origen
 * 18463), como QDLink (WF/a.java:486-489; IU/e.java:163-165; WF/d.java:80).
 *
 * hql: el socket se puede reabrir en marcha ([reopen]: tras un corte de radio, si no llega nada en un buen rato); los
 * ACK pendientes salen siempre por el socket abierto en ese momento. Cuenta datagramas y ACK (pedidos y no pedidos)
 * para el diagnóstico de la espera del coche.
 *
 * Hilos: uno de recepción (`qd-discovery-rx`, otro nuevo con cada reapertura) y, si hay reintentos de ACK, uno
 * programado (`qd-discovery-ack`). Los callbacks se llaman desde esos hilos: no bloquearlos.
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

        /**
         * hql: como el anterior, diciendo si es un ACK **no pedido** (sin broadcast que contestar: re-acogida tras un
         * corte). Por defecto llama al anterior.
         */
        fun onAckSent(target: InetSocketAddress, mirrorPort: Int, attempt: Int, bytes: ByteArray, unsolicited: Boolean) =
            onAckSent(target, mirrorPort, attempt, bytes)

        /** hql: el socket UDP se ha reabierto ([reopen]) por [reason]. */
        fun onReopened(reason: String) {}

        fun onError(error: Throwable) {}
    }

    private val closed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val cars = ConcurrentHashMap<String, CarAnnouncement>()

    /** Envíos y cambio de socket se excluyen: un ACK nunca sale por un socket a medio cerrar. */
    private val sendLock = Any()

    @Volatile
    private var socket: DatagramSocket? = null

    /** Puerto en el que se escucha (el real si se configuró 0), para reabrir en el mismo. */
    @Volatile
    private var boundPort = -1
    private val rxThreads = CopyOnWriteArrayList<Thread>()
    private var ackScheduler: ScheduledExecutorService? = null

    private val datagrams = AtomicLong()
    private val acksSolicited = AtomicLong()
    private val acksUnsolicited = AtomicLong()
    private val reopens = AtomicInteger()

    @Volatile
    private var lastDatagramNanos = 0L

    @Synchronized
    private fun ackScheduler(): ScheduledExecutorService? {
        if (closed.get()) return null
        return ackScheduler ?: Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "qd-discovery-ack").apply { isDaemon = true }
        }.also { ackScheduler = it }
    }

    /** Puerto local real (útil si se configuró 0). -1 antes de [start]. */
    val localPort: Int get() = socket?.localPort ?: boundPort

    /** hql: hay un socket UDP abierto (falso tras una reapertura fallida, hasta la siguiente). */
    val isOpen: Boolean get() = socket?.isClosed == false

    /** hql: datagramas recibidos desde [start] (de cualquier tipo). */
    val datagramsReceived: Long get() = datagrams.get()

    /** hql: `System.nanoTime()` del último datagrama recibido (0 = ninguno). */
    val lastDatagramAtNanos: Long get() = lastDatagramNanos

    /** hql: ACK enviados en respuesta a un broadcast (y sus reenvíos). */
    val solicitedAcksSent: Long get() = acksSolicited.get()

    /** hql: ACK enviados sin broadcast que contestar (re-acogida). */
    val unsolicitedAcksSent: Long get() = acksUnsolicited.get()

    /** hql: reaperturas del socket UDP hechas. */
    val reopenCount: Int get() = reopens.get()

    /** Abre el socket (lanza si no se puede) y arranca el hilo de recepción. */
    @Synchronized
    @Throws(IOException::class)
    fun start(): DiscoveryListener {
        check(!started.get() && !closed.get()) { "DiscoveryListener ya arrancado o cerrado" }
        val s = openSocket(config.port)
        started.set(true)
        boundPort = s.localPort
        socket = s
        log.i(TAG, "escuchando UDP en ${s.localSocketAddress}")
        startRx(s)
        return this
    }

    /**
     * hql: cierra el socket UDP y abre otro en el mismo puerto (con su hilo de recepción). Para cuando, tras un corte
     * de radio, no llega nada: no se sabe si el socket sigue recibiendo los broadcasts. Devuelve `false` si no se pudo
     * (se reintentará con la siguiente llamada) o si el descubrimiento está cerrado.
     */
    @Synchronized
    fun reopen(reason: String): Boolean {
        if (closed.get() || !started.get()) return false
        log.i(TAG, "descubrimiento: reabro el socket UDP ($reason)")
        synchronized(sendLock) {
            val old = socket
            socket = null
            old?.close()
        }
        val s = try {
            openSocket(boundPort)
        } catch (e: IOException) {
            log.e(TAG, "descubrimiento: no se pudo reabrir el UDP $boundPort", e)
            safe { callback.onError(e) }
            return false
        }
        synchronized(sendLock) {
            if (closed.get()) {
                s.close()
                return false
            }
            socket = s
        }
        reopens.incrementAndGet()
        log.i(TAG, "escuchando UDP en ${s.localSocketAddress} (reabierto)")
        startRx(s)
        safe { callback.onReopened(reason) }
        return true
    }

    @Throws(IOException::class)
    private fun openSocket(port: Int): DatagramSocket {
        val s = DatagramSocket(null as InetSocketAddress?)
        try {
            s.reuseAddress = config.reuseAddress
            s.broadcast = config.broadcast
            s.bind(InetSocketAddress(config.bindAddress, port))
        } catch (e: IOException) {
            s.close()
            throw e
        }
        return s
    }

    private fun startRx(s: DatagramSocket) {
        rxThreads.removeAll { !it.isAlive }
        rxThreads += Thread({ receiveLoop(s) }, "qd-discovery-rx").apply {
            isDaemon = true
            start()
        }
    }

    /** Coches vistos hasta ahora (orden: el más reciente primero). */
    fun cars(): List<CarAnnouncement> = cars.values.sortedByDescending { it.lastSeenMillis }

    fun forgetAll() = cars.clear()

    /** Envía el ACK al coche ([CarAnnouncement.address]:[DiscoveryConfig.ackPort]) con la política indicada. */
    fun sendAck(car: CarAnnouncement, mirrorPort: Int, policy: AckPolicy = config.ackPolicy): AckHandle =
        sendAck(car.address, mirrorPort, policy)

    /** Igual, para una IP elegida a mano. El primer envío es síncrono; los reintentos van en otro hilo. */
    fun sendAck(address: InetAddress, mirrorPort: Int, policy: AckPolicy = config.ackPolicy): AckHandle =
        sendAck(address, mirrorPort, policy, unsolicited = false)

    /**
     * hql: como [sendAck], marcando si es un ACK **no pedido** ([unsolicited]: sin broadcast que contestar, p. ej. la
     * re-acogida tras un corte). Solo cambia la cuenta y el log.
     */
    fun sendAck(address: InetAddress, mirrorPort: Int, policy: AckPolicy, unsolicited: Boolean): AckHandle {
        check(started.get()) { "DiscoveryListener no arrancado" }
        val bytes = UdpCodec.buildBroadcastAck(mirrorPort, config.deviceName, config.deviceUuid, config.passistMobileNum)
        val target = InetSocketAddress(address, config.ackPort)
        val handle = AckSender(bytes, target, mirrorPort, policy, unsolicited)
        handle.sendOnce()
        if (policy.retries > 0) handle.scheduleNext(policy.firstRetryDelayMs)
        return handle
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(sendLock) { socket?.close() }
        rxThreads.forEach { it.interrupt() }
        synchronized(this) { ackScheduler?.shutdownNow() }
        log.i(TAG, "descubrimiento cerrado")
    }

    /** Espera a que terminen los hilos (tras [close]). */
    fun awaitTermination(timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        fun left() = maxOf(1L, (deadline - System.nanoTime()) / 1_000_000)
        for (t in rxThreads) t.join(left())
        val rxDone = rxThreads.none { it.isAlive }
        val sched = synchronized(this) { ackScheduler }
        val ackDone = sched == null || sched.awaitTermination(left(), TimeUnit.MILLISECONDS)
        return rxDone && ackDone
    }

    private fun receiveLoop(s: DatagramSocket) {
        val buf = ByteArray(config.receiveBufferSize)
        while (!closed.get() && socket === s) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                s.receive(packet)
            } catch (e: IOException) {
                // Cerrado o reabierto (hql): este hilo termina; si se reabrió, otro lee del socket nuevo.
                if (closed.get() || socket !== s || s.isClosed) break
                log.e(TAG, "error al recibir UDP", e)
                safe { callback.onError(e) }
                try {
                    Thread.sleep(100)
                } catch (_: InterruptedException) {
                    break
                }
                continue
            }
            datagrams.incrementAndGet()
            lastDatagramNanos = System.nanoTime()
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
        override val bytes: ByteArray,
        private val target: InetSocketAddress,
        private val mirrorPort: Int,
        private val policy: AckPolicy,
        private val unsolicited: Boolean,
    ) : AckHandle {
        private val sent = AtomicInteger(0)
        private val cancelled = AtomicBoolean(false)
        private val firstNanos = System.nanoTime()

        @Volatile
        private var future: ScheduledFuture<*>? = null

        /** Reenvíos hechos (solo desde el hilo programado). */
        private var retriesDone = 0

        override val attempts: Int get() = sent.get()

        @Volatile
        override var lastSentAtNanos = 0L
            private set

        /**
         * Envío y [cancel] se excluyen: cuando [cancel] vuelve, no hay ningún envío en curso ni habrá más. hql: sale por
         * el socket abierto ahora (tras una reapertura, el nuevo); sin socket (reapertura fallida) no se envía.
         */
        fun sendOnce() {
            var error: IOException? = null
            val n = synchronized(this) {
                if (cancelled.get() || closed.get()) return
                synchronized(sendLock) {
                    val s = socket
                    if (s == null || s.isClosed) {
                        0
                    } else {
                        try {
                            lastSentAtNanos = System.nanoTime()
                            s.send(DatagramPacket(bytes, bytes.size, target))
                            sent.incrementAndGet()
                        } catch (e: IOException) {
                            error = e
                            -1
                        }
                    }
                }
            }
            val err = error
            if (err != null) {
                log.e(TAG, "no se pudo enviar el ACK a $target", err)
                safe { callback.onError(err) }
                return
            }
            if (n == 0) {
                log.w(TAG, "ACK a $target sin enviar: el socket UDP no está abierto")
                return
            }
            (if (unsolicited) acksUnsolicited else acksSolicited).incrementAndGet()
            val what = if (unsolicited) "ACK no pedido" else "ACK"
            if (n == 1) {
                log.i(TAG, "$what #$n a $target (MirrorPort=$mirrorPort, ${bytes.size} B): ${String(bytes, Charsets.UTF_8)}")
            } else {
                log.i(TAG, "$what #$n a $target (MirrorPort=$mirrorPort)")
            }
            safe { callback.onAckSent(target, mirrorPort, n, bytes, unsolicited) }
        }

        /** hql: programa el siguiente reenvío (cadencia variable: [AckPolicy.delayAfter]). */
        fun scheduleNext(delayMs: Long) {
            val scheduler = ackScheduler() ?: return
            synchronized(this) {
                if (cancelled.get()) return
                future = try {
                    scheduler.schedule({ retry() }, delayMs, TimeUnit.MILLISECONDS)
                } catch (_: RejectedExecutionException) {
                    null // cerrado mientras tanto
                }
            }
        }

        private fun retry() {
            if (cancelled.get() || closed.get() || retriesDone >= policy.retries) return
            retriesDone++
            sendOnce()
            if (retriesDone < policy.retries) {
                // Envíos hechos (o intentados) hasta ahora: el primero y los reenvíos.
                val elapsedMs = (System.nanoTime() - firstNanos) / 1_000_000
                scheduleNext(policy.delayAfter(retriesDone + 1, elapsedMs))
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
