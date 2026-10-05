package dev.qdauto.core.session

import dev.qdauto.core.discovery.AckHandle
import dev.qdauto.core.discovery.AckPolicy
import dev.qdauto.core.discovery.CarAnnouncement
import dev.qdauto.core.discovery.DiscoveryConfig
import dev.qdauto.core.discovery.DiscoveryListener
import dev.qdauto.core.util.QdLog
import dev.qdauto.core.util.e
import dev.qdauto.core.util.i
import dev.qdauto.core.util.w
import dev.qdauto.core.wire.AppMessage
import dev.qdauto.core.wire.BinBlock
import dev.qdauto.core.wire.BinaryMessage
import dev.qdauto.core.wire.BtAddrRequest
import dev.qdauto.core.wire.CarInfo
import dev.qdauto.core.wire.CarKey
import dev.qdauto.core.wire.ControlMessage
import dev.qdauto.core.wire.TouchEvent
import dev.qdauto.core.wire.TraceEvent
import dev.qdauto.core.wire.UnknownMessage
import dev.qdauto.core.wire.VideoArgs
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger

enum class LinkState {
    /** Sin arrancar o cerrado. */
    STOPPED,

    /** Escuchando broadcasts, sin sesión. */
    SEARCHING,

    /** `ServerSocket` abierto y ACK enviado; esperando el TCP del coche. */
    CONNECTING,

    /** Hay una [PhoneSession] activa. */
    CONNECTED,
}

/** Configuración de [PhoneLink]. */
data class PhoneLinkConfig(
    val discovery: DiscoveryConfig = DiscoveryConfig(),
    val session: SessionConfig = SessionConfig(),
    /** [MirrorServer.RANDOM_PORT] (10001-65535, como QDLink) o un puerto fijo. */
    val mirrorPort: Int = MirrorServer.RANDOM_PORT,
    /** Espera del TCP tras el ACK (QDLink: 20 s, WF/d.java:65). */
    val acceptTimeoutMs: Long = MirrorServer.DEFAULT_ACCEPT_TIMEOUT_MS,
    /** Conectar solo con el primer coche que pase [carFilter]; si no, hay que llamar a [PhoneLink.connect]. */
    val autoConnect: Boolean = true,
    val carFilter: (CarAnnouncement) -> Boolean = { true },
    /** Tras cerrarse la sesión o fallar un intento, volver a conectar automáticamente con el siguiente broadcast. */
    val reconnect: Boolean = true,
    /** Espera mínima entre el final de un intento y el siguiente automático (0 = con el broadcast siguiente). */
    val retryDelayMs: Long = 3_000,
    /** hql (C4): configuración de la sesión de cada coche (se evalúa al aceptar el TCP); `null` = [session]. */
    val sessionConfigFor: ((CarAnnouncement) -> SessionConfig)? = null,
    /**
     * hql (C4): filtro de cada conexión TCP aceptada (origen, coche al que se mandó el ACK). Si devuelve `false` se
     * cierra y se sigue esperando con el tiempo que quede. `null` = se acepta todo.
     */
    val acceptFilter: ((InetSocketAddress?, CarAnnouncement) -> Boolean)? = null,
    /** hql (C4): IP local en la que escuchar el TCP de cada coche; `null` = todas (si falla, también todas). */
    val mirrorBindAddressFor: ((CarAnnouncement) -> InetAddress?)? = null,
    /** hql (C4): reenviar el ACK con los broadcasts del mismo coche mientras no conecta, como mucho cada esto (0 = no). */
    val reAckIntervalMs: Long = 0,
    /**
     * hql (C4): si el mismo coche se vuelve a anunciar con una sesión abierta, darla por muerta y conectar ya. El C10
     * solo se anuncia sin sesión. Solo con sesiones de al menos [supersedeMinSessionAgeMs] que lleven
     * [supersedeMinSilenceMs] sin recibir nada (evita cortar por un broadcast que ya estaba en el aire).
     */
    val supersedeOnRebroadcast: Boolean = false,
    val supersedeMinSessionAgeMs: Long = 2_000,
    val supersedeMinSilenceMs: Long = 1_000,
)

