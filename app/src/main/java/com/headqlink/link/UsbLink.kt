package com.headqlink.link

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.R
import dev.qdauto.core.session.CloseReason
import dev.qdauto.core.session.ForwardingSessionListener
import dev.qdauto.core.session.PhoneIdentity
import dev.qdauto.core.session.PhoneSession
import dev.qdauto.core.session.SessionListener
import dev.qdauto.core.session.StreamTransport
import dev.qdauto.core.wire.BlockFraming
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Enlace por cable USB (experimental), como el modo USB de QDLink (spec 01 §7): el coche es el *host* USB y pone el móvil
 * en modo accesorio (AOA) con «Neusoft / QDriveLink / 1»; el móvil abre el accesorio (`UsbManager.openAccessory`) y la
 * sesión de siempre (mismo protocolo 5A5A, handshake, heartbeats, táctil y vídeo) va por el descriptor con la trama de
 * bloques de 512 B ([BlockFraming]: relleno al escribir y lectura en bloques). Sin descubrimiento UDP ni TCP.
 *
 * Lo crea LinkService con el servicio, sea cual sea la conexión elegida:
 * - siempre, el **sondeo** ([UsbProbe], etiqueta HQL/USB): USB_STATE, alimentación y accesorios en cada cambio;
 * - **en espera** (Wi-Fi activo): si aparece un accesorio del coche con permiso, avisa ([Callbacks.onUsbCarPresent]) y
 *   LinkService le da prioridad al cable (para el Wi-Fi y llama a [activate]);
 * - **activo**: abre el accesorio (`getAccessoryList` hasta 5 veces, cada 50 ms, como QDLink; con el permiso del aviso
 *   de conexión o, si no lo hay, pidiéndolo), arranca la sesión con un [QdSessionBridge] (el mismo camino de Android
 *   Auto que por Wi-Fi) y, si la sesión termina con el cable puesto, vuelve a abrirlo con una espera creciente
 *   ([UsbReopenPolicy]: 1, 2, 5, 10, 30, 60 s; solo la reinicia una sesión con `CAR_INFO`). Al quitar el cable
 *   (`USB_ACCESSORY_DETACHED`), perder la alimentación (`ACTION_POWER_DISCONNECTED`, como QDLink) o ver ENODEV/EIO en el
 *   descriptor (accesorio desaparecido: no se reabre hasta que vuelva) cierra la sesión: LinkService pasa a «coche
 *   perdido» y, al volver a enchufarlo, se reanuda al instante.
 *
 * Hilos: los receptores en el principal; abrir y esperar, en `hql-usb`; los eventos de la sesión, en los suyos.
 */
