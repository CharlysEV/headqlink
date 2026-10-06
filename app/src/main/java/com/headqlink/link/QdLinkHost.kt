package com.headqlink.link

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.andrerinas.openheadunit.R
import dev.qdauto.core.discovery.AckPolicy
import dev.qdauto.core.discovery.CarAnnouncement
import dev.qdauto.core.discovery.DiscoveryConfig
import dev.qdauto.core.session.CarReturn
import dev.qdauto.core.session.CloseReason
import dev.qdauto.core.session.LinkState as CoreLinkState
import dev.qdauto.core.session.MirrorServer
import dev.qdauto.core.session.PhoneIdentity
import dev.qdauto.core.session.PhoneLink
import dev.qdauto.core.session.PhoneLinkConfig
import dev.qdauto.core.session.PhoneLinkListener
import dev.qdauto.core.session.PhoneSession
import dev.qdauto.core.session.RecoveryConfig
import java.io.IOException
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Motor QDAuto dentro del fork (qdauto §4.4): dueño del [PhoneLink] del núcleo mientras vive el servicio. Construye su
 * configuración (filtro de pares, espera entre intentos, *bind* a la interfaz del coche, re-ACK, relevo), crea un
 * [QdSessionBridge] por sesión y pasa a LinkService (hilo principal) los eventos de coche visto, conectado y perdido.
 * Si el UDP 18463 está ocupado (QDLink abierto) lo dice y reintenta cada 5 s.
 *
 * Vuelta del coche tras un corte de radio (RecoveryConfig del núcleo, ajustes Config.QD_*; todo activado por defecto):
 * ACK reenviado cada 2 s hasta el TCP, puerto estable, re-acogida (el mismo puerto hasta «Esperar al coche» y ACK no
 * pedidos), reapertura del UDP tras 20 s sin anuncios y diagnóstico cada 10 s con ping a la IP del coche. Tras perder la
 * sesión, cada vez que el enlace vuelve a esperar al coche (o se reabre el UDP) se pide a LinkService que vuelva a coger
 * el MulticastLock.
 */