/** Eventos de [PhoneLink]. Llegan desde hilos internos (descubrimiento, aceptación o eventos de la sesión). */
interface PhoneLinkListener {
    fun onLinkStateChanged(state: LinkState) {}
    fun onCarFound(car: CarAnnouncement) {}
    fun onCarSeen(car: CarAnnouncement) {}
    fun onConnecting(car: CarAnnouncement, mirrorPort: Int) {}
    fun onAcceptTimeout(car: CarAnnouncement) {}
    fun onSessionStarted(session: PhoneSession) {}
    fun onSessionEnded(session: PhoneSession, reason: CloseReason) {}

    /** El coche abrió otra conexión TCP con una sesión ya activa (se registra y se cierra). */
    fun onExtraConnection(from: InetSocketAddress?) {}
    fun onError(message: String, error: Throwable?) {}

    /** hql (C4): ACK enviado a [car]; [attempt] cuenta los ACK de este intento (1 = el primero). */
    fun onAckSent(car: CarAnnouncement, mirrorPort: Int, attempt: Int) {}

    /** hql (C4): una conexión TCP no pasó [PhoneLinkConfig.acceptFilter] y se cerró. */
    fun onConnectionRejected(from: InetSocketAddress?, car: CarAnnouncement) {}

    /** hql (C4): el coche se volvió a anunciar con [old] abierta: se cierra con `SUPERSEDED` y se conecta otra vez. */
    fun onSessionSuperseded(old: PhoneSession, car: CarAnnouncement) {}
}

/**
 * Orquestación opcional del lado del teléfono (spec §10.1, pasos 1-5) sobre [DiscoveryListener], [MirrorServer] y
 * [PhoneSession]: escucha broadcasts, elige coche, abre el `ServerSocket` **antes** del ACK, envía el ACK desde el
 * socket de 18463, acepta con límite de tiempo, arranca la sesión y, cuando termina, vuelve a buscar.
 * Las piezas sueltas siguen disponibles para quien necesite otro flujo.
 *
 * hql (C4): cada sesión puede tener su propio [SessionListener] ([sessionListenerFactory], llamado una vez por sesión
 * y antes de cualquier evento suyo) y su propia configuración; filtros de IP para el TCP, *bind* del `ServerSocket`,
 * re-ACK con los broadcasts y relevo de la sesión cuando el coche la da por muerta (ver [PhoneLinkConfig]).
 *
 * Hilos: los del [DiscoveryListener], uno de aceptación por intento (`qd-link-accept`) y los de cada sesión.
 * Los callbacks de [PhoneLinkListener] nunca se llaman con locks internos tomados.
 */
