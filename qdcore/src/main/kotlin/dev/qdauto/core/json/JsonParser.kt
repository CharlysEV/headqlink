package dev.qdauto.core.json

class JsonParseException(message: String, val offset: Int) : Exception("$message (posición $offset)")

/**
 * Parser JSON (RFC 8259) con dos concesiones a `org.json`:
 * - `allowTrailing = true` ignora lo que haya detrás del primer valor, como `new JSONObject(String)` de Android
 *   (útil si el coche añade un `\0` o relleno al final);
 * - acepta caracteres de control sin escapar dentro de las cadenas.
 * Los enteros se guardan como Int/Long (Double si no caben en long) y los literales con `.`/`e`/`E` como Double.
 */
object JsonParser {
    private const val MAX_DEPTH = 256

    fun parse(text: String, allowTrailing: Boolean = false): JsonValue {
        val p = Cursor(text)
        p.skipWs()
        if (p.pos < text.length && text[p.pos] == '﻿') p.pos++
        p.skipWs()
        val v = p.value(0)
        p.skipWs()
        if (!allowTrailing && p.pos < text.length) throw JsonParseException("contenido sobrante tras el valor JSON", p.pos)
        return v
    }

    fun parseObject(text: String, allowTrailing: Boolean = false): JsonObject =
        parse(text, allowTrailing) as? JsonObject ?: throw JsonParseException("el valor raíz no es un objeto", 0)

    fun parseOrNull(text: String, allowTrailing: Boolean = false): JsonValue? = try {
        parse(text, allowTrailing)
    } catch (_: JsonParseException) {
        null
    }

    private class Cursor(val s: String) {
        var pos = 0

        fun skipWs() {
            while (pos < s.length) {
                when (s[pos]) {
                    ' ', '\t', '\n', '\r' -> pos++
                    else -> return
                }
            }
        }

        fun fail(msg: String): Nothing = throw JsonParseException(msg, pos)

        fun value(depth: Int): JsonValue {
            if (depth > MAX_DEPTH) fail("anidamiento demasiado profundo")
            if (pos >= s.length) fail("fin inesperado")
            return when (val c = s[pos]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> JsonString(str())
                't' -> literal("true", JsonBool(true))
                'f' -> literal("false", JsonBool(false))
                'n' -> literal("null", JsonNull)
                else -> if (c == '-' || c in '0'..'9') num() else fail("carácter inesperado '${printable(c)}'")
            }
        }

        private fun literal(word: String, v: JsonValue): JsonValue {
            if (!s.startsWith(word, pos)) fail("literal no válido")
            pos += word.length
            return v
        }

        private fun obj(depth: Int): JsonObject {
            pos++ // {
            val map = LinkedHashMap<String, JsonValue>()
            skipWs()
            if (pos < s.length && s[pos] == '}') {
                pos++
                return JsonObject(map)
            }
            while (true) {
                skipWs()
                if (pos >= s.length || s[pos] != '"') fail("se esperaba una clave entre comillas")
                val key = str()
                skipWs()
                if (pos >= s.length || s[pos] != ':') fail("se esperaba ':'")
                pos++
                skipWs()
                map[key] = value(depth + 1) // clave repetida: gana la última (como org.json)
                skipWs()
                if (pos >= s.length) fail("objeto sin cerrar")
                when (s[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return JsonObject(map)
                    }
                    else -> fail("se esperaba ',' o '}'")
                }
            }
        }

        private fun arr(depth: Int): JsonArray {
            pos++ // [
            val items = ArrayList<JsonValue>()
            skipWs()
            if (pos < s.length && s[pos] == ']') {
                pos++
                return JsonArray(items)
            }
            while (true) {
                skipWs()
                items.add(value(depth + 1))
                skipWs()
                if (pos >= s.length) fail("array sin cerrar")
                when (s[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return JsonArray(items)
                    }
                    else -> fail("se esperaba ',' o ']'")
                }
            }
        }

        private fun str(): String {
            pos++ // "
            val sb = StringBuilder()
            while (true) {
                if (pos >= s.length) fail("cadena sin cerrar")
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= s.length) fail("escape incompleto")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) fail("escape \\u incompleto")
                                val code = s.substring(pos, pos + 4).toIntOrNull(16) ?: fail("escape \\u no válido")
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> fail("escape no válido '\\${printable(e)}'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): JsonNumber {
            val start = pos
            if (s[pos] == '-') pos++
            if (pos >= s.length || s[pos] !in '0'..'9') fail("número no válido")
            while (pos < s.length && s[pos] in '0'..'9') pos++
            var isDouble = false
            if (pos < s.length && s[pos] == '.') {
                isDouble = true
                pos++
                if (pos >= s.length || s[pos] !in '0'..'9') fail("número no válido")
                while (pos < s.length && s[pos] in '0'..'9') pos++
            }
            if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
                isDouble = true
                pos++
                if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
                if (pos >= s.length || s[pos] !in '0'..'9') fail("exponente no válido")
                while (pos < s.length && s[pos] in '0'..'9') pos++
            }
            val lit = s.substring(start, pos)
            if (!isDouble) {
                lit.toLongOrNull()?.let { return JsonNumber.of(it) }
            }
            return JsonNumber.of(lit.toDouble())
        }

        private fun printable(c: Char): String = if (c.code in 0x20..0x7E) c.toString() else "\\u%04x".format(c.code)
    }
}
