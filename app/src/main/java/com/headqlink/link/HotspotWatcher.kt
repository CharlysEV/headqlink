package com.headqlink.link

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.provider.Settings
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.SoftApState
import com.andrerinas.openheadunit.utils.SoftApStateReader

/**
 * Estado de la zona Wi-Fi del móvil en el modo «Punto de acceso del móvil» (qdauto §5.5). Tres fuentes combinadas:
 * 1. el broadcast del sistema `WIFI_AP_STATE_CHANGED` (`wifi_state`: 10 desactivándose, 11 desactivada, 12
 *    activándose, 13 activada, 14 fallo);
 * 2. la API oculta `getWifiApState` por reflexión ([SoftApStateReader]: `UNKNOWN` si está bloqueada);
 * 3. un escaneo de interfaces cada 3 s en `hql-net`: alguna de zona Wi-Fi levantada con IPv4 privada.
 *
 * `ON` si cualquiera lo dice; `OFF` si (1) dice 11/14, o (2) dice que no y (3) no encuentra nada; si no, `UNKNOWN`.
 * Cada cambio se publica en [LinkState] (fila «Red»), en el log unificado y en el diario del coche, y se avisa a
 * [listener] en el hilo principal. Android no deja a una app normal encender la zona Wi-Fi: lo hace el usuario.
 */
internal class HotspotWatcher(private val ctx: Context, private val listener: Listener) {
    enum class State { ON, OFF, UNKNOWN }

    fun interface Listener {
        fun onHotspotState(state: State, detail: String)
    }

    /** Resultado de una comprobación: estado, interfaz de la zona Wi-Fi (si se vio) y de dónde sale. */
    class Probe(val state: State, val iface: NetIfaces.Iface?, val why: String)

    private val app = ctx.applicationContext
    private val thread = HandlerThread("hql-net")
    private var handler: Handler? = null
    private val main = Handler(android.os.Looper.getMainLooper())

    /** Último `wifi_state` del broadcast (-1 = ninguno). */
    @Volatile
    private var apState = -1

    @Volatile
    var state: State = State.UNKNOWN
        private set

    @Volatile
    var iface: NetIfaces.Iface? = null
        private set

