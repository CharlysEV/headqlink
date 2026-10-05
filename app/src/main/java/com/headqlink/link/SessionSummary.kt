package com.headqlink.link

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Resúmenes del motor QDAuto (qdauto §7.4): el bloque de cada sesión (log unificado, L y diario del coche), su fila en
 * `logs/sessions.csv` (se recorta a las últimas [MAX_ROWS]) y el resumen del viaje al parar el servicio. Sin Android:
 * lo prueban los tests.
 */
internal object SessionSummary {
    const val MAX_ROWS = 2_000
    const val CSV_NAME = "sessions.csv"

    /** Todo lo que se sabe de una sesión al cerrarse. Tiempos relativos al TCP aceptado (ms; -1 = no pasó). */
    class Record(
        val sid: Int,
        val startWallMs: Long,
        val endWallMs: Long,
        val engine: String,
        val link: String,
        val videoMode: String,
        val profile: String,
        val video: String,
        val carIp: String,
        val carPort: Int,
        val carName: String,
        val local: String,
        val iface: String,
        val closeKind: String,
        val closeDetail: String,
        val reachedVideo: Boolean,
        val tCarInfoMs: Long,
        val tVideoCtrlMs: Long,
        val tFirstFrameMs: Long,
        val tFirstIdrMs: Long,
        val carKeyframeRequests: Int,
        val frames: Long,
        val bytes: Long,
        val idr: Long,
        val dropped: Long,
        val flushes: Long,
        val maxWriteMs: Long,
        val maxLagMs: Long,
        val carHeartbeats: Long,
        val heartbeatMinMs: Long,
        val heartbeatMaxMs: Long,
        val touches: Long,
        val maxCarGapMs: Long,
        val stalls: Int,
        val maxStallMs: Long,
        val retrans: Int,
        val radio: String,
        val reconnectMs: Long,
        val reconnect: String,
        val videoVerdict: String,
        val aaCycles: Int,
        /** Estado térmico de Android al cerrar y el máximo de la sesión (-1 = no se sabe). */
        val thermalEnd: Int = -1,
        val thermalMax: Int = -1,
        /** fps máximos del vídeo al cerrar y el mínimo de la sesión (los de la sesión, o el tope térmico; 0 = sin vídeo). */
        val fpsCapEnd: Int = 0,
        val fpsCapMin: Int = 0,
        /** Mensaje de vídeo más grande enviado (cabeceras incluidas; el C10 se cuelga con más de 512 KiB). */
        val maxMessageBytes: Long = 0,
        /** Frames descartados por pasar del tope de tamaño, y el mayor de ellos (bytes). */
        val oversizedDrops: Long = 0,
        val oversizedMaxBytes: Long = 0,
        /** Bitrate más bajo que aplicó el controlador del enlace (kbps; 0 = sin controlador o sin vídeo). */
        val bitrateMinKbps: Int = 0,
        /** Pasos por congestión del enlace (bajadas de bitrate o de fps). */
        val congestionEvents: Int = 0,
        /** Writes bloqueados más de 10 s con el coche hablando (la sesión aguantó hasta 20 s). */
        val writeStalls: Long = 0,
    ) {
        val durationS: Double get() = (endWallMs - startWallMs) / 1000.0
        val fps: Double get() = if (videoSeconds > 0) frames / videoSeconds else 0.0
        val kbps: Double get() = if (videoSeconds > 0) bytes * 8 / videoSeconds / 1000 else 0.0

        /** Segundos con vídeo: desde el primer frame (o toda la sesión si no se sabe). */
        val videoSeconds: Double
            get() = if (tFirstFrameMs >= 0) (endWallMs - startWallMs - tFirstFrameMs) / 1000.0 else durationS
    }

