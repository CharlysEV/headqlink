package dev.qdauto.core

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.DatagramSocket
import java.net.ServerSocket

/** Utilidades de test. */
object TestSupport {
    /**
     * Bytes de un volcado de la spec: `0000: 35 41 …  |ASCII|`. Ignora el offset (hasta el primer ':') y la columna
     * ASCII (desde el primer '|'); acepta líneas de solo hex.
     */
    fun fromDump(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        for (raw in text.lines()) {
            var line = raw.trim()
            if (line.isEmpty()) continue
            val bar = line.indexOf('|')
            if (bar >= 0) line = line.substring(0, bar)
            val colon = line.indexOf(':')
            if (colon in 0..8) line = line.substring(colon + 1)
            line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() && it != "…" }.forEach { out.write(it.toInt(16)) }
        }
        return out.toByteArray()
    }

    fun freeUdpPort(): Int = DatagramSocket(0).use { it.localPort }
    fun freeTcpPort(): Int = ServerSocket(0).use { it.localPort }

    /** Espera activa (con sondeo) a que se cumpla [condition]. */
    fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    /** InputStream que entrega los datos de [chunk] en [chunk] bytes por `read()`. */
    class ChunkedInputStream(private val data: ByteArray, private val chunk: Int = 1) : InputStream() {
        private var pos = 0
        override fun read(): Int = if (pos < data.size) data[pos++].toInt() and 0xFF else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (pos >= data.size) return -1
            val n = minOf(len, chunk, data.size - pos)
            System.arraycopy(data, pos, b, off, n)
            pos += n
            return n
        }
    }

    /** Hilos vivos cuyo nombre empieza por alguno de los prefijos. */
    fun liveThreads(vararg prefixes: String): List<String> =
        Thread.getAllStackTraces().keys.filter { t -> t.isAlive && prefixes.any { t.name.startsWith(it) } }.map { it.name }
}
