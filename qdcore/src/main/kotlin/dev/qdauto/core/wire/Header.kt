package dev.qdauto.core.wire

import dev.qdauto.core.util.BE

/**
 * Cabecera común "5A5A" de 16 bytes (todo big-endian).
 * QDLink: h0/a.java:35-56 (serializa), :114-122 (parsea).
 *
 * | byte | campo |
 * |---|---|
 * | 0-3 | magic ASCII "5A5A" = 35 41 35 41 |
 * | 4-7 | totalSize u32 = 16 + extLen + payload |
 * | 8-9 | extendHeaderTotalSize u16 (0 en control, 32 en vídeo) |
 * | 10 | msgType |
 * | 11, 12 | sin nombre; QDLink manda 0 y no los mira |
 * | 13 | payLoadFormat (0 binario, 1 JSON, 2 vídeo) |
 * | 14 | reservedOne (subtipo del msgType 99) |
 * | 15 | relleno, QDLink no lo lee |
 */
data class Header(
    val totalSize: Int,
    val extLen: Int = 0,
    val msgType: Int,
    val byte11: Int = 0,
    val byte12: Int = 0,
    val payloadFormat: Int,
    val reservedOne: Int = 0,
    val byte15: Int = 0,
) {
    /** Bytes que siguen a la cabecera común (cabecera extendida + payload). */
    val bodySize: Int get() = totalSize - SIZE
    val payloadSize: Int get() = totalSize - SIZE - extLen

    fun encode(): ByteArray = ByteArray(SIZE).also { encodeInto(it, 0) }

    fun encodeInto(dst: ByteArray, off: Int) {
        MAGIC.copyInto(dst, off)
        BE.putInt(dst, off + 4, totalSize)
        BE.putShort(dst, off + 8, extLen)
        dst[off + 10] = msgType.toByte()
        dst[off + 11] = byte11.toByte()
        dst[off + 12] = byte12.toByte()
        dst[off + 13] = payloadFormat.toByte()
        dst[off + 14] = reservedOne.toByte()
        dst[off + 15] = byte15.toByte()
    }

    /** "type=0(CONTROL) total=35 ext=0 fmt=1". */
    fun describe(): String = buildString {
        append("type=").append(msgType).append('(').append(MsgType.name(msgType)).append(')')
        append(" total=").append(totalSize).append(" ext=").append(extLen).append(" fmt=").append(payloadFormat)
        if (byte11 != 0 || byte12 != 0 || reservedOne != 0 || byte15 != 0) {
            append(" b11=").append(byte11).append(" b12=").append(byte12)
            append(" r1=").append(reservedOne).append(" b15=").append(byte15)
        }
    }

    companion object {
        const val SIZE = 16
        const val MAGIC_TEXT = "5A5A"

        /** "5A5A" en ASCII (no 0x5A 0x5A). QDLink: h0/a.java:33. */
        val MAGIC: ByteArray get() = byteArrayOf(0x35, 0x41, 0x35, 0x41)

        fun hasMagic(src: ByteArray, off: Int = 0): Boolean =
            src.size - off >= 4 && src[off] == 0x35.toByte() && src[off + 1] == 0x41.toByte() &&
                src[off + 2] == 0x35.toByte() && src[off + 3] == 0x41.toByte()

        /** Lee los campos sin comprobar el magic. Bytes 8-9 como u16, el resto u8 (sin signo). */
        fun decode(src: ByteArray, off: Int = 0): Header {
            require(src.size - off >= SIZE) { "faltan bytes para la cabecera 5A5A" }
            return Header(
                totalSize = BE.getInt(src, off + 4),
                extLen = BE.getUShort(src, off + 8),
                msgType = BE.getUByte(src, off + 10),
                byte11 = BE.getUByte(src, off + 11),
                byte12 = BE.getUByte(src, off + 12),
                payloadFormat = BE.getUByte(src, off + 13),
                reservedOne = BE.getUByte(src, off + 14),
                byte15 = BE.getUByte(src, off + 15),
            )
        }
    }
}

/** msgType de la cabecera 5A5A. QDLink: LC/a.java:1944-2318. */
object MsgType {
    const val CONTROL = 0
    const val VIDEO = 1
    const val TOUCH = 2
    const val SPEECH = 12
    const val APP = 13
    const val CUSTOM = 99

    fun name(type: Int): String = when (type) {
        CONTROL -> "CONTROL"
        VIDEO -> "VIDEO"
        TOUCH -> "TOUCH"
        SPEECH -> "SPEECH"
        APP -> "APP"
        CUSTOM -> "CUSTOM"
        else -> "?"
    }
}

/** payLoadFormat (byte 13). QDLink: LC/a.java:953, :1476, :2076. */
object PayloadFormat {
    const val BINARY = 0
    const val JSON = 1
    const val VIDEO = 2
}

/** Montaje de mensajes 5A5A completos: un único array listo para un solo `write()`. */
object Frames {
    /** Mensaje con payload JSON (payLoadFormat 1, sin cabecera extendida). `totalSize` = 16 + bytes UTF-8. */
    fun json(msgType: Int, json: String, reservedOne: Int = 0): ByteArray =
        binary(msgType, PayloadFormat.JSON, json.toByteArray(Charsets.UTF_8), reservedOne)

    /** Mensaje con payload arbitrario y sin cabecera extendida. */
    fun binary(msgType: Int, payloadFormat: Int, payload: ByteArray, reservedOne: Int = 0): ByteArray {
        val out = ByteArray(Header.SIZE + payload.size)
        Header(totalSize = out.size, msgType = msgType, payloadFormat = payloadFormat, reservedOne = reservedOne).encodeInto(out, 0)
        payload.copyInto(out, Header.SIZE)
        return out
    }
}
