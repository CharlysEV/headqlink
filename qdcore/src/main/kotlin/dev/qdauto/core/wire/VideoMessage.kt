package dev.qdauto.core.wire

import dev.qdauto.core.util.BE
import java.nio.ByteBuffer

/**
 * Lo que va en la cabecera extendida de 32 bytes de cada mensaje de vídeo.
 * QDLink: LC/a.java:1453-1481 (`l0()` la rellena), h0/d.java:71-88 (serializa).
 *
 * - [width]/[height]: "dataWidth/dataHeight" (u32). In-app = inCar; espejo = outHor.
 * - [fps]/[bitrate]/[gop]/[encodingType]: eco crudo de `VIDEO_ARGS` (FrameRate, BitRate, FrameInterval, EncodingType).
 * - [appType]: 1 = UI propia (in-app), 2 = espejo.
 * - [angle] (i16) y [orientation] (i8, -1 = 0xFF): in-app 90 / 1.
 */
data class VideoParams(
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrate: Int,
    val gop: Int,
    val encodingType: Int,
    val appType: Int = APP_TYPE_IN_APP,
    val angle: Int = 90,
    val orientation: Int = 1,
) {
    companion object {
        const val APP_TYPE_IN_APP = 1
        const val APP_TYPE_MIRROR = 2
    }
}

/** Cabecera extendida de vídeo decodificada, con los campos fijos para poder validarlos (simulador). */
data class VideoExtHeader(
    /** u16 en [0]: longitud de la ext, QDLink siempre 32 (h0/d.java:9, :51). */
    val extLength: Int,
    /** u8 en [2]: QDLink siempre 1 (`h0/b.e(1)`, LC/a.java:1458). */
    val version: Int,
    /** u8 en [3]: nunca se asigna (0). */
    val reserved3: Int,
    val params: VideoParams,
    /** Bytes [29..31], QDLink los deja a 0. */
    val tail: Int,
) {
    companion object {
        fun decode(src: ByteArray, off: Int): VideoExtHeader {
            require(src.size - off >= VideoMessage.EXT_SIZE) { "faltan bytes para la cabecera de vídeo" }
            return VideoExtHeader(
                extLength = BE.getUShort(src, off),
                version = BE.getUByte(src, off + 2),
                reserved3 = BE.getUByte(src, off + 3),
                params = VideoParams(
                    width = BE.getInt(src, off + 4),
                    height = BE.getInt(src, off + 8),
                    angle = BE.getShort(src, off + 12),
                    orientation = BE.getByte(src, off + 14),
                    encodingType = BE.getUByte(src, off + 15),
                    fps = BE.getInt(src, off + 16),
                    bitrate = BE.getInt(src, off + 20),
                    gop = BE.getInt(src, off + 24),
                    appType = BE.getUByte(src, off + 28),
                ),
                tail = (BE.getUByte(src, off + 29) shl 16) or (BE.getUByte(src, off + 30) shl 8) or BE.getUByte(src, off + 31),
            )
        }
    }
}

/**
 * Mensaje de vídeo (msgType 1): 16 B de cabecera común + 32 B de cabecera extendida + H.264 Annex-B.
 * `totalSize` = 48 + payload; sin timestamp, secuencia, CRC ni marca de keyframe (spec §8.1).
 * Un mensaje por buffer de salida del encoder; SPS‖PPS van juntos en un mensaje propio (spec §8.2).
 */
object VideoMessage {
    const val EXT_SIZE = 32
    const val HEADER_SIZE = Header.SIZE + EXT_SIZE

    /** Mensaje completo copiando el payload una sola vez. */
    fun build(params: VideoParams, payload: ByteArray, off: Int = 0, len: Int = payload.size - off): ByteArray {
        require(off >= 0 && len >= 0 && off + len <= payload.size) { "rango fuera del array" }
        val msg = ByteArray(HEADER_SIZE + len)
        System.arraycopy(payload, off, msg, HEADER_SIZE, len)
        writeHeaders(msg, len, params)
        return msg
    }

    /** Copia los bytes restantes de [payload] (de `position` a `limit`) sin mover su `position`. */
    fun build(params: VideoParams, payload: ByteBuffer): ByteArray {
        val src = payload.duplicate()
        val len = src.remaining()
        val msg = ByteArray(HEADER_SIZE + len)
        src.get(msg, HEADER_SIZE, len)
        writeHeaders(msg, len, params)
        return msg
    }

    /**
     * Rellena en su sitio las dos cabeceras de un array que ya lleva el payload en [HEADER_SIZE]
     * (para quien quiera reservar el hueco y evitar cualquier copia extra).
     */
    fun writeHeaders(msg: ByteArray, payloadLength: Int, params: VideoParams) {
        require(msg.size >= HEADER_SIZE + payloadLength) { "array demasiado pequeño" }
        Header(
            totalSize = HEADER_SIZE + payloadLength,
            extLen = EXT_SIZE,
            msgType = MsgType.VIDEO,
            payloadFormat = PayloadFormat.VIDEO,
        ).encodeInto(msg, 0)
        writeExt(msg, Header.SIZE, params)
    }

    /** Cabecera extendida. QDLink: h0/d.java:71-88 (orden comprobado en dex `h0.d.h()`). */
    fun writeExt(dst: ByteArray, off: Int, p: VideoParams) {
        dst.fill(0, off, off + EXT_SIZE)
        BE.putShort(dst, off, EXT_SIZE)
        dst[off + 2] = 1
        BE.putInt(dst, off + 4, p.width)
        BE.putInt(dst, off + 8, p.height)
        BE.putShort(dst, off + 12, p.angle)
        dst[off + 14] = p.orientation.toByte()
        dst[off + 15] = p.encodingType.toByte()
        BE.putInt(dst, off + 16, p.fps)
        BE.putInt(dst, off + 20, p.bitrate)
        BE.putInt(dst, off + 24, p.gop)
        dst[off + 28] = p.appType.toByte()
    }
}
