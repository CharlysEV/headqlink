package com.headqlink.link

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Locale
import java.util.concurrent.Executor

/**
 * Radio de la zona Wi-Fi del móvil (viaje del 2026-10-07: 127 cortes de radio sin saber en qué banda iba). Con la
 * conexión «Zona Wi-Fi» se escucha `WifiManager.SoftApCallback` (Android 11+) y se registra una vez por cambio:
 * frecuencia (banda 2,4/5/6 GHz y canal), ancho de canal, estándar Wi-Fi (Android 12+), clientes conectados (Android 12+:
 * por instancia, con la MAC recortada) y desconexiones (Android 13+, con el motivo en Android 14+).
 *
 * No hay API pública para esto: `SoftApCallback`, `SoftApInfo` y `WifiClient` son API de sistema (no están en el SDK),
 * así que va por reflexión y un `Proxy`. En un móvil normal Android suele negarlo (`SecurityException`: hace falta
 * NETWORK_SETTINGS, que solo tienen Ajustes y el sistema; o el método oculto bloqueado): se dice **una vez** y la banda
 * queda «desconocida» en el resumen. Tampoco hay forma de leer sin permisos de sistema el RSSI ni la velocidad del
 * coche como cliente: se dice una vez; cada 30 s de sesión se registra lo que se sepa (banda y clientes).
 *
 * El estado es del proceso (lo lee el resumen de la sesión): una sola zona Wi-Fi, un solo [HotspotRadio] vivo (lo
 * arranca y para [HotspotWatcher] en su hilo `hql-net`). Las funciones puras de abajo las prueban los tests.
 */
internal class HotspotRadio(ctx: Context, private val handler: Handler) {
    private val app = ctx.applicationContext
    private var wifi: WifiManager? = null
    private var callbackClass: Class<*>? = null
    private var callback: Any? = null

    /** Último texto registrado de cada cosa (para registrar solo los cambios). Hilo hql-net. */
    private var lastRadio = ""
    private var lastClients = ""
    private var listSeen = false

    /** Clientes por instancia (Android 12+, puede haber dos con la zona Wi-Fi en dos bandas a la vez). */
    private val clientsByInstance = LinkedHashMap<String, List<String>>()

