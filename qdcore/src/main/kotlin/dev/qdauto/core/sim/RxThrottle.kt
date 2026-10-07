package dev.qdauto.core.sim

import java.io.FilterInputStream
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * hql: parones periódicos de la lectura del coche: deja de leer del todo [forMs] cada [everyMs] (el primero a los
 * [everyMs] de conectar). Se escribe como `every=20s,for=1500ms` (s o ms; sin unidad, ms).
 */
data class RxStall(val everyMs: Long, val forMs: Long) {
    init {
        require(everyMs > 0) { "every tiene que ser > 0" }
        require(forMs in 1 until everyMs) { "for tiene que ser > 0 y menor que every" }
    }

    /** Si a los [elapsedMs] de conectar toca un parón, cuándo acaba (ms desde la conexión); si no, -1. */
    fun stallEndAt(elapsedMs: Long): Long {
        if (elapsedMs < everyMs) return -1
        val start = elapsedMs / everyMs * everyMs
        return if (elapsedMs - start < forMs) start + forMs else -1
    }

    fun describe(): String = "parón de ${ms(forMs)} cada ${ms(everyMs)}"

    companion object {
        /** `every=20s,for=1500ms` (también `every=15s,for=1.2s`). */
        fun parse(text: String): RxStall {
            val parts = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val m = HashMap<String, Long>()
            for (p in parts) {
                val eq = p.indexOf('=')
                require(eq > 0) { "--rx-stall espera every=20s,for=1500ms: $text" }
                val key = p.substring(0, eq).trim().lowercase(Locale.ROOT)
                require(key == "every" || key == "for") { "--rx-stall: clave desconocida «$key» (every, for)" }
                m[key] = duration(p.substring(eq + 1).trim()) ?: throw IllegalArgumentException("--rx-stall: duración no válida en «$p»")
            }
            val every = m["every"] ?: throw IllegalArgumentException("--rx-stall: falta every=…")
            val forMs = m["for"] ?: throw IllegalArgumentException("--rx-stall: falta for=…")
            require(every > 0 && forMs in 1 until every) { "--rx-stall: for tiene que ser > 0 y menor que every ($text)" }
            return RxStall(every, forMs)
        }

        /** `20s`, `1.5s`, `1500ms` o `1500` (ms). */
        fun duration(s: String): Long? {
            val t = s.lowercase(Locale.ROOT).replace(',', '.')
            val (num, factor) = when {
                t.endsWith("ms") -> t.removeSuffix("ms") to 1.0
                t.endsWith("s") -> t.removeSuffix("s") to 1000.0
                else -> t to 1.0
            }
            val v = num.trim().toDoubleOrNull() ?: return null
            if (v < 0) return null
            return Math.round(v * factor)
        }

        private fun ms(v: Long) = if (v % 1000 == 0L) "${v / 1000} s" else "$v ms"
    }
}

/**
 * hql: radio floja simulada en el lado del coche. El coche lee el TCP a como mucho [kbps] (cubo de fichas) y, con
 * [stall], deja de leer del todo a ratos. Lo que no lee se queda en su búfer de recepción, que se deja pequeño
 * ([receiveBufferBytes]): la ventana TCP se cierra y la cola de envío del móvil se llena como con la radio del C10
 * saturada (cola del kernel alta, write() que tarda, cortes «RADIO» en el detector del móvil).
 */
data class RxLimit(
    /** Lectura máxima en kbit/s (1000 bit/s); 0 = sin límite. */
    val kbps: Int = 0,
    val stall: RxStall? = null,
    /** SO_RCVBUF del socket del coche cuando hay límite (antes de conectar). */
    val receiveBufferBytes: Int = DEFAULT_RECEIVE_BUFFER_BYTES,
) {
    init {
        require(kbps >= 0) { "--rx-kbps no puede ser negativo" }
    }

    val active: Boolean get() = kbps > 0 || stall != null

    fun describe(): String = listOfNotNull(
        if (kbps > 0) "lectura a $kbps kbit/s" else null,
        stall?.describe(),
        "búfer de recepción ${receiveBufferBytes / 1024} KiB",
    ).joinToString(" · ")

    companion object {
        const val DEFAULT_RECEIVE_BUFFER_BYTES = 32 * 1024
    }
}

