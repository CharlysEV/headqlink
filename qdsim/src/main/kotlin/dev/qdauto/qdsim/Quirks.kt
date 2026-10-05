package dev.qdauto.qdsim

import dev.qdauto.core.sim.CarSimConfig
import dev.qdauto.core.sim.CodecConfigSummary
import dev.qdauto.core.sim.ReceiverHang
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Resultado de una comprobación de qdsim: WARN no cuenta como fallo salvo en el escenario `estricto`. */
enum class Level { PASS, WARN, FAIL, SKIP }

data class Verdict(val level: Level, val text: String)

/**
 * Las manías del C10 vistas el 2026-10-05, como comprobaciones de qdsim (las mismas que `tamano_mensaje`,
 * `sps_repetido` y `decodifica` de carsim):
 * 1. el receptor del coche se cuelga con mensajes de vídeo de más de 512 KiB (el teléfono debe recortar a 480 KiB);
 * 2. el coche reinicia el decodificador con cada SPS/PPS, así que solo valen el primero y los que preceden a un IDR
 *    pedido con `KEY_FRAME_REQ`;
 * 3. opcionalmente, ffmpeg tiene que decodificar el vídeo recibido sin errores.
 */
object Quirks {
    /** `tamano_mensaje`: FAIL si el coche se colgó (o se colgaría sin emular el límite); WARN por encima de [warnBytes]. */
    fun messageSize(
        videoMessages: Int,
        maxMessageBytes: Int,
        largeMessages: Int,
        hang: ReceiverHang?,
        hangAtMs: Long?,
        limitBytes: Int,
        warnBytes: Int = CarSimConfig.RECOMMENDED_MAX_MESSAGE_BYTES,
        carLimitBytes: Int = CarSimConfig.C10_RECEIVER_LIMIT_BYTES,
    ): Verdict {
        if (videoMessages == 0) return Verdict(Level.SKIP, "tamano_mensaje: sin vídeo")
        val max = "máximo $maxMessageBytes B en $videoMessages mensajes"
        if (hang != null) {
            val at = hangAtMs?.let { " a los $it ms" } ?: ""
            return Verdict(Level.FAIL, "tamano_mensaje: el coche se colgó con el ${hang.describe()}$at y cerró tras ${hang.hangMs} ms sin leer; $max")
        }
        if (maxMessageBytes > carLimitBytes) {
            return Verdict(Level.FAIL, "tamano_mensaje: sin emular el cuelgue (--limit ${limitBytes / 1024}), pero el C10 se colgaría: $max")
        }
        if (largeMessages > 0) {
            return Verdict(Level.WARN, "tamano_mensaje: $largeMessages mensajes de más de ${warnBytes / 1024} KiB (el teléfono tiene que recortar a ${warnBytes / 1024} KiB); $max")
        }
        return Verdict(Level.PASS, "tamano_mensaje: $max (límite del coche ${carLimitBytes / 1024} KiB)")
    }

    /** `sps_repetido`: FAIL si algún SPS/PPS no tiene un IDR detrás; WARN si precede a un IDR que nadie pidió. */
    fun spsRepeat(videoMessages: Int, s: CodecConfigSummary): Verdict {
        if (videoMessages == 0) return Verdict(Level.SKIP, "sps_repetido: sin vídeo")
        if (s.count == 0) return Verdict(Level.FAIL, "sps_repetido: ningún SPS/PPS")
        val detail = s.describe()
        return when {
            s.failed -> Verdict(Level.FAIL, "sps_repetido: $detail; el C10 reinicia el decodificador sin IDR detrás (artefactos)")
            s.warned -> Verdict(Level.WARN, "sps_repetido: $detail; cada SPS/PPS de más reinicia el decodificador del C10")
            else -> Verdict(Level.PASS, "sps_repetido: $detail")
        }
    }

    /** `decodifica`: FAIL con cualquier línea de error del decodificador. */
    fun decode(r: DecodeResult): Verdict {
        val frames = r.frames?.let { "$it frames" } ?: "frames: ?"
        val base = "$frames en ${r.elapsedMs} ms (${r.ffmpeg.path})"
        r.failure?.let { return Verdict(Level.FAIL, "decodifica: $it; $base") }
        if (r.exitCode == 0 && r.errorLines.isEmpty()) return Verdict(Level.PASS, "decodifica: $base")
        val errs = r.errorLines.take(3).joinToString(" | ") { if (it.length > 160) it.substring(0, 160) + "..." else it }
        return Verdict(Level.FAIL, "decodifica: ${r.errorLines.size} líneas de error: $errs; código ${r.exitCode}; $base")
    }
}

