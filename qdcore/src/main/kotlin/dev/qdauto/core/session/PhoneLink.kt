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
import dev.qdauto.core.wire.BlockFraming
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
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
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

    /**
     * hql: re-acogida tras perder la sesión de mala manera ([RecoveryConfig]): escuchando otra vez en el puerto de la
     * sesión perdida y llamando al coche con ACK no pedidos. Un broadcast suyo se contesta en el acto con ese puerto.
     */
    RECOVERING,
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
    /**
     * hql (C4): mientras el coche no conecta, contestar cada broadcast suyo con un ACK nuevo con el puerto del intento
     * (por defecto, sí). En la re-acogida se contesta siempre en el acto.
     */
    val reAckOnBroadcast: Boolean = true,
    /** hql (C4): … como mucho cada esto (0 = todos los broadcasts). */
    val reAckIntervalMs: Long = 0,
    /**
     * hql (C4): si el mismo coche se vuelve a anunciar con una sesión abierta, darla por muerta y conectar ya. El C10
     * solo se anuncia sin sesión. Solo con sesiones de al menos [supersedeMinSessionAgeMs] que lleven
     * [supersedeMinSilenceMs] sin recibir nada (evita cortar por un broadcast que ya estaba en el aire).
     */
    val supersedeOnRebroadcast: Boolean = false,
    val supersedeMinSessionAgeMs: Long = 2_000,
    val supersedeMinSilenceMs: Long = 1_000,
    /** hql: vuelta del coche tras un corte de radio: puerto estable, re-acogida y vigilancia ([RecoveryConfig]). */
    val recovery: RecoveryConfig = RecoveryConfig(),
    /**
     * hql: la trama por bloques del USB (relleno a 512 B al escribir, lectura en bloques; [BlockFraming]) sobre el TCP
     * del Wi-Fi. Solo para probar la trama desde el PC con `qdsim --usb-framing`: el C10 por Wi-Fi no la usa.
     */
    val blockFraming: Boolean = false,
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

    /** hql: como el anterior, diciendo si es un ACK no pedido (re-acogida). Por defecto llama al anterior. */
    fun onAckSent(car: CarAnnouncement, mirrorPort: Int, attempt: Int, unsolicited: Boolean) = onAckSent(car, mirrorPort, attempt)

    /** hql (C4): una conexión TCP no pasó [PhoneLinkConfig.acceptFilter] y se cerró. */
    fun onConnectionRejected(from: InetSocketAddress?, car: CarAnnouncement) {}

    /** hql (C4): el coche se volvió a anunciar con [old] abierta: se cierra con `SUPERSEDED` y se conecta otra vez. */
    fun onSessionSuperseded(old: PhoneSession, car: CarAnnouncement) {}

    /** hql: empieza la re-acogida tras perder la sesión por [reason]: se escucha otra vez en [mirrorPort]. */
    fun onReclaimStarted(car: CarAnnouncement, mirrorPort: Int, reason: CloseReason) {}

    /** hql: el coche ha vuelto ([afterMs] desde la pérdida de la sesión, [how]); llega después de [onSessionStarted]. */
    fun onCarBack(car: CarAnnouncement, how: CarReturn, afterMs: Long) {}

    /** hql: se reabrió el socket UDP del descubrimiento tras [quietMs] sin datagramas (-1: no estaba abierto). */
    fun onDiscoveryReopened(quietMs: Long) {}
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
 * hql: vuelta del coche tras un corte de radio ([RecoveryConfig]): el `MirrorPort` de la última sesión se reutiliza;
 * tras un final anormal, **re-acogida** (estado [LinkState.RECOVERING]: el mismo puerto con una espera larga y ACK no
 * pedidos al coche); mientras se espera al coche, diagnóstico periódico y reapertura del UDP si no llega nada; al
 * volver, por dónde volvió. [close] (Desconectar) lo para todo.
 *
 * Hilos: los del [DiscoveryListener], uno de aceptación por intento (`qd-link-accept`), el de la vigilancia tras una
 * pérdida (`qd-link-watch`) y los de cada sesión. Los callbacks de [PhoneLinkListener] nunca se llaman con locks
 * internos tomados.
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
    private var lastAttemptEndMillis = 0L
    private var attempts = 0
    private var reserving = false

    /** hql: la reserva en curso es la de una re-acogida (cuenta para [isRecovering], no para [isConnecting]). */
    private var reservingReclaim = false
    private var closed = false

    /** hql: `MirrorPort` de la última sesión (0 = ninguna todavía). */
    private var stablePort = 0

    /** hql: espera del coche tras perder una sesión (null = no se espera). */
    private var loss: Loss? = null
    private var watcher: ScheduledExecutorService? = null
    private var watchTask: ScheduledFuture<*>? = null
    private val tcpAccepts = AtomicInteger()

    /** Intentos recientes (también los relevados), para [awaitTermination]. */
    private val recent = ArrayDeque<Attempt>()

    private val recovery: RecoveryConfig get() = config.recovery

    val discovery: DiscoveryListener = DiscoveryListener(config.discovery, DiscoveryCallbacks(), log)

    val state: LinkState get() = synchronized(lock) { linkState }

    /** Sesión activa (o `null`). */
    val currentSession: PhoneSession? get() = synchronized(lock) { attempt?.session?.takeUnless { it.isClosed } }

    /**
     * hql (C4): hay un intento esperando el TCP del coche (sin sesión todavía). hql: la re-acogida solo cuenta si el
     * coche se ha anunciado hace menos de [PhoneLinkConfig.acceptTimeoutMs]; si no, es una espera pasiva.
     */
    val isConnecting: Boolean
        get() = synchronized(lock) {
            val a = attempt ?: return@synchronized reserving && !reservingReclaim
            a.session == null && (!a.reclaim || a.announcedWithin(System.nanoTime(), config.acceptTimeoutMs))
        }

    /** hql: re-acogida en marcha (esperando al coche en el puerto de la sesión perdida). */
    val isRecovering: Boolean get() = synchronized(lock) { attempt?.let { it.reclaim && it.session == null } ?: reservingReclaim }

    /** hql: `MirrorPort` que reutilizarán los intentos siguientes (0 = ninguno todavía, o sin puerto estable). */
    val stableMirrorPort: Int get() = synchronized(lock) { if (recovery.stableMirrorPort) stablePort else 0 }

    /** hql: conexiones TCP aceptadas desde el arranque (también las rechazadas y las extra). */
    val tcpAcceptCount: Int get() = tcpAccepts.get()

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
        return open(car, null)
    }

    /**
     * Con la reserva hecha ([reserving]): abre el `ServerSocket` y arranca el intento. [lost] != null: re-acogida en el
     * puerto de la sesión perdida (sin ACK pedido: ACK no pedidos). Si no se puede, el enlace vuelve a buscar.
     */
    private fun open(car: CarAnnouncement, lost: Lost?): Boolean {
        val wanted = synchronized(lock) { lost?.port ?: preferredPortLocked() }
        // Puerto reutilizado (el de la sesión anterior): si no se puede, uno aleatorio.
        val reused = lost != null || (config.mirrorPort == MirrorServer.RANDOM_PORT && wanted != MirrorServer.RANDOM_PORT)
        var error: IOException? = null
        val server = try {
            openServer(car, wanted, reused)
        } catch (e: IOException) {
            error = e
            null
        }
        val a = synchronized(lock) {
            reserving = false
            reservingReclaim = false
            attempts++
            if (server == null || closed) {
                lastAttemptEndMillis = System.currentTimeMillis()
                null
            } else {
                val window = if (lost != null) recovery.reclaimWindowMs else config.acceptTimeoutMs
                Attempt(car, server, lost != null, window).also {
                    it.lastAckNanos = System.nanoTime()
                    attempt = it
                    recent.addLast(it)
                    while (recent.size > 4) recent.removeFirst()
                }
            }
        }
        if (a == null) {
            server?.close()
            val err = error
            if (err != null) {
                log.e(TAG, "no se pudo abrir el puerto TCP $wanted", err)
                listener.onError("no se pudo abrir el puerto TCP $wanted: ${err.message}", err)
            }
            if (transition(LinkState.SEARCHING)) listener.onLinkStateChanged(LinkState.SEARCHING)
            return false
        }
        if (lost == null) {
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
        } else {
            if (transition(LinkState.RECOVERING)) listener.onLinkStateChanged(LinkState.RECOVERING)
            val port = a.server.port
            val acks = if (recovery.unsolicitedAcks) {
                val p = recovery.unsolicitedAckPolicy
                " y llamo a ${car.host} con ACK no pedidos (cada ${p.retryIntervalMs} ms" +
                    (if (p.slowAfterMs > 0) "; desde los ${p.slowAfterMs / 1000} s, cada ${p.slowIntervalMs} ms" else "") + ")"
            } else {
                " (sin ACK no pedidos)"
            }
            log.i(TAG, "re-acogida tras ${lost.reason.kind}: escucho otra vez en el puerto $port" +
                (if (port != lost.port) " (el ${lost.port} no se pudo reutilizar)" else "") +
                " hasta ${recovery.reclaimWindowMs / 1000} s$acks")
            listener.onReclaimStarted(car, port, lost.reason)
            if (recovery.unsolicitedAcks) {
                a.ack = try {
                    discovery.sendAck(car.address, port, recovery.unsolicitedAckPolicy, unsolicited = true)
                } catch (e: Exception) {
                    listener.onError("no se pudo enviar el ACK no pedido: ${e.message}", e)
                    null
                }
            }
        }
        a.thread = Thread({ acceptLoop(a) }, "qd-link-accept").apply {
            isDaemon = true
            start()
        }
        return true
    }

    /** Puerto del intento siguiente: el fijo de la configuración, el de la última sesión (puerto estable) o aleatorio. */
    private fun preferredPortLocked(): Int = when {
        config.mirrorPort != MirrorServer.RANDOM_PORT -> config.mirrorPort
        recovery.stableMirrorPort && stablePort > 0 -> stablePort
        else -> MirrorServer.RANDOM_PORT
    }

    /**
     * `ServerSocket` en [port] (en la IP de [PhoneLinkConfig.mirrorBindAddressFor] o, si no se puede, en todas). Con
     * [reused] (hql: el puerto de la sesión anterior), si el puerto no se puede usar, uno aleatorio.
     */
    @Throws(IOException::class)
    private fun openServer(car: CarAnnouncement, port: Int, reused: Boolean): MirrorServer {
        val bind = bindAddressFor(car)
        fun on(p: Int): MirrorServer {
            try {
                return MirrorServer(p, bind, log)
            } catch (e: IOException) {
                val busy = e.message?.contains("in use", ignoreCase = true) == true
                if (bind == null || busy) throw e
                // La IP pudo dejar de ser local (la zona Wi-Fi se apagó, cambió de red…): todas las interfaces.
                log.w(TAG, "no se pudo escuchar en ${bind.hostAddress} (${e.message}); se escucha en todas las interfaces")
                return MirrorServer(p, null, log)
            }
        }
        if (!reused) return on(port)
        return try {
            on(port)
        } catch (e: IOException) {
            log.w(TAG, "no se pudo reutilizar el puerto $port (${e.message}); uso uno aleatorio")
            on(MirrorServer.RANDOM_PORT)
        }
    }

    /** Cierra la sesión o el intento en curso; con [PhoneLinkConfig.reconnect] se volverá a conectar. */
    fun disconnect() {
        val a = synchronized(lock) { attempt } ?: return
        a.cancelAcks()
        a.session?.close()
        a.server.close()
    }

    /** hql (C4): como [disconnect], con el motivo en el cierre de la sesión (`LOCAL`, [message]). */
    fun closeSession(message: String) {
        val a = synchronized(lock) { attempt } ?: return
        a.cancelAcks()
        a.session?.closeLocal(message)
        a.server.close()
    }

    /** Lo cierra todo: descubrimiento (y sus ACK pendientes), intento o re-acogida, sesión y vigilancia. */
    override fun close() {
        val a: Attempt?
        val w: ScheduledExecutorService?
        synchronized(lock) {
            if (closed) return
            closed = true
            a = attempt
            loss = null
            stopWatchLocked()
            w = watcher
        }
        w?.shutdownNow()
        discovery.close()
        a?.cancelAcks()
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
        val w = synchronized(lock) { watcher }
        if (w != null && !w.awaitTermination(left(), TimeUnit.MILLISECONDS)) done = false
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
            val base = System.nanoTime() + a.acceptTimeoutMs * 1_000_000
            var socket: Socket
            var acceptNanos: Long
            while (true) {
                // hql: en la re-acogida, un broadcast del coche (contestado con un ACK) alarga la espera hasta
                // acceptTimeoutMs después de él, para no cerrar el puerto justo antes de su TCP.
                val announced = a.lastBroadcastNanos
                val deadline = if (announced != 0L) maxOf(base, announced + config.acceptTimeoutMs * 1_000_000) else base
                val leftMs = (deadline - System.nanoTime()) / 1_000_000
                if (leftMs <= 0) {
                    acceptTimedOut(a)
                    return
                }
                socket = try {
                    a.server.accept(leftMs)
                } catch (_: SocketTimeoutException) {
                    continue // se vuelve a mirar el plazo (puede haberse alargado)
                } catch (e: IOException) {
                    if (!a.server.isClosed) listener.onError("error esperando la conexión: ${e.message}", e)
                    return
                }
                acceptNanos = System.nanoTime()
                tcpAccepts.incrementAndGet()
                val from = socket.remoteSocketAddress as? InetSocketAddress
                if (acceptsTcp(from, a.car)) break
                log.w(TAG, "conexión TCP de $from rechazada (no es ${a.car}); se sigue esperando")
                listener.onConnectionRejected(from, a.car)
                try {
                    socket.close()
                } catch (_: IOException) {
                }
            }
            // hql: el último ACK no pedido antes de esta conexión (para decir por dónde volvió el coche).
            val unsolicitedAt = if (a.reclaim) a.ack?.lastSentAtNanos?.takeIf { it in 1..acceptNanos } ?: 0L else 0L
            a.cancelAcks()
            val forwarding = ForwardingSessionListener(sessionListener)
            val transport = TcpTransport(socket, if (config.blockFraming) BlockFraming.BLOCK else 0)
            val session = PhoneSession(transport, sessionConfigFor(a.car), SessionWatcher(a, forwarding), log)
            var back: Loss? = null
            val accepted = synchronized(lock) {
                if (closed || attempt !== a) {
                    false
                } else {
                    a.session = session
                    stablePort = a.server.port
                    back = loss
                    loss = null
                    true
                }
            }
            // Un reenvío arrancado por un broadcast justo antes de fijar la sesión (ver onBroadcast) se para aquí.
            a.cancelAcks()
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
            back?.let { carBack(it, a, acceptNanos, unsolicitedAt) }
            // QDLink deja el ServerSocket abierto; registramos (y cerramos) cualquier conexión extra del coche.
            while (!a.server.isClosed && !session.isClosed) {
                val extra = try {
                    a.server.accept(0)
                } catch (_: IOException) {
                    break
                }
                tcpAccepts.incrementAndGet()
                val from = extra.remoteSocketAddress as? InetSocketAddress
                log.w(TAG, "conexión TCP extra de $from con la sesión activa; se cierra")
                listener.onExtraConnection(from)
                try {
                    extra.close()
                } catch (_: IOException) {
                }
            }
        } finally {
            a.cancelAcks()
            a.server.close()
            if (a.session == null) endAttempt(a)
        }
    }

    private fun acceptTimedOut(a: Attempt) {
        if (a.reclaim) {
            log.w(TAG, "re-acogida: el coche no volvió al puerto ${a.server.port} en ${a.acceptTimeoutMs / 1000} s")
        } else {
            log.w(TAG, "el coche no conectó en ${a.acceptTimeoutMs} ms")
        }
        listener.onAcceptTimeout(a.car)
    }

    /**
     * Termina el intento: vuelve a SEARCHING (o se queda en STOPPED si se cerró). hql: si termina porque su sesión se
     * perdió ([lost]), empieza la espera del coche (vigilancia) y, tras un final anormal, la re-acogida.
     */
    private fun endAttempt(a: Attempt, lost: CloseReason? = null) {
        var reclaim: Lost? = null
        val searching = synchronized(lock) {
            if (attempt !== a) return
            attempt = null
            lastAttemptEndMillis = System.currentTimeMillis()
            if (closed) return@synchronized false
            if (lost != null && a.session != null) {
                beginLossLocked(a.car, lost)
                if (recovery.reclaim && lost.kind in recovery.reclaimOn && config.autoConnect && config.reconnect && !reserving) {
                    reserving = true
                    reservingReclaim = true
                    reclaim = Lost(a.server.port, lost)
                }
            }
            reclaim == null
        }
        val r = reclaim
        if (r != null) {
            open(a.car, r)
        } else if (searching && transition(LinkState.SEARCHING)) {
            listener.onLinkStateChanged(LinkState.SEARCHING)
        }
        ensureWatch()
    }

    /**
     * Cada broadcast que pasa [PhoneLinkConfig.carFilter]: conexión automática si no hay intento; re-ACK si el mismo
     * coche sigue sin conectar (en la re-acogida, en el acto y con el mismo puerto); intento nuevo si el coche llega
     * desde otra IP (o, en la re-acogida, si es otro coche); relevo si se anuncia con una sesión que ya no habla.
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
        var replaced: Attempt? = null
        var replacedWhy = ""
        var superseded: Attempt? = null
        var oldSession: PhoneSession? = null
        var staleServer: MirrorServer? = null
        var autoConnect = false
        var searching = false
        synchronized(lock) {
            if (closed) return
            val now = System.nanoTime()
            loss?.let { if (sameCar(it.car, car)) it.car = car }
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
                    // hql: su servidor se cierra ya (lo cerraría ese onClosed), para poder reutilizar el puerto.
                    staleServer = a.server
                    autoConnect = config.autoConnect && config.reconnect && !reserving && config.retryDelayMs <= 0
                    // Lo que haría endAttempt (que ya no hará nada): si no se conecta ahora, el enlace vuelve a buscar.
                    searching = !autoConnect
                }
                s == null && sameCar(a.car, car) && a.car.address != car.address -> {
                    // hql: el mismo coche desde otra IP (zona Wi-Fi reiniciada): el intento escucha en la IP de antes y
                    // filtra el TCP por la IP vieja; se abre otro.
                    attempt = null
                    lastAttemptEndMillis = System.currentTimeMillis()
                    replaced = a
                    replacedWhy = "${car.name} se anuncia desde otra IP (${a.car.host} → ${car.host})"
                }
                s == null && a.reclaim && !sameCar(a.car, car) -> {
                    attempt = null
                    lastAttemptEndMillis = System.currentTimeMillis()
                    replaced = a
                    replacedWhy = "se anuncia otro coche ($car)"
                }
                s == null && sameCar(a.car, car) -> {
                    if (a.reclaim) a.lastBroadcastNanos = now
                    if (a.reclaim || (config.reAckOnBroadcast && now - a.lastAckNanos >= config.reAckIntervalMs * 1_000_000)) {
                        a.lastAckNanos = now
                        reAck = a
                    }
                }
                config.supersedeOnRebroadcast && s != null && sameCar(a.car, car) && !s.isClosed && isDead(s) -> {
                    attempt = null
                    lastAttemptEndMillis = System.currentTimeMillis()
                    superseded = a
                    oldSession = s
                }
            }
        }
        staleServer?.close()
        val again = reAck
        val other = replaced
        val gone = superseded
        val old = oldSession
        when {
            again != null -> {
                if (again.reclaim) {
                    // El coche está buscando: ACK en el acto y, hasta que conecte, los reenvíos de un intento normal.
                    log.i(TAG, "re-acogida: ${car.host} se anuncia; ACK en el acto con el puerto ${again.server.port}")
                } else {
                    log.i(TAG, "el coche sigue sin conectar: ACK otra vez a ${car.host} (puerto ${again.server.port})")
                }
                val handle = try {
                    discovery.sendAck(car, again.server.port, if (again.reclaim) config.discovery.ackPolicy else AckPolicy.QDLINK)
                } catch (e: Exception) {
                    listener.onError("no se pudo reenviar el ACK: ${e.message}", e)
                    null
                }
                if (handle != null && again.reclaim) {
                    val stale = synchronized(lock) {
                        if (!closed && attempt === again && again.session == null) {
                            again.reAck.also { again.reAck = handle }
                        } else {
                            handle
                        }
                    }
                    stale?.cancel()
                }
            }
            other != null -> {
                log.w(TAG, (if (other.reclaim) "re-acogida" else "intento") + " en el puerto ${other.server.port} terminado: $replacedWhy; conecto otra vez")
                other.cancelAcks()
                other.server.close()
                connect(car)
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
        a.cancelAcks()
        connect(car)
    }

    /** Mismo coche: `DeviceUUID` no vacío e igual, o misma IP. */
    private fun sameCar(a: CarAnnouncement, b: CarAnnouncement): Boolean =
        (a.uuid.isNotEmpty() && a.uuid == b.uuid) || a.address == b.address

    // ---------------------------------------------------------------- hql: espera del coche tras una pérdida

    /** Empieza la espera del coche tras perder la sesión con [car] por [reason] (con el lock). */
    private fun beginLossLocked(car: CarAnnouncement, reason: CloseReason) {
        loss = Loss(
            car = car,
            reason = reason,
            watch = LossWatch(System.nanoTime(), recovery),
            datagrams0 = discovery.datagramsReceived,
            solicited0 = discovery.solicitedAcksSent,
            unsolicited0 = discovery.unsolicitedAcksSent,
            tcp0 = tcpAccepts.get(),
            reopens0 = discovery.reopenCount,
        )
    }

    /** Arranca la vigilancia (diagnóstico y reapertura del UDP) si hay una pérdida y no está ya en marcha. */
    private fun ensureWatch() {
        val r = recovery
        if (r.watchMs <= 0 || (r.diagIntervalMs <= 0 && r.udpRefreshSilenceMs <= 0)) return
        synchronized(lock) {
            if (closed || loss == null || watchTask != null) return
            val ex = watcher ?: Executors.newSingleThreadScheduledExecutor { task ->
                Thread(task, "qd-link-watch").apply { isDaemon = true }
            }.also { watcher = it }
            watchTask = try {
                ex.scheduleWithFixedDelay({ watchTick() }, r.tickMs, r.tickMs, TimeUnit.MILLISECONDS)
            } catch (_: RejectedExecutionException) {
                null
            }
        }
    }

    private fun stopWatchLocked() {
        watchTask?.cancel(false)
        watchTask = null
    }

    /** Hilo `qd-link-watch`: lo que toque de la espera del coche ([LossWatch]). */
    private fun watchTick() {
        try {
            val l: Loss
            val due: LossWatch.Due
            synchronized(lock) {
                val cur = loss
                if (closed || cur == null) {
                    stopWatchLocked()
                    return
                }
                l = cur
                due = cur.watch.due(System.nanoTime(), discovery.lastDatagramAtNanos)
                if (due.expired) {
                    loss = null
                    stopWatchLocked()
                }
            }
            if (due.expired) {
                log.i(TAG, "dejo de vigilar la vuelta del coche: ${recovery.watchMs / 1000} s sin él desde ${l.reason.kind} (${counts(l)})")
                if (!discovery.isOpen) reopenDiscovery(l, "el socket UDP no está abierto", -1)
                return
            }
            if (due.reopenQuietMs >= 0) {
                reopenDiscovery(l, "${due.reopenQuietMs / 1000} s sin anuncios", due.reopenQuietMs)
            } else if (!discovery.isOpen && System.nanoTime() - l.lastOpenRetryNanos >= OPEN_RETRY_NS) {
                // Una reapertura falló: se reintenta (como mucho cada 5 s) sin esperar a otro silencio entero.
                reopenDiscovery(l, "el socket UDP no está abierto", -1)
            }
            if (due.diag) diagnose(l)
        } catch (e: Exception) {
            log.e(TAG, "error en la vigilancia del enlace", e)
        }
    }

    private fun reopenDiscovery(l: Loss, why: String, quietMs: Long) {
        l.lastOpenRetryNanos = System.nanoTime()
        if (discovery.reopen(why)) {
            try {
                listener.onDiscoveryReopened(quietMs)
            } catch (e: Exception) {
                log.e(TAG, "excepción en onDiscoveryReopened", e)
            }
        }
    }

    /** Una línea de diagnóstico de la espera (con el ping de la app, si lo hay: puede tardar ~1 s). */
    private fun diagnose(l: Loss) {
        val where = synchronized(lock) {
            val a = attempt
            when {
                a == null -> if (reservingReclaim || reserving) "abriendo intento" else "buscando"
                a.session != null -> "con sesión"
                a.reclaim -> "re-acogida en el puerto ${a.server.port}"
                else -> "conectando en el puerto ${a.server.port}"
            }
        }
        val reach = recovery.carReachable?.let { probe ->
            val ok = try {
                probe(l.car.address)
            } catch (e: Exception) {
                log.w(TAG, "no se pudo comprobar si ${l.car.host} responde: ${e.message}")
                null
            }
            " · coche en la zona Wi-Fi: " + when (ok) {
                true -> "sí"
                false -> "no"
                null -> "?"
            } + " (ping)"
        } ?: ""
        if (synchronized(lock) { closed || loss !== l }) return // cerrado (o el coche volvió) durante el ping
        val waited = (System.nanoTime() - l.watch.startNanos) / 1_000_000
        log.i(TAG, "esperando al coche tras ${l.reason.kind} (${seconds(waited)} s): ${counts(l)} · $where$reach")
    }

    private fun counts(l: Loss): String =
        "${discovery.datagramsReceived - l.datagrams0} datagramas · ACK ${discovery.solicitedAcksSent - l.solicited0} pedidos / " +
            "${discovery.unsolicitedAcksSent - l.unsolicited0} no pedidos · ${tcpAccepts.get() - l.tcp0} TCP · " +
            "reaperturas UDP ${discovery.reopenCount - l.reopens0}"

    /** La sesión de [a] empieza tras la pérdida [l]: por dónde volvió el coche. */
    private fun carBack(l: Loss, a: Attempt, acceptNanos: Long, unsolicitedAt: Long) {
        val how = if (!a.reclaim) {
            CarReturn.BROADCAST
        } else {
            CarReturn.classify(acceptNanos, a.lastBroadcastNanos, unsolicitedAt, config.acceptTimeoutMs, recovery.ackReactionMs)
        }
        val afterMs = (acceptNanos - l.watch.startNanos) / 1_000_000
        fun ago(t: Long) = if (t == 0L) "ninguno" else "hace ${(acceptNanos - t) / 1_000_000} ms"
        val detail = if (a.reclaim) {
            "re-acogida en el puerto ${a.server.port}; último anuncio ${ago(a.lastBroadcastNanos)}, último ACK no pedido ${ago(unsolicitedAt)}"
        } else {
            "intento en el puerto ${a.server.port}"
        }
        log.i(TAG, "vuelta del coche tras ${seconds(afterMs)} s: ${how.label} ($detail · desde ${l.reason.kind}: ${counts(l)})")
        try {
            listener.onCarBack(a.car, how, afterMs)
        } catch (e: Exception) {
            log.e(TAG, "excepción en onCarBack", e)
        }
    }

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

    /** Sesión perdida de mala manera: la re-acogida escucha en su puerto. */
    private class Lost(val port: Int, val reason: CloseReason)

    private class Attempt(val car: CarAnnouncement, val server: MirrorServer, val reclaim: Boolean, val acceptTimeoutMs: Long) {
        @Volatile
        var ack: AckHandle? = null

        /** hql: reenvíos arrancados por el último broadcast durante la re-acogida. */
        @Volatile
        var reAck: AckHandle? = null

        @Volatile
        var thread: Thread? = null

        @Volatile
        var session: PhoneSession? = null

        /** `System.nanoTime()` del último ACK (con el lock del enlace). */
        var lastAckNanos = 0L

        /** ACK enviados en este intento. */
        val acks = AtomicInteger()

        /** hql: último broadcast del coche durante la re-acogida (0 = ninguno). */
        @Volatile
        var lastBroadcastNanos = 0L

        fun announcedWithin(now: Long, ms: Long) = lastBroadcastNanos != 0L && now - lastBroadcastNanos < ms * 1_000_000

        fun cancelAcks() {
            ack?.cancel()
            reAck?.cancel()
        }
    }

    /** hql: la espera del coche tras perder una sesión, con las cuentas del descubrimiento al empezar. */
    private class Loss(
        @Volatile var car: CarAnnouncement,
        val reason: CloseReason,
        val watch: LossWatch,
        val datagrams0: Long,
        val solicited0: Long,
        val unsolicited0: Long,
        val tcp0: Int,
        val reopens0: Int,
    ) {
        /** Último intento de reabrir el UDP (hilo de la vigilancia). */
        var lastOpenRetryNanos = 0L
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

        override fun onAckSent(target: InetSocketAddress, mirrorPort: Int, attempt: Int, bytes: ByteArray, unsolicited: Boolean) {
            val a = synchronized(lock) { this@PhoneLink.attempt?.takeIf { it.server.port == mirrorPort } } ?: return
            listener.onAckSent(a.car, mirrorPort, a.acks.incrementAndGet(), unsolicited)
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
                endAttempt(a, reason)
            }
        }
    }

    private companion object {
        const val TAG = "QD/Link"

        /** Reintento de abrir el UDP tras una reapertura fallida. */
        const val OPEN_RETRY_NS = 5_000_000_000L

        /** «7,3» (segundos con una cifra decimal, coma decimal). */
        fun seconds(ms: Long): String = "${ms / 1000},${(ms % 1000) / 100}"
    }
}