    private val TIME = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }
    private val DATE = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }

    private fun t(ms: Long) = TIME.get()!!.format(Date(ms))
    private fun d(ms: Long) = DATE.get()!!.format(Date(ms))
    private fun rel(ms: Long) = if (ms < 0) "—" else "+$ms ms"

    /** KiB redondeados hacia arriba (un mensaje de 491 521 B ya no cabe en 480). */
    internal fun kb(bytes: Long): Long = (bytes + 1023) / 1024
    private fun f1(v: Double) = String.format(Locale.getDefault(), "%.1f", v)

    /** Bloque de varias líneas para el log (formato de qdauto §7.4). */
    fun block(r: Record): String = buildString {
        append("==== S").append(r.sid).append(" · ").append(t(r.startWallMs)).append(" → ").append(t(r.endWallMs))
            .append(" (").append(f1(r.durationS)).append(" s) · fin ").append(r.closeKind)
        if (r.closeDetail.isNotEmpty()) append(": ").append(r.closeDetail)
        append('\n')
        append("coche ").append(r.carIp).append(':').append(r.carPort).append(" «").append(r.carName).append("» · local ")
            .append(r.local).append(' ').append(r.iface).append(" · motor ").append(r.engine).append(" · ")
            .append(r.videoMode).append(if (r.profile.isEmpty()) "" else "/" + r.profile).append(' ').append(r.video).append('\n')
        append("handshake: CAR_INFO ").append(rel(r.tCarInfoMs)).append(" · VIDEO_CTRL{1} ").append(rel(r.tVideoCtrlMs))
            .append(" · primer frame ").append(rel(r.tFirstFrameMs)).append(" · primer IDR ").append(rel(r.tFirstIdrMs))
            .append(" · KEY_FRAME_REQ ×").append(r.carKeyframeRequests).append('\n')
        append("vídeo: ").append(r.frames).append(" frames (").append(f1(r.fps)).append(" fps) · ")
            .append(String.format(Locale.getDefault(), "%.1f", r.kbps / 1000)).append(" Mbit/s · ").append(r.idr).append(" IDR · descartados ")
            .append(r.dropped).append(" · vaciados ").append(r.flushes).append(" · máx. write ").append(r.maxWriteMs)
            .append(" ms · máx. cola ").append(r.maxLagMs).append(" ms")
        if (r.maxMessageBytes > 0) append(" · mensaje máx. ").append(kb(r.maxMessageBytes)).append(" KB")
        if (r.oversizedDrops > 0) {
            append(" · descartados por tamaño ").append(r.oversizedDrops).append(" (máx. ").append(kb(r.oversizedMaxBytes))
                .append(" KB)")
        }
        if (r.aaCycles > 0) append(" · ciclos de foco de AA ").append(r.aaCycles)
        if (r.thermalMax >= 0) {
            append(" · térmico ").append(r.thermalEnd).append(" (máx. ").append(r.thermalMax).append(')')
        }
        if (r.fpsCapEnd > 0) append(" · tope ").append(r.fpsCapEnd).append(" fps (mín. ").append(r.fpsCapMin).append(')')
        if (r.bitrateMinKbps > 0 || r.congestionEvents > 0) {
            append(" · enlace: bitrate mín. ").append(String.format(Locale.getDefault(), "%.1f", r.bitrateMinKbps / 1000.0))
                .append(" Mbit/s · congestiones ").append(r.congestionEvents)
        }
        if (r.writeStalls > 0) append(" · writes bloqueados ").append(r.writeStalls)
        append('\n')
        append("coche: ").append(r.carHeartbeats).append(" heartbeats")
        if (r.heartbeatMinMs >= 0) {
            append(" (").append(f1(r.heartbeatMinMs / 1000.0)).append("–").append(f1(r.heartbeatMaxMs / 1000.0)).append(" s)")
        }
        append(" · ").append(r.touches).append(" toques · hueco máx. ").append(f1(r.maxCarGapMs / 1000.0)).append(" s\n")
        append("radio: ").append(r.radio).append('\n')
        append("reconexión: ").append(if (r.reconnect.isEmpty()) "primera sesión" else r.reconnect)
        append(" · vídeo ").append(r.videoVerdict.ifEmpty { "—" })
    }

    val CSV_HEADER = listOf(
        "inicio", "fin", "duracion_s", "sesion", "motor", "conexion", "modo_video", "perfil", "coche_ip", "coche_nombre", "iface",
        "cierre", "cierre_detalle", "llego_a_video", "t_car_info_ms", "t_video_ctrl_ms", "t_primer_frame_ms", "t_primer_idr_ms",
        "frames", "fps", "kbps", "idr", "keyframe_req_coche", "descartados", "vaciados", "max_write_ms", "max_cola_ms",
        "heartbeats_coche", "toques", "max_hueco_coche_ms", "cortes", "max_corte_ms", "retrans", "reconexion_ms",
        "video_reutilizado", "ciclos_foco_aa", "termico_fin", "termico_max", "tope_fps_fin", "tope_fps_min",
        "frame_max_kb", "descartados_grandes", "bitrate_min_kbps", "congestiones", "writes_bloqueados",
    ).joinToString(",")

    fun csvRow(r: Record): String = listOf(
        d(r.startWallMs), d(r.endWallMs), String.format(Locale.US, "%.1f", r.durationS), r.sid.toString(), r.engine, r.link,
        r.videoMode, r.profile, r.carIp, r.carName, r.iface, r.closeKind, r.closeDetail, if (r.reachedVideo) "1" else "0",
        r.tCarInfoMs.toString(), r.tVideoCtrlMs.toString(), r.tFirstFrameMs.toString(), r.tFirstIdrMs.toString(),
        r.frames.toString(), String.format(Locale.US, "%.1f", r.fps), String.format(Locale.US, "%.0f", r.kbps), r.idr.toString(),
        r.carKeyframeRequests.toString(), r.dropped.toString(), r.flushes.toString(), r.maxWriteMs.toString(), r.maxLagMs.toString(),
        r.carHeartbeats.toString(), r.touches.toString(), r.maxCarGapMs.toString(), r.stalls.toString(), r.maxStallMs.toString(),
        r.retrans.toString(), r.reconnectMs.toString(), if (r.videoVerdict == "REUTILIZADO") "1" else "0", r.aaCycles.toString(),
        r.thermalEnd.toString(), r.thermalMax.toString(), r.fpsCapEnd.toString(), r.fpsCapMin.toString(),
        kb(r.maxMessageBytes).toString(), r.oversizedDrops.toString(), r.bitrateMinKbps.toString(), r.congestionEvents.toString(),
        r.writeStalls.toString(),
    ).joinToString(",") { csv(it) }

    private val NUMBER = Regex("-?[0-9]+(\\.[0-9]+)?")

    /**
     * Una celda: entre comillas si hace falta y, si empieza como una fórmula (`= + - @`, tabulador o retorno), con `'`
     * delante para que la hoja de cálculo no la ejecute (el nombre del coche llega del broadcast UDP). Los números
     * negativos (-1 = no pasó) se quedan como están.
     */
    internal fun csv(cell: String): String {
        val v = if (cell.isNotEmpty() && cell[0] in "=+-@\t\r" && !NUMBER.matches(cell)) "'$cell" else cell
        return if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"").replace('\n', ' ').replace('\r', ' ') + "\"" else v
    }

    /** Añade la fila a `dir/sessions.csv` (con cabecera si es nuevo) y lo recorta a las últimas [maxRows] filas. */
    @Synchronized
    fun appendCsv(dir: File, row: String, maxRows: Int = MAX_ROWS) {
        dir.mkdirs()
        val f = File(dir, CSV_NAME)
        val lines = if (f.exists()) f.readLines(Charsets.UTF_8).filter { it.isNotEmpty() } else emptyList()
        val headerOk = lines.isNotEmpty() && lines[0] == CSV_HEADER
        val body = if (headerOk) lines.drop(1) else lines.filter { it != CSV_HEADER }
        if (headerOk && body.size + 1 <= maxRows) {
            // Lo normal: añadir al final.
            OutputStreamWriter(FileOutputStream(f, true), Charsets.UTF_8).use { w ->
                w.write(row)
                w.write("\n")
            }
            return
        }
        // Fichero nuevo, cabecera distinta o demasiadas filas: se reescribe entero.
        val keep = (body + row).takeLast(maxRows)
        OutputStreamWriter(FileOutputStream(f, false), Charsets.UTF_8).use { w ->
            w.write(CSV_HEADER)
            w.write("\n")
            for (l in keep) {
                w.write(l)
                w.write("\n")
            }
        }
    }

    /** Últimas [n] filas de `sessions.csv` (para el resumen de la exportación). */
    fun lastRows(dir: File, n: Int): List<String> {
        val f = File(dir, CSV_NAME)
        if (!f.exists()) return emptyList()
        return f.readLines(Charsets.UTF_8).drop(1).filter { it.isNotEmpty() }.takeLast(n)
    }

    // ---------------------------------------------------------------- viaje (servicio)

    private val trip = ArrayList<Record>()
    private var tripStartMs = 0L

    /** Arranques de AA del proceso al empezar el viaje: el contador es de todo el proceso, no del servicio. */
    private var tripAaLaunchesAtStart = 0

    /** Empieza el viaje; [aaLaunches] = arranques de AA del proceso hasta ahora. */
    @Synchronized
    fun startTrip(nowMs: Long, aaLaunches: Int) {
        trip.clear()
        tripStartMs = nowMs
        tripAaLaunchesAtStart = aaLaunches
    }

    @Synchronized
    fun noteSession(r: Record) {
        if (trip.size < 10_000) trip += r
    }

    /**
     * Resumen del viaje (qdauto §7.4): duración, sesiones, vídeo, reconexiones, motivos de cierre, cortes y AA.
     * [aaLaunches] = arranques de AA del proceso hasta ahora; se cuentan los de este viaje.
     */
    @Synchronized
    fun tripSummary(nowMs: Long, aaLaunches: Int): String = buildString {
        val start = if (tripStartMs > 0) tripStartMs else trip.firstOrNull()?.startWallMs ?: nowMs
        append("==== viaje · ").append(t(start)).append(" → ").append(t(nowMs)).append(" (")
            .append(f1((nowMs - start) / 60_000.0)).append(" min) · ").append(trip.size).append(" sesiones\n")
        val videoS = trip.filter { it.reachedVideo }.sumOf { it.videoSeconds.coerceAtLeast(0.0) }
        append("con vídeo: ").append(f1(videoS / 60)).append(" min\n")
        val gaps = trip.map { it.reconnectMs }.filter { it >= 0 }
        append("reconexiones: ").append(gaps.size)
        if (gaps.isNotEmpty()) {
            append(" · hueco mín. ").append(gaps.minOrNull()).append(" ms, medio ").append(gaps.average().toLong())
                .append(" ms, máx. ").append(gaps.maxOrNull()).append(" ms")
            append(" · vídeo reutilizado ").append(trip.count { it.videoVerdict == "REUTILIZADO" })
        }
        append('\n')
        append("cierres: ").append(trip.groupingBy { it.closeKind }.eachCount().entries.joinToString(" · ") { "${it.key} ${it.value}" }.ifEmpty { "—" })
            .append('\n')
        append("cortes: ").append(trip.sumOf { it.stalls }).append(" (máx. ").append(trip.maxOfOrNull { it.maxStallMs } ?: 0).append(" ms)\n")
        val maxMsg = trip.maxOfOrNull { it.maxMessageBytes } ?: 0L
        if (maxMsg > 0) {
            append("mensajes de vídeo: máx. ").append(kb(maxMsg)).append(" KB · descartados por tamaño ")
                .append(trip.sumOf { it.oversizedDrops }).append('\n')
        }
        val congestions = trip.sumOf { it.congestionEvents }
        if (congestions > 0) {
            append("enlace: ").append(congestions).append(" congestiones · bitrate mín. ")
                .append(f1((trip.filter { it.bitrateMinKbps > 0 }.minOfOrNull { it.bitrateMinKbps } ?: 0) / 1000.0)).append(" Mbit/s\n")
        }
        val stalls = trip.sumOf { it.writeStalls }
        if (stalls > 0) append("writes bloqueados más de 10 s con el coche hablando: ").append(stalls).append('\n')
        val thermalMax = trip.maxOfOrNull { it.thermalMax } ?: -1
        if (thermalMax >= 0) {
            append("térmico: máx. ").append(thermalMax)
            trip.filter { it.fpsCapMin > 0 }.minOfOrNull { it.fpsCapMin }?.let { append(" · tope mín. ").append(it).append(" fps") }
            append('\n')
        }
        append("ciclos de foco de AA: ").append(trip.sumOf { it.aaCycles }).append(" · arranques de AA (Self-Mode): ")
            .append((aaLaunches - tripAaLaunchesAtStart).coerceAtLeast(0))
    }
}
