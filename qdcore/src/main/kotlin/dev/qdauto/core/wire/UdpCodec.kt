package dev.qdauto.core.wire

import dev.qdauto.core.json.JsonObject
import dev.qdauto.core.json.JsonParseException
import dev.qdauto.core.json.JsonParser

/**
 * Datagrama de descubrimiento decodificado de forma tolerante (spec §1.2-§1.4).
 * - [envelopeOk]: magic y campos de longitud presentes y coherentes con el tamaño del datagrama.
 * - [qdlinkCompatible]: QDLink lo aceptaría como coche (JSON en el offset 49 con `DeviceUUID` y `DeviceName`).
 */
class UdpMessage(
    val type: String?,
    val json: JsonObject?,
    val jsonText: String?,
    val magicOffset: Int,
    val declaredTotal: Int?,
    val declaredTypeLength: Int?,
    val declaredJsonLength: Int?,
    val envelopeOk: Boolean,
    val qdlinkCompatible: Boolean,
    val warnings: List<String>,
) {
    override fun toString(): String = "UdpMessage(type=$type, json=$jsonText, ok=$envelopeOk, qdlink=$qdlinkCompatible, warnings=$warnings)"
}

/** `Broadcast_ACK` decodificado (lado coche / simulador). */
data class BroadcastAck(
    val mirrorPort: Int,
    val controlPort: Int,
    val audioPort: Int,
    val os: Int,
    val deviceName: String?,
    val deviceUuid: String?,
    val json: JsonObject,
)

/**
 * Sobre UDP: `QDrive_SSPLink_UDP_MSG` + total (4 hex) + N (2 hex) + tipo + M (4 hex) + JSON, todo ASCII, hex en
 * MAYÚSCULAS. `total` = 32 + N + M cuenta el datagrama entero. QDLink: WF/a.java:30-34, :118-137, :171-190, :491-531.
 */
object UdpCodec {
    const val MAGIC = "QDrive_SSPLink_UDP_MSG"
    const val CONNECT_BROADCAST = "Connect_Broadcast"
    const val BROADCAST_ACK = "Broadcast_ACK"

    /** Puerto en el que escucha el teléfono (WF/a.java:30). */
    const val PHONE_PORT = 18463

    /** Puerto del coche al que va el ACK (WF/a.java:31). */
    const val CAR_PORT = 18464

    /** QDLink lee el JSON del broadcast en `substring(49)` = 22 + 4 + 2 + 17 + 4 (WF/a.java:501). */
    const val QDLINK_JSON_OFFSET = 49

    /** Sobre completo. Las longitudes salen de `String.length` como en QDLink (WF/a.java:187-189). */
    fun encode(type: String, json: String): ByteArray {
        val total = MAGIC.length + 4 + 2 + type.length + 4 + json.length
        return (MAGIC + hex(total, 4) + hex(type.length, 2) + type + hex(json.length, 4) + json).toByteArray(Charsets.UTF_8)
    }

    /**
     * JSON del ACK con las claves en el orden de inserción de QDLink (WF/a.java:171-186):
     * `{"ControlPort":0,"MirrorPort":P,"AudioPort":0,"OS":0,"DeviceName":"","DeviceUUID":"","DeviceFeature":{"PassistMobileNum":""}}`.
     */
    fun broadcastAckJson(
        mirrorPort: Int,
        deviceName: String = "",
        deviceUuid: String = "",
        passistMobileNum: String = "",
        controlPort: Int = 0,
        audioPort: Int = 0,
        os: Int = 0,
    ): String = JsonObject.of(
        "ControlPort" to controlPort,
        "MirrorPort" to mirrorPort,
        "AudioPort" to audioPort,
        "OS" to os,
        "DeviceName" to deviceName,
        "DeviceUUID" to deviceUuid,
        "DeviceFeature" to JsonObject.of("PassistMobileNum" to passistMobileNum),
    ).toJson()

    /** ACK listo para enviar desde el socket de 18463 a `IP_coche:18464`. Con un puerto de 5 cifras mide 174 B. */
    fun buildBroadcastAck(
        mirrorPort: Int,
        deviceName: String = "",
        deviceUuid: String = "",
        passistMobileNum: String = "",
    ): ByteArray = encode(BROADCAST_ACK, broadcastAckJson(mirrorPort, deviceName, deviceUuid, passistMobileNum))

    /** `Connect_Broadcast` como lo mandaría el coche (simulador). [extra] se añade tras las dos claves obligatorias. */
    fun buildConnectBroadcast(deviceUuid: String, deviceName: String, extra: JsonObject? = null): ByteArray {
        val map = LinkedHashMap<String, Any?>()
        map["DeviceUUID"] = deviceUuid
        map["DeviceName"] = deviceName
        extra?.forEach { (k, v) -> map[k] = v }
        return encode(CONNECT_BROADCAST, dev.qdauto.core.json.Json.toValue(map).toJson())
    }

