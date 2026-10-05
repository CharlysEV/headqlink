package dev.qdauto.core.h264

/** Una unidad NAL dentro de un buffer Annex-B: [offset]/[length] cubren la NAL sin el start code. */
data class NalUnit(val offset: Int, val length: Int, val startCodeLength: Int, val header: Int) {
    val type: Int get() = header and 0x1F
    val forbiddenBit: Boolean get() = header and 0x80 != 0
    val nalRefIdc: Int get() = (header ushr 5) and 0x3
}

/** Tipos de NAL H.264 más habituales. */
object NalType {
    const val SLICE = 1
    const val IDR = 5
    const val SEI = 6
    const val SPS = 7
    const val PPS = 8
    const val AUD = 9

    fun name(type: Int): String = when (type) {
        SLICE -> "P/B"
        IDR -> "IDR"
        SEI -> "SEI"
        SPS -> "SPS"
        PPS -> "PPS"
        AUD -> "AUD"
        else -> "NAL$type"
    }
}

/** Utilidades para H.264 en formato Annex-B (start codes `00 00 01` / `00 00 00 01`). */
object AnnexB {
    /** Longitud del start code al principio de [buf] (3 o 4), o 0 si no empieza por uno. */
    fun startCodeLength(buf: ByteArray, off: Int = 0, len: Int = buf.size - off): Int = when {
        len >= 4 && buf[off] == 0.toByte() && buf[off + 1] == 0.toByte() && buf[off + 2] == 0.toByte() && buf[off + 3] == 1.toByte() -> 4
        len >= 3 && buf[off] == 0.toByte() && buf[off + 1] == 0.toByte() && buf[off + 2] == 1.toByte() -> 3
        else -> 0
    }

    /** Todas las NAL del buffer, en orden. Lo que haya antes del primer start code se ignora. */
    fun nalUnits(buf: ByteArray, off: Int = 0, len: Int = buf.size - off): List<NalUnit> {
        val end = off + len
        val starts = ArrayList<IntArray>() // [posición del start code, longitud del start code]
        var i = off
        while (i + 3 <= end) {
            if (buf[i] == 0.toByte() && buf[i + 1] == 0.toByte()) {
                if (buf[i + 2] == 1.toByte()) {
                    val four = i > off && buf[i - 1] == 0.toByte()
                    starts.add(if (four) intArrayOf(i - 1, 4) else intArrayOf(i, 3))
                    i += 3
                    continue
                }
            }
            i++
        }
        val out = ArrayList<NalUnit>(starts.size)
        for ((k, s) in starts.withIndex()) {
            val nalStart = s[0] + s[1]
            val nalEnd = if (k + 1 < starts.size) starts[k + 1][0] else end
            if (nalStart >= nalEnd) continue
            out.add(NalUnit(nalStart, nalEnd - nalStart, s[1], buf[nalStart].toInt() and 0xFF))
        }
        return out
    }

    fun nalTypes(buf: ByteArray, off: Int = 0, len: Int = buf.size - off): List<Int> = nalUnits(buf, off, len).map { it.type }

    fun containsIdr(buf: ByteArray, off: Int = 0, len: Int = buf.size - off): Boolean = nalTypes(buf, off, len).contains(NalType.IDR)

    /** SPS y PPS sin slices: el mensaje de configuración que QDLink manda aparte (spec §8.2). */
    fun isCodecConfig(buf: ByteArray, off: Int = 0, len: Int = buf.size - off): Boolean {
        val types = nalTypes(buf, off, len)
        return NalType.SPS in types && NalType.PPS in types && types.none { it == NalType.IDR || it == NalType.SLICE }
    }
}
