package dev.qdauto.core.sim

import dev.qdauto.core.json.JsonObject
import dev.qdauto.core.util.QdLog
import dev.qdauto.core.util.e
import dev.qdauto.core.util.i
import dev.qdauto.core.util.w
import dev.qdauto.core.wire.BinBlock
import dev.qdauto.core.wire.BroadcastAck
import dev.qdauto.core.wire.CarMessages
import dev.qdauto.core.wire.Cmd
import dev.qdauto.core.wire.Direction
import dev.qdauto.core.wire.FrameReader
import dev.qdauto.core.wire.Header
import dev.qdauto.core.wire.MsgType
import dev.qdauto.core.wire.TouchCodec
import dev.qdauto.core.wire.TouchPointer
import dev.qdauto.core.wire.Traces
import dev.qdauto.core.wire.UdpCodec
import dev.qdauto.core.wire.WireMessage
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Simulador del coche (lado head unit) para tests y para `:carsim`:
 * 1. emite `Connect_Broadcast` cada [CarSimConfig.broadcastIntervalMs] hasta recibir el `Broadcast_ACK`;
 * 2. conecta por TCP a la IP origen del ACK y al `MirrorPort`;
 * 3. hace el handshake del coche: `CAR_INFO` → `VIDEO_SUP_REQ` → `VIDEO_ARGS` → `VIDEO_CTRL{PlayStatus:1}`,
 *    esperando cada respuesta del teléfono (si [CarSimConfig.waitForReplies]);
 * 4. manda heartbeats, valida el vídeo recibido ([VideoValidator]) y permite inyectar táctil y teclas.
 *
 * Hilos: `carsim-main` (descubrimiento y handshake), `carsim-reader` y `carsim-timer`. Las funciones de envío
 * son thread-safe y devuelven `false` si no se pudo enviar. Cada instancia sirve para una sola conexión.
 */