    private var lastIfaces: List<NetIfaces.Iface> = emptyList()
    private var started = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            apState = i.getIntExtra("wifi_state", -1)
            QdTrace.i("HQL/Red", "WIFI_AP_STATE_CHANGED wifi_state=$apState")
            handler?.post { evaluate("aviso del sistema") }
        }
    }

    private val scan = object : Runnable {
        override fun run() {
            evaluate(null)
            handler?.postDelayed(this, SCAN_MS)
        }
    }

    fun start() {
        if (started) return
        started = true
        thread.start()
        val h = Handler(thread.looper)
        handler = h
        val filter = IntentFilter(ACTION_AP_STATE)
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(receiver, filter, null, h, Context.RECEIVER_NOT_EXPORTED)
            } else {
                app.registerReceiver(receiver, filter, null, h)
            }
        } catch (e: RuntimeException) {
            L.w("zona Wi-Fi: sin aviso del sistema (${e.message}); se usa solo el escaneo")
        }
        h.post(scan)
    }

    /** Vuelve a publicar el estado actual (p. ej. tras un error que ocupó la fila «Red»). */
    fun republish() {
        handler?.post {
            val p = Probe(state, iface, "republicado")
            LinkState.setNetwork(level(p.state), text(p))
        }
    }

    fun stop() {
        if (!started) return
        started = false
        handler?.removeCallbacksAndMessages(null)
        try {
            app.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
        }
        thread.quitSafely()
    }

    /** Hilo hql-net. [reason] solo para el log de un cambio provocado por el sistema. */
    private fun evaluate(reason: String?) {
        val ifaces = NetIfaces.scan()
        if (ifaces != lastIfaces) {
            lastIfaces = ifaces
            QdTrace.i("HQL/Red", "interfaces: " + NetIfaces.describe(ifaces))
        }
        val p = combine(apState, readReflection(app), ifaces)
        val changed = p.state != state || p.iface != iface
        state = p.state
        iface = p.iface
        if (!changed) return
        val text = text(p)
        QdTrace.i("HQL/Red", "zona Wi-Fi ${p.state} (${p.why}${reason?.let { ", $it" } ?: ""}): $text")
        CarTrace.note("WIFI", "zona Wi-Fi ${p.state}: $text")
        LinkState.setNetwork(level(p.state), text)
        main.post { listener.onHotspotState(p.state, text) }
    }

    companion object {
        const val ACTION_AP_STATE = "android.net.wifi.WIFI_AP_STATE_CHANGED"
        private const val SCAN_MS = 3_000L

        /** Combinación de las tres fuentes (pura: la prueban los tests). */
        @JvmStatic
        fun combine(apState: Int, reflected: SoftApState, ifaces: List<NetIfaces.Iface>): Probe {
            val hs = NetIfaces.hotspot(ifaces)
            return when {
                apState == 13 -> Probe(State.ON, hs, "sistema: activada")
                reflected == SoftApState.ENABLED -> Probe(State.ON, hs, "getWifiApState: activada")
                hs != null -> Probe(State.ON, hs, "interfaz ${hs.name}")
                apState == 11 || apState == 14 -> Probe(State.OFF, null, "sistema: " + if (apState == 11) "desactivada" else "fallo")
                reflected == SoftApState.NOT_ENABLED -> Probe(State.OFF, null, "getWifiApState: no activada y sin interfaz")
                else -> Probe(State.UNKNOWN, null, "sin datos")
            }
        }

        /** Comprobación puntual (pantallas de inicio y configuración). Lenta: no en el hilo principal en bucle. */
        @JvmStatic
        fun probe(ctx: Context): Probe = probe(ctx, -1)

        /** Ídem con el último `wifi_state` de [ACTION_AP_STATE] que vio quien llama (-1 = ninguno). */
        @JvmStatic
        fun probe(ctx: Context, apState: Int): Probe = combine(apState, readReflection(ctx.applicationContext), NetIfaces.scan())

        @JvmStatic
        fun level(s: State): LinkState.Level = when (s) {
            State.ON -> LinkState.Level.OK
            State.OFF -> LinkState.Level.ERROR
            State.UNKNOWN -> LinkState.Level.IDLE
        }

        @JvmStatic
        fun text(p: Probe): String = when (p.state) {
            State.ON -> Str.get(R.string.hql_hotspot_on, p.iface?.let { "${it.name} ${it.host}" } ?: "?")
            State.OFF -> Str.get(R.string.hql_hotspot_off)
            State.UNKNOWN -> Str.get(R.string.hql_hotspot_unknown)
        }

        private fun readReflection(ctx: Context): SoftApState = try {
            SoftApStateReader.read(ctx)
        } catch (_: Throwable) {
            SoftApState.UNKNOWN
        }

        /**
         * Abre los ajustes de la zona Wi-Fi: la acción de anclaje, las dos actividades de Ajustes que la muestran y, si
         * no hay ninguna, los ajustes de conexiones. Devuelve si se pudo abrir alguna.
         */
        @JvmStatic
        fun openSettings(ctx: Context): Boolean {
            val attempts = listOf(
                Intent("android.settings.TETHER_SETTINGS"),
                Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.TetherSettings")),
                Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.Settings\$TetherSettingsActivity")),
                Intent(Settings.ACTION_WIRELESS_SETTINGS),
            )
            for (i in attempts) {
                try {
                    ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    return true
                } catch (_: ActivityNotFoundException) {
                } catch (_: SecurityException) {
                }
            }
            L.w("no se pudieron abrir los ajustes de la zona Wi-Fi")
            return false
        }
    }
}
