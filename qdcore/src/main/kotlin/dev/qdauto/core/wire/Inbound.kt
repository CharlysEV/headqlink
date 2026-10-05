package dev.qdauto.core.wire

import dev.qdauto.core.json.JsonObject
import dev.qdauto.core.json.JsonParseException
import dev.qdauto.core.json.JsonParser
import dev.qdauto.core.util.Hex

/**
 * Mensaje de control (msgType 0) recibido. [cmd] es `""` si el JSON no se puede leer o no trae `CMD`
 * (igual que `h0/c.f()`); [json] es `null` si el JSON no se pudo parsear ([parseError] dice por qué).
 */
class ControlMessage(
    val header: Header,
    val cmd: String,
    val json: JsonObject?,
    val text: String,
    val parseError: String?,
) {
    val para: JsonObject? get() = json?.obj("PARA")
    override fun toString(): String = "ControlMessage($cmd, $text)"

    companion object {
        fun parse(header: Header, text: String): ControlMessage {
            val (json, error) = parseJsonObject(text)
            return ControlMessage(header, json?.string("CMD") ?: "", json, text, error)
        }
    }
}

/** Mensaje de "apps" (msgType 13): `{"AppID":…,"FunctionID":…,"Para":{…}}`. */
class AppMessage(
    val header: Header,
    val appId: String,
    val functionId: String,
    val json: JsonObject?,
    val text: String,
    val parseError: String?,
) {
    val para: JsonObject? get() = json?.obj("Para")
    val key: String get() = "$appId/$functionId"
    override fun toString(): String = "AppMessage($key, $text)"

    companion object {
        fun parse(header: Header, text: String): AppMessage {
            val (json, error) = parseJsonObject(text)
            return AppMessage(header, json?.string("AppID") ?: "", json?.string("FunctionID") ?: "", json, text, error)
        }
    }
}

/** msgType 12 (voz) y 99 (personalizado): QDLink los pasa a callbacks vacíos. */
class BinaryMessage(val header: Header, val payload: ByteArray) {
    override fun toString(): String = "BinaryMessage(${header.describe()}, ${payload.size} B)"
}

/** Algo que no sabemos interpretar: se registra siempre, nunca se descarta en silencio. */
class UnknownMessage(
    /** Cabecera 5A5A si la había (`null` para `!BIN` y basura). */
    val header: Header?,
    val reason: String,
    /** Bytes completos del mensaje (cabecera incluida), recortados para el log. */
    val bytes: ByteArray,
    val totalBytes: Long = bytes.size.toLong(),
) {
    val hexdump: String get() = Hex.dump(bytes, maxBytes = 512)
    override fun toString(): String = "UnknownMessage($reason, $totalBytes B)"
}

internal fun parseJsonObject(text: String): Pair<JsonObject?, String?> = try {
    // Como `new JSONObject(String)` de Android: lo que haya detrás del objeto (p. ej. un '\0') se ignora.
    JsonParser.parseObject(text, allowTrailing = true) to null
} catch (e: JsonParseException) {
    null to e.message
}
