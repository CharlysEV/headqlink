package com.headqlink.link

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.connection.CommManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Vigila las sesiones con Android Auto que se cortan solas a los pocos segundos de conectar (el síntoma de Open Headunit
 * #985 con AA 17.8: «conecta y a los 1-2 s se desconecta»; volver a 17.7 lo arregla). Escucha el estado de CommManager
 * y se lo pasa a [AaFlapDetector]: con dos cortes seguidos en menos de 10 s, una línea clara en el log (cada vez que se
 * llega a la racha) y, solo la primera vez del proceso, un aviso con lo que se puede probar: borrar la caché de Android
 * Auto, parar e iniciar su servidor o volver a una versión probada. Con racha, [relaunchHoldMs] frena los
 * relanzamientos automáticos a uno cada 10 s como mucho. Los cierres de HeadQLink ([AaClose], el apagado del servidor,
 * el enlace parado) no cuentan.
 */
internal object AaFlapWatch {
    private const val CHANNEL = "aa_compat"
    private const val NOTIF_FLAP = 10

    private val detector = AaFlapDetector()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var job: Job? = null

    /** Hubo sesión con AA (transporte o handshake) desde el último corte: solo entonces un corte es «AA se ha caído». */
    @Volatile
    private var up = false

    /** Empieza a escuchar (una vez por proceso; idempotente). */
    @JvmStatic
    fun ensure(ctx: Context) {
        val app = ctx.applicationContext
        synchronized(this) {
            if (job?.isActive == true) return
            val cm = try {
                App.provide(app).commManager
            } catch (e: RuntimeException) {
                L.w("AA: no se pudo vigilar la conexión con Android Auto: $e")
                return
            }
            job = scope.launch {
                cm.connectionState.collect { onState(app, it) }
            }
        }
    }

    /** Cuánto debe esperar aún un relanzamiento automático de AA (0: ya puede). */
    @JvmStatic
    fun relaunchHoldMs(): Long = synchronized(detector) { detector.relaunchHoldMs(SystemClock.elapsedRealtime()) }

    private fun onState(app: Context, st: CommManager.ConnectionState) {
        val now = SystemClock.elapsedRealtime()
        when (st) {
            CommManager.ConnectionState.HandshakeComplete, CommManager.ConnectionState.TransportStarted -> {
                up = true
                synchronized(detector) { detector.onConnected(now) }
            }
            is CommManager.ConnectionState.Disconnected, is CommManager.ConnectionState.Error -> {
                // Lo cerró HeadQLink (Desconectar, fin del viaje, cambio de perfil, apagado del servidor) o el enlace no
                // está en marcha: no es el síntoma.
                val ours = AaClose.ownCloseRecent() || !LinkState.running
                val wasUp = up
                up = false
                // AA se ha caído solo con el enlace en marcha (sesión perdida, sin despedida: no es salir de AA a propósito):
                // si hay sesión con el coche esperando su vídeo, se relanza (AaPassthroughSource.onAaDropped; con racha de
                // cortes, con el freno de relaunchHoldMs).
                val lost = st is CommManager.ConnectionState.Error ||
                    (st is CommManager.ConnectionState.Disconnected && !st.isClean && !st.isUserExit)
                if (!ours && wasUp && lost) AaPassthroughSource.onAaDropped()
                val result: AaFlapDetector.Result
                val durationMs: Long
                val streak: Int
                val notice: Boolean
                synchronized(detector) {
                    result = detector.onDisconnected(now, ours)
                    durationMs = detector.lastDurationMs()
                    streak = detector.streak()
                    notice = result == AaFlapDetector.Result.FLAPPING && detector.takeNotice()
                }
                when (result) {
                    AaFlapDetector.Result.SHORT -> L.lifeWarn(
                        "AA: Android Auto se desconectó solo a los ${secs(durationMs)} de conectar (corte corto $streak de " +
                            "${AaFlapDetector.STREAK} para el diagnóstico)",
                    )
                    AaFlapDetector.Result.FLAPPING -> {
                        L.lifeWarn(diagnosis(app, durationMs, streak, notice))
                        if (notice) notifyFlapping(app)
                    }
                    else -> Unit
                }
            }
            else -> Unit
        }
    }

    /** La línea del diagnóstico (también la prueban los tests a través de sus piezas). */
    private fun diagnosis(app: Context, durationMs: Long, streak: Int, notice: Boolean): String {
        val version = AaVersions.describe(AaVersions.read(app), AaVersions.lastCode())
        return "AA: diagnóstico: Android Auto se conecta y se desconecta solo a los pocos segundos ($streak veces " +
            "seguidas; la última, a los ${secs(durationMs)}). Android Auto $version. Es el síntoma de Open Headunit #985 " +
            "con algunas versiones nuevas de Android Auto. Prueba: borrar la caché de Android Auto (Ajustes › " +
            "Aplicaciones › Android Auto › Almacenamiento › Borrar caché), parar e iniciar su servidor (⋮) o volver a " +
            "una versión probada (${AaVersions.verifiedList()}); los relanzamientos automáticos esperan al menos " +
            "${AaFlapDetector.MIN_RELAUNCH_GAP_MS / 1000} s" + if (notice) "; aviso «${Str.get(R.string.hql_aa_flap_title)}»" else ""
    }

    private fun notifyFlapping(app: Context) {
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, Str.get(R.string.hql_aa_compat_channel), NotificationManager.IMPORTANCE_HIGH),
        )
        // Tocar: «Info. de la app» de Android Auto (Almacenamiento › Borrar caché). Botón: sus ajustes (⋮ › servidor).
        val info = PendingIntent.getActivity(
            app, NOTIF_FLAP,
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + AaServerStarter.AA_PKG))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val aa = PendingIntent.getActivity(
            app, NOTIF_FLAP + 100, AaServerStarter.aaSettingsIntent(app),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = Str.get(R.string.hql_aa_flap_text, AaVersions.shortLabel(app), AaVersions.verifiedList())
        nm.notify(
            NOTIF_FLAP,
            Notification.Builder(app, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setColor(app.getColor(R.color.hql_warn))
                .setContentTitle(Str.get(R.string.hql_aa_flap_title))
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setCategory(Notification.CATEGORY_STATUS)
                .setOnlyAlertOnce(true)
                .setContentIntent(info)
                .addAction(Notification.Action.Builder(null, Str.get(R.string.hql_req_app_info), info).build())
                .addAction(Notification.Action.Builder(null, Str.get(R.string.hql_open_aa), aa).build())
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun secs(ms: Long) = String.format(Locale.getDefault(), "%.1f s", ms / 1000.0)
}