class PhoneLink(
    val config: PhoneLinkConfig = PhoneLinkConfig(),
    private val listener: PhoneLinkListener = object : PhoneLinkListener {},
    private val sessionListener: SessionListener = object : SessionListener {},
    private val log: QdLog = QdLog.NONE,
    /** hql (C4): listener propio de cada sesión; `null` = [sessionListener] para todas. */
    private val sessionListenerFactory: ((PhoneSession) -> SessionListener)? = null,
) : Closeable {
    private val lock = Any()
    private var linkState = LinkState.STOPPED
    private var attempt: Attempt? = null
    private var lastAttempt: Attempt? = null
    private var lastAttemptEndMillis = 0L
    private var attempts = 0
    private var reserving = false
    private var closed = false

    /** Intentos recientes (también los relevados), para [awaitTermination]. */
    private val recent = ArrayDeque<Attempt>()

    val discovery: DiscoveryListener = DiscoveryListener(config.discovery, DiscoveryCallbacks(), log)

    val state: LinkState get() = synchronized(lock) { linkState }

    /** Sesión activa (o `null`). */
    val currentSession: PhoneSession? get() = synchronized(lock) { attempt?.session?.takeUnless { it.isClosed } }

    /** hql (C4): hay un intento esperando el TCP del coche (sin sesión todavía). */
    val isConnecting: Boolean get() = synchronized(lock) { attempt?.let { it.session == null } ?: reserving }

    /** Abre el socket UDP (lanza si no se puede) y empieza a buscar. */
    @Throws(IOException::class)
    fun start(): PhoneLink {
        discovery.start()
        if (transition(LinkState.SEARCHING)) listener.onLinkStateChanged(LinkState.SEARCHING)
        return this
    }

    /** Conecta con [car] si no hay otro intento o sesión en marcha. Devuelve `false` si está ocupado o cerrado. */
    fun connect(car: CarAnnouncement): Boolean {
        synchronized(lock) {
            if (closed || attempt != null || reserving) return false
            reserving = true
        }
        var error: IOException? = null
        val bind = bindAddressFor(car)
        var server: MirrorServer? = null
        try {
            server = MirrorServer(config.mirrorPort, bind, log)
        } catch (e: IOException) {
            if (bind == null) {
                error = e
            } else {
                // La IP pudo dejar de ser local (la zona Wi-Fi se apagó, cambió de red…): todas las interfaces.
                log.w(TAG, "no se pudo escuchar en ${bind.hostAddress} (${e.message}); se escucha en todas las interfaces")
                try {
                    server = MirrorServer(config.mirrorPort, null, log)
                } catch (e2: IOException) {
                    error = e2
                }
            }
        }
        val a = synchronized(lock) {
            reserving = false
            attempts++
            val srv = server
            if (srv == null || closed) {
                lastAttemptEndMillis = System.currentTimeMillis()
                null
            } else {
                Attempt(car, srv).also {
                    it.lastAckNanos = System.nanoTime()
                    attempt = it
                    lastAttempt = it
                    recent.addLast(it)
                    while (recent.size > 4) recent.removeFirst()
                }
            }
        }
        if (a == null) {
            server?.close()
            if (error != null) {
                log.e(TAG, "no se pudo abrir el puerto TCP ${config.mirrorPort}", error)
                listener.onError("no se pudo abrir el puerto TCP ${config.mirrorPort}: ${error.message}", error)
            }
            return false
        }
        if (transition(LinkState.CONNECTING)) listener.onLinkStateChanged(LinkState.CONNECTING)
        log.i(TAG, "conectando con $car en el puerto ${a.server.port}")
        listener.onConnecting(car, a.server.port)
        // El ServerSocket ya escucha antes de enviar el ACK (recomendación spec §1.4).
        a.ack = try {
            discovery.sendAck(car, a.server.port)
        } catch (e: Exception) {
            listener.onError("no se pudo enviar el ACK: ${e.message}", e)
            null
        }
        a.thread = Thread({ acceptLoop(a) }, "qd-link-accept").apply {
            isDaemon = true
            start()
        }
        return true
    }

    /** Cierra la sesión o el intento en curso; con [PhoneLinkConfig.reconnect] se volverá a conectar. */
    fun disconnect() {
        val a = synchronized(lock) { attempt } ?: return
        a.ack?.cancel()
        a.session?.close()
        a.server.close()
    }

    /** hql (C4): como [disconnect], con el motivo en el cierre de la sesión (`LOCAL`, [message]). */
    fun closeSession(message: String) {
        val a = synchronized(lock) { attempt } ?: return
        a.ack?.cancel()
        a.session?.closeLocal(message)
        a.server.close()
    }

    override fun close() {
        val a = synchronized(lock) {
            if (closed) return
            closed = true
            attempt
        }
        discovery.close()
        a?.ack?.cancel()
        a?.session?.close()
        a?.server?.close()
        if (transition(LinkState.STOPPED)) listener.onLinkStateChanged(LinkState.STOPPED)
    }

    /** Espera a que terminen todos los hilos (tras [close]). No llamar desde un callback. */
    fun awaitTermination(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        fun left() = maxOf(1L, deadline - System.currentTimeMillis())
        val list = synchronized(lock) { recent.toList() }
        var done = true
        for (a in list) {
            a.thread?.join(left())
            if (a.thread?.isAlive == true) done = false
            if (a.session?.awaitTermination(left()) == false) done = false
        }
        return discovery.awaitTermination(left()) && done
    }

    /** Número de intentos de conexión hechos (para pruebas y estadísticas). */
    val attemptCount: Int get() = synchronized(lock) { attempts }

    private fun bindAddressFor(car: CarAnnouncement): InetAddress? {
        val f = config.mirrorBindAddressFor ?: return null
        return try {
            f(car)
        } catch (e: Exception) {
            log.e(TAG, "mirrorBindAddressFor falló; se escucha en todas las interfaces", e)
            null
        }
    }

    private fun acceptsTcp(from: InetSocketAddress?, car: CarAnnouncement): Boolean {
        val f = config.acceptFilter ?: return true
        return try {
            f(from, car)
        } catch (e: Exception) {
            log.e(TAG, "acceptFilter falló; se rechaza la conexión de $from", e)
            false
        }
    }

    private fun sessionConfigFor(car: CarAnnouncement): SessionConfig {
        val f = config.sessionConfigFor ?: return config.session
        return try {
            f(car)
        } catch (e: Exception) {
            log.e(TAG, "sessionConfigFor falló; se usa la configuración por defecto", e)
            config.session
        }
    }

    private fun acceptLoop(a: Attempt) {
        try {
            val deadline = System.nanoTime() + config.acceptTimeoutMs * 1_000_000
            var socket: Socket
            while (true) {
                val leftMs = (deadline - System.nanoTime()) / 1_000_000
                if (leftMs <= 0) {
                    acceptTimedOut(a)
                    return
                }
                socket = try {
                    a.server.accept(leftMs)
                } catch (_: SocketTimeoutException) {
                    acceptTimedOut(a)
                    return
                } catch (e: IOException) {
                    if (!a.server.isClosed) listener.onError("error esperando la conexión: ${e.message}", e)
                    return
                }
                val from = socket.remoteSocketAddress as? InetSocketAddress
                if (acceptsTcp(from, a.car)) break
                log.w(TAG, "conexión TCP de $from rechazada (no es ${a.car}); se sigue esperando")
                listener.onConnectionRejected(from, a.car)
                try {
                    socket.close()
                } catch (_: IOException) {
                }
            }
            a.ack?.cancel()
            val forwarding = ForwardingSessionListener(sessionListener)
            val session = PhoneSession(socket, sessionConfigFor(a.car), SessionWatcher(a, forwarding), log)
            val accepted = synchronized(lock) {
                if (closed || attempt !== a) {
                    false
                } else {
                    a.session = session
                    true
                }
            }
            if (!accepted) {
                session.close()
                return
            }
            // El listener propio de la sesión, antes de arrancarla: ningún evento suyo puede llegar a otro.
            sessionListenerFactory?.let { factory ->
                forwarding.target = try {
                    factory(session)
                } catch (e: Exception) {
                    log.e(TAG, "sessionListenerFactory falló; se usa el listener común", e)
                    sessionListener
                }
            }
            try {
                session.start()
            } catch (e: IOException) {
                listener.onError("no se pudo arrancar la sesión: ${e.message}", e)
                session.close() // SessionWatcher.onClosed cierra el intento
                return
            } catch (e: IllegalStateException) {
                // Cerrada entre medias (close() del enlace o relevo).
                session.close()
                return
            }
            if (transition(LinkState.CONNECTED)) listener.onLinkStateChanged(LinkState.CONNECTED)
            listener.onSessionStarted(session)
            // QDLink deja el ServerSocket abierto; registramos (y cerramos) cualquier conexión extra del coche.
            while (!a.server.isClosed && !session.isClosed) {
                val extra = try {
                    a.server.accept(0)
                } catch (_: IOException) {
                    break
                }
                val from = extra.remoteSocketAddress as? InetSocketAddress
                log.w(TAG, "conexión TCP extra de $from con la sesión activa; se cierra")
                listener.onExtraConnection(from)
                try {
                    extra.close()
                } catch (_: IOException) {
                }
            }
        } finally {
            a.ack?.cancel()
            a.server.close()
            if (a.session == null) endAttempt(a)
        }
    }

    private fun acceptTimedOut(a: Attempt) {
        log.w(TAG, "el coche no conectó en ${config.acceptTimeoutMs} ms")
        listener.onAcceptTimeout(a.car)
    }

    /** Termina el intento: vuelve a SEARCHING (o se queda en STOPPED si se cerró). */
    private fun endAttempt(a: Attempt) {
        val searching = synchronized(lock) {
            if (attempt !== a) return
            attempt = null
            lastAttemptEndMillis = System.currentTimeMillis()
            !closed
        }
        if (searching && transition(LinkState.SEARCHING)) listener.onLinkStateChanged(LinkState.SEARCHING)
    }

    /**
     * Cada broadcast que pasa [PhoneLinkConfig.carFilter]: conexión automática si no hay intento; re-ACK si el mismo
     * coche sigue sin conectar; relevo si se anuncia con una sesión que ya no habla.
     */
    private fun onBroadcast(car: CarAnnouncement) {
        val passes = try {
            config.carFilter(car)
        } catch (e: Exception) {
            log.e(TAG, "carFilter falló; se ignora el broadcast de $car", e)
            false
        }
        if (!passes) return
        var reAck: Attempt? = null
        var superseded: Attempt? = null
        var oldSession: PhoneSession? = null
        var autoConnect = false
        var searching = false
        synchronized(lock) {
            if (closed) return
            val a = attempt
            val s = a?.session
            when {
                a == null -> {
                    val first = attempts == 0
                    autoConnect = config.autoConnect && !reserving && (first || config.reconnect) &&
                        (first || System.currentTimeMillis() - lastAttemptEndMillis >= config.retryDelayMs)
                }
                s != null && s.isClosed -> {
                    // La sesión ya se cerró pero su intento aún no ha terminado (endAttempt va detrás del onClosed, en el
                    // hilo de eventos): se da por terminado aquí para no perder este broadcast.
                    attempt = null
                    lastAttemptEndMillis = System.currentTimeMillis()
                    autoConnect = config.autoConnect && config.reconnect && !reserving && config.retryDelayMs <= 0
                    // Lo que haría endAttempt (que ya no hará nada): si no se conecta ahora, el enlace vuelve a buscar.
                    searching = !autoConnect
                }
                s == null -> {
                    val now = System.nanoTime()
                    if (config.reAckIntervalMs > 0 && sameCar(a.car, car) && now - a.lastAckNanos >= config.reAckIntervalMs * 1_000_000) {
                        a.lastAckNanos = now
                        reAck = a
                    }
                }
                config.supersedeOnRebroadcast && sameCar(a.car, car) && !s.isClosed && isDead(s) -> {
                    attempt = null
                    lastAttemptEndMillis = System.currentTimeMillis()
                    superseded = a
                    oldSession = s
                }
            }
        }
        val again = reAck
        val gone = superseded
        val old = oldSession
        when {
            again != null -> {
                log.i(TAG, "el coche sigue sin conectar: ACK otra vez a ${car.host} (puerto ${again.server.port})")
                try {
                    discovery.sendAck(car, again.server.port, AckPolicy.QDLINK)
                } catch (e: Exception) {
                    listener.onError("no se pudo reenviar el ACK: ${e.message}", e)
                }
            }
            gone != null && old != null -> supersede(gone, old, car)
            autoConnect -> connect(car)
        }
        if (searching && transition(LinkState.SEARCHING)) listener.onLinkStateChanged(LinkState.SEARCHING)
    }

    /** La sesión es lo bastante vieja y lleva lo bastante callada como para que un broadcast signifique que murió. */
    private fun isDead(s: PhoneSession): Boolean {
        val started = s.startedAtNanos
        if (started == 0L) return false
        val now = System.nanoTime()
        val ageMs = (now - started) / 1_000_000
        val silentMs = (now - s.io().lastReceiveNanos) / 1_000_000
        return ageMs >= config.supersedeMinSessionAgeMs && silentMs >= config.supersedeMinSilenceMs
    }

    private fun supersede(a: Attempt, old: PhoneSession, car: CarAnnouncement) {
        val silentMs = (System.nanoTime() - old.io().lastReceiveNanos) / 1_000_000
        log.w(TAG, "$car se vuelve a anunciar con la sesión S${old.id} abierta y callada $silentMs ms: la doy por muerta y conecto otra vez")
        try {
            listener.onSessionSuperseded(old, car)
        } catch (e: Exception) {
            log.e(TAG, "excepción en onSessionSuperseded", e)
        }
        old.closeWith(CloseReason(CloseReason.Kind.SUPERSEDED, "el coche volvió a anunciarse ($silentMs ms sin recibir nada)"))
        a.server.close()
        a.ack?.cancel()
        connect(car)
    }

    /** Mismo coche: `DeviceUUID` no vacío e igual, o misma IP. */
    private fun sameCar(a: CarAnnouncement, b: CarAnnouncement): Boolean =
        (a.uuid.isNotEmpty() && a.uuid == b.uuid) || a.address == b.address

    /** Cambia el estado; devuelve si cambió (el aviso se hace fuera del lock). */
    private fun transition(s: LinkState): Boolean {
        val changed = synchronized(lock) {
            if (linkState == s || (closed && s != LinkState.STOPPED)) {
                false
            } else {
                linkState = s
                true
            }
        }
        if (changed) log.i(TAG, "enlace → $s")
        return changed
    }

    private class Attempt(val car: CarAnnouncement, val server: MirrorServer) {
        @Volatile
        var ack: AckHandle? = null

        @Volatile
        var thread: Thread? = null

        @Volatile
        var session: PhoneSession? = null

        /** `System.nanoTime()` del último ACK (con el lock del enlace). */
        var lastAckNanos = 0L

        /** ACK enviados en este intento. */
        val acks = AtomicInteger()
    }

    private inner class DiscoveryCallbacks : DiscoveryListener.Callback {
        override fun onCarFound(car: CarAnnouncement) {
            listener.onCarFound(car)
            onBroadcast(car)
        }

        override fun onCarSeen(car: CarAnnouncement) {
            listener.onCarSeen(car)
            onBroadcast(car)
        }

        override fun onAckSent(target: InetSocketAddress, mirrorPort: Int, attempt: Int, bytes: ByteArray) {
            val a = synchronized(lock) { this@PhoneLink.attempt?.takeIf { it.server.port == mirrorPort } } ?: return
            listener.onAckSent(a.car, mirrorPort, a.acks.incrementAndGet())
        }

        override fun onError(error: Throwable) = listener.onError("descubrimiento: ${error.message}", error)
    }

    /**
     * Reenvía todo al listener de la sesión ([ForwardingSessionListener]) y detecta su final. hql (C4): cada sesión
     * tiene el suyo, así que sus eventos (también el `onClosed` de una sesión relevada) nunca llegan a otra.
     */
    private inner class SessionWatcher(private val a: Attempt, private val forwarding: SessionListener) : SessionListener by forwarding {
        override fun onClosed(reason: CloseReason) {
            try {
                forwarding.onClosed(reason)
            } finally {
                a.server.close()
                a.session?.let { listener.onSessionEnded(it, reason) }
                endAttempt(a)
            }
        }
    }

    private companion object {
        const val TAG = "QD/Link"
    }
}

