package dev.qdauto.core.session

import dev.qdauto.core.json.JsonObject
import dev.qdauto.core.util.QdLog
import dev.qdauto.core.util.d
import dev.qdauto.core.util.e
import dev.qdauto.core.util.i
import dev.qdauto.core.util.w
import dev.qdauto.core.wire.BinBlock
import dev.qdauto.core.wire.CarInfo
import dev.qdauto.core.wire.Cmd
import dev.qdauto.core.wire.FrameReader
import dev.qdauto.core.wire.Frames
import dev.qdauto.core.wire.Header
import dev.qdauto.core.wire.MsgType
import dev.qdauto.core.wire.PhoneInfo
import dev.qdauto.core.wire.PhoneInfoChange
import dev.qdauto.core.wire.PhoneMessages
import dev.qdauto.core.wire.TraceEvent
import dev.qdauto.core.wire.VideoArgs
import dev.qdauto.core.wire.VideoMessage
import dev.qdauto.core.wire.VideoParams
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.nio.ByteBuffer
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Sesión QDLink del lado del teléfono sobre un TCP ya aceptado (ver [MirrorServer] y [PhoneLink]).
 *
 * Al [start]: AppStatus `!BIN`, heartbeat (1 s y luego cada 3 s), watchdog de recepción y respuestas automáticas del
 * handshake (`PHONE_INFO` + `UPDATE_NOTIFY`, `VIDEO_SUP_RSP`, `SPEECH_ARGS`, `LAND_MODE_RSP`, `WhitelistAppOn`…),
 * cada una configurable en [SessionConfig]. Tras `VIDEO_CTRL{1}` acepta vídeo con [sendCodecConfig]/[sendFrame].
 *
 * Hilos (todos terminan al cerrar): `qd-sN-reader` (lee y responde), `qd-sN-writer` (único que escribe en el socket:
 * un `write()` por mensaje, control antes que vídeo), `qd-sN-timer` (heartbeat, watchdog, lista blanca) y
 * `qd-sN-events` (entrega en orden los callbacks de [SessionListener]). Todos los métodos públicos son thread-safe y
 * los de envío nunca bloquean.
 */