class CarSim(
    val config: CarSimConfig = CarSimConfig(),
    private val listener: CarSimListener = object : CarSimListener {},
    private val log: QdLog = QdLog.NONE,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val writeLock = Any()
    private val seen = PhoneObserver(config, listener, log)

    @Volatile
    private var silent = false

    /** hql (C4): lectura en pausa hasta este `System.nanoTime()` (0 = leyendo; `Long.MAX_VALUE` = hasta [resumeReading]). */
    private val readGate = Object()
    private var readPausedUntilNanos = 0L

    @Volatile
    private var heartbeatsEnabled = config.heartbeatEnabled

    @Volatile
    private var udp: DatagramSocket? = null

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var out: OutputStream? = null
    private var mainThread: Thread? = null
    private var readerThread: Thread? = null
    private val timer = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "carsim-timer").apply { isDaemon = true } }.apply {
        executeExistingDelayedTasksAfterShutdownPolicy = false
        continueExistingPeriodicTasksAfterShutdownPolicy = false
    }

    private val videoWidth = config.videoArgs.width ?: config.carInfo.carWidth
    private val videoHeight = config.videoArgs.height ?: config.carInfo.carHeight

    val currentState: CarSimState get() = seen.state

    /** Arranca el descubrimiento y el handshake en segundo plano. */
    fun start(): CarSim {
        check(started.compareAndSet(false, true)) { "start() ya se llamó" }
        mainThread = Thread({ runMain() }, "carsim-main").apply {
            isDaemon = true
            start()
        }
        return this
    }

    // ===================================================================== esperas

    fun awaitState(target: CarSimState, timeoutMs: Long): Boolean = seen.awaitState(target, timeoutMs)
    fun awaitPhoneMessage(key: String, count: Int = 1, timeoutMs: Long): Boolean = seen.awaitCount(key, count, timeoutMs)
    fun awaitVideoMessages(count: Int, timeoutMs: Long): Boolean = seen.awaitVideo(count, timeoutMs)
    fun awaitClosed(timeoutMs: Long): Boolean = awaitState(CarSimState.CLOSED, timeoutMs)

    // ===================================================================== inyección

    /** Mensaje táctil arbitrario (msgType 2). */
    fun sendTouch(action: Int, pointers: List<TouchPointer>): Boolean =
        send(TouchCodec.build(action, pointers)).also { if (it) seen.noteTouch(SentTouch(action, pointers.toList())) }

    /** Táctiles enviados, en orden (los primeros [MAX_TOUCH_LOG]). */
    fun sentTouches(): List<SentTouch> = seen.sentTouches()

    /** Pulsación: down, espera [holdMs] y up, con un dedo. Bloquea al llamante. */
    fun tap(x: Float, y: Float, id: Int = 0, holdMs: Long = 50): Boolean {
        if (!sendTouch(TouchCodec.ACTION_DOWN, listOf(TouchPointer.down(id, x, y)))) return false
        sleep(holdMs)
        return sendTouch(TouchCodec.ACTION_UP, listOf(TouchPointer.up(id, x, y)))
    }

    /** Arrastre con un dedo en [steps] movimientos repartidos en [durationMs]. Bloquea al llamante. */
    fun drag(x0: Float, y0: Float, x1: Float, y1: Float, steps: Int = 10, durationMs: Long = 300, id: Int = 0): Boolean {
        if (!sendTouch(TouchCodec.ACTION_DOWN, listOf(TouchPointer.down(id, x0, y0)))) return false
        val n = steps.coerceAtLeast(1)
        for (k in 1..n) {
            sleep(durationMs / n)
            val t = k.toFloat() / n
            if (!sendTouch(TouchCodec.ACTION_MOVE, listOf(TouchPointer.move(id, x0 + (x1 - x0) * t, y0 + (y1 - y0) * t)))) return false
        }
        return sendTouch(TouchCodec.ACTION_UP, listOf(TouchPointer.up(id, x1, y1)))
    }

    /**
     * Pellizco con dos dedos en horizontal alrededor de ([cx], [cy]): la distancia pasa de [startDistance] a
     * [endDistance]. Códigos de `action` supuestos (spec §9.2, [INFERENCIA]): 0 down, 5 pointer-down, 2 move,
     * 6 pointer-up, 1 up. Bloquea al llamante.
     */
    fun pinch(cx: Float, cy: Float, startDistance: Float, endDistance: Float, steps: Int = 10, durationMs: Long = 300): Boolean {
        fun left(d: Float) = cx - d / 2
        fun right(d: Float) = cx + d / 2
        var d = startDistance
        if (!sendTouch(TouchCodec.ACTION_DOWN, listOf(TouchPointer.down(0, left(d), cy)))) return false
        if (!sendTouch(ACTION_POINTER_DOWN, listOf(TouchPointer.move(0, left(d), cy), TouchPointer.down(1, right(d), cy)))) return false
        val n = steps.coerceAtLeast(1)
        for (k in 1..n) {
            sleep(durationMs / n)
            d = startDistance + (endDistance - startDistance) * k / n
            if (!sendTouch(TouchCodec.ACTION_MOVE, listOf(TouchPointer.move(0, left(d), cy), TouchPointer.move(1, right(d), cy)))) return false
        }
        if (!sendTouch(ACTION_POINTER_UP, listOf(TouchPointer.move(0, left(d), cy), TouchPointer.up(1, right(d), cy)))) return false
        return sendTouch(TouchCodec.ACTION_UP, listOf(TouchPointer.up(0, left(d), cy)))
    }

    /** `PHONE_KEYS`: 1 Home, 2 Atrás, 3 Recientes. */
    fun sendPhoneKey(code: Int): Boolean = send(CarMessages.phoneKeys(code)).also { if (it) seen.noteKey() }

    /** msgType 13 `Music/<functionId>` (PlayControl, PlayControlPlay, PlayControlPause, Prev, Next, MuteControl). */
    fun sendMusicKey(functionId: String): Boolean = send(CarMessages.music(functionId)).also { if (it) seen.noteKey() }

    /** `VIDEO_CTRL{PlayStatus}`; con 1 el simulador pasa a [CarSimState.STREAMING] (si estaba en el handshake). */
    fun sendVideoCtrl(playStatus: Int): Boolean = send(CarMessages.videoCtrl(playStatus)).also { sent ->
        if (sent && playStatus == 1 && seen.state == CarSimState.HANDSHAKE) setState(CarSimState.STREAMING)
    }

    /** Cambia lo que se valida en las cabeceras de vídeo (p. ej. tras mandar otro `VIDEO_ARGS` a mano). */
    fun updateVideoExpectations(expectations: VideoExpectations) = seen.updateExpectations(expectations)

    fun requestKeyframe(): Boolean = send(CarMessages.keyFrameReq()).also { if (it) seen.noteKeyframeRequest() }
    fun sendLandModeReq(orientation: Int): Boolean = send(CarMessages.landModeReq(orientation))
    fun sendBtAddr(address: String, status: Int, needAutoConnect: Int): Boolean = send(CarMessages.btAddr(address, status, needAutoConnect))
    fun sendGoInLinkApp(): Boolean = send(CarMessages.goInLinkApp())
    fun sendDisconnectReq(): Boolean = send(CarMessages.disconnectReq())
    fun sendHeartbeat(): Boolean = send(CarMessages.heartbeat())
    fun sendLegacyHeartbeat(): Boolean = send(BinBlock.legacyHeartbeatRequest())
    fun sendControl(cmd: String, para: JsonObject? = null): Boolean = send(CarMessages.control(cmd, para))
    fun sendAppMessage(appId: String, functionId: String, para: JsonObject? = null): Boolean = send(CarMessages.app(appId, functionId, para))

    /** Bytes arbitrarios (ya enmarcados). */
    fun sendRaw(bytes: ByteArray): Boolean = send(bytes)

    fun setHeartbeatsEnabled(enabled: Boolean) {
        heartbeatsEnabled = enabled
    }

    /** Deja de enviar cualquier cosa (heartbeats incluidos) pero sigue leyendo: para probar el watchdog del teléfono. */
    fun goSilent() {
        silent = true
        log.i(TAG, "simulador en silencio")
    }

    /**
     * hql (C4): deja de leer el socket durante [ms] (≤ 0 = hasta [resumeReading]): el `write()` del teléfono se bloquea
     * cuando se llenan los búferes. Con [goSilent] reproduce un corte de radio en los dos sentidos. El mensaje que
     * se esté leyendo se termina.
     */
    fun pauseReading(ms: Long) {
        synchronized(readGate) {
            readPausedUntilNanos = if (ms <= 0) Long.MAX_VALUE else System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms)
        }
        log.i(TAG, "simulador sin leer" + if (ms > 0) " $ms ms" else "")
    }

    /** hql (C4): vuelve a leer. */
    fun resumeReading() {
        synchronized(readGate) {
            readPausedUntilNanos = 0L
            readGate.notifyAll()
        }
        log.i(TAG, "simulador leyendo otra vez")
    }

    /** hql (C4): cierre con RST (`SO_LINGER` 0), como un coche que corta de golpe. */
    fun closeAbruptly() {
        try {
            socket?.setSoLinger(true, 0)
        } catch (_: IOException) {
        }
        finish("cierre abrupto (RST)")
    }

    // ===================================================================== informe y cierre

    fun report(): CarSimReport = seen.report()

    override fun close() = finish("cierre local")

    fun awaitTermination(timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        fun left(): Long = maxOf(1L, (deadline - System.nanoTime()) / 1_000_000)
        mainThread?.join(left())
        readerThread?.join(left())
        val timerDone = timer.awaitTermination(left(), TimeUnit.MILLISECONDS)
        return mainThread?.isAlive != true && readerThread?.isAlive != true && timerDone
    }

    // ===================================================================== hilo principal

    private fun runMain() {
        try {
            setState(CarSimState.DISCOVERING)
            val found = discover()
            if (found == null) {
                if (!closed.get()) finish("no llegó ningún Broadcast_ACK en ${config.discoveryTimeoutMs} ms")
                return
            }
            val (ack, from) = found
            setState(CarSimState.CONNECTING)
            val host = config.connectHost ?: from.address.hostAddress
            val s = Socket()
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, ack.mirrorPort), config.connectTimeoutMs)
            socket = s
            out = s.getOutputStream()
            seen.noteConnected("$host:${ack.mirrorPort}")
            log.i(TAG, "conectado a $host:${ack.mirrorPort}")
            if (closed.get()) {
                s.close()
                return
            }
            val input = s.getInputStream()
            readerThread = Thread({ readLoop(input) }, "carsim-reader").apply {
                isDaemon = true
                start()
            }
            schedule {
                timer.scheduleWithFixedDelay({ heartbeatTick() }, config.heartbeatPeriodMs, config.heartbeatPeriodMs, TimeUnit.MILLISECONDS)
            }
            setState(CarSimState.HANDSHAKE)
            handshake()
        } catch (e: Exception) {
            if (!closed.get()) {
                log.e(TAG, "error en el simulador", e)
                finish("error: $e")
            }
        }
    }

    /** Broadcast periódico desde el puerto del ACK hasta recibir un `Broadcast_ACK`. */
    private fun discover(): Pair<BroadcastAck, InetSocketAddress>? {
        val sock = DatagramSocket(null as InetSocketAddress?)
        sock.reuseAddress = true
        sock.broadcast = true
        sock.bind(InetSocketAddress(config.carAckPort))
        udp = sock
        val payload = UdpCodec.buildConnectBroadcast(config.deviceUuid, config.deviceName, config.broadcastExtra)
        val target = InetSocketAddress(config.broadcastAddress, config.phoneDiscoveryPort)
        val buf = ByteArray(65_535)
        var acksIgnored = 0
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.discoveryTimeoutMs)
        try {
            while (!closed.get() && System.nanoTime() < deadline) {
                sendBroadcast(sock, payload, target)
                val next = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.broadcastIntervalMs)
                while (!closed.get()) {
                    val waitMs = TimeUnit.NANOSECONDS.toMillis(next - System.nanoTime())
                    if (waitMs <= 0) break
                    sock.soTimeout = waitMs.toInt().coerceAtLeast(1)
                    val p = DatagramPacket(buf, buf.size)
                    try {
                        sock.receive(p)
                    } catch (_: SocketTimeoutException) {
                        break
                    }
                    val bytes = p.data.copyOfRange(p.offset, p.offset + p.length)
                    val from = p.socketAddress as InetSocketAddress
                    val ack = UdpCodec.parseBroadcastAck(bytes)
                    if (ack == null) {
                        seen.noteUnexpected("UDP de $from que no es un ACK: ${String(bytes, Charsets.UTF_8).take(200)}")
                        continue
                    }
                    if (acksIgnored < config.ignoreAcks) {
                        // hql (C4): ACK perdido a propósito; el coche sigue anunciándose.
                        acksIgnored++
                        log.i(TAG, "ACK de $from ignorado a propósito ($acksIgnored de ${config.ignoreAcks})")
                        continue
                    }
                    log.i(TAG, "ACK de $from: ${String(bytes, Charsets.UTF_8)}")
                    seen.noteAck(ack, from)
                    listener.onAck(ack, from)
                    if (config.keepBroadcasting) {
                        schedule {
                            timer.scheduleWithFixedDelay({ sendBroadcast(sock, payload, target) }, config.broadcastIntervalMs,
                                config.broadcastIntervalMs, TimeUnit.MILLISECONDS)
                        }
                    }
                    return ack to from
                }
            }
            return null
        } finally {
            if (!config.keepBroadcasting || !seen.hasAck) sock.close()
        }
    }

    private fun sendBroadcast(sock: DatagramSocket, payload: ByteArray, target: InetSocketAddress) {
        if (closed.get() || silent) return
        try {
            sock.send(DatagramPacket(payload, payload.size, target))
            seen.noteBroadcast()
        } catch (e: IOException) {
            if (!closed.get()) log.w(TAG, "no se pudo enviar el broadcast a $target: $e")
        }
    }

    private fun handshake() {
        val va = config.videoArgs
        step("CAR_INFO", CarMessages.carInfo(config.carInfo.toPara()), Cmd.PHONE_INFO)
        step("VIDEO_SUP_REQ", CarMessages.videoSupReq(config.videoFormat), Cmd.VIDEO_SUP_RSP)
        step(
            "VIDEO_ARGS",
            CarMessages.videoArgs(videoWidth, videoHeight, va.encodingType, va.frameRate, va.bitRate, va.frameInterval),
            Cmd.SPEECH_ARGS,
        )
        if (config.sendPlay && !closed.get()) sendVideoCtrl(1)
    }

    /** Envía un paso del handshake y espera la respuesta esperada del teléfono. */
    private fun step(label: String, message: ByteArray, expectedReply: String) {
        if (closed.get()) return
        val before = seen.count(expectedReply)
        if (!send(message)) {
            seen.noteHandshakeError("no se pudo enviar $label")
            return
        }
        if (config.waitForReplies && !seen.awaitCount(expectedReply, before + 1, config.replyTimeoutMs) && !closed.get()) {
            log.w(TAG, "sin $expectedReply tras $label")
            seen.noteHandshakeError("sin $expectedReply en ${config.replyTimeoutMs} ms tras $label")
        }
        if (config.stepDelayMs > 0) sleep(config.stepDelayMs)
    }

    private fun heartbeatTick() {
        if (heartbeatsEnabled && !silent) send(CarMessages.heartbeat())
    }

    private fun readLoop(input: InputStream) {
        val reader = FrameReader(input, config.maxMessageBytes)
        try {
            while (!closed.get()) {
                waitWhileReadingPaused()
                if (closed.get()) return
                val m = reader.next()
                if (m == null) {
                    finish("EOF: el teléfono cerró la conexión")
                    return
                }
                try {
                    seen.onPhoneMessage(m)
                } catch (e: Exception) {
                    log.e(TAG, "error procesando un mensaje del teléfono", e)
                }
                if (m is WireMessage.Frame && exceedsReceiverLimit(m.header)) {
                    hangLikeTheCar(m.header.totalSize)
                    return
                }
            }
        } catch (e: IOException) {
            if (!closed.get()) finish("error de lectura: $e")
        }
    }

    private fun exceedsReceiverLimit(h: Header): Boolean =
        config.receiverLimitBytes > 0 && h.msgType == MsgType.VIDEO && h.totalSize > config.receiverLimitBytes

    /**
     * Manía del C10 (2026-10-05): con un mensaje de vídeo de más de [CarSimConfig.receiverLimitBytes] el receptor
     * deja de leer el TCP (el teléfono se queda bloqueado en `write()`) mientras sigue mandando heartbeats, y la
     * sesión acaba cayendo ~10 s después. Aquí: se deja de leer [CarSimConfig.receiverHangMs] y se cierra.
     */
    private fun hangLikeTheCar(messageBytes: Int) {
        val hang = ReceiverHang(seen.count("VIDEO") - 1, messageBytes, config.receiverLimitBytes, config.receiverHangMs)
        seen.noteReceiverHang(hang)
        log.w(TAG, "receptor colgado como el C10: ${hang.describe()}; sin leer ${hang.hangMs} ms")
        listener.onReceiverHang(hang)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(hang.hangMs)
        while (!closed.get()) {
            val leftMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (leftMs <= 0) break
            sleep(minOf(leftMs, 100L))
        }
        finish("receptor colgado como el C10: ${hang.describe()}; cerrado tras ${hang.hangMs} ms sin leer")
    }

    /** hql (C4): espera mientras la lectura esté en pausa ([pauseReading]). */
    private fun waitWhileReadingPaused() {
        synchronized(readGate) {
            while (!closed.get()) {
                val until = readPausedUntilNanos
                if (until == 0L) return
                val left = until - System.nanoTime()
                if (left <= 0) {
                    readPausedUntilNanos = 0L
                    return
                }
                try {
                    readGate.wait(maxOf(1L, TimeUnit.NANOSECONDS.toMillis(left)))
                } catch (_: InterruptedException) {
                    return
                }
            }
        }
    }

    // ===================================================================== envío y cierre

    private fun send(bytes: ByteArray): Boolean {
        if (closed.get() || silent) return false
        val o = out ?: return false
        synchronized(writeLock) {
            try {
                o.write(bytes)
                o.flush()
            } catch (e: IOException) {
                if (!closed.get()) {
                    val hung = if (seen.hasReceiverHang) "receptor colgado como el C10 y el teléfono cortó antes; " else ""
                    finish("${hung}error de escritura: $e")
                }
                return false
            }
        }
        traceOut(bytes)
        return true
    }

    private fun traceOut(bytes: ByteArray) {
        val event = when (val msg = FrameReader.readAll(bytes).firstOrNull()) {
            is WireMessage.Frame -> Traces.ofFrame(Direction.OUT, msg, config.traceMaxJsonChars, 64)
            is WireMessage.Bin -> Traces.ofBinary(Direction.OUT, "!BIN", null, bytes, msg.block.describe(), 0)
            else -> Traces.ofBinary(Direction.OUT, "RAW", null, bytes, "${bytes.size} B", 64)
        }
        listener.onTrace(event)
    }

    private fun setState(s: CarSimState) {
        if (seen.setState(s)) {
            log.i(TAG, "estado → $s")
            listener.onStateChanged(s)
        }
    }

    private fun finish(reason: String) {
        if (!closed.compareAndSet(false, true)) return
        log.i(TAG, "cerrando: $reason")
        seen.close(reason)
        timer.shutdownNow()
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        udp?.close()
        mainThread?.interrupt()
        synchronized(readGate) { readGate.notifyAll() }
        listener.onStateChanged(CarSimState.CLOSED)
        listener.onClosed(reason)
    }

    private inline fun schedule(block: () -> Unit) {
        try {
            block()
        } catch (_: RejectedExecutionException) {
        }
    }

    private fun sleep(ms: Long) {
        if (ms <= 0) return
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        private const val TAG = "QD/CarSim"
        const val MAX_TOUCH_LOG = 10_000

        /** `action` supuestos para el segundo dedo ([INFERENCIA], spec §9.2: códigos de `MotionEvent`). */
        const val ACTION_POINTER_DOWN = 5
        const val ACTION_POINTER_UP = 6
    }
}