    fun start() {
        state = State.STARTING
        infos = emptyList()
        clients = -1
        if (Build.VERSION.SDK_INT < 30) {
            unavailable("Android ${Build.VERSION.RELEASE} no tiene el aviso de la zona Wi-Fi (llega en Android 11)")
            return
        }
        try {
            val wm = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wm == null) {
                unavailable("sin WifiManager")
                return
            }
            val cls = Class.forName("android.net.wifi.WifiManager\$SoftApCallback")
            val proxy = Proxy.newProxyInstance(cls.classLoader, arrayOf(cls), InvocationHandler { p, m, a -> dispatch(p, m, a) })
            val register = WifiManager::class.java.getMethod("registerSoftApCallback", Executor::class.java, cls)
            register.invoke(wm, Executor { r -> handler.post(r) }, proxy)
            wifi = wm
            callbackClass = cls
            callback = proxy
            state = State.LISTENING
            log("radio de la zona Wi-Fi: escuchando los cambios (banda, canal, ancho, estándar y clientes)")
        } catch (e: Throwable) {
            unavailable(reason(e))
        }
    }

    fun stop() {
        val wm = wifi
        val cb = callback
        val cls = callbackClass
        wifi = null
        callback = null
        if (wm != null && cb != null && cls != null) {
            try {
                WifiManager::class.java.getMethod("unregisterSoftApCallback", cls).invoke(wm, cb)
            } catch (_: Throwable) {
            }
        }
        state = State.NONE
        infos = emptyList()
        clients = -1
    }

    private fun unavailable(why: String) {
        state = State.UNAVAILABLE
        val line = "radio de la zona Wi-Fi: no se puede leer ($why): ni banda, ni canal, ni clientes, ni la señal del coche. " +
            "Compruébala en Ajustes › Zona Wi-Fi › Banda (mejor 5 GHz)"
        QdTrace.i(TAG, line)
        L.quiet("I", line)
    }

    /** Hilo hql-net (el Executor del registro). */
    private fun dispatch(proxy: Any, m: Method, args: Array<out Any?>?): Any? {
        when (m.name) {
            "equals" -> return proxy === args?.getOrNull(0)
            "hashCode" -> return System.identityHashCode(proxy)
            "toString" -> return "HotspotRadio"
        }
        try {
            val a = args ?: emptyArray()
            when (m.name) {
                "onInfoChanged" -> {
                    val arg = a.getOrNull(0)
                    if (arg is List<*>) {
                        listSeen = true
                        onInfos(arg.mapNotNull { readInfo(it) })
                    } else if (!listSeen) {
                        onInfos(listOfNotNull(readInfo(arg)))
                    }
                }
                "onConnectedClientsChanged" -> {
                    if (a.size >= 2) {
                        val info = readInfo(a[0])
                        val list = (a[1] as? List<*>).orEmpty().map { mac(it) }
                        clientsByInstance[info?.instance ?: info?.let { radioText(it) } ?: "?"] = list
                        onClients()
                    } else if (clientsByInstance.isEmpty()) {
                        val list = (a.getOrNull(0) as? List<*>).orEmpty().map { mac(it) }
                        clientsByInstance["*"] = list
                        onClients()
                    }
                }
                "onClientsDisconnected" -> {
                    val info = readInfo(a.getOrNull(0))
                    for (c in (a.getOrNull(1) as? List<*>).orEmpty()) {
                        val why = call(c, "getDisconnectReason") as? Int
                        log(
                            "radio de la zona Wi-Fi: cliente ${mac(c)} desconectado" + (info?.let { " de " + radioText(it) } ?: "") +
                                (why?.let { " (motivo 802.11 $it)" } ?: ""),
                        )
                    }
                }
                "onBlockedClientConnecting" -> log("radio de la zona Wi-Fi: cliente ${mac(a.getOrNull(0))} rechazado (motivo ${a.getOrNull(1)})")
                "onStateChanged" -> {
                    val st = a.getOrNull(0) as? Int ?: -1
                    if (st == 14) log("radio de la zona Wi-Fi: fallo de la zona Wi-Fi (motivo ${a.getOrNull(1)})")
                }
            }
        } catch (e: Throwable) {
            QdTrace.w(TAG, "radio de la zona Wi-Fi: aviso ${m.name} ilegible: $e")
        }
        return null
    }

    private fun onInfos(list: List<Radio>) {
        val live = list.filter { it.frequencyMhz > 0 }
        infos = live
        val text = if (live.isEmpty()) "sin radio activa" else live.joinToString(" + ") { radioText(it) }
        if (text == lastRadio) return
        lastRadio = text
        log("radio de la zona Wi-Fi: $text")
        CarTrace.note("WIFI", "radio de la zona Wi-Fi: $text")
        if (live.isNotEmpty() && live.all { bandOf(it.frequencyMhz) == BAND_24 }) {
            val w = "la zona Wi-Fi va en 2,4 GHz: más lenta y con más cortes; ponla en 5 GHz (Ajustes › Zona Wi-Fi › Banda)"
            QdTrace.w(TAG, w)
            L.w(w)
        }
    }

    private fun onClients() {
        val all = clientsByInstance.values.flatten()
        clients = all.size
        val byBand = clientsByInstance.entries.filter { it.value.isNotEmpty() }.joinToString("; ") { (inst, macs) ->
            val where = infos.firstOrNull { it.instance == inst }?.let { " en " + bandChannel(it) } ?: ""
            macs.joinToString(", ") + where
        }
        val text = "${all.size} " + (if (all.size == 1) "cliente" else "clientes") + (if (byBand.isNotEmpty()) " ($byBand)" else "")
        if (text == lastClients) return
        lastClients = text
        log("radio de la zona Wi-Fi: $text")
    }

    private fun log(line: String) {
        QdTrace.i(TAG, line)
        L.quiet("I", line)
    }

    /** Una frecuencia, ancho, estándar e instancia de la zona Wi-Fi (0/-1/null = no se sabe). */
    class Radio(val frequencyMhz: Int, val bandwidth: Int, val standard: Int, val instance: String?)

    enum class State { NONE, STARTING, LISTENING, UNAVAILABLE }

    companion object {
        private const val TAG = "HQL/Red"
        const val BAND_24 = "2,4 GHz"
        const val BAND_5 = "5 GHz"
        const val BAND_6 = "6 GHz"
        const val BAND_60 = "60 GHz"
        const val UNKNOWN = "desconocida"
        const val SESSION_LOG_MS = 30_000L

        @Volatile
        var state: State = State.NONE
            private set

        @Volatile
        var infos: List<Radio> = emptyList()
            private set

        /** Clientes conectados (-1 = no se sabe). */
        @Volatile
        var clients: Int = -1
            private set

        private var tickSid = -1
        private var tickAtMs = 0L
        private var signalNoted = false

        /** Banda para el resumen de la sesión: "" si no hay zona Wi-Fi vigilada, [UNKNOWN] si no se sabe. */
        fun summaryBand(): String {
            if (state == State.NONE) return ""
            val bands = infos.mapNotNull { bandOf(it.frequencyMhz) }.distinct()
            return if (bands.isEmpty()) UNKNOWN else joinBands(bands)
        }

        /** Detalle para el bloque de la sesión («canal 36 · 80 MHz · Wi-Fi 6 (802.11ax)»), o "". */
        fun summaryDetail(): String = infos.joinToString(" + ") { detail(it) }

        /**
         * Cada estadística de 5 s de una sesión por la zona Wi-Fi (hilo net-monitor): al empezar la sesión y cada 30 s,
         * una línea con lo que se sepa de la radio del coche. Si no se puede leer, nada (ya se dijo al arrancar).
         */
        @Synchronized
        fun onSessionTick(sid: Int, nowMs: Long) {
            if (state != State.LISTENING) return
            if (sid == tickSid && nowMs - tickAtMs < SESSION_LOG_MS) return
            tickSid = sid
            tickAtMs = nowMs
            if (!signalNoted) {
                signalNoted = true
                val l = "radio del coche: Android no da a una app el RSSI ni la velocidad de cada cliente de la zona Wi-Fi " +
                    "(solo a Ajustes); cada 30 s, banda y clientes, y en las estadísticas de 5 s el TCP con el coche (rtt, cwnd, retrans)"
                QdTrace.i(TAG, l)
                L.quiet("I", l)
            }
            val radio = infos.joinToString(" + ") { radioText(it) }.ifEmpty { "banda aún sin aviso" }
            val c = clients
            val line = "radio del coche S$sid: zona Wi-Fi $radio · " +
                (if (c < 0) "clientes ?" else "$c " + if (c == 1) "cliente" else "clientes")
            QdTrace.i(TAG, line)
            L.quiet("I", line)
        }

        // ------------------------------------------------------------ puras (tests)

        /** Banda de una frecuencia (MHz), o null. */
        fun bandOf(mhz: Int): String? = when (mhz) {
            in 2400..2500 -> BAND_24
            in 4900..5924 -> BAND_5
            in 5925..7125 -> BAND_6
            in 57000..71000 -> BAND_60
            else -> null
        }

        /** Canal de una frecuencia (MHz), o -1. */
        fun channelOf(mhz: Int): Int = when (bandOf(mhz)) {
            BAND_24 -> if (mhz == 2484) 14 else (mhz - 2407) / 5
            BAND_5 -> if (mhz < 5000) (mhz - 4000) / 5 else (mhz - 5000) / 5
            BAND_6 -> if (mhz == 5935) 2 else (mhz - 5950) / 5
            BAND_60 -> (mhz - 56160) / 2160
            else -> -1
        }

        /** `SoftApInfo.CHANNEL_WIDTH_*` en texto, o null. */
        fun widthText(bw: Int): String? = when (bw) {
            1 -> "20 MHz (sin HT)"
            2 -> "20 MHz"
            3 -> "40 MHz"
            4 -> "80 MHz"
            5 -> "80+80 MHz"
            6 -> "160 MHz"
            7 -> "2160 MHz"
            8 -> "4320 MHz"
            9 -> "6480 MHz"
            10 -> "8640 MHz"
            11 -> "320 MHz"
            else -> null
        }

        /** `ScanResult.WIFI_STANDARD_*` en texto, o null. */
        fun standardText(std: Int): String? = when (std) {
            1 -> "802.11a/b/g"
            4 -> "Wi-Fi 4 (802.11n)"
            5 -> "Wi-Fi 5 (802.11ac)"
            6 -> "Wi-Fi 6 (802.11ax)"
            7 -> "802.11ad"
            8 -> "Wi-Fi 7 (802.11be)"
            else -> null
        }

        fun bandChannel(r: Radio): String {
            val band = bandOf(r.frequencyMhz) ?: return "${r.frequencyMhz} MHz"
            val ch = channelOf(r.frequencyMhz)
            return if (ch > 0) "$band canal $ch" else band
        }

        /** «5 GHz canal 36 (5180 MHz) · 80 MHz · Wi-Fi 6 (802.11ax)». */
        fun radioText(r: Radio): String = buildString {
            append(bandChannel(r))
            if (bandOf(r.frequencyMhz) != null) append(" (").append(r.frequencyMhz).append(" MHz)")
            widthText(r.bandwidth)?.let { append(" · ").append(it) }
            standardText(r.standard)?.let { append(" · ").append(it) }
        }

        /** Para el bloque de la sesión: «canal 36 · 80 MHz · Wi-Fi 6 (802.11ax)». */
        fun detail(r: Radio): String = listOfNotNull(
            channelOf(r.frequencyMhz).takeIf { it > 0 }?.let { "canal $it" },
            widthText(r.bandwidth),
            standardText(r.standard),
        ).joinToString(" · ")

        fun joinBands(bands: List<String>): String =
            if (bands.size == 1) bands[0] else bands.joinToString("+") { it.removeSuffix(" GHz") } + " GHz"

        /** MAC recortada a los dos últimos bytes («…:3f:a1»): basta para distinguir clientes sin guardar la entera. */
        fun maskMac(mac: String?): String {
            val parts = mac?.split(':').orEmpty()
            return if (parts.size >= 2) "…:" + parts.takeLast(2).joinToString(":").lowercase(Locale.US) else "?"
        }

        private fun mac(client: Any?): String = maskMac(call(client, "getMacAddress")?.toString())

        private fun readInfo(o: Any?): Radio? {
            if (o == null) return null
            val f = call(o, "getFrequency") as? Int ?: return null
            return Radio(
                f,
                call(o, "getBandwidth") as? Int ?: -1,
                call(o, "getWifiStandard") as? Int ?: -1,
                call(o, "getApInstanceIdentifier") as? String,
            )
        }

        private fun call(o: Any?, name: String): Any? = try {
            o?.javaClass?.getMethod(name)?.invoke(o)
        } catch (_: Throwable) {
            null
        }

        private fun reason(e: Throwable): String {
            val c = if (e is InvocationTargetException) e.targetException ?: e else e
            return when (c) {
                is SecurityException -> "Android pide un permiso de sistema: ${c.message ?: "SecurityException"}"
                is NoSuchMethodException, is ClassNotFoundException -> "API de sistema no accesible para una app (${c.javaClass.simpleName})"
                else -> c.toString()
            }
        }
    }
}