/** Lo que hizo el limitador (para el informe). */
data class RxThrottleStats(
    val bytes: Long,
    val elapsedMs: Long,
    /** Tiempo esperando fichas (la lectura iba al límite). */
    val throttledMs: Long,
    val stalls: Int,
    val stalledMs: Long,
) {
    val kbps: Double get() = if (elapsedMs > 0) bytes * 8.0 / elapsedMs else 0.0

    fun describe(): String = String.format(
        Locale.US, "leídos %d KB a %.0f kbit/s de media · al límite %.1f s (%d %%) · parones %d (%.1f s)",
        bytes / 1024, kbps, throttledMs / 1000.0, if (elapsedMs > 0) throttledMs * 100 / elapsedMs else 0, stalls,
        stalledMs / 1000.0,
    )
}

/**
 * El limitador de [RxLimit] (un hilo lector; las estadísticas se pueden leer desde otro). [nanoTime] y [sleepMs]
 * se pueden cambiar en los tests.
 */
class RxThrottle(
    val limit: RxLimit,
    private val nanoTime: () -> Long = System::nanoTime,
    private val sleepMs: (Long) -> Unit = { Thread.sleep(it) },
) {
    private val startNs = nanoTime()
    private val rate = limit.kbps * 125.0 // B/s
    /** Cubo: 50 ms de lectura (como poco 8 KiB), para que la resolución del sleep (~15 ms en Windows) no baje la tasa. */
    private val capacity = maxOf(8 * 1024.0, rate * 0.05)
    private var tokens = capacity
    private var lastNs = startNs
    private var lastStallEnd = -1L

    @Volatile private var bytes = 0L
    @Volatile private var throttledNs = 0L
    @Volatile private var stalls = 0
    @Volatile private var stalledNs = 0L

    /** Antes de leer: espera lo que toque (parón, fichas) y dice cuántos bytes (≥ 1, ≤ [want]) se pueden leer ya. */
    fun beforeRead(want: Int): Int {
        if (want <= 0) return want
        while (true) {
            val now = nanoTime()
            val elapsedMs = (now - startNs) / 1_000_000
            val stallEnd = limit.stall?.stallEndAt(elapsedMs) ?: -1L
            if (stallEnd >= 0) {
                if (stallEnd != lastStallEnd) {
                    lastStallEnd = stallEnd
                    stalls++
                }
                val wait = maxOf(1L, stallEnd - elapsedMs)
                sleepMs(wait)
                stalledNs += nanoTime() - now
                // Fichas de un parón no se acumulan más allá del cubo.
                refill(nanoTime())
                continue
            }
            if (limit.kbps <= 0) return want
            refill(now)
            val chunk = minOf(want.toDouble(), minOf(capacity, 1460.0))
            if (tokens >= chunk) return minOf(want, tokens.toInt()).coerceAtLeast(1)
            val waitMs = maxOf(1L, Math.ceil((chunk - tokens) / rate * 1000).toLong())
            sleepMs(waitMs)
            throttledNs += nanoTime() - now
        }
    }

    /** Después de leer [n] bytes. */
    fun afterRead(n: Int) {
        if (n <= 0) return
        bytes += n
        if (limit.kbps > 0) tokens -= n
    }

    private fun refill(now: Long) {
        if (limit.kbps > 0) tokens = minOf(capacity, tokens + (now - lastNs) / 1e9 * rate)
        lastNs = now
    }

    fun stats(): RxThrottleStats = RxThrottleStats(
        bytes = bytes,
        elapsedMs = (nanoTime() - startNs) / 1_000_000,
        throttledMs = TimeUnit.NANOSECONDS.toMillis(throttledNs),
        stalls = stalls,
        stalledMs = TimeUnit.NANOSECONDS.toMillis(stalledNs),
    )
}

/** [InputStream] que lee a través de un [RxThrottle]: nunca pide al de debajo más de lo que el límite deja. */
class ThrottledInputStream(input: InputStream, private val throttle: RxThrottle) : FilterInputStream(input) {
    override fun read(): Int {
        val one = ByteArray(1)
        val n = read(one, 0, 1)
        return if (n <= 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val allowed = throttle.beforeRead(len)
        val n = `in`.read(b, off, allowed)
        throttle.afterRead(n)
        return n
    }

    override fun skip(n: Long): Long {
        val buf = ByteArray(minOf(n, 8192L).toInt().coerceAtLeast(1))
        val r = read(buf, 0, buf.size)
        return if (r < 0) 0 else r.toLong()
    }

    override fun available(): Int = 0
}
