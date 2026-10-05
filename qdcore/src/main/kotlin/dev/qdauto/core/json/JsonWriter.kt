package dev.qdauto.core.json

import java.util.Locale

/**
 * Serializador compacto con la misma salida que `JSONObject.toString()` de Android (`JSONStringer`):
 * - sin espacios;
 * - escapa `"`, `\` y `/` con barra invertida, `\b \t \n \f \r` con su forma corta y el resto de controles
 *   (≤ 0x1F) como `\u00xx` en minúsculas; nada más (ni U+2028 ni no-ASCII);
 * - números como `JSONObject.numberToString`: enteros sin decimales, `double` integrales sin `.0`, `-0.0` → `-0`;
 *   NaN e infinitos no están permitidos.
 */
object JsonWriter {
    fun write(v: JsonValue): String = StringBuilder().also { append(it, v) }.toString()

    fun append(sb: StringBuilder, v: JsonValue) {
        when (v) {
            JsonNull -> sb.append("null")
            is JsonBool -> sb.append(if (v.value) "true" else "false")
            is JsonNumber -> sb.append(numberToString(v.value))
            is JsonString -> quote(sb, v.value)
            is JsonArray -> {
                sb.append('[')
                v.forEachIndexed { i, item ->
                    if (i > 0) sb.append(',')
                    append(sb, item)
                }
                sb.append(']')
            }
            is JsonObject -> {
                sb.append('{')
                var first = true
                for ((k, item) in v) {
                    if (!first) sb.append(',')
                    first = false
                    quote(sb, k)
                    sb.append(':')
                    append(sb, item)
                }
                sb.append('}')
            }
        }
    }

    fun quote(s: String): String = StringBuilder(s.length + 2).also { quote(it, s) }.toString()

    fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"', '\\', '/' -> sb.append('\\').append(c)
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\u000C' -> sb.append("\\f")
                else -> if (c.code <= 0x1F) sb.append(String.format(Locale.ROOT, "\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    fun numberToString(n: Number): String {
        if (n is Int || n is Long || n is Short || n is Byte) return n.toString()
        val d = n.toDouble()
        require(!d.isNaN() && !d.isInfinite()) { "JSON no admite NaN ni infinitos: $n" }
        if (d == 0.0 && 1.0 / d < 0) return "-0"
        val l = d.toLong()
        return if (d == l.toDouble()) l.toString() else d.toString()
    }
}
