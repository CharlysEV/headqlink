package dev.qdauto.core.util

/** Utilidades hexadecimales para logs y tests. */
object Hex {
    private val DIGITS = "0123456789abcdef".toCharArray()

    /** "35 41 35 41" (minúsculas, separadas por [separator]). */
    fun encode(bytes: ByteArray, off: Int = 0, len: Int = bytes.size - off, separator: String = " "): String {
        require(off >= 0 && len >= 0 && off + len <= bytes.size) { "rango fuera del array" }
        val sb = StringBuilder(len * (2 + separator.length))
        for (i in 0 until len) {
            if (i > 0) sb.append(separator)
            appendByte(sb, bytes[off + i].toInt())
        }
        return sb.toString()
    }

    /** Decodifica pares hex ignorando espacios, saltos de línea, ':' y '-'. */
    fun decode(text: String): ByteArray {
        val clean = text.filter { !it.isWhitespace() && it != ':' && it != '-' }
        require(clean.length % 2 == 0) { "número impar de dígitos hex" }
        return ByteArray(clean.length / 2) { i ->
            ((Character.digit(clean[2 * i], 16) shl 4) or Character.digit(clean[2 * i + 1], 16)).also {
                require(it >= 0) { "dígito hex no válido en la posición ${2 * i}" }
            }.toByte()
        }
    }

    /**
     * Volcado clásico, el mismo formato que usa la especificación:
     * `0000: 35 41 35 41 00 00 00 23  00 00 00 00 00 01 00 00  |5A5A...#........|`.
     * Si hay más de [maxBytes] bytes, se corta y se añade una línea con lo que falta.
     */
    fun dump(bytes: ByteArray, off: Int = 0, len: Int = bytes.size - off, maxBytes: Int = Int.MAX_VALUE): String {
        require(off >= 0 && len >= 0 && off + len <= bytes.size) { "rango fuera del array" }
        val shown = minOf(len, maxBytes)
        val offsetDigits = maxOf(4, Integer.toHexString(maxOf(shown - 1, 0)).length)
        val sb = StringBuilder()
        var line = 0
        while (line < shown) {
            if (line > 0) sb.append('\n')
            sb.append(Integer.toHexString(line).padStart(offsetDigits, '0')).append(": ")
            val n = minOf(16, shown - line)
            for (i in 0 until 16) {
                if (i == 8) sb.append(' ')
                if (i < n) appendByte(sb, bytes[off + line + i].toInt()).append(' ') else sb.append("   ")
            }
            sb.append(" |")
            for (i in 0 until n) {
                val c = bytes[off + line + i].toInt() and 0xFF
                sb.append(if (c in 0x20..0x7E) c.toChar() else '.')
            }
            sb.append('|')
            line += 16
        }
        if (shown < len) {
            if (shown > 0) sb.append('\n')
            sb.append("… (${len - shown} bytes más, $len en total)")
        }
        return sb.toString()
    }

    /** Prefijo corto para una línea de log: "35 41 35 41 … (+123 B)". */
    fun prefix(bytes: ByteArray, off: Int = 0, len: Int = bytes.size - off, maxBytes: Int = 32): String {
        val shown = minOf(len, maxBytes)
        val head = encode(bytes, off, shown)
        return if (shown < len) "$head … (+${len - shown} B)" else head
    }

    private fun appendByte(sb: StringBuilder, b: Int): StringBuilder =
        sb.append(DIGITS[(b ushr 4) and 0xF]).append(DIGITS[b and 0xF])
}
