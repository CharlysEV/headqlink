package dev.qdauto.core.wire

/**
 * hql: trama por bloques del modo USB (AOA) de QDLink (spec 01 §7, 04 §3.5 y §8.4). Mismo protocolo 5A5A y misma
 * sesión que por Wi-Fi, con dos diferencias:
 * - **al escribir**, cada mensaje (JSON de control, heartbeat, SPS/PPS, IDR, P) se rellena con ceros hasta un múltiplo
 *   de [BLOCK] bytes y sale en **un solo** `write()`; el `totalSize` de la cabecera sigue siendo el tamaño real (48 + N
 *   en vídeo). El AppStatus `!BIN` ya mide 512 B;
 * - **al leer**, QDLink lee un bloque de [BLOCK] bytes, mira el magic, saca `totalSize` de la cabecera y lee el resto
 *   redondeado a bloques, ignorando el relleno ([FrameReader] con `blockSize`).
 *
 * Por qué bloques también al leer: en el accesorio USB cada `read()` es una transferencia *bulk*; con una petición que
 * no sea múltiplo del paquete (512 B) el núcleo de Linux tira lo que sobre del paquete, y con una más larga que el
 * mensaje la lectura espera a que llegue más. Así que las peticiones van siempre en múltiplos de [BLOCK] y sin pasar del
 * final (con relleno) del mensaje que se está leyendo.
 */
object BlockFraming {
    /** Tamaño de bloque del USB de QDLink (y del paquete *bulk* de USB 2.0 de alta velocidad). */
    const val BLOCK = 512

    /** [size] redondeado hacia arriba a un múltiplo de [block] (0 se queda en 0). */
    @JvmStatic
    @JvmOverloads
    fun paddedSize(size: Int, block: Int = BLOCK): Int {
        require(block > 0) { "bloque de $block B" }
        require(size >= 0) { "tamaño negativo: $size" }
        val r = size % block
        return if (r == 0) size else size + (block - r)
    }

    /** Ceros que hay que añadir a un mensaje de [size] bytes. */
    @JvmStatic
    @JvmOverloads
    fun padding(size: Int, block: Int = BLOCK): Int = paddedSize(size, block) - size

    /** [bytes] con ceros hasta un múltiplo de [block] (el mismo array si ya lo es). */
    @JvmStatic
    @JvmOverloads
    fun pad(bytes: ByteArray, block: Int = BLOCK): ByteArray {
        val n = paddedSize(bytes.size, block)
        return if (n == bytes.size) bytes else bytes.copyOf(n)
    }
}
