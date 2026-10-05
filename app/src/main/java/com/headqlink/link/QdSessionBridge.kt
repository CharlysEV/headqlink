package com.headqlink.link

import android.os.PowerManager
import android.os.SystemClock
import dev.qdauto.core.discovery.CarAnnouncement
import dev.qdauto.core.session.CloseReason
import dev.qdauto.core.session.KeyframeReason
import dev.qdauto.core.session.OversizedFrame
import dev.qdauto.core.session.PhoneSession
import dev.qdauto.core.session.SessionListener
import dev.qdauto.core.wire.AppMessage
import dev.qdauto.core.wire.BtAddrRequest
import dev.qdauto.core.wire.CarInfo
import dev.qdauto.core.wire.CarKey
import dev.qdauto.core.wire.CarParams
import dev.qdauto.core.wire.ControlMessage
import dev.qdauto.core.wire.Direction
import dev.qdauto.core.wire.MsgType
import dev.qdauto.core.wire.TouchCodec
import dev.qdauto.core.wire.TouchEvent
import dev.qdauto.core.wire.TraceEvent
import dev.qdauto.core.wire.UnknownMessage
import dev.qdauto.core.wire.VideoArgs
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Locale

private val WALL = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
}

/**
 * [SessionListener] de **una** sesión del motor QDAuto (qdauto §4.5), creado por la fábrica de [QdLinkHost] antes de
 * arrancarla: traduce los eventos del núcleo al vídeo ([VideoHub]), al estado visible ([LinkState]) y a los registros
 * (diario del coche, traza de rendimiento, log unificado). Lleva el monitor de red de 50 ms de la sesión (NetStat,
 * detector de congelación y estadísticas de 5 s). Todos los callbacks llegan en orden en el hilo de eventos de la
 * sesión (`qd-sN-events`); ninguno bloquea.
 */
