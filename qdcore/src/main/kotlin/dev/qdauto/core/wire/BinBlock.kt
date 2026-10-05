package dev.qdauto.core.wire

import dev.qdauto.core.util.BE

/**
 * Bloque legado "!BIN" de 512 bytes (todos los campos u32 big-endian) más los datos extra que lo acompañan
 * en `dataType` 3 (con `action` 1) y 12. QDLink: LC/message/f.java:56-98 (nombres de campo), LC/a.java:342-356.
 */
class BinBlock(bytes: ByteArray, extra: ByteArray = ByteArray(0)) {
    private val block = bytes.copyOf()
    private val extraData = extra.copyOf()

    init {
        require(block.size == SIZE) { "un bloque !BIN mide $SIZE bytes" }
    }

    val bytes: ByteArray get() = block.copyOf()
    val extra: ByteArray get() = extraData.copyOf()
    val extraSize: Int get() = extraData.size

    val dataType: Int get() = BE.getInt(block, 4)
    val totalSize: Int get() = BE.getInt(block, 8)
    val headerSize: Int get() = BE.getInt(block, 12)
    val commonHeaderSize: Int get() = BE.getInt(block, 16)
    val requestHeaderSize: Int get() = BE.getInt(block, 20)
    val responseHeaderSize: Int get() = BE.getInt(block, 24)

    /** 1 = petición del coche, 2 = mensaje del teléfono. */
    val action: Int get() = BE.getInt(block, 28)
    val timestampOrDataSize: Int get() = BE.getInt(block, 64)
    val cmd: Int get() = BE.getInt(block, 68)
    val value: Int get() = BE.getInt(block, 72)
    val ret: Int get() = BE.getInt(block, 192)

    /** Heartbeat legado del coche (dataType 0, action 1, cmd 10, value 1). QDLink lo devuelve: LC/a.java:1122-1129. */
    val isLegacyHeartbeatRequest: Boolean get() = dataType == DATA_TYPE_COMMAND && action == 1 && cmd == CMD_HEARTBEAT && value == 1

    fun describe(): String =
        "!BIN dataType=$dataType action=$action cmd=$cmd value=$value total=$totalSize header=$headerSize ret=$ret extra=${extraData.size}"

    companion object {
        const val SIZE = 512
        const val MAGIC_TEXT = "!BIN"

        /** dataType. QDLink: LC/message/e.java:7-31. */
        const val DATA_TYPE_COMMAND = 0
        const val DATA_TYPE_SCREEN = 1
        const val DATA_TYPE_HU = 3
        const val DATA_TYPE_TOUCH = 10
        const val DATA_TYPE_SPEECH = 12

        /** cmd. QDLink: LC/message/c.java. */
        const val CMD_APP_STATUS = 1
        const val CMD_HEARTBEAT = 10

        fun hasMagic(src: ByteArray, off: Int = 0): Boolean =
            src.size - off >= 4 && src[off] == 0x21.toByte() && src[off + 1] == 0x42.toByte() &&
                src[off + 2] == 0x49.toByte() && src[off + 3] == 0x4E.toByte()

        /**
         * Datos extra que hay que leer tras el bloque (spec §3.6), o `null` si los campos no son coherentes:
         * dataType 3 con action 1 → totalsize − headersize; dataType 12 → totalsize − 512; el resto → 0.
         * QDLink: LC/a.java:1214-1242, :1263-1288.
         */
        fun extraLength(block: ByteArray): Int? {
            val dataType = BE.getInt(block, 4)
            val action = BE.getInt(block, 28)
            val n = when {
                dataType == DATA_TYPE_HU && action == 1 -> BE.getInt(block, 8).toLong() - BE.getInt(block, 12).toLong()
                dataType == DATA_TYPE_SPEECH -> BE.getInt(block, 8).toLong() - SIZE
                else -> 0L
            }
            return if (n in 0..Int.MAX_VALUE.toLong()) n.toInt() else null
        }

        /**
         * AppStatus que el teléfono manda una vez al empezar la sesión (512 B).
         * QDLink: LC/message/b.java:56-83, IU/b.java:31-35 (integrator_server = 2), enviado en LC/a.java:1344-1352.
         */
        fun appStatus(sdkInt: Int, integratorServer: Int = 2): ByteArray {
            val b = header(dataType = DATA_TYPE_COMMAND, action = 2)
            BE.putInt(b, 64, 0) // TimeStamp
            BE.putInt(b, 68, CMD_APP_STATUS)
            BE.putInt(b, 72, 1) // value
            BE.putInt(b, 76, sdkInt) // versionAndroid = Build.VERSION.SDK_INT
            BE.putInt(b, 80, integratorServer)
            return b
        }

        /**
         * Respuesta al heartbeat legado: mismos campos [4..72] y mark, `ret` = 1 en [192], resto a 0.
         * QDLink: LC/message/f.java:56-94 (`c()` lee, `a()` serializa), LC/a.java:1122-1129.
         */
        fun legacyHeartbeatReply(request: ByteArray): ByteArray {
            require(request.size >= SIZE) { "bloque !BIN incompleto" }
            val b = ByteArray(SIZE)
            request.copyInto(b, 0, 0, 76)
            BE.putInt(b, 192, 1)
            return b
        }

        /** Heartbeat legado tal y como lo mandaría el coche (para el simulador). */
        fun legacyHeartbeatRequest(): ByteArray = header(dataType = DATA_TYPE_COMMAND, action = 1).also {
            BE.putInt(it, 68, CMD_HEARTBEAT)
            BE.putInt(it, 72, 1)
        }

        /** Cabecera común de 64 bytes: magic, sizes 512/512/64/128/128, action y mark `20..3F`. */
        private fun header(dataType: Int, action: Int): ByteArray {
            val b = ByteArray(SIZE)
            b[0] = 0x21; b[1] = 0x42; b[2] = 0x49; b[3] = 0x4E // "!BIN"
            BE.putInt(b, 4, dataType)
            BE.putInt(b, 8, SIZE) // totalsize
            BE.putInt(b, 12, SIZE) // headersize
            BE.putInt(b, 16, 64) // commonHeaderSize
            BE.putInt(b, 20, 128) // requestHeaderSize
            BE.putInt(b, 24, 128) // responseHeaderSize
            BE.putInt(b, 28, action)
            for (i in 0 until 32) b[32 + i] = (0x20 + i).toByte() // mark
            return b
        }
    }
}