internal class UsbLink(
    private val ctx: Context,
    private val cfg: Config,
    private val callbacks: Callbacks,
) : BridgeHost {
    /** Avisos a LinkService, siempre en el hilo principal. */
    interface Callbacks {
        /** En espera (Wi-Fi activo): hay un accesorio del coche con permiso. El cable tiene prioridad. */
        fun onUsbCarPresent(description: String)

        /** El accesorio del coche se ha abierto (como el primer anuncio del coche por Wi-Fi). */
        fun onCarSeen(name: String)
        fun onCarConnected(detail: String)
        fun onCarSize(detail: String)
        fun onCarLost(reason: String)

        /** Activo: el cable se quitó o el coche dejó de alimentar el puerto. */
        fun onUsbGone(why: String)
    }

    override val linkMode: String get() = Config.LINK_USB
    override val linkLabel: String get() = "cable USB"
    override val isUsb: Boolean get() = true

    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "hql-usb").apply { isDaemon = true } }
    private val book = SessionBook { r -> main.post(r) }
    private val bridges = ConcurrentHashMap<Int, QdSessionBridge>()

    @Volatile
    private var phone: PhoneIdentity? = null

    /** Abriendo el accesorio y con sesiones (si no, solo sondeo y espera). */
    @Volatile
    var active = false
        private set

    @Volatile
    private var stopping = false

    @Volatile
    private var hub: VideoHub? = null

    @Volatile
    private var session: PhoneSession? = null

    /** La última sesión cerrada (para [awaitStopped]). */
    @Volatile
    private var lastSession: PhoneSession? = null

    @Volatile
    private var openedAtMs = 0L

    /** Nada de aperturas hasta este instante (aplicar ajustes). */
    @Volatile
    private var pauseUntilMs = 0L

    /** Solo `hql-usb`: espera creciente entre sesiones fallidas y accesorio desaparecido (ENODEV/EIO). */
    private val reopen = UsbReopenPolicy()

    /** Solo `hql-usb`: ya se dijo en el log que el accesorio desapareció y no se reabre (una línea, no una por intento). */
    private var goneLogged = false

    /**
     * Solo `hql-usb`: la espera creciente tras una sesión manda; hasta este instante (elapsedRealtime) solo abre el
     * reintento programado, no otros avisos (USB_STATE repetido, alimentación). 0 = sin espera.
     */
    private var backoffUntilMs = 0L
    private var backoffLogged = false
    private var retry: ScheduledFuture<*>? = null

    /** Último texto del sondeo de accesorios y de USB_STATE (para no repetir líneas iguales). */
    @Volatile
    private var lastListText = ""
    private var lastStateText = ""
    private var lastAccessoryMode: Boolean? = null

    /** Accesorio para el que ya se pidió permiso (un diálogo, no uno por intento). */
    @Volatile
    private var permissionAskedFor: String? = null
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                UsbProbe.ACTION_USB_STATE -> onUsbState(i)
                UsbManager.ACTION_USB_ACCESSORY_DETACHED -> onDetached(UsbProbe.accessoryFrom(i))
                Intent.ACTION_POWER_CONNECTED -> {
                    UsbProbe.log("alimentación conectada (ACTION_POWER_CONNECTED) · ${UsbProbe.power(ctx)}")
                    lookForCar("alimentación conectada", expectAccessory = false)
                }
                Intent.ACTION_POWER_DISCONNECTED -> onPowerLost()
                ACTION_PERMISSION -> onPermission(i)
            }
        }
    }

    // ---------------------------------------------------------------- ciclo de vida (hilo principal)

    /** Con el servicio: receptores y sondeo. Empieza en espera (sin abrir nada) hasta [activate]. */
    fun start() {
        if (registered) return
        registered = true
        UsbProbe.logState(ctx, "servicio en marcha")
        val filter = IntentFilter().apply {
            addAction(UsbProbe.ACTION_USB_STATE)
            addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(ACTION_PERMISSION)
        }
        try {
            ContextCompat.registerReceiver(ctx, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        } catch (e: RuntimeException) {
            UsbProbe.warn("no se pudo escuchar el estado USB: $e")
        }
        lookForCar("arranque del servicio", expectAccessory = false)
    }

    /** El cable pasa a ser el enlace: abrir el accesorio del coche (y pedir permiso si hace falta). */
    fun activate(hub: VideoHub, why: String) {
        this.hub = hub
        if (phone == null) phone = SessionConfigs.phoneIdentity(ctx, cfg)
        if (!active) {
            active = true
            UsbProbe.step("enlace por cable activo ($why): busco el accesorio del coche (bloques de ${BlockFraming.BLOCK} B, sin UDP ni TCP)")
        }
        network(LinkState.Level.BUSY, Str.get(R.string.hql_usb_net_waiting))
        // Activar no vale como «el accesorio volvió» (puede venir de un USB_STATE viejo): la espera sí empieza de cero.
        submit { resetBackoff(); tryOpen(why, requestPermission = true, preferred = null) }
    }

    /** Vuelta al Wi-Fi: se cierra la sesión por cable y se queda en espera (sondeo). */
    fun deactivate(why: String) {
        if (!active) return
        active = false
        UsbProbe.step("enlace por cable en espera ($why)")
        submit { retry?.cancel(false) }
        session?.closeLocal("cable USB en espera: $why")
    }

    /** El sistema lanzó la actividad del accesorio (cable conectado): abrirlo ya, con el permiso que trae. */
    fun offer(acc: UsbAccessory?, why: String) {
        book.noteBroadcast(System.nanoTime())
        if (!active) return
        submit {
            // Con el accesorio del aviso (USB_ACCESSORY_ATTACHED) el coche lo ha vuelto a poner: se puede abrir otra vez.
            if (acc != null) accessoryArrived("USB_ACCESSORY_ATTACHED") else resetBackoff()
            tryOpen(why, requestPermission = true, preferred = acc)
        }
    }

    /** Con el servicio: todo cerrado (sesión, receptores, hilo). */
    fun stop() {
        stopping = true
        active = false
        if (registered) {
            registered = false
            try {
                ctx.unregisterReceiver(receiver)
            } catch (_: IllegalArgumentException) {
            }
        }
        session?.closeLocal("servicio parado")
        exec.shutdownNow()
    }

    /** Tras [stop]: espera (como mucho [timeoutMs]) a que la última sesión deje su resumen. */
    fun awaitStopped(timeoutMs: Long): Boolean {
        val s = session ?: lastSession ?: return true
        return s.awaitTermination(timeoutMs)
    }

    /** Cierra la sesión por cable (si se reabre, la espera de [pauseReconnect]). */
    fun closeSession(why: String) {
        session?.closeLocal(why)
    }

    /** Nada de aperturas durante [ms]. */
    fun pauseReconnect(ms: Long) {
        pauseUntilMs = SystemClock.elapsedRealtime() + ms
    }

    /** Ajustes aplicados con el cable: el vídeo se rehace en [delayMs] sin cerrar la sesión (hilo principal). */
    fun restartVideo(delayMs: Long, why: String) {
        main.postDelayed({
            if (!stopping) bridges[book.currentId]?.restartVideo(why)
        }, delayMs)
    }

    fun isConnected(): Boolean = book.currentId != 0

    fun isBusy(): Boolean = session?.isClosed == false

    fun portFor(id: Int): SessionPort? = bridges[id]?.port

    fun describeCurrent(): String = bridges[book.currentId]?.describeLink() ?: ""

    override fun onCarSize(bridge: QdSessionBridge, detail: String) {
        if (!book.isCurrentOrNone(bridge.session.id)) return
        main.post { if (!stopping) callbacks.onCarSize(detail) }
    }

    // ---------------------------------------------------------------- receptores (hilo principal)

    private fun onUsbState(i: Intent) {
        val text = UsbProbe.describeState(i)
        if (text != lastStateText) {
            lastStateText = text
            UsbProbe.log("USB_STATE: $text")
        }
        val mode = UsbProbe.accessoryMode(i)
        val changed = mode != lastAccessoryMode
        lastAccessoryMode = mode
        if (mode == true && changed) {
            book.noteBroadcast(System.nanoTime())
            // Paso nuevo a modo accesorio: si desapareció con ENODEV/EIO, ya se puede volver a abrir.
            submit { accessoryArrived("USB_STATE accessory=true") }
        }
        // Al pasar a modo accesorio la lista puede tardar un poco: hasta 5 intentos (como QDLink).
        if (changed || mode == true) lookForCar("USB_STATE", expectAccessory = mode == true)
        if (mode == false && changed && active && (session != null || openedAtMs != 0L)) {
            onDetached(null)
        }
    }

    private fun onDetached(acc: UsbAccessory?) {
        if (acc != null) UsbProbe.step("accesorio desconectado: ${UsbProbe.describe(acc)}")
        if (acc != null && !UsbProbe.isNeusoft(acc)) return
        lastListText = ""
        val s = session
        if (s != null && !s.isClosed) {
            UsbProbe.step("cierro la sesión S${s.id}: cable USB desconectado")
            s.closeLocal("cable USB desconectado")
        }
        submit { retry?.cancel(false) }
        if (openedAtMs != 0L || s != null) {
            openedAtMs = 0L
            if (active && !stopping) callbacks.onUsbGone("accesorio desconectado")
        }
    }

    /** Como QDLink: sin alimentación por el cable se cierra la sesión. Si el accesorio sigue, se vuelve a abrir. */
    private fun onPowerLost() {
        UsbProbe.log("alimentación desconectada (ACTION_POWER_DISCONNECTED) · ${UsbProbe.power(ctx)}")
        val s = session
        if (s != null && !s.isClosed) {
            UsbProbe.step("cierro la sesión S${s.id}: el cable dejó de alimentar el móvil (como QDLink)")
            s.closeLocal("cable USB sin alimentación")
        }
        // ¿Sigue el accesorio? Si sí, tryOpen lo vuelve a abrir; si no, llegará USB_ACCESSORY_DETACHED.
        submitDelayed(POWER_RECHECK_MS) {
            val still = UsbProbe.accessories(ctx).any { UsbProbe.isNeusoft(it) }
            UsbProbe.log("tras perder la alimentación: accesorio del coche ${if (still) "sigue (lo vuelvo a abrir)" else "fuera"}")
            if (still) {
                if (active) tryOpen("el accesorio sigue tras perder la alimentación", requestPermission = false, preferred = null)
            } else {
                main.post { if (openedAtMs != 0L) onDetached(null) }
            }
        }
    }

    private fun onPermission(i: Intent) {
        val granted = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
        val acc = UsbProbe.accessoryFrom(i)
        UsbProbe.step("permiso para ${UsbProbe.shortName(acc)}: ${if (granted) "concedido" else "denegado"}")
        permissionAskedFor = null
        if (granted) {
            submit { tryOpen("permiso concedido", requestPermission = false, preferred = acc) }
        } else {
            network(LinkState.Level.ERROR, Str.get(R.string.hql_usb_net_denied))
        }
    }

    // ---------------------------------------------------------------- apertura (hilo hql-usb)

    /** Mira los accesorios: en espera avisa si está el del coche con permiso; activo, lo abre. */
    private fun lookForCar(why: String, expectAccessory: Boolean) = submit {
        val list = accessoryList(if (expectAccessory) OPEN_TRIES else 1)
        val car = pick(list)
        if (!active) {
            if (car != null && !reopen.mayOpen()) {
                // Sigue en la lista tras un ENODEV/EIO (lista vieja mientras se quita el cable): no se le da prioridad.
                logGoneOnce("en espera")
            } else if (car != null && UsbProbe.hasPermission(ctx, car)) {
                UsbProbe.step("accesorio del coche presente (${UsbProbe.shortName(car)}) con permiso: el cable tiene prioridad sobre el Wi-Fi")
                main.post { if (!stopping && !active) callbacks.onUsbCarPresent(UsbProbe.shortName(car)) }
            } else if (car != null) {
                UsbProbe.log(
                    "accesorio del coche presente (${UsbProbe.shortName(car)}) sin permiso para HeadQLink: lo tendrá otra app (¿QDLink?) " +
                        "o hay que elegir HeadQLink al enchufar el cable",
                )
            }
            return@submit
        }
        tryOpen(why, requestPermission = true, preferred = car)
    }

    private fun tryOpen(why: String, requestPermission: Boolean, preferred: UsbAccessory?, fromRetry: Boolean = false) {
        if (!active || stopping) return
        val cur = session
        if (cur != null && !cur.isClosed) return
        if (!fromRetry && SystemClock.elapsedRealtime() < backoffUntilMs) {
            if (!backoffLogged) {
                backoffLogged = true
                UsbProbe.log("espera creciente en curso: no abro por «$why» (lo hará el reintento programado)")
            }
            return
        }
        if (fromRetry) backoffUntilMs = 0L
        if (!reopen.mayOpen()) {
            logGoneOnce(why)
            network(LinkState.Level.BUSY, Str.get(R.string.hql_usb_net_waiting))
            return
        }
        val wait = pauseUntilMs - SystemClock.elapsedRealtime()
        if (wait > 0) {
            scheduleRetry(wait + 50, "reconexión en pausa")
            return
        }
        val list = accessoryList(OPEN_TRIES)
        // Solo lo que dice el sistema: el accesorio de un Intent ([preferred]) solo sirve para el log.
        val acc = pick(list)
        if (acc == null && preferred != null) UsbProbe.log("el accesorio del aviso (${UsbProbe.shortName(preferred)}) no está en getAccessoryList")
        if (acc == null) {
            val other = list.firstOrNull()
            if (other != null) {
                UsbProbe.log("accesorio conectado que no es el del coche (se espera Neusoft QDriveLink): ${UsbProbe.describe(other)}; no lo abro")
                network(LinkState.Level.ERROR, Str.get(R.string.hql_usb_net_other, UsbProbe.shortName(other)))
            } else {
                UsbProbe.log("sin accesorio del coche ($why): espero a que el coche ponga el móvil en modo accesorio")
                network(LinkState.Level.BUSY, Str.get(R.string.hql_usb_net_waiting))
            }
            return
        }
        if (!UsbProbe.isCarAccessory(acc)) UsbProbe.log("accesorio de Neusoft con otro modelo (${UsbProbe.shortName(acc)}): lo intento igualmente")
        if (!UsbProbe.hasPermission(ctx, acc)) {
            if (requestPermission) askPermission(acc) else UsbProbe.log("sin permiso para ${UsbProbe.shortName(acc)} ($why)")
            return
        }
        open(acc, why)
    }

    /** `getAccessoryList()` hasta [tries] veces, cada 50 ms (como QDLink), registrando la lista cuando cambia. */
    private fun accessoryList(tries: Int): List<UsbAccessory> {
        var list = UsbProbe.accessories(ctx)
        var n = 1
        while (list.isEmpty() && n < tries) {
            try {
                Thread.sleep(OPEN_TRY_GAP_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return list
            }
            list = UsbProbe.accessories(ctx)
            n++
        }
        val text = UsbProbe.describeList(ctx, list)
        if (text != lastListText) {
            lastListText = text
            UsbProbe.log("getAccessoryList (${if (n > 1) "$n intentos" else "1 intento"}): $text")
        }
        return list
    }

    private fun pick(list: List<UsbAccessory>): UsbAccessory? =
        list.firstOrNull { UsbProbe.isCarAccessory(it) } ?: list.firstOrNull { UsbProbe.isNeusoft(it) }

    private fun askPermission(acc: UsbAccessory) {
        val key = UsbProbe.shortName(acc)
        network(LinkState.Level.BUSY, Str.get(R.string.hql_usb_net_permission, key))
        if (permissionAskedFor == key) return
        permissionAskedFor = key
        // El sistema añade al Intent el accesorio y si se concedió: tiene que ser mutable (Android 12+) y explícito (14+).
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(ctx, PERMISSION_REQUEST, Intent(ACTION_PERMISSION).setPackage(ctx.packageName), flags)
        try {
            UsbProbe.usbManager(ctx)?.requestPermission(acc, pi)
            UsbProbe.step("pido permiso para abrir $key (diálogo de Android)")
        } catch (e: RuntimeException) {
            permissionAskedFor = null
            UsbProbe.warn("no se pudo pedir permiso para $key: $e")
        }
    }

    private fun open(acc: UsbAccessory, why: String) {
        val name = UsbProbe.shortName(acc)
        val pfd: ParcelFileDescriptor? = try {
            UsbProbe.usbManager(ctx)?.openAccessory(acc)
        } catch (e: RuntimeException) {
            UsbProbe.warn("openAccessory($name) falló: $e")
            null
        }
        if (pfd == null) {
            UsbProbe.warn("no se pudo abrir $name (¿lo tiene abierto otra app, como QDLink?); reintento en ${RETRY_OPEN_MS / 1000} s")
            network(LinkState.Level.ERROR, Str.get(R.string.hql_usb_net_open_failed, name))
            scheduleRetry(RETRY_OPEN_MS, "reintento de apertura")
            return
        }
        UsbProbe.step("accesorio $name conectado · abierto ($why)")
        book.noteAck(System.nanoTime())
        val fd = pfd.fileDescriptor
        val transport = StreamTransport(FileInputStream(fd), FileOutputStream(fd), "USB", BlockFraming.BLOCK, "USB · $name", pfd)
        var created: PhoneSession? = null
        val forward = object : ForwardingSessionListener(object : SessionListener {}) {
            override fun onClosed(reason: CloseReason) {
                super.onClosed(reason)
                created?.let { ended(it, reason) }
            }
        }
        val config = SessionConfigs.forCurrentSettings(cfg, phone ?: SessionConfigs.phoneIdentity(ctx, cfg), ::portFor)
        val s = PhoneSession(transport, config, forward, QdTrace.qdLog)
        created = s
        val h = hub
        if (h == null) {
            UsbProbe.warn("sin vídeo para la sesión por cable; la cierro")
            s.close()
            return
        }
        val b = QdSessionBridge(ctx, s, this, h, cfg, null)
        bridges[s.id] = b
        forward.target = b
        session = s
        openedAtMs = SystemClock.elapsedRealtime()
        main.post { if (!stopping) callbacks.onCarSeen(name) }
        try {
            s.start()
        } catch (e: Exception) {
            UsbProbe.warn("no arrancó la sesión por cable: $e")
            s.close()
            return
        }
        val start = book.started(s.id, b.endStamp) { s.isClosed }
        if (!start.current) {
            UsbProbe.log("S${s.id} se cerró antes de terminar de empezar")
            return
        }
        b.onStarted(start.gap)
        UsbProbe.step("sesión S${s.id} por cable en marcha (${s.transport.describe()})")
        network(LinkState.Level.OK, Str.get(R.string.hql_usb_net_connected, name))
        val detail = b.describeLink()
        book.confirm(s.id, Runnable { if (!stopping) callbacks.onCarConnected(detail) })
    }

    /**
     * Hilo de eventos de [s]: fin de la sesión. Con el cable puesto, se vuelve a abrir con la espera de [UsbReopenPolicy]
     * (crece mientras las sesiones no traigan CAR_INFO); con ENODEV/EIO en el descriptor, el accesorio ha desaparecido.
     */
    private fun ended(s: PhoneSession, reason: CloseReason) {
        val bridge = bridges.remove(s.id)
        if (session === s) session = null
        lastSession = s
        val text = reason.toString()
        val current = book.ended(s.id, Runnable { if (!stopping) callbacks.onCarLost(text) })
        UsbProbe.step("sesión S${s.id} por cable terminada: $reason")
        if (!current || stopping || !active) return
        val stats = try {
            s.stats()
        } catch (_: RuntimeException) {
            null
        }
        val lived = stats?.uptimeMs ?: (SystemClock.elapsedRealtime() - openedAtMs)
        val carMessages = stats?.messagesReceived ?: 0L
        val carInfo = bridge?.gotCarInfo == true
        val reasonText = describe(reason)
        submit {
            val d = reopen.onSessionEnd(carMessages, carInfo, lived, reasonText)
            network(LinkState.Level.BUSY, Str.get(R.string.hql_usb_net_waiting))
            if (!d.reopen) {
                retry?.cancel(false)
                goneLogged = true
                UsbProbe.warn("S${s.id}: ${d.text}")
                main.post { onAccessoryGone("accesorio desaparecido tras S${s.id} (ENODEV/EIO)") }
                return@submit
            }
            backoffUntilMs = SystemClock.elapsedRealtime() + d.delayMs
            backoffLogged = false
            scheduleRetry(d.delayMs, "S${s.id}: ${d.text}")
        }
    }

    /** Motivo de cierre con toda la cadena de causas (el ENODEV/EIO puede venir en una causa). */
    private fun describe(reason: CloseReason): String {
        val sb = StringBuilder(reason.toString())
        var t: Throwable? = reason.error
        var depth = 0
        while (t != null && depth < 5) {
            sb.append(" | ").append(t.toString())
            t = t.cause
            depth++
        }
        return sb.toString()
    }

    /** Hilo principal: el descriptor dio ENODEV/EIO; como un cable quitado (LinkService vuelve al Wi-Fi si toca). */
    private fun onAccessoryGone(why: String) {
        lastListText = ""
        if (openedAtMs != 0L) {
            openedAtMs = 0L
            if (active && !stopping) callbacks.onUsbGone(why)
        }
    }

    /** Solo `hql-usb`: el coche vuelve a ofrecer el accesorio; si había desaparecido, se puede abrir otra vez. */
    private fun accessoryArrived(how: String) {
        val wasGone = reopen.onAccessoryArrived()
        goneLogged = false
        backoffUntilMs = 0L
        if (wasGone) UsbProbe.step("$how: el accesorio del coche ha vuelto; se puede abrir otra vez")
    }

    /** Solo `hql-usb`: la espera creciente empieza de cero (sin tocar «accesorio desaparecido»). */
    private fun resetBackoff() {
        if (reopen.mayOpen()) reopen.onAccessoryArrived()
        backoffUntilMs = 0L
    }

    private fun logGoneOnce(why: String) {
        if (goneLogged) return
        goneLogged = true
        UsbProbe.log(
            "accesorio desaparecido (ENODEV/EIO): no lo abro ($why) hasta que el coche lo vuelva a poner en modo accesorio " +
                "(USB_STATE accessory=true o USB_ACCESSORY_ATTACHED)",
        )
    }

    private fun scheduleRetry(delayMs: Long, why: String) {
        retry?.cancel(false)
        if (!active || stopping) return
        UsbProbe.log("vuelvo a mirar el accesorio en $delayMs ms ($why)")
        retry = try {
            exec.schedule({ tryOpen(why, requestPermission = false, preferred = null, fromRetry = true) }, delayMs, TimeUnit.MILLISECONDS)
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun network(level: LinkState.Level, text: String) {
        if (active && !stopping) LinkState.setNetwork(level, text)
    }

    private fun submit(task: () -> Unit) {
        try {
            exec.execute {
                try {
                    task()
                } catch (e: Exception) {
                    UsbProbe.warn("error en el enlace por cable: $e")
                }
            }
        } catch (_: RuntimeException) {
            // servicio parado
        }
    }

    private fun submitDelayed(delayMs: Long, task: () -> Unit) {
        try {
            exec.schedule({
                try {
                    task()
                } catch (e: Exception) {
                    UsbProbe.warn("error en el enlace por cable: $e")
                }
            }, delayMs, TimeUnit.MILLISECONDS)
        } catch (_: RuntimeException) {
        }
    }

    companion object {
        /** Respuesta del diálogo de permiso (PendingIntent propio, explícito). */
        const val ACTION_PERMISSION = "com.headqlink.link.USB_PERMISSION"
        private const val PERMISSION_REQUEST = 7

        /** `getAccessoryList()`: como QDLink, hasta 5 intentos cada 50 ms. */
        private const val OPEN_TRIES = 5
        private const val OPEN_TRY_GAP_MS = 50L

        /** openAccessory devolvió null: otro intento en este tiempo. */
        private const val RETRY_OPEN_MS = 2_000L

        /** Tras perder la alimentación, cuándo se mira si el accesorio sigue. */
        private const val POWER_RECHECK_MS = 1_500L
    }
}