    /**
     * Decodificación tolerante (recomendación de spec §1.4): localiza el magic, lee las longitudes en hex sin distinguir
     * mayúsculas y, si no cuadran, usa el offset 49 y luego el primer `{`. Nunca lanza.
     */
    fun parse(data: ByteArray, off: Int = 0, len: Int = data.size - off): UdpMessage {
        val bytes = data.copyOfRange(off, off + len)
        val latin = String(bytes, Charsets.ISO_8859_1) // 1 char = 1 byte, para trabajar con offsets de byte
        val warnings = ArrayList<String>()
        val magicAt = latin.indexOf(MAGIC)
        var type: String? = null
        var declaredTotal: Int? = null
        var declaredTypeLen: Int? = null
        var declaredJsonLen: Int? = null
        var jsonStart = -1
        var envelopeOk = false

        if (magicAt < 0) {
            warnings += "no aparece el magic $MAGIC"
        } else {
            if (magicAt > 0) warnings += "el magic empieza en el byte $magicAt"
            var p = magicAt + MAGIC.length
            declaredTotal = hexAt(latin, p, 4)
            declaredTypeLen = hexAt(latin, p + 4, 2)
            if (declaredTotal == null || declaredTypeLen == null) {
                warnings += "campos de longitud ilegibles tras el magic"
            } else {
                p += 6
                if (p + declaredTypeLen + 4 > latin.length) {
                    warnings += "la longitud del tipo ($declaredTypeLen) no cabe en el datagrama"
                } else {
                    type = latin.substring(p, p + declaredTypeLen)
                    p += declaredTypeLen
                    declaredJsonLen = hexAt(latin, p, 4)
                    if (declaredJsonLen == null) {
                        warnings += "longitud del JSON ilegible"
                    } else {
                        jsonStart = p + 4
                        val realJson = latin.length - jsonStart
                        val realTotal = latin.length - magicAt
                        envelopeOk = declaredJsonLen == realJson && declaredTotal == realTotal
                        if (declaredJsonLen != realJson) warnings += "longitud de JSON declarada $declaredJsonLen, real $realJson"
                        if (declaredTotal != realTotal) warnings += "longitud total declarada $declaredTotal, real $realTotal"
                        if (latin.getOrNull(jsonStart) != '{') {
                            warnings += "el JSON no empieza donde dicen las longitudes"
                            jsonStart = -1
                            envelopeOk = false
                        }
                    }
                }
            }
        }
        if (type == null) {
            type = when {
                latin.contains(CONNECT_BROADCAST) -> CONNECT_BROADCAST
                latin.contains(BROADCAST_ACK) -> BROADCAST_ACK
                else -> null
            }
        }
        if (jsonStart < 0) {
            jsonStart = if (latin.getOrNull(QDLINK_JSON_OFFSET) == '{') QDLINK_JSON_OFFSET else latin.indexOf('{')
            if (jsonStart >= 0) warnings += "JSON localizado por búsqueda en el byte $jsonStart"
        }
        var json: JsonObject? = null
        var jsonText: String? = null
        if (jsonStart >= 0) {
            jsonText = String(bytes, jsonStart, bytes.size - jsonStart, Charsets.UTF_8)
            try {
                json = JsonParser.parseObject(jsonText, allowTrailing = true)
            } catch (e: JsonParseException) {
                warnings += "JSON ilegible: ${e.message}"
            }
        } else {
            warnings += "no hay JSON"
        }
        return UdpMessage(
            type = type,
            json = json,
            jsonText = jsonText,
            magicOffset = magicAt,
            declaredTotal = declaredTotal,
            declaredTypeLength = declaredTypeLen,
            declaredJsonLength = declaredJsonLen,
            envelopeOk = envelopeOk,
            qdlinkCompatible = qdlinkAccepts(bytes),
            warnings = warnings,
        )
    }

    /** Decodifica un ACK (simulador). `null` si no es un `Broadcast_ACK` con `MirrorPort` válido. */
    fun parseBroadcastAck(data: ByteArray, off: Int = 0, len: Int = data.size - off): BroadcastAck? {
        val m = parse(data, off, len)
        val json = m.json ?: return null
        if (m.type != BROADCAST_ACK) return null
        val port = json.int("MirrorPort") ?: return null
        return BroadcastAck(
            mirrorPort = port,
            controlPort = json.int("ControlPort") ?: 0,
            audioPort = json.int("AudioPort") ?: 0,
            os = json.int("OS") ?: 0,
            deviceName = json.string("DeviceName"),
            deviceUuid = json.string("DeviceUUID"),
            json = json,
        )
    }

    /**
     * Lo que exige QDLink a un `Connect_Broadcast`: `new JSONObject(str.substring(49))` con `DeviceUUID` y `DeviceName`
     * (WF/a.java:501-503) y, para listarlo, que el texto contenga el magic y `Connect_Broadcast` (WF/a.java:561).
     */
    fun qdlinkAccepts(bytes: ByteArray): Boolean {
        val str = String(bytes, Charsets.UTF_8)
        if (str.length <= QDLINK_JSON_OFFSET || !str.contains(MAGIC) || !str.contains(CONNECT_BROADCAST)) return false
        return try {
            val json = JsonParser.parseObject(str.substring(QDLINK_JSON_OFFSET), allowTrailing = true)
            json.containsKey("DeviceUUID") && json.containsKey("DeviceName")
        } catch (_: JsonParseException) {
            false
        }
    }

    /** Hex en mayúsculas con ceros a la izquierda hasta [digits] (IU/i.java:179-193). */
    fun hex(value: Int, digits: Int): String = Integer.toHexString(value).uppercase().padStart(digits, '0')

    private fun hexAt(s: String, at: Int, digits: Int): Int? {
        if (at < 0 || at + digits > s.length) return null
        val part = s.substring(at, at + digits)
        if (!part.all { Character.digit(it, 16) >= 0 }) return null
        return part.toInt(16)
    }
}