class PhoneSession(
    /** hql (C3): el socket aceptado (solo lectura: `NetStat`, IP local, log; no escribir ni cerrar desde fuera). */
    val socket: Socket,
    val config: SessionConfig = SessionConfig(),
    internal val listener: SessionListener = object : SessionListener {},
    internal val log: QdLog = QdLog.NONE,
) : Closeable {

    val id: Int = ID_SEQ.incrementAndGet()
    internal val tag = "QD/S$id"

    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val stateLock = Any()

    @Volatile
    private var currentState = SessionState.CONNECTED

    @Volatile
    var closeReason: CloseReason? = null
        private set

    /** Último `CAR_INFO` recibido. */
    @Volatile
    var carInfo: CarInfo? = null
        internal set

    /** Último `VIDEO_ARGS` recibido. */
    @Volatile
    var videoArgs: VideoArgs? = null
        internal set

    /** Geometría calculada con el último `CAR_INFO`. */
    @Volatile
    var geometry: MirrorGeometry? = null
        internal set

    /** Último `PHONE_INFO` enviado (o que se enviaría). */
    @Volatile
    var lastPhoneInfo: PhoneInfo? = null
        internal set

    @Volatile
    private var overrides: VideoOverrides = config.videoOverrides

    /** Cabecera de vídeo que llevarán los próximos frames (automática + [VideoOverrides]). */
    @Volatile
    var videoParams: VideoParams = computeVideoParams()
        private set

    @Volatile
    private var codecConfig: ByteArray? = null

    @Volatile
    private var whitelistValue: Int = config.whitelistValue
    private var whitelistStarted = false

    /** El coche ya ha mandado algún mensaje 5A5A (el `W == "5A5A"` de QDLink). */
    @Volatile
    internal var carSpoke = false

    @Volatile
    private var watchdogWarned = false

    @Volatile
    private var lastKeyframeRequestNanos = 0L

    /** hql (C2): `System.nanoTime()` al crear la sesión. */
    private val createdNanos = System.nanoTime()

    /** hql (C4): `System.nanoTime()` de [start] (0 si aún no ha arrancado). */
    @Volatile
    var startedAtNanos: Long = 0L
        private set

    internal val counters = SessionCounters()
    internal val queue = SendQueue(
        backlogFrames = config.videoBacklogFrames,
        backlogBytes = config.videoBacklogBytes.toLong(),
        hardLimitBytes = config.videoQueueHardLimitBytes.toLong(),
        resendConfigAfterDrop = config.resendCodecConfigAfterDrop,
        controlCapacity = config.controlQueueCapacity,
        dropPolicy = config.videoDropPolicy,
        maxLagNanos = SendQueue.lagNanos(config.videoMaxLagMs),
    )
    private val events = EventDispatcher("qd-s$id-events", log, tag) { threadStarted(ThreadRole.EVENTS) }
    // hql (C1): sin removeOnCancelPolicy (API 21): la sesión no cancela tareas sueltas y el temporizador se cierra
    // con shutdownNow.
    private val timer = ScheduledThreadPoolExecutor(1) { r ->
        Thread({
            threadStarted(ThreadRole.TIMER)
            r.run()
        }, "qd-s$id-timer").apply { isDaemon = true }
    }.apply {
        executeExistingDelayedTasksAfterShutdownPolicy = false
        continueExistingPeriodicTasksAfterShutdownPolicy = false
    }
    private val inbound = InboundHandler(this)
    private val writer = SocketWriter(
        queue, counters, config,
        trace = ::trace,
        onWriteError = { e -> if (!closed.get()) closeWith(CloseReason(CloseReason.Kind.WRITE_ERROR, "error de escritura: ${e.message}", e)) },
        onCloseAfter = { item -> closeWith(CloseReason(CloseReason.Kind.DISCONNECT_REQ, "cierre tras ${item.label}")) },
        finish = { item, outcome -> finish(item, outcome) },
        onFlush = { frames ->
            log.w(tag, "vídeo atrasado más de ${config.videoMaxLagMs} ms: $frames frames descartados, se espera un IDR")
            requestKeyframe(KeyframeReason.BACKLOG, force = true)
        },
        gate = config.videoWriteGate?.let { g -> { g.canWriteVideo(this) } },
        gateMaxWaitNanos = config.videoWriteGateMaxWaitMs * 1_000_000,
        gatePollNanos = maxOf(1L, config.videoWriteGatePollMs) * 1_000_000,
        log = log,
        tag = tag,
    )

    @Volatile
    private var reader: FrameReader? = null
    private var readerThread: Thread? = null
    private var writerThread: Thread? = null

    val state: SessionState get() = currentState
    val isClosed: Boolean get() = closed.get()
    val remoteAddress: InetSocketAddress? get() = socket.remoteSocketAddress as? InetSocketAddress

    /** Tamaño y parámetros sugeridos para el encoder (inCar y `VIDEO_ARGS`, con los valores de QDLink si llegan a 0). */
    val encoderSuggestion: EncoderSuggestion
        get() {
            val g = geometry ?: MirrorGeometry.forCarInfo(config.phone.screenLongSide, config.phone.screenShortSide, 0, 0)
            val a = videoArgs
            return EncoderSuggestion(
                width = g.inCarWidth,
                height = g.inCarHeight,
                frameRate = a?.effectiveFrameRate ?: VideoArgs.DEFAULT_FRAME_RATE,
                bitRate = a?.effectiveBitRate ?: VideoArgs.DEFAULT_BIT_RATE,
                iFrameIntervalSec = a?.effectiveFrameInterval ?: VideoArgs.DEFAULT_FRAME_INTERVAL,
            )
        }

    /** Arranca hilos, AppStatus y temporizadores. Solo una vez. */
    @Throws(IOException::class)
    fun start(): PhoneSession {
        check(started.compareAndSet(false, true)) { "start() ya se llamó" }
        check(!closed.get()) { "sesión cerrada" }
        startedAtNanos = System.nanoTime()
        configureSocket()
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        val r = FrameReader(input, config.maxMessageBytes)
        reader = r
        log.i(tag, "sesión con ${socket.remoteSocketAddress} (local ${socket.localSocketAddress})")
        // AppStatus nada más conectar (LC/a.java:1697-1698), antes que cualquier respuesta: se encola antes del lector.
        if (config.sendAppStatus) enqueueRaw(BinBlock.appStatus(config.phone.sdkInt), "!BIN AppStatus")
        writerThread = startThread("qd-s$id-writer", ThreadRole.WRITER) { writer.run(output) }
        readerThread = startThread("qd-s$id-reader", ThreadRole.READER) { readLoop(r) }
        if (config.heartbeatEnabled) {
            // Timer.schedule(task, 1000, 3000): retardo fijo (LC/a.java:1761).
            schedule {
                timer.scheduleWithFixedDelay({ sendHeartbeat() }, config.heartbeatInitialDelayMs, config.heartbeatPeriodMs, TimeUnit.MILLISECONDS)
            }
        }
        if (config.watchdogEnabled || config.writeStallTimeoutMs > 0) {
            val period = config.watchdogCheckIntervalMs
            schedule { timer.scheduleAtFixedRate({ watchdogTick() }, period, period, TimeUnit.MILLISECONDS) }
        }
        return this
    }

    // ===================================================================== vídeo

    /** Guarda SPS‖PPS (Annex-B) y lo envía en un mensaje propio (o al empezar el vídeo, si aún no toca). */
    fun sendCodecConfig(annexB: ByteArray, offset: Int = 0, length: Int = annexB.size - offset): Boolean {
        if (closed.get()) return false
        val copy = annexB.copyOfRange(offset, offset + length)
        codecConfig = copy
        if (!videoAllowed()) {
            queue.requestConfigResend()
            log.d(tag, "SPS/PPS guardado (${copy.size} B); se enviará al empezar el vídeo")
            return true
        }
        return queue.offerConfig(configMessage(copy, videoParams))
    }

    /** Igual que el anterior, desde un `ByteBuffer` (no mueve su `position`). */
    fun sendCodecConfig(annexB: ByteBuffer): Boolean {
        val dup = annexB.duplicate()
        return sendCodecConfig(ByteArray(dup.remaining()).also { dup.get(it) })
    }

    /**
     * Encola un access unit (Annex-B). Nunca bloquea: devuelve `false` si se descartó (sesión cerrada, vídeo aún no
     * pedido, atasco o esperando un IDR). [ptsUs] no viaja (el protocolo no tiene timestamps); solo va a la traza.
     */
    fun sendFrame(annexB: ByteArray, isKeyframe: Boolean, ptsUs: Long): Boolean = sendFrame(annexB, 0, annexB.size, isKeyframe, ptsUs, null)

    fun sendFrame(annexB: ByteArray, offset: Int, length: Int, isKeyframe: Boolean, ptsUs: Long): Boolean =
        sendFrame(annexB, offset, length, isKeyframe, ptsUs, null)

    /**
     * hql (C2): como el anterior, con aviso de cómo termina el frame ([FrameCompletion]: exactamente una vez; si
     * devuelve `false`, ya se ha avisado antes de volver).
     */
    fun sendFrame(annexB: ByteArray, offset: Int, length: Int, isKeyframe: Boolean, ptsUs: Long, completion: FrameCompletion?): Boolean {
        if (!acceptFrame(isKeyframe, length, ptsUs, completion)) return false
        val params = videoParams
        return offerFrame(VideoMessage.build(params, annexB, offset, length), isKeyframe, ptsUs, params, completion, length)
    }

    /** Desde el buffer de salida de `MediaCodec` (de `position` a `limit`, sin moverlos): una sola copia. */
    fun sendFrame(annexB: ByteBuffer, isKeyframe: Boolean, ptsUs: Long): Boolean = sendFrame(annexB, isKeyframe, ptsUs, null)

    /** hql (C2): ídem con [FrameCompletion]. */
    fun sendFrame(annexB: ByteBuffer, isKeyframe: Boolean, ptsUs: Long, completion: FrameCompletion?): Boolean {
        val length = annexB.remaining()
        if (!acceptFrame(isKeyframe, length, ptsUs, completion)) return false
        val params = videoParams
        return offerFrame(VideoMessage.build(params, annexB), isKeyframe, ptsUs, params, completion, length)
    }

    /** Cambia en caliente los campos forzados de la cabecera de vídeo. */
    fun setVideoOverrides(overrides: VideoOverrides) {
        this.overrides = overrides
        recomputeVideoParams()
        log.i(tag, "cabecera de vídeo: $videoParams")
    }

    // ===================================================================== control manual

    fun sendControl(cmd: String, para: JsonObject? = null): Boolean = enqueueControl(cmd, PhoneMessages.controlJson(cmd, para))
    fun sendAppMessage(appId: String, functionId: String, para: JsonObject? = null): Boolean =
        enqueueApp("$appId/$functionId", PhoneMessages.appJson(appId, functionId, para))

    /** Bytes arbitrarios (ya enmarcados) para experimentos. */
    fun sendRaw(bytes: ByteArray, label: String = "RAW"): Boolean = enqueueRaw(bytes.copyOf(), label)

    fun sendHeartbeat(): Boolean = enqueueControl(Cmd.HEARTBEAT, PhoneMessages.HEARTBEAT_JSON)
    fun sendLockScreenStatus(status: Int): Boolean = enqueueControl(Cmd.LOCK_SCREEN_STATUS, PhoneMessages.lockScreenStatusJson(status))
    fun sendCarAppBackground(): Boolean = enqueueControl(Cmd.CAR_APP_BACKGROUND, PhoneMessages.controlJson(Cmd.CAR_APP_BACKGROUND, null))
    fun sendCarAppForeground(): Boolean = enqueueControl(Cmd.CAR_APP_FOREGROUND, PhoneMessages.controlJson(Cmd.CAR_APP_FOREGROUND, null))
    fun sendBtResult(result: Int): Boolean = enqueueControl(Cmd.BT_RESULT, PhoneMessages.btResultJson(result))
    fun sendDisconnectRsp(canDisconnect: Int = 1): Boolean = enqueueControl(Cmd.DISCONNECT_RSP, PhoneMessages.disconnectRspJson(canDisconnect))
    fun sendSpeechCtrl(status: Int): Boolean = enqueueControl(Cmd.SPEECH_CTRL, PhoneMessages.speechCtrlJson(status))
    fun sendPhoneInfoChange(change: PhoneInfoChange): Boolean = enqueueControl(Cmd.PHONE_INFO_CHANGE, PhoneMessages.phoneInfoChangeJson(change))
    fun sendWhitelistAppOn(value: Int): Boolean = enqueueApp("Mirror/WhitelistAppOn", PhoneMessages.whitelistAppOnJson(value))
    fun sendPlayState(state: Int): Boolean = enqueueApp("Music/PlayState", PhoneMessages.playStateJson(state))
    fun sendCustom(subtype: Int, payload: ByteArray): Boolean =
        queue.offerControl(Outgoing(PhoneMessages.custom(subtype, payload), OutKind.CONTROL, "CUSTOM($subtype)", MsgType.CUSTOM))

    /** Vuelve a mandar `PHONE_INFO` (con el último `CAR_INFO`). */
    fun resendPhoneInfo(): Boolean {
        val car = carInfo
        val info = PhoneInfoFactory.build(car, config.phone, phoneInfoOverridesFor(car))
        lastPhoneInfo = info
        return enqueueControl(Cmd.PHONE_INFO, PhoneMessages.phoneInfoJson(info))
    }

    /** Valor de los `WhitelistAppOn` periódicos a partir de ahora. */
    fun setWhitelistValue(value: Int) {
        whitelistValue = value
    }

    // ===================================================================== estado

    fun stats(): SessionStats = counters.snapshot(currentState, queue, reader)

    /** hql (C2): frames de vídeo en cola, sin candados (para consultas frecuentes). */
    fun videoQueueFrames(): Int = queue.videoFrameDepth()

    /** hql (C2): bytes de vídeo en cola, sin candados. */
    fun videoQueueBytes(): Long = queue.videoByteDepth()

    /** hql (C2): la cola está descartando P-frames a la espera de un IDR (sin candados; puede ir un instante tarde). */
    fun isWaitingForKeyframe(): Boolean = queue.waitingForIdrView

    /** hql (C2): vaciados de la cola por `MAX_LAG`. */
    fun videoFlushes(): Long = queue.flushes

    /** hql (C2): foto barata de la E/S (sin candados ni objetos grandes). */
    fun io(): IoSnapshot {
        val now = System.nanoTime()
        val r = reader
        val cur = writer.current
        val ws = cur?.writeStartNanos ?: 0L
        val writing = cur != null && ws != 0L && cur.writeEndNanos == 0L
        return IoSnapshot(
            atNanos = now,
            lastReceiveNanos = r?.lastActivityNanos ?: createdNanos,
            bytesReceived = r?.totalBytesRead ?: 0L,
            writingSinceNanos = if (writing) ws else 0L,
            writingLabel = if (writing) cur?.label else null,
            writingBytes = if (writing) cur?.bytes?.size ?: 0 else 0,
            lastWriteEndNanos = writer.lastWriteEndNanos,
            bytesSent = counters.bytesSent.get(),
            videoQueueFrames = queue.videoFrameDepth(),
            videoQueueBytes = queue.videoByteDepth(),
            controlQueue = queue.controlDepth(),
        )
    }

    /** Cierre local. Idempotente; los hilos terminan solos ([awaitTermination] para esperarlos). */
    override fun close() = closeWith(CloseReason(CloseReason.Kind.LOCAL, "cierre local"))

    /** hql (C3): cierre local con motivo ("aplicar ajustes", "usuario"…), para el log y el resumen de la sesión. */
    fun closeLocal(message: String) = closeWith(CloseReason(CloseReason.Kind.LOCAL, message))

    /** Espera a que terminen todos los hilos de la sesión. No llamar desde un callback del listener. */
    fun awaitTermination(timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        fun left(): Long = maxOf(1L, (deadline - System.nanoTime()) / 1_000_000)
        readerThread?.join(left())
        writerThread?.join(left())
        val timerDone = timer.awaitTermination(left(), TimeUnit.MILLISECONDS)
        val eventsDone = events.awaitTermination(left())
        return readerThread?.isAlive != true && writerThread?.isAlive != true && timerDone && eventsDone
    }

    override fun toString(): String = "PhoneSession#$id(${socket.remoteSocketAddress}, $currentState)"

    // ===================================================================== interno (también para InboundHandler)

    internal fun post(block: () -> Unit) = events.post(block)
    internal fun trace(event: TraceEvent) = events.post { listener.onTrace(event) }

    internal fun enqueueControl(cmd: String, json: String, closeAfter: Boolean = false): Boolean =
        queue.offerControl(Outgoing(Frames.json(MsgType.CONTROL, json), OutKind.CONTROL, cmd, MsgType.CONTROL, json, closeAfter))

    internal fun enqueueRaw(bytes: ByteArray, label: String): Boolean {
        val msgType = if (Header.hasMagic(bytes) && bytes.size >= Header.SIZE) bytes[10].toInt() and 0xFF else null
        return queue.offerControl(Outgoing(bytes, OutKind.CONTROL, label, msgType))
    }

    private fun enqueueApp(label: String, json: String): Boolean =
        queue.offerControl(Outgoing(Frames.json(MsgType.APP, json), OutKind.CONTROL, label, MsgType.APP, json))

    internal fun recomputeVideoParams() {
        videoParams = computeVideoParams()
    }

    internal fun requestKeyframe(reason: KeyframeReason, force: Boolean) {
        val now = System.nanoTime()
        if (!force && now - lastKeyframeRequestNanos < config.minKeyframeRequestIntervalMs * 1_000_000) return
        lastKeyframeRequestNanos = now
        counters.keyframeRequests.incrementAndGet()
        log.d(tag, "pedir IDR: $reason")
        post { listener.onKeyframeRequested(reason) }
    }

    /** `WhitelistAppOn` periódico desde el primer `VIDEO_SUP_RSP` (DLinkNotifyL.java:948-961, spec §6.6). */
    internal fun maybeStartWhitelist() {
        val enabled = when (config.whitelistMode) {
            WhitelistMode.NEVER -> false
            WhitelistMode.ALWAYS -> true
            WhitelistMode.AUTO -> carInfo?.legalAppWatch == 1
        }
        if (!enabled) return
        synchronized(stateLock) {
            if (whitelistStarted) return
            whitelistStarted = true
        }
        log.i(tag, "WhitelistAppOn cada ${config.whitelistPeriodMs} ms")
        schedule {
            timer.scheduleWithFixedDelay(
                { enqueueApp("Mirror/WhitelistAppOn", PhoneMessages.whitelistAppOnJson(whitelistValue)) },
                config.whitelistInitialDelayMs, config.whitelistPeriodMs, TimeUnit.MILLISECONDS,
            )
        }
    }

    /** Cambia de estado y avisa (en orden). `false` si ya estaba en él o la sesión está cerrada. */
    internal fun setState(to: SessionState): Boolean {
        synchronized(stateLock) {
            val from = currentState
            if (from == to || from == SessionState.CLOSED) return false
            currentState = to
            log.i(tag, "estado $from → $to")
            post { listener.onStateChanged(from, to) }
            return true
        }
    }

    /** Las etapas del handshake solo avanzan, y nunca desde STREAMING/PAUSED. */
    internal fun advanceHandshake(to: SessionState) {
        synchronized(stateLock) {
            val cur = currentState
            if (cur == SessionState.STREAMING || cur == SessionState.PAUSED || cur == SessionState.CLOSED) return
            if (cur.ordinal < to.ordinal) setState(to)
        }
    }

    internal fun closeWith(reason: CloseReason) {
        if (!closed.compareAndSet(false, true)) return
        closeReason = reason
        if (reason.kind == CloseReason.Kind.LOCAL) log.i(tag, "cerrando: $reason") else log.w(tag, "cerrando: $reason", reason.error)
        synchronized(stateLock) {
            val from = currentState
            currentState = SessionState.CLOSED
            post { listener.onStateChanged(from, SessionState.CLOSED) }
        }
        timer.shutdownNow()
        val pending = queue.close()
        // hql (C3): shutdown antes de close: si alguien tiene un duplicado del descriptor (NetStat), close() no basta
        // para que el kernel mande el FIN al coche.
        try {
            socket.shutdownInput()
        } catch (_: Exception) {
        }
        try {
            socket.shutdownOutput()
        } catch (_: Exception) {
        }
        try {
            socket.close()
        } catch (_: IOException) {
        }
        // hql (C2): lo que quedaba en cola termina con CLOSED (fuera de cualquier candado).
        for (item in pending) finish(item, FrameOutcome.CLOSED)
        events.closeWith { listener.onClosed(reason) }
    }

    /** hql (C3): `PHONE_INFO` para [car]: el proveedor de la configuración si lo hay; si no, los valores fijos. */
    internal fun phoneInfoOverridesFor(car: CarInfo?): PhoneInfoOverrides {
        val provider = config.phoneInfoOverridesFor ?: return config.phoneInfoOverrides
        return try {
            provider(car)
        } catch (e: Exception) {
            log.e(tag, "phoneInfoOverridesFor falló; se usan los valores de la configuración", e)
            config.phoneInfoOverrides
        }
    }

    /** hql (C3): gancho de inicio de hilo ([SessionConfig.onThreadStart]); nunca deja escapar una excepción. */
    private fun threadStarted(role: ThreadRole) {
        val hook = config.onThreadStart ?: return
        try {
            hook(role)
        } catch (t: Throwable) {
            log.w(tag, "onThreadStart($role) falló", t)
        }
    }

    /** hql (C2): avisa a la finalización de [item] (si la tiene) y registra la excepción del callback. */
    internal fun finish(item: Outgoing, outcome: FrameOutcome) {
        val error = item.complete(outcome, queue.videoFrameDepth()) ?: return
        log.e(tag, "excepción en FrameCompletion ($outcome)", error)
    }

    /** hql (C2): aviso inmediato a un frame que no llega a la cola. */
    private fun completeEarly(completion: FrameCompletion?, outcome: FrameOutcome, isKeyframe: Boolean, length: Int, ptsUs: Long) {
        if (completion == null) return
        try {
            completion.onFrameDone(FrameDone(outcome, isKeyframe, length, ptsUs, 0L, 0L, 0L, queue.videoFrameDepth()))
        } catch (t: Throwable) {
            log.e(tag, "excepción en FrameCompletion ($outcome)", t)
        }
    }

    // ===================================================================== privado

    private fun readLoop(r: FrameReader) {
        try {
            while (!closed.get()) {
                val msg = r.next()
                if (msg == null) {
                    closeWith(CloseReason(CloseReason.Kind.EOF, "el coche cerró la conexión"))
                    return
                }
                counters.carMessage()
                watchdogWarned = false
                try {
                    inbound.dispatch(msg)
                } catch (e: Exception) {
                    log.e(tag, "error procesando un mensaje del coche", e)
                }
            }
        } catch (e: IOException) {
            if (!closed.get()) closeWith(CloseReason(CloseReason.Kind.READ_ERROR, "error de lectura: ${e.message}", e))
        } catch (t: Throwable) {
            if (!closed.get()) closeWith(CloseReason(CloseReason.Kind.INTERNAL_ERROR, "fallo en el lector: $t", t))
        }
    }

    /** Watchdog de recepción (QDLink: LC/a.java:689-703) y detector de `write()` bloqueado. */
    private fun watchdogTick() {
        if (closed.get()) return
        val now = System.nanoTime()
        val ws = writer.writeStartNanos
        if (config.writeStallTimeoutMs > 0 && ws != 0L && (now - ws) / 1_000_000 > config.writeStallTimeoutMs) {
            closeWith(CloseReason(CloseReason.Kind.WRITE_STALL, "un write() lleva más de ${config.writeStallTimeoutMs} ms bloqueado"))
            return
        }
        if (!config.watchdogEnabled) return
        val r = reader ?: return
        val silentMs = (now - r.lastActivityNanos) / 1_000_000
        if (silentMs >= config.watchdogTimeoutMs && (!config.watchdogRequiresCarTraffic || carSpoke)) {
            closeWith(CloseReason(CloseReason.Kind.WATCHDOG, "$silentMs ms sin recibir nada del coche"))
            return
        }
        if (silentMs >= config.watchdogWarnMs) {
            if (!watchdogWarned) {
                watchdogWarned = true
                log.w(tag, "$silentMs ms sin recibir nada del coche")
                post { listener.onWatchdogWarning(silentMs) }
            }
        } else {
            watchdogWarned = false
        }
    }

    private fun configMessage(payload: ByteArray, params: VideoParams): Outgoing =
        Outgoing(VideoMessage.build(params, payload), OutKind.VIDEO_CONFIG, "VIDEO_CONFIG", MsgType.VIDEO)

    private fun videoAllowed(): Boolean = config.sendVideoBeforePlay || currentState == SessionState.STREAMING

    private fun acceptFrame(isKeyframe: Boolean, length: Int, ptsUs: Long, completion: FrameCompletion?): Boolean {
        if (closed.get()) {
            completeEarly(completion, FrameOutcome.CLOSED, isKeyframe, length, ptsUs)
            return false
        }
        if (!videoAllowed()) {
            counters.videoFramesRejected.incrementAndGet()
            completeEarly(completion, FrameOutcome.REJECTED, isKeyframe, length, ptsUs)
            return false
        }
        return true
    }

    private fun offerFrame(
        message: ByteArray,
        isKeyframe: Boolean,
        ptsUs: Long,
        params: VideoParams,
        completion: FrameCompletion?,
        payloadBytes: Int,
    ): Boolean {
        val item = Outgoing(
            message,
            if (isKeyframe) OutKind.VIDEO_KEY else OutKind.VIDEO_DELTA,
            if (isKeyframe) "VIDEO_IDR" else "VIDEO_P",
            MsgType.VIDEO,
            ptsUs = ptsUs,
            completion = completion,
            payloadBytes = payloadBytes,
        )
        val dropped = ArrayList<Outgoing>(0)
        val result = queue.offerFrame(item, dropped) { codecConfig?.let { configMessage(it, params) } }
        // Fuera del candado de la cola: los P encolados que se han tirado y, si no entra, el propio frame.
        for (d in dropped) finish(d, FrameOutcome.DROPPED)
        return when (result) {
            FrameOffer.ACCEPTED -> true
            FrameOffer.DROPPED_BACKLOG -> {
                finish(item, FrameOutcome.DROPPED)
                log.w(tag, "atasco de vídeo: P-frames descartados, se espera un IDR (cola ${queue.videoFrameDepth()} frames, ${queue.videoByteDepth()} B)")
                requestKeyframe(KeyframeReason.BACKLOG, force = false)
                false
            }
            FrameOffer.DROPPED_WAITING_IDR -> {
                finish(item, FrameOutcome.DROPPED)
                requestKeyframe(KeyframeReason.BACKLOG, force = false)
                false
            }
            FrameOffer.CLOSED -> {
                finish(item, FrameOutcome.CLOSED)
                false
            }
        }
    }

    private fun computeVideoParams(): VideoParams {
        val ov = overrides
        val g = geometry
        val a = videoArgs
        return VideoParams(
            width = ov.width ?: g?.inCarWidth ?: 800,
            height = ov.height ?: g?.inCarHeight ?: 480,
            fps = ov.fps ?: a?.frameRate ?: VideoArgs.DEFAULT_FRAME_RATE,
            bitrate = ov.bitrate ?: a?.bitRate ?: VideoArgs.DEFAULT_BIT_RATE,
            gop = ov.gop ?: a?.frameInterval ?: VideoArgs.DEFAULT_FRAME_INTERVAL,
            encodingType = ov.encodingType ?: a?.encodingType ?: 3,
            appType = ov.appType ?: VideoParams.APP_TYPE_IN_APP,
            angle = ov.angle ?: 90,
            orientation = ov.orientation ?: 1,
        )
    }

    /**
     * Opciones de WF/d.java:88-94, salvo los tamaños de buffer (ver [SessionConfig.sendBufferBytes]).
     * hql (C3): después, `IP_TOS` ([SessionConfig.trafficClass], con su propio `try`: hay sistemas que lo ignoran o lo
     * rechazan) y el configurador de la app.
     */
    private fun configureSocket() {
        try {
            socket.tcpNoDelay = config.tcpNoDelay
            config.sendBufferBytes?.let { socket.sendBufferSize = it }
            config.receiveBufferBytes?.let { socket.receiveBufferSize = it }
            socket.keepAlive = config.keepAlive
        } catch (e: SocketException) {
            log.w(tag, "no se pudieron aplicar las opciones del socket", e)
        }
        config.trafficClass?.let { tc ->
            try {
                socket.trafficClass = tc
            } catch (e: Exception) {
                log.w(tag, "no se pudo marcar IP_TOS=0x${Integer.toHexString(tc)}", e)
            }
        }
        config.socketConfigurator?.let { configure ->
            try {
                configure(socket)
            } catch (e: Exception) {
                log.w(tag, "el configurador del socket falló", e)
            }
        }
        try {
            log.i(
                tag,
                "socket: TCP_NODELAY=${socket.tcpNoDelay} SO_SNDBUF=${socket.sendBufferSize} " +
                    "SO_RCVBUF=${socket.receiveBufferSize} SO_KEEPALIVE=${socket.keepAlive} " +
                    "IP_TOS=0x${Integer.toHexString(socket.trafficClass)}",
            )
        } catch (e: SocketException) {
            log.w(tag, "no se pudieron leer las opciones del socket", e)
        }
    }

    private fun startThread(name: String, role: ThreadRole, body: () -> Unit): Thread = Thread({
        threadStarted(role)
        body()
    }, name).apply {
        isDaemon = true
        priority = config.ioThreadPriority
        start()
    }

    private inline fun schedule(block: () -> Unit) {
        try {
            block()
        } catch (_: RejectedExecutionException) {
            // sesión cerrada mientras tanto
        }
    }

    private companion object {
        val ID_SEQ = AtomicInteger()
    }
}
