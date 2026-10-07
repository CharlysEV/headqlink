package dev.qdauto.qdsim

import dev.qdauto.core.sim.VideoKind
import java.util.Locale

/**
 * Vídeo que recibe el coche simulado, para el informe de la radio floja (`--rx-kbps`, `--rx-stall`, `radio-mala`):
 * frames por segundo, el P-frame más grande, el peor hueco entre frames y los segundos con menos de la mitad de los
 * fps pedidos. Lo que decide el móvil (congestión, QP-P) no viaja en el flujo: está en su log (`enlace: congestión …`,
 * `VIDEO P-frames: …`). Thread-safe.
 */
class VideoFlow(private val nominalFps: Int) {
    private val times = ArrayList<Long>()
    private var pFrames = 0
    private var pBytes = 0L
    private var pMax = 0
    private val pSizes = ArrayList<Int>()
    private var idr = 0
    private var bytes = 0L

    /** Un mensaje de vídeo a los [ms] de empezar (los CONFIG no cuentan como frame). */
    @Synchronized
    fun onFrame(ms: Long, kind: VideoKind, payloadBytes: Int) {
        if (kind == VideoKind.CONFIG) return
        times += ms
        bytes += payloadBytes
        if (kind == VideoKind.IDR) {
            idr++
        } else {
            pFrames++
            pBytes += payloadBytes
            pSizes += payloadBytes
            if (payloadBytes > pMax) pMax = payloadBytes
        }
    }

    data class Summary(
        val frames: Int,
        val idr: Int,
        val seconds: Double,
        val fps: Double,
        /** fps del peor segundo entero (sin el primero ni el último). */
        val minFps: Int,
        /** Segundos enteros con menos de la mitad de los fps pedidos. */
        val lowSeconds: Int,
        val worstGapMs: Long,
        /** Cuándo empezó el peor hueco (ms desde el primer frame). */
        val worstGapAtMs: Long,
        val kbps: Double,
        val pFrames: Int,
        val pMaxBytes: Int,
        val pAvgBytes: Int,
        /** P-frames de más de 6 P-frames medios (las ráfagas que atascan la radio). */
        val pBursts: Int,
    ) {
        fun describe(): String = String.format(
            Locale.US,
            "%d frames en %.1f s (%.1f fps; peor segundo %d fps; %d s con menos de la mitad) · peor hueco %d ms (a los %.1f s) · " +
                "%.0f kbit/s · P-frames %d: máx. %d KB, medio %.1f KB, %d de más de 6 medios · IDR %d",
            frames, seconds, fps, minFps, lowSeconds, worstGapMs, worstGapAtMs / 1000.0, kbps, pFrames, (pMaxBytes + 512) / 1024,
            pAvgBytes / 1024.0, pBursts, idr,
        )
    }

    @Synchronized
    fun summary(): Summary {
        if (times.isEmpty()) return Summary(0, 0, 0.0, 0.0, 0, 0, 0, 0, 0.0, 0, 0, 0, 0)
        val t0 = times.first()
        val t1 = times.last()
        val seconds = (t1 - t0) / 1000.0
        var worst = 0L
        var worstAt = 0L
        for (i in 1 until times.size) {
            val gap = times[i] - times[i - 1]
            if (gap > worst) {
                worst = gap
                worstAt = times[i - 1] - t0
            }
        }
        val buckets = IntArray(((t1 - t0) / 1000 + 1).toInt())
        for (t in times) buckets[((t - t0) / 1000).toInt()]++
        val whole = if (buckets.size > 2) buckets.copyOfRange(1, buckets.size - 1) else IntArray(0)
        val avgP = if (pFrames > 0) (pBytes / pFrames).toInt() else 0
        return Summary(
            frames = times.size,
            idr = idr,
            seconds = seconds,
            fps = if (seconds > 0) (times.size - 1) / seconds else 0.0,
            minFps = whole.minOrNull() ?: 0,
            lowSeconds = whole.count { it * 2 < nominalFps },
            worstGapMs = worst,
            worstGapAtMs = worstAt,
            kbps = if (seconds > 0) bytes * 8 / seconds / 1000 else 0.0,
            pFrames = pFrames,
            pMaxBytes = pMax,
            pAvgBytes = avgP,
            pBursts = if (avgP > 0) pSizes.count { it > 6L * avgP } else 0,
        )
    }
}