/**
 * hql (C4): [SessionListener] que reenvía cada evento a [target], que se puede fijar después de construir la sesión
 * (antes de arrancarla). Sobrescribe **todos** los métodos de forma explícita (un test lo comprueba por reflexión),
 * para que uno nuevo no se pierda en silencio.
 */
internal class ForwardingSessionListener(@Volatile var target: SessionListener) : SessionListener {
    override fun onStateChanged(from: SessionState, to: SessionState) = target.onStateChanged(from, to)
    override fun onCarInfo(info: CarInfo, message: ControlMessage) = target.onCarInfo(info, message)
    override fun onVideoSupportRequest(videoFormat: Int, message: ControlMessage) = target.onVideoSupportRequest(videoFormat, message)
    override fun onVideoArgs(args: VideoArgs, message: ControlMessage) = target.onVideoArgs(args, message)
    override fun onVideoControl(play: Boolean, playStatus: Int, message: ControlMessage) = target.onVideoControl(play, playStatus, message)
    override fun onKeyframeRequested(reason: KeyframeReason) = target.onKeyframeRequested(reason)
    override fun onTouch(event: TouchEvent) = target.onTouch(event)
    override fun onKey(key: CarKey) = target.onKey(key)
    override fun onPhoneKey(code: Int, message: ControlMessage) = target.onPhoneKey(code, message)
    override fun onLandModeRequest(orientation: Int, message: ControlMessage) = target.onLandModeRequest(orientation, message)
    override fun onBtAddr(request: BtAddrRequest, message: ControlMessage) = target.onBtAddr(request, message)
    override fun onDisconnectRequest(message: ControlMessage) = target.onDisconnectRequest(message)
    override fun onGoInLinkApp(message: ControlMessage) = target.onGoInLinkApp(message)
    override fun onControlMessage(message: ControlMessage) = target.onControlMessage(message)
    override fun onAppMessage(message: AppMessage) = target.onAppMessage(message)
    override fun onBinaryMessage(message: BinaryMessage) = target.onBinaryMessage(message)
    override fun onLegacyMessage(block: BinBlock) = target.onLegacyMessage(block)
    override fun onUnknownMessage(message: UnknownMessage) = target.onUnknownMessage(message)
    override fun onWatchdogWarning(silentMs: Long) = target.onWatchdogWarning(silentMs)
    override fun onTrace(event: TraceEvent) = target.onTrace(event)
    override fun onClosed(reason: CloseReason) = target.onClosed(reason)
}