/**
 * hql (C4): [SessionListener] que reenvía cada evento a [target], que se puede fijar después de construir la sesión
 * (antes de arrancarla). Sobrescribe **todos** los métodos de forma explícita (un test lo comprueba por reflexión),
 * para que uno nuevo no se pierda en silencio. hql: pública y abierta para el enlace USB del app (el mismo uso fuera de
 * [PhoneLink]: el puente de una sesión necesita la sesión).
 */
open class ForwardingSessionListener(@Volatile var target: SessionListener) : SessionListener {
    override fun onStateChanged(from: SessionState, to: SessionState) = target.onStateChanged(from, to)
    override fun onCarInfo(info: CarInfo, message: ControlMessage) = target.onCarInfo(info, message)
    override fun onVideoSupportRequest(videoFormat: Int, message: ControlMessage) = target.onVideoSupportRequest(videoFormat, message)
    override fun onVideoArgs(args: VideoArgs, message: ControlMessage) = target.onVideoArgs(args, message)
    override fun onVideoControl(play: Boolean, playStatus: Int, message: ControlMessage) = target.onVideoControl(play, playStatus, message)
    override fun onKeyframeRequested(reason: KeyframeReason) = target.onKeyframeRequested(reason)
    override fun onVideoFrameOversized(frame: OversizedFrame) = target.onVideoFrameOversized(frame)
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
