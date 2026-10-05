package dev.qdauto.core.util

/**
 * Lectura/escritura big-endian sobre arrays. Todo el protocolo QDLink es big-endian.
 * QDLink: IU/i.java:58-68 (lee int32), :91-93 (int16), :213-225 (escribe int32/int16), :54-56 (float BE).
 */
object BE {
    fun putInt(dst: ByteArray, off: Int, v: Int) {
        dst[off] = (v ushr 24).toByte()
        dst[off + 1] = (v ushr 16).toByte()
        dst[off + 2] = (v ushr 8).toByte()
        dst[off + 3] = v.toByte()
    }

    fun putShort(dst: ByteArray, off: Int, v: Int) {
        dst[off] = (v ushr 8).toByte()
        dst[off + 1] = v.toByte()
    }

    fun putFloat(dst: ByteArray, off: Int, v: Float) = putInt(dst, off, v.toRawBits())

    /** int32 con signo. */
    fun getInt(src: ByteArray, off: Int): Int =
        ((src[off].toInt() and 0xFF) shl 24) or
            ((src[off + 1].toInt() and 0xFF) shl 16) or
            ((src[off + 2].toInt() and 0xFF) shl 8) or
            (src[off + 3].toInt() and 0xFF)

    /** uint32 (0..4294967295). */
    fun getUInt(src: ByteArray, off: Int): Long = getInt(src, off).toLong() and 0xFFFF_FFFFL

    /** uint16 (0..65535). */
    fun getUShort(src: ByteArray, off: Int): Int =
        ((src[off].toInt() and 0xFF) shl 8) or (src[off + 1].toInt() and 0xFF)

    /** int16 con signo. */
    fun getShort(src: ByteArray, off: Int): Int = getUShort(src, off).toShort().toInt()

    fun getUByte(src: ByteArray, off: Int): Int = src[off].toInt() and 0xFF

    /** Byte con signo (-128..127), como hace QDLink con `fingerCount`/`fingerId`. */
    fun getByte(src: ByteArray, off: Int): Int = src[off].toInt()

    /** float32 IEEE-754 big-endian. */
    fun getFloat(src: ByteArray, off: Int): Float = Float.fromBits(getInt(src, off))
}