internal class QdLinkHost(
    private val ctx: Context,
    private val cfg: Config,
    /** Conexión con la que arrancó el servicio (Config.LINK_*): la de las sesiones de este motor, aunque se cambie en marcha. */
    override val linkMode: String,
    private val hub: VideoHub,
    private val callbacks: Callbacks,
) : BridgeHost {
    /** Avisos a LinkService, siempre en el hilo principal. */
    interface Callbacks {
        /** Primer anuncio del coche desde la última sesión. */
        fun onCarSeen(name: String)

        /** Otro anuncio del coche sin sesión (como mucho uno cada HEARD_EVERY_MS): «sin coche» vuelve a contar. */
        fun onCarHeard()
        fun onCarConnected(detail: String)
        fun onCarSize(detail: String)
        fun onCarLost(reason: String)
        fun onLinkError(message: String)

        /** El UDP 18463 ya está abierto (tras un error): la fila «Red» vuelve a lo suyo. */
        fun onLinkReady()

        /** Esperando al coche tras perder la sesión (o UDP reabierto): volver a coger el MulticastLock. */
        fun onRefreshMulticast(why: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private val backoff = QuickBackoff()
    val hotspotMode = Config.LINK_HOTSPOT == linkMode

    /** Prueba: la trama del cable USB sobre el TCP (qdsim --usb-framing); se lee al arrancar el motor. */
    private val usbOverTcp = cfg.qdUsbOverTcp()

    override val linkLabel: String = (if (hotspotMode) "zona Wi-Fi" else "Wi-Fi Direct") + if (usbOverTcp) " (trama USB)" else ""
    override val isUsb: Boolean get() = false
    /** Interfaces del móvil, como mucho un escaneo por segundo (se consultan con cada broadcast). */
    @Volatile
    private var ifaceCache: Pair<Long, List<NetIfaces.Iface>>? = null

    private fun interfaces(): List<NetIfaces.Iface> {
        val now = System.nanoTime()
        val c = ifaceCache
        if (c != null && now - c.first < 1_000_000_000L) return c.second
        val list = NetIfaces.scan()
        ifaceCache = now to list
        return list
    }

    @Volatile
    private var startFailed = false

    private val peer = PeerPolicy(hotspotMode, cfg.peerStrict(), interfaces = { interfaces() }, log = { msg ->
        L.i("pares: $msg")
        QdTrace.i("HQL/Pares", msg)
    })
    private val bridges = ConcurrentHashMap<Int, QdSessionBridge>()
    private val phone: PhoneIdentity = SessionConfigs.phoneIdentity(ctx, cfg)

    @Volatile
    private var link: PhoneLink? = null

    /** El enlace cerrado por [stop], para [awaitStopped]. */
    @Volatile
    private var closedLink: PhoneLink? = null

    @Volatile
    private var stopping = false

    /**
     * Sesión actual y hueco de reconexión. Inicio (hilo de aceptación de la nueva) y fin (hilo de eventos de la vieja) se
     * cruzan en los relevos y las reconexiones rápidas: los cambios de la actual y sus avisos a LinkService van juntos.
     */
    private val book = SessionBook { r -> main.post(r) }

    /** Se perdió una sesión y el coche aún no ha vuelto (para el MulticastLock). */
    @Volatile
    private var lostPending = false

    /** Ya se avisó de "coche detectado" desde la última sesión (un aviso, no uno por broadcast). */
    @Volatile
    private var seenNotified = false

    /** Último aviso de «coche anunciado» a LinkService (ms monótonos), para no mandar uno por broadcast. */
    @Volatile
    private var lastHeardMs = Long.MIN_VALUE / 4

    @Volatile
    var lastCar: CarAnnouncement? = null
        private set

    /** Hilo principal: reintento de arranque (18463 ocupado). */
    private val retryStart = Runnable { start() }

    /** Abre el UDP y empieza a buscar al coche; si el puerto está ocupado, aviso y reintento cada 5 s. */
    fun start() {
        if (stopping || link != null) return
        val l = buildLink()
        try {
            l.start()
            link = l
            LinkState.setUdpBusy(false)
            QdTrace.i("HQL/Enlace", "motor QDAuto escuchando ($linkLabel) · móvil $phone")
            if (usbOverTcp) {
                L.w("prueba: trama del cable USB (bloques de 512 B) sobre el TCP del Wi-Fi; solo para qdsim --usb-framing, el coche no la entiende")
                QdTrace.w("HQL/USB", "prueba qd_usb_over_tcp: las sesiones por Wi-Fi usan la trama del cable USB (relleno a 512 B y lectura en bloques)")
            }
            if (startFailed) {
                startFailed = false
                L.i("motor QDAuto: el UDP 18463 ya está libre; escuchando")
                main.post { if (!stopping) callbacks.onLinkReady() }
            }
        } catch (e: IOException) {
            l.close()
            val busy = e is BindException || e.message?.contains("in use", ignoreCase = true) == true
            val msg = if (busy) Str.get(R.string.hql_udp_busy) else "UDP ${DiscoveryConfig().port}: ${e.message}"
            if (!startFailed) L.e("motor QDAuto: no se pudo abrir el UDP 18463; reintento cada 5 s", e)
            startFailed = true
            LinkState.setUdpBusy(busy)
            LinkState.setNetwork(LinkState.Level.ERROR, msg)
            main.post { callbacks.onLinkError(msg) }
            main.removeCallbacks(retryStart)
            main.postDelayed(retryStart, RETRY_START_MS)
        }
    }

    fun stop() {
        stopping = true
        main.removeCallbacks(retryStart)
        val l = link ?: return
        link = null
        l.close()
        closedLink = l
    }

    /**
     * Tras [stop]: espera como mucho [timeoutMs] a que la sesión termine y deje su resumen (para que el del viaje la
     * incluya). No llamar desde un callback de la sesión.
     */
    fun awaitStopped(timeoutMs: Long): Boolean {
        val l = closedLink ?: return true
        return try {
            l.awaitTermination(timeoutMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    /** Cierra la sesión (y el intento) en curso con un motivo (`LOCAL`). */
    fun closeSession(why: String) {
        link?.closeSession(why)
    }

    /** Nada de intentos nuevos durante [ms] (p. ej. mientras AA renegocia tras aplicar ajustes). */
    fun pauseReconnect(ms: Long) {
        backoff.pause(ms)
        L.i("motor QDAuto: reconexión en pausa $ms ms")
    }

    /** Hay una sesión con el coche ahora mismo. */
    fun isConnected(): Boolean = link?.currentSession != null

    /** Hay una sesión o un intento (ACK enviado, esperando el TCP). */
    fun isBusy(): Boolean = link?.let { it.currentSession != null || it.isConnecting } ?: false

    /** La sesión [sid] es la actual (o no hay ninguna): para no borrar la información de una sesión más nueva. */
    fun isCurrentOrNone(sid: Int): Boolean = book.isCurrentOrNone(sid)

    /** Puerto de la sesión [id] (para la puerta de escritura del freno). */
    fun portFor(id: Int): SessionPort? = bridges[id]?.port

    /** Para la tarjeta «Conexión» del coche y el log: coche e interfaz de la sesión actual. */
    fun describeCurrent(): String = bridges[book.currentId]?.describeLink() ?: ""

    private fun buildLink(): PhoneLink {
        val qdlink = "qdlink" == cfg.qdPhoneInfo()
        val waitMs = cfg.carWaitMs()
        val ping: ((InetAddress) -> Boolean?)? = if (cfg.qdCarPing()) { ip -> CarPing.reachable(ip) } else null
        val recovery = RecoveryConfig(
            stableMirrorPort = cfg.qdStablePort(),
            reclaim = cfg.qdReclaim(),
            // La re-acogida y la vigilancia duran lo que se espera al coche (después LinkLifecycle lo cierra todo).
            reclaimWindowMs = waitMs,
            watchMs = waitMs,
            udpRefreshSilenceMs = if (cfg.qdUdpRefresh()) 20_000 else 0,
            carReachable = ping,
        )
        L.i("motor QDAuto: vuelta tras un corte: " + cfg.qdRecoverySummary() + " · espera " + waitMs / 1000 + " s")
        val config = PhoneLinkConfig(
            discovery = DiscoveryConfig(
                deviceName = if (qdlink) "" else cfg.deviceName(),
                deviceUuid = if (qdlink) "" else cfg.deviceUuid(),
                // Cada 2 s hasta el TCP (el C10 se anunció una vez tras un corte y nunca conectó con un ACK suelto).
                ackPolicy = if (cfg.qdAckResend()) AckPolicy.UNTIL_CONNECTED else AckPolicy.QDLINK,
            ),
            mirrorPort = MirrorServer.RANDOM_PORT,
            acceptTimeoutMs = 20_000,
            autoConnect = true,
            reconnect = true,
            retryDelayMs = 0,
            carFilter = { car -> peer.acceptBroadcast(car) && backoff.canAttempt() },
            sessionConfigFor = { _ -> SessionConfigs.forCurrentSettings(cfg, phone, ::portFor) },
            acceptFilter = { from, car -> peer.acceptTcp(from, car) },
            mirrorBindAddressFor = { car -> peer.bindAddressFor(car) },
            // ACK en cada broadcast mientras el coche no conecta (como el fork), como mucho cada 400 ms.
            reAckIntervalMs = 400,
            // El C10 solo se anuncia sin sesión: un broadcast con la sesión abierta y callada es que la dio por muerta.
            supersedeOnRebroadcast = cfg.qdSupersede(),
            recovery = recovery,
            blockFraming = usbOverTcp,
        )
        return PhoneLink(config, LinkEvents(), object : dev.qdauto.core.session.SessionListener {}, QdTrace.qdLog) { s ->
            QdSessionBridge(ctx, s, this, hub, cfg, lastCar).also { bridges[s.id] = it }
        }
    }

    /** Lo llama el puente con el tamaño del coche (CAR_INFO). */
    override fun onCarSize(bridge: QdSessionBridge, detail: String) {
        // CAR_INFO puede llegar antes de que onSessionStarted fije la sesión actual (el coche responde en ~13 ms).
        if (!isCurrentOrNone(bridge.session.id)) return
        main.post { if (!stopping) callbacks.onCarSize(detail) }
    }

    private inner class LinkEvents : PhoneLinkListener {
        override fun onCarFound(car: CarAnnouncement) = seen(car, true)

        override fun onCarSeen(car: CarAnnouncement) = seen(car, false)

        private fun seen(car: CarAnnouncement, first: Boolean) {
            CarTrace.udp(car.host, car.rawJson)
            // Sin sesión viva (también con la anterior ya cerrada aunque su cierre aún no haya terminado).
            val live = link?.currentSession != null
            if (!live) book.noteBroadcast(System.nanoTime())
            if (first) QdTrace.i("HQL/Enlace", "coche anunciado: $car")
            if (!seenNotified && !live && !stopping) {
                seenNotified = true
                lastHeardMs = android.os.SystemClock.elapsedRealtime()
                main.post { if (!stopping) callbacks.onCarSeen(car.name) }
                return
            }
            // Cada anuncio sin sesión (aunque el TCP no llegue): «sin coche» vuelve a contar (LinkLifecycle.carHeard).
            if (!live && !stopping) {
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastHeardMs >= HEARD_EVERY_MS) {
                    lastHeardMs = now
                    main.post { if (!stopping) callbacks.onCarHeard() }
                }
            }
        }

        override fun onAckSent(car: CarAnnouncement, mirrorPort: Int, attempt: Int, unsolicited: Boolean) {
            // El hueco de reconexión cuenta el primer ACK que contesta a un anuncio (los no pedidos salen desde el corte).
            if (!unsolicited) book.noteAck(System.nanoTime())
            CarTrace.tx("UDP ${car.host}:18464", "Broadcast_ACK #$attempt MirrorPort=$mirrorPort" + if (unsolicited) " (no pedido)" else "")
        }

        override fun onLinkStateChanged(state: CoreLinkState) {
            // Tras perder la sesión, cada vez que el enlace vuelve a esperar al coche: MulticastLock otra vez.
            if (lostPending && !stopping && (state == CoreLinkState.SEARCHING || state == CoreLinkState.RECOVERING)) {
                main.post { if (!stopping) callbacks.onRefreshMulticast("enlace $state tras perder la sesión") }
            }
        }

        // El núcleo ya lo dice en el log unificado (QD/Link); aquí, al diario del coche.
        override fun onReclaimStarted(car: CarAnnouncement, mirrorPort: Int, reason: CloseReason) =
            CarTrace.note("REACOGIDA", "tras ${reason.kind} · puerto $mirrorPort · coche ${car.host}")

        override fun onCarBack(car: CarAnnouncement, how: CarReturn, afterMs: Long) =
            CarTrace.note("VUELTA", "tras $afterMs ms: ${how.label}")

        override fun onDiscoveryReopened(quietMs: Long) {
            if (!stopping) main.post { if (!stopping) callbacks.onRefreshMulticast("UDP reabierto") }
        }

        override fun onConnecting(car: CarAnnouncement, mirrorPort: Int) {
            lastCar = car
            val iface = NetIfaces.find(NetIfaces.scan(), car.address)
            QdTrace.i("HQL/Enlace", "conectando con ${car.host} (${car.name}) en el puerto $mirrorPort · interfaz ${iface ?: "?"}")
        }

        override fun onSessionStarted(session: PhoneSession) {
            lostPending = false
            val b = bridges[session.id]
            val start = book.started(session.id, b?.endStamp) { session.isClosed }
            seenNotified = false
            if (!start.current) {
                // Se cerró nada más empezar: su fin no la encontró como actual, así que tampoco hay «conectado».
                QdTrace.i("HQL/Enlace", "S${session.id} se cerró antes de terminar de empezar")
                return
            }
            b?.onStarted(start.gap)
            val detail = b?.describeLink() ?: (session.remoteAddress?.toString() ?: "")
            book.confirm(session.id, Runnable { if (!stopping) callbacks.onCarConnected(detail) })
        }

        override fun onSessionEnded(session: PhoneSession, reason: CloseReason) {
            val b = bridges.remove(session.id)
            val text = reason.toString()
            val current = book.ended(session.id, Runnable { if (!stopping) callbacks.onCarLost(text) })
            QdTrace.i("HQL/Enlace", "S${session.id} terminada: $reason" + if (current) "" else " (no era la sesión actual)")
            if (!current) return
            seenNotified = false
            lostPending = true
            backoff.onSessionEnded(b?.reachedStreaming == true)
        }

        override fun onAcceptTimeout(car: CarAnnouncement) {
            backoff.onAcceptTimeout()
            peer.noteAcceptTimeout()
            L.w("el coche ${car.host} no conectó por TCP a tiempo tras el ACK (o no volvió en la re-acogida)")
        }

        override fun onConnectionRejected(from: InetSocketAddress?, car: CarAnnouncement) {
            L.w("conexión TCP de $from rechazada: no es el coche (${car.host})")
        }

        override fun onExtraConnection(from: InetSocketAddress?) {
            L.w("conexión TCP extra de $from con la sesión activa; se cierra")
        }

        override fun onSessionSuperseded(old: PhoneSession, car: CarAnnouncement) {
            // El fin de la vieja (para el hueco de la nueva) es ahora, con el broadcast que lo provoca.
            book.superseded(bridges[old.id]?.endStamp, System.nanoTime())
            L.w("el coche ${car.host} se ha vuelto a anunciar con S${old.id} abierta: la doy por muerta y conecto otra vez")
        }

        override fun onError(message: String, error: Throwable?) {
            L.e("motor QDAuto: $message", error)
        }
    }

    private companion object {
        const val RETRY_START_MS = 5_000L

        /** Avisos de «coche anunciado» a LinkService: como mucho uno cada tanto (el coche se anuncia cada ~1 s). */
        const val HEARD_EVERY_MS = 2_000L
    }
}