/** Lo que dijo ffmpeg del vídeo recibido (`-f h264 -i - -f null -`). */
data class DecodeResult(
    val ffmpeg: File,
    val frames: Int?,
    val errorLines: List<String>,
    val exitCode: Int?,
    val elapsedMs: Long,
    val failure: String?,
)

/** ffmpeg para `--decode`: se busca en `--ffmpeg`, en [DEFAULT_PATH] y en el `PATH`. */
object Ffmpeg {
    /** `QDAUTO_FFMPEG` o `tools/ffmpeg/...` relativo al directorio de trabajo. */
    val DEFAULT_PATH = File(System.getenv("QDAUTO_FFMPEG") ?: "tools/ffmpeg/ffmpeg-master-latest-win64-gpl/bin/ffmpeg.exe")

    fun locate(explicit: File? = null, path: String? = System.getenv("PATH"), default: File = DEFAULT_PATH): File? {
        if (explicit != null) return if (explicit.isFile) explicit else null
        if (default.isFile) return default
        val names = if (File.separatorChar == '\\') listOf("ffmpeg.exe", "ffmpeg") else listOf("ffmpeg")
        for (dir in (path ?: "").split(File.pathSeparatorChar).filter { it.isNotBlank() }) {
            for (name in names) {
                val f = File(dir, name)
                if (f.isFile) return f
            }
        }
        return null
    }

    /** Manda [h264] por la entrada estándar de ffmpeg; stderr (con `-loglevel error`) son los errores. */
    fun decode(ffmpeg: File, h264: File, timeoutMs: Long = 300_000): DecodeResult {
        val started = System.nanoTime()
        fun elapsed() = (System.nanoTime() - started) / 1_000_000
        val progress = try {
            File.createTempFile("qdsim-ffmpeg-", ".txt")
        } catch (e: IOException) {
            return DecodeResult(ffmpeg, null, emptyList(), null, elapsed(), "no se pudo crear el fichero de progreso: ${e.message}")
        }
        val command = listOf(
            ffmpeg.path, "-hide_banner", "-loglevel", "error", "-nostats", "-progress", progress.path,
            "-f", "h264", "-i", "-", "-f", "null", "-",
        )
        val process = try {
            ProcessBuilder(command).start()
        } catch (e: IOException) {
            progress.delete()
            return DecodeResult(ffmpeg, null, emptyList(), null, elapsed(), "no se pudo ejecutar ${ffmpeg.path}: ${e.message}")
        }
        val stderr = StringBuilder()
        val stderrThread = Thread({
            try {
                process.errorStream.bufferedReader(Charsets.UTF_8).forEachLine { stderr.append(it).append('\n') }
            } catch (_: IOException) {
            }
        }, "qdsim-ffmpeg-stderr").apply { isDaemon = true; start() }
        val stdoutThread = Thread({
            try {
                process.inputStream.use { it.readBytes() }
            } catch (_: IOException) {
            }
        }, "qdsim-ffmpeg-stdout").apply { isDaemon = true; start() }
        var feedError: String? = null
        try {
            process.outputStream.use { out -> h264.inputStream().use { it.copyTo(out, 256 * 1024) } }
        } catch (e: IOException) {
            feedError = e.message // ffmpeg cerró la entrada al abortar: los errores salen por stderr
        }
        val finished = try {
            process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!finished) {
            process.destroyForcibly()
            stderrThread.join(1_000)
            progress.delete()
            return DecodeResult(ffmpeg, null, errorLines(stderr.toString()), null, elapsed(), "ffmpeg no terminó en ${timeoutMs / 1000} s")
        }
        stderrThread.join(5_000)
        stdoutThread.join(5_000)
        val frames = try {
            parseFrames(progress.readText())
        } catch (_: IOException) {
            null
        } finally {
            progress.delete()
        }
        val errors = errorLines(stderr.toString())
        val failure = if (feedError != null && errors.isEmpty() && process.exitValue() != 0) "ffmpeg cerró la entrada: $feedError" else null
        return DecodeResult(ffmpeg, frames, errors, process.exitValue(), elapsed(), failure)
    }

    fun errorLines(stderr: String): List<String> = stderr.lines().map { it.trimEnd() }.filter { it.isNotBlank() }

    fun parseFrames(progress: String): Int? =
        progress.lines().lastOrNull { it.startsWith("frame=") }?.substringAfter('=')?.trim()?.toIntOrNull()
}