internal class QdSessionBridge(
    private val ctx: android.content.Context,
    val session: PhoneSession,
    private val host: QdLinkHost,
    private val hub: VideoHub,
    private val cfg: Config,
    private val car: CarAnnouncement?,
) : SessionListener {
    val port = SessionPort(session)
    val sid: Int = session.id

    /** Fin de esta sesión, para el hueco de la siguiente: se sella al empezar [onClosed] (o en el relevo). */
    val endStamp = SessionBook.EndStamp(sid)

    /** Hueco desde la sesión anterior (para la métrica de reconexión), o null si es la primera; lo fija [onStarted]. */
    @Volatile
    private var gap: SessionBook.Gap? = null

    /** La sesión llegó a vídeo y escribió al menos un frame (para la espera entre intentos y el resumen). */
    val reachedStreaming: Boolean get() = port.stats.frames > 0

    @Volatile
    private var carInfo: CarInfo? = null

    @Volatile
    private var videoArgs: VideoArgs? = null
    private var perfToken = 0
    private var whitelistLogged = false
    private var lastUnknownLogMs = 0L
    private var touches = 0L
    private var carKeyframeRequests = 0

    /** TCP aceptado (creación del puente) y `VIDEO_CTRL{1}`, para la métrica de reconexión y el resumen. */
    private val createdNanos = System.nanoTime()
    private val createdWallMs = System.currentTimeMillis()

    @Volatile
    private var carInfoNanos = 0L

    /** Detector de cortes de radio (qdauto §7.3), evaluado cada 100 ms en el monitor de red. */
    private val stalls = StallDetector(sid, ::wallText, freezeMs = 250)

    @Volatile
    private var lastRxKind: String? = null
    private var lastCarHeartbeatNanos = 0L
    private var heartbeatMinMs = -1L
    private var heartbeatMaxMs = -1L
    private var reconnectText = ""
    private var aaCyclesAtStart = -1

    @Volatile
    private var videoCtrlNanos = 0L

    /** Estado térmico máximo y tope de fps mínimo de la sesión (se miran con las estadísticas de 5 s y al cerrar). */
    @Volatile
    private var thermalMax = -1

    @Volatile
    private var fpsCapMin = 0

    /** Reconexión medida (ms del fin de la sesión anterior al primer IDR escrito en esta), o -1. */
    @Volatile
    var reconnectMs = -1L
        private set
    private var reconnectLogged = false

    @Volatile
    private var monitor: Thread? = null

    @Volatile
    private var monitorRunning = false
    private val localIface: String by lazy {
        try {
            NetworkInterface.getByInetAddress(session.socket.localAddress)?.name ?: "?"
        } catch (_: Exception) {
            "?"
        }
    }

    init {
        QdTrace.i(
            "HQL/Puente S$sid",
            "sesión nueva con ${session.remoteAddress} (${car?.name ?: "?"}) · motor QDAuto · ${cfg.mode()} · " +
                (if (host.hotspotMode) "zona Wi-Fi" else "Wi-Fi Direct"),
        )
    }

    /** Coche e interfaz de esta sesión, para la pantalla del coche y la notificación. */
    fun describeLink(): String {
        val remote = session.remoteAddress?.address?.hostAddress ?: "?"
        val local = session.socket.localAddress?.hostAddress ?: "?"
        return "$remote · $localIface $local"
    }

    /** La sesión arrancó (hilo de aceptación): diarios, traza y monitor de red. */
    fun onStarted(gap: SessionBook.Gap?) {
        this.gap = gap
        CarTrace.beginSession(session.remoteAddress.toString())
        perfToken = PerfTrace.beginSession("S$sid")
        PerfTrace.event(if (isInteractive()) "screen_on" else "screen_off", 1)
        SystemMonitor.setLinkIface(localIface, sid)
        LinkState.setLinkDetail(describeLink(), sid)
        if (port.closed) {
            // Se cerró mientras tanto y su onClosed ya pudo borrar lo suyo: que no quede lo de una sesión muerta.
            SystemMonitor.clearLinkIface(sid)
            LinkState.clearLinkDetail(sid)
        }
        QdTrace.i("HQL/Puente S$sid", "TCP ${session.remoteAddress} → local ${session.socket.localSocketAddress} ($localIface)")
        QdTrace.i("HQL/Red", "interfaces al empezar S$sid: " + NetIfaces.describe(NetIfaces.scan()))
        aaCyclesAtStart = hub.aaCycles()
        startMonitor()
    }

    private fun isInteractive(): Boolean = try {
        ctx.getSystemService(PowerManager::class.java)?.isInteractive != false
    } catch (_: RuntimeException) {
        true
    }

    // ---------------------------------------------------------------- eventos del núcleo (hilo qd-sN-events)

    override fun onTrace(event: TraceEvent) {
        QdTrace.trace(event, sid)
        if (event.direction == Direction.IN) {
            PerfTrace.rx(event.msgType ?: -1, event.size)
            lastRxKind = event.kind
            if (event.kind == "HEARTBEAT") {
                val now = System.nanoTime()
                if (lastCarHeartbeatNanos != 0L) {
                    val ms = (now - lastCarHeartbeatNanos) / 1_000_000
                    if (heartbeatMinMs < 0 || ms < heartbeatMinMs) heartbeatMinMs = ms
                    if (ms > heartbeatMaxMs) heartbeatMaxMs = ms
                }
                lastCarHeartbeatNanos = now
            }
        }
        val json = event.msgType == MsgType.CONTROL || event.msgType == MsgType.APP
        if (!json || event.isVideo) return
        if (event.kind == "Mirror/WhitelistAppOn") {
            if (!whitelistLogged && event.direction == Direction.OUT) {
                whitelistLogged = true
                L.i("-> APP ${event.summary} (se repite cada segundo)")
                CarTrace.tx("APP", event.summary)
                PerfTrace.event("whitelist", 1)
            }
            return
        }
        if (event.kind == "HEARTBEAT") return
        if (event.direction == Direction.IN) {
            L.i("<- ${event.summary}")
        } else {
            L.i("-> ${event.summary}")
            CarTrace.tx("CMD", event.summary)
        }
    }

    override fun onCarInfo(info: CarInfo, message: ControlMessage) {
        carInfo = info
        if (carInfoNanos == 0L) carInfoNanos = System.nanoTime()
        val detail = "${info.carWidth}×${info.carHeight}"
        host.onCarSize(this, detail)
    }

    override fun onVideoArgs(args: VideoArgs, message: ControlMessage) {
        videoArgs = args
    }

    override fun onVideoControl(play: Boolean, playStatus: Int, message: ControlMessage) {
        if (!play) {
            L.i("VIDEO_CTRL PlayStatus=$playStatus (se mantiene el vídeo)")
            return
        }
        if (videoCtrlNanos == 0L) videoCtrlNanos = System.nanoTime()
        hub.attachOrCreate(port, carVideo())
    }

    override fun onKeyframeRequested(reason: KeyframeReason) {
        if (reason == KeyframeReason.CAR_REQUEST) {
            carKeyframeRequests++
            PerfTrace.event("idr_req", 0)
        }
        hub.requestKeyFrame(port, reason)
    }

    /**
     * El núcleo descartó un frame por pasar del tope que aguanta el coche (su aviso ya está en el log). Llega antes que la
     * petición de IDR (OVERSIZED) que lo acompaña: el vídeo lo cuenta (reenvío directo) antes de pedir otro.
     */
    override fun onVideoFrameOversized(frame: OversizedFrame) {
        PerfTrace.event("idr_oversized", ((frame.messageBytes + 1023) / 1024).toLong())
        hub.onOversized(port, frame.messageBytes, frame.isKeyframe)
    }

    override fun onTouch(event: TouchEvent) {
        touches++
        val fingers = Array(event.pointers.size) { i ->
            val p = event.pointers[i]
            Proto.Finger().apply {
                id = p.id and 0xFF
                action = p.action
                x = p.x
                y = p.y
            }
        }
        val sb = StringBuilder("<- TOUCH")
        var edge = false
        for (f in fingers) {
            sb.append(String.format(Locale.US, " [id%d a%d %.1f,%.1f]", f.id, f.action, f.x, f.y))
            if (f.id == 0) PerfTrace.touch("touch", f.action, f.x, f.y)
            if (f.action != TouchCodec.FINGER_MOVE) edge = true
        }
        // Todos los dedos a la vez (multitáctil: el coche manda hasta 3).
        hub.touch(event.action, fingers)
        // Con "Optimizaciones de latencia" solo se registran el inicio y el fin de cada gesto.
        if (!LowLatency.enabled || edge) L.i("$sb  global=${event.action}")
    }

    override fun onAppMessage(message: AppMessage) {
        if (message.key == "Global/DarkModeOn") {
            val v = CarParams.appInt(message.json, "DarkModeOn")
            if (v != null) {
                // Valor sin confirmar en el coche: se asume 1 = noche.
                val dark = v == 1
                L.i("coche en modo " + if (dark) "noche" else "día")
                hub.setCarDark(dark)
                return
            }
        }
        L.i("<- APP ${message.text}")
    }

    override fun onDisconnectRequest(message: ControlMessage) {
        L.i("el coche pide desconectar (DISCONNECT_REQ): respondo y cierro")
        CarTrace.note("SESION", "DISCONNECT_REQ del coche")
    }

    override fun onLandModeRequest(orientation: Int, message: ControlMessage) {
        L.i("LAND_MODE_REQ orientación $orientation (respondido)")
    }

    override fun onKey(key: CarKey) {
        L.i("tecla del coche: $key")
    }

    override fun onPhoneKey(code: Int, message: ControlMessage) {
        L.i("PHONE_KEYS $code")
    }

    override fun onBtAddr(request: BtAddrRequest, message: ControlMessage) {
        L.i("BT_ADDR del coche: $request")
    }

    override fun onGoInLinkApp(message: ControlMessage) {
        L.i("GO_IN_LINK_APP")
    }

    override fun onUnknownMessage(message: UnknownMessage) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastUnknownLogMs < 1_000) return
        lastUnknownLogMs = now
        L.w("mensaje no reconocido del coche: ${message.reason}")
    }

    override fun onWatchdogWarning(silentMs: Long) {
        L.w("el coche lleva $silentMs ms callado")
        QdTrace.w("HQL/Puente S$sid", "coche callado $silentMs ms")
    }

    override fun onClosed(reason: CloseReason) {
        endStamp.stamp(System.nanoTime())
        monitorRunning = false
        monitor?.let { t ->
            // El monitor también usa el detector de cortes y la métrica de reconexión: se espera a que salga.
            t.interrupt()
            try {
                t.join(MONITOR_JOIN_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        stalls.onClose(monoMs(), reason.toString())?.let { emitStall(it) }
        port.close()
        logStats(force = true)
        maybeLogReconnect()
        summarize(reason)
        if (cfg.qdKeepVideo()) {
            hub.detach(port, reason.toString())
        } else {
            hub.stopFor(port, "S$sid cerrada")
        }
        // Solo lo de esta sesión: si la nueva ya puso lo suyo (relevo, reconexión rápida), se queda.
        SystemMonitor.clearLinkIface(sid)
        LinkState.clearLinkDetail(sid)
        CarTrace.endSession()
        CarTrace.note("SESION", "S$sid: $reason")
        PerfTrace.endSession(perfToken)
        L.i("sesión S$sid cerrada: $reason")
    }

    // ---------------------------------------------------------------- vídeo, monitor y estadísticas

    private fun carVideo(): VideoPlan.Car {
        val c = carInfo
        val a = videoArgs
        return VideoPlan.Car(
            c?.carWidth ?: 0, c?.carHeight ?: 0,
            a?.width ?: 0, a?.height ?: 0, a?.frameRate ?: 0, a?.bitRate ?: 0, a?.frameInterval ?: 0, a?.encodingType ?: 0,
            a != null,
        )
    }

    /**
     * Cada 50 ms (como SspSession.startMonitor): estado del socket (cola del kernel, RTT, retransmisiones) y detector
     * de congelación (si entre dos vueltas pasan más de 150 ms, el proceso no estuvo corriendo); cada 5 s, estadísticas.
     */
    private fun startMonitor() {
        monitorRunning = true
        val t = Thread({
            if (port.monitorSample() == null) L.w("traza: sin estado del socket (librería hqlnet no disponible)")
            var last = SystemClock.elapsedRealtime()
            var tick = 0
            try {
                while (monitorRunning && !port.closed) {
                    Thread.sleep(50)
                    val now = SystemClock.elapsedRealtime()
                    if (now - last > 150) PerfTrace.event("stall", now - last)
                    last = now
                    val net = port.monitorSample()
                    net?.let { PerfTrace.net(it) }
                    if (++tick % 2 == 0) checkStalls(net)
                    maybeLogReconnect()
                    logStats(force = false)
                }
            } catch (_: InterruptedException) {
            }
        }, "net-monitor")
        t.priority = Thread.MAX_PRIORITY
        t.isDaemon = true
        monitor = t
        t.start()
    }

    /**
     * «reconexión X ms» (qdauto §6.1): del fin de la sesión anterior al primer IDR escrito en esta, desglosado en
     * broadcast, ACK, TCP, VIDEO_CTRL e IDR. Una vez por sesión, en cuanto sale el primer IDR. En un relevo o una
     * reconexión rápida el fin de la anterior puede sellarse después: se espera a él como mucho [GAP_WAIT_NS].
     */
    @Synchronized
    private fun maybeLogReconnect() {
        if (reconnectLogged) return
        val idr = port.stats.firstIdrNanos
        if (idr == 0L) return
        val g = gap
        val end = g?.endNanos ?: 0L
        if (g != null && end == 0L && System.nanoTime() - idr < GAP_WAIT_NS) return
        reconnectLogged = true
        if (g == null || end == 0L || end > idr) return
        fun ms(a: Long, b: Long) = if (a == 0L || b == 0L) "?" else ((b - a) / 1_000_000).toString()
        reconnectMs = (idr - end) / 1_000_000
        reconnectText = "${reconnectMs} ms desde el fin de S${g.prevSid} (broadcast +${ms(end, g.broadcastNanos)} · " +
            "ACK +${ms(g.broadcastNanos, g.ackNanos)} · TCP +${ms(g.ackNanos, createdNanos)} · " +
            "VIDEO_CTRL +${ms(createdNanos, videoCtrlNanos)} · IDR +${ms(videoCtrlNanos, idr)})"
        val line = "reconexión $reconnectText · vídeo ${hub.lastVerdict()}"
        L.i(line)
        QdTrace.i("HQL/Puente S$sid", line)
        CarTrace.note("SESION", line)
        PerfTrace.event("reconnect_ms", reconnectMs)
    }

    // ---------------------------------------------------------------- cortes de radio y resumen

    private fun monoMs(): Long = System.nanoTime() / 1_000_000

    /** Hora del día de un instante monótono (ms de `System.nanoTime`). */
    private fun wallText(mono: Long): String {
        val wall = System.currentTimeMillis() - (monoMs() - mono)
        return WALL.get()!!.format(java.util.Date(wall))
    }

    /** Hilo net-monitor, cada 100 ms: una muestra para el detector de cortes. */
    private fun checkStalls(net: IntArray?) {
        val io = session.io()
        val sample = StallDetector.Sample(
            nowMs = io.atNanos / 1_000_000,
            lastReceiveMs = io.lastReceiveNanos / 1_000_000,
            lastRxKind = lastRxKind,
            writingSinceMs = if (io.writingSinceNanos == 0L) 0L else io.writingSinceNanos / 1_000_000,
            writingLabel = io.writingLabel,
            writingBytes = io.writingBytes,
            lastWriteEndMs = if (io.lastWriteEndNanos == 0L) 0L else io.lastWriteEndNanos / 1_000_000,
            outq = net?.get(0) ?: -1,
            unacked = net?.get(3) ?: -1,
            retrans = net?.get(4) ?: -1,
            rttMs = net?.let { it[1] / 1000 } ?: -1,
            rttVarMs = net?.let { it[2] / 1000 } ?: -1,
            cwnd = net?.get(5) ?: -1,
            rwndLimitedMs = net?.get(9) ?: -1,
            carWindow = net?.get(11) ?: -1,
            context = stallContext(),
        )
        for (ev in stalls.onSample(sample)) emitStall(ev)
        // Bitrate según el enlace (LinkRateController, en hql-video): la misma muestra, más los vaciados por retraso.
        hub.onLinkSample(
            port,
            LinkRateController.Sample(
                SystemClock.elapsedRealtime(), sample.outq, sample.unacked, sample.retrans, sample.rttMs, 0, session.videoFlushes(),
            ),
        )
    }

    private fun stallContext(): String {
        val sb = StringBuilder()
        sb.append(if (host.hotspotMode) "zona Wi-Fi " else "Wi-Fi Direct ").append(localIface)
        if (LinkState.network.isNotEmpty()) sb.append(" (").append(LinkState.network).append(')')
        sb.append(" · pantalla ").append(if (isInteractive()) "encendida" else "apagada")
        return sb.toString()
    }

    private fun emitStall(ev: StallDetector.Event) {
        QdTrace.w("QD/Corte", ev.text)
        L.quiet("W", ev.text)
        if (ev.phase != StallDetector.Phase.PROGRESS) CarTrace.note("CORTE", ev.text)
        val row = when (ev.kind) {
            StallDetector.Kind.RADIO -> "stall_radio"
            StallDetector.Kind.COCHE_NO_LEE -> "stall_peer"
            StallDetector.Kind.MOVIL_CONGELADO -> "stall_phone"
        }
        if (ev.phase != StallDetector.Phase.PROGRESS) PerfTrace.event(row, ev.durationMs)
    }

    /** Bloque de la sesión (log unificado, L y diario del coche), fila de `sessions.csv` y viaje (qdauto §7.4). */
    private fun summarize(reason: CloseReason) {
        try {
            val st = session.stats()
            val s = port.stats
            fun rel(n: Long) = if (n == 0L) -1L else (n - createdNanos) / 1_000_000
            val remote = session.remoteAddress
            val ifaceKind = NetIfaces.kindOf(localIface).label
            val cycles = hub.aaCycles()
            sampleThermal()
            val record = SessionSummary.Record(
                sid = sid,
                startWallMs = createdWallMs,
                endWallMs = System.currentTimeMillis(),
                engine = Config.ENGINE_QDAUTO,
                link = host.linkMode,
                videoMode = cfg.mode(),
                profile = if (Config.isAa(cfg.mode())) cfg.videoProfile().id else "",
                video = port.headerText(),
                carIp = remote?.address?.hostAddress ?: "?",
                carPort = remote?.port ?: 0,
                carName = car?.name ?: "",
                local = (session.socket.localAddress?.hostAddress ?: "?") + ":" + session.socket.localPort,
                iface = "$localIface ($ifaceKind)",
                closeKind = reason.kind.name,
                closeDetail = reason.message,
                reachedVideo = reachedStreaming,
                tCarInfoMs = rel(carInfoNanos),
                tVideoCtrlMs = rel(videoCtrlNanos),
                tFirstFrameMs = rel(s.firstFrameNanos),
                tFirstIdrMs = rel(s.firstIdrNanos),
                carKeyframeRequests = carKeyframeRequests,
                frames = s.frames,
                bytes = s.bytes,
                idr = s.keyframes,
                dropped = s.dropped,
                flushes = session.videoFlushes(),
                maxWriteMs = s.maxWriteMs,
                maxLagMs = s.maxLagMs,
                carHeartbeats = st.carHeartbeats,
                heartbeatMinMs = heartbeatMinMs,
                heartbeatMaxMs = heartbeatMaxMs,
                touches = touches,
                maxCarGapMs = st.maxCarGapMs,
                stalls = stalls.count,
                maxStallMs = stalls.maxMs,
                retrans = if (stalls.retransAtFirst >= 0 && stalls.lastRetrans >= 0) stalls.lastRetrans - stalls.retransAtFirst else -1,
                radio = stalls.summary(),
                reconnectMs = reconnectMs,
                reconnect = reconnectText,
                videoVerdict = if (s.frames > 0 || videoCtrlNanos != 0L) hub.lastVerdict() else "",
                aaCycles = if (aaCyclesAtStart >= 0 && cycles >= aaCyclesAtStart) cycles - aaCyclesAtStart else 0,
                thermalEnd = ThermalGuard.status(),
                thermalMax = thermalMax,
                fpsCapEnd = if (s.frames > 0) hub.fpsCap() else 0,
                fpsCapMin = if (s.frames > 0) fpsCapMin else 0,
                maxMessageBytes = st.maxVideoMessageBytes,
                oversizedDrops = st.videoFramesOversized,
                oversizedMaxBytes = st.maxOversizedBytes,
                bitrateMinKbps = if (s.frames > 0) hub.linkMinKbps(port) else 0,
                congestionEvents = if (s.frames > 0) hub.linkCongestionEvents(port) else 0,
                writeStalls = st.writeStalls,
            )
            val block = SessionSummary.block(record)
            QdTrace.block("HQL/Resumen", block)
            for (line in block.split('\n')) L.quiet("I", line)
            CarTrace.note("SESION", block.replace('\n', ' '))
            SessionSummary.noteSession(record)
            val row = SessionSummary.csvRow(record)
            val dir = QdTrace.dir()
            if (dir != null) {
                QdTrace.post {
                    try {
                        SessionSummary.appendCsv(dir, row)
                    } catch (e: Exception) {
                        L.w("no se pudo escribir sessions.csv: $e")
                    }
                }
            }
            QdTrace.i("HQL/Red", "interfaces al terminar S$sid: " + NetIfaces.describe(NetIfaces.scan()))
        } catch (e: RuntimeException) {
            L.e("resumen de la sesión S$sid", e)
        }
    }

    /** Estadísticas de 5 s (§4.11): mismo formato que SspSession.maybeLogStats, más las del núcleo en el log unificado. */
    /** Estado térmico y tope de fps ahora (máximo y mínimo de la sesión). */
    private fun sampleThermal() {
        val st = ThermalGuard.status()
        if (st > thermalMax) thermalMax = st
        val cap = hub.fpsCap()
        if (cap > 0 && (fpsCapMin == 0 || cap < fpsCapMin)) fpsCapMin = cap
    }

    @Synchronized
    private fun logStats(force: Boolean) {
        val w = port.stats.takeWindow(session.videoFlushes(), force) ?: return
        sampleThermal()
        if (force && w.fps == 0.0 && w.dropped == 0) return
        val line = w.line()
        L.i(line)
        CarTrace.note("VIDEO", "$line · cabecera ${port.headerText()}")
        hub.takeStats(port)?.forEach { L.i("  $it") }
        hub.setStatus(port, line)
        if (!force) LinkState.setVideo(w.short())
        val st = session.stats()
        val net = port.monitorSample()
        QdTrace.i(
            "HQL/Puente S$sid",
            "núcleo: cola ${st.videoQueueFrames} frames/${st.videoQueueBytes} B · control ${st.controlQueueDepth} · " +
                "heartbeats del coche ${st.carHeartbeats} · hueco máx. del coche ${st.maxCarGapMs} ms · IDR pedidos ${st.keyframeRequests}" +
                " · mensaje de vídeo máx. ${(st.maxVideoMessageBytes + 1023) / 1024} KB" +
                (if (st.videoFramesOversized > 0) " · descartados por tamaño ${st.videoFramesOversized}" else "") +
                (net?.let { " · outq ${it[0]} B rtt ${it[1] / 1000} ms retrans ${it[4]} cwnd ${it[5]}" } ?: ""),
        )
    }

    private companion object {
        /** Espera al monitor de red al cerrar (comparte con onClosed el detector de cortes y la métrica). */
        const val MONITOR_JOIN_MS = 200L

        /** Espera máxima al sello del fin de la sesión anterior tras el primer IDR. */
        const val GAP_WAIT_NS = 5_000_000_000L
    }
}
