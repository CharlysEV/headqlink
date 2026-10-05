package com.headqlink.link

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import dev.qdauto.core.util.Hex
import dev.qdauto.core.util.LogLevel
import dev.qdauto.core.util.QdLog
import dev.qdauto.core.wire.Direction
import dev.qdauto.core.wire.TraceEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Log unificado de HeadQLink (qdauto §7.2): `logs/qd-AAAAMMDD-HHMMSS[-pN].log` en `getExternalFilesDir`, uno por
 * arranque del proceso, rotativo ([QdLogFile]). Junta en orden:
 * - todo el log del núcleo ([qdLog]: descubrimiento, enlace, `MirrorServer`, sesiones), con nivel, etiqueta e hilo;
 * - la traza de cada mensaje con el coche ([trace]): JSON completo, táctil decodificado con su volcado hexadecimal,
 *   una línea por frame de vídeo;
 * - una copia de cada línea de `L` ([forkLine]) y los eventos del puente, la red y el sistema ([i], [w]).
 *
 * Nunca escribe en el hilo que llama: formatea y encola. Pública (la llama `App.kt`); en su API solo tipos públicos.
 */
object QdTrace {
    private const val TAG = "HeadQLink"

    @Volatile
    private var file: QdLogFile? = null

    @Volatile
    private var logDir: File? = null

    private val ts = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }

    private fun stamp(millis: Long = System.currentTimeMillis()): String = ts.get()!!.format(Date(millis))

    /** Sumidero del log del núcleo (`dev.qdauto.core`): a este fichero y, los avisos y errores, también a `L`. */
    @JvmField
    val qdLog: QdLog = QdLog { level, tag, message, error -> core(level, tag, message, error) }

    /** Abre el log del proceso (en `App.onCreate`, junto a `LogcatCapture.start`). Idempotente. */
    @JvmStatic
    @Synchronized
    fun init(ctx: Context) {
        if (file != null) return
        val app = ctx.applicationContext
        val dir = File(app.getExternalFilesDir(null) ?: app.filesDir, "logs")
        val base = "qd-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val f = QdLogFile(
            dir = dir,
            baseName = base,
            header = { header(app) },
            onThreadStart = {
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                } catch (_: Throwable) {
                }
            },
            onError = { what, t -> Log.e(TAG, "log unificado: $what", t) },
            now = { stamp() },
        )
        logDir = dir
        file = f
        f.start()
    }

    /** Directorio `logs/` (o `null` antes de [init]). */
    @JvmStatic
    fun dir(): File? = logDir

    /** Fichero en uso (o `null`). */
    @JvmStatic
    fun currentFile(): File? = file?.currentFile

    /** Espera (como mucho [timeoutMs]) a que lo encolado esté en disco. */
    @JvmStatic
    fun flush(timeoutMs: Long): Boolean = file?.flush(timeoutMs) ?: false

    /** Copia de una línea de `L` (`I`, `W` o `E`). */
    @JvmStatic
    fun forkLine(level: String, msg: String) {
        file?.write("${stamp()} $level/HQL: $msg")
    }

    /** Evento propio (puente, red, sistema…) con nivel informativo. */
    @JvmStatic
    fun i(tag: String, msg: String) {
        file?.write("${stamp()} I/$tag: $msg")
    }

    /** Evento propio con nivel de aviso (no pasa por `L`). */
    @JvmStatic
    fun w(tag: String, msg: String) {
        file?.write("${stamp()} W/$tag: $msg")
    }

    /** Ejecuta [task] en el hilo del log (escrituras pequeñas fuera de los hilos de la sesión); sin log, en el acto. */
    @JvmStatic
    fun post(task: Runnable) {
        val f = file
        if (f == null || !f.post(task)) task.run()
    }

    /** Bloque de varias líneas (resúmenes): cada línea con la hora y la etiqueta. */
    @JvmStatic
    fun block(tag: String, text: String) {
        val t = stamp()
        val sb = StringBuilder()
        for (line in text.split('\n')) {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(t).append(" I/").append(tag).append(": ").append(line)
        }
        file?.write(sb.toString())
    }

    /**
     * Traza de un mensaje de la sesión [sid]. Control y app: el JSON y el volcado hexadecimal (salvo heartbeats y la
     * lista blanca, repetitivos); táctil: decodificado y volcado completo (con los bits crudos de los floats); vídeo:
     * una línea por frame.
     */
    @JvmStatic
    fun trace(e: TraceEvent, sid: Int) {
        val f = file ?: return
        val arrow = if (e.direction == Direction.IN) "<-" else "->"
        val head = "${stamp(e.timeMillis)} T/S$sid $arrow ${e.kind} (${e.size} B) ${e.summary}"
        val bytes = e.bytes
        if (e.isVideo || bytes == null || e.kind == "HEARTBEAT" || e.kind == "Mirror/WhitelistAppOn") {
            f.write(head)
        } else {
            f.write(head + "\n" + Hex.dump(bytes, maxBytes = 4096).trimEnd('\n'))
        }
    }

    internal fun core(level: LogLevel, tag: String, message: String, error: Throwable?) {
        val f = file
        val letter = level.name[0]
        if (f != null) {
            val line = "${stamp()} $letter/$tag [${Thread.currentThread().name}]: $message" +
                (error?.let { "\n" + Log.getStackTraceString(it).trimEnd('\n') } ?: "")
            f.write(line)
        } else {
            Log.println(if (level >= LogLevel.WARN) Log.WARN else Log.INFO, TAG, "$tag: $message")
        }
        // Avisos y errores también al log de HeadQLink (logcat, headqlink-*.log y diario del coche), fuera del hilo
        // que llama: en el hilo del log.
        if (level >= LogLevel.WARN) {
            val text = "$tag: $message"
            val task = Runnable { L.fromCore(level == LogLevel.ERROR, text, error) }
            if (f == null || !f.post(task)) task.run()
        }
    }

    private fun header(ctx: Context): String {
        val sb = StringBuilder()
        sb.append("==== HeadQLink · log unificado · ").append(stamp()).append('\n')
        sb.append("versión ").append(com.andrerinas.openheadunit.BuildConfig.VERSION_NAME)
            .append(" (").append(com.andrerinas.openheadunit.BuildConfig.GIT_SHA).append(")")
            .append(" · ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(" · Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        try {
            val cfg = Config(ctx)
            sb.append("ajustes: ").append(cfg.summary()).append('\n')
        } catch (e: RuntimeException) {
            sb.append("ajustes: no disponibles (").append(e.message).append(")\n")
        }
        sb.append("formato: HH:mm:ss.SSS nivel/etiqueta [hilo]: texto · T/Sn = traza de la sesión n (<- coche, -> móvil)")
        return sb.toString()
    }
}
