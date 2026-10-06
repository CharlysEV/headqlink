package dev.qdauto.core.wire

import dev.qdauto.core.util.BE
import dev.qdauto.core.util.Hex
import java.io.IOException
import java.io.InputStream

/** Lo que sale del flujo TCP, en cualquiera de los dos sentidos. */
sealed class WireMessage {
    /** Mensaje 5A5A completo. [body] = cabecera extendida + payload (`totalSize − 16` bytes). */
    class Frame(val header: Header, val body: ByteArray) : WireMessage() {
        /** La cabecera extendida cabe en el cuerpo (si no, QDLink descarta el mensaje: LC/a.java:1938, :2319). */
        val extValid: Boolean get() = header.extLen in 0..body.size
        val payloadOffset: Int get() = if (extValid) header.extLen else body.size
        val payloadLength: Int get() = body.size - payloadOffset
        fun payload(): ByteArray = body.copyOfRange(payloadOffset, body.size)

        /** Payload como texto UTF-8 (lo que hace QDLink con `new String(bytes)`). */
        fun payloadText(): String = String(body, payloadOffset, payloadLength, Charsets.UTF_8)

        /** Mensaje completo tal y como viajó (cabecera + cuerpo). */
        fun wireBytes(): ByteArray = ByteArray(Header.SIZE + body.size).also {
            header.encodeInto(it, 0)
            body.copyInto(it, Header.SIZE)
        }
    }

    /** Bloque legado `!BIN` (512 B) más sus datos extra. */
    class Bin(val block: BinBlock) : WireMessage()

    /**
     * Bytes que no forman un mensaje válido: magic desconocido, cabecera incoherente o flujo cortado a medias.
     * [bytes] guarda como mucho [FrameReader.MAX_GARBAGE_KEPT] bytes; [totalBytes] cuenta todos.
     */
    class Garbage(val bytes: ByteArray, val totalBytes: Long, val reason: String) : WireMessage() {
        fun hexdump(): String = Hex.dump(bytes)
    }
}

/**
 * Reensambla mensajes "5A5A" y "!BIN" de un [InputStream] (spec §3.6):
 * - lee la cabecera completa aunque llegue a trozos (QDLink hace un solo `read()` y se desincroniza);
 * - 5A5A: exige `16 ≤ totalSize ≤ maxMessageSize`; el cuerpo se lee entero (`readFully`);
 * - !BIN: 512 bytes y los datos extra de `dataType` 3 (action 1) y 12, como QDLink (LC/a.java:1214-1288);
 * - magic desconocido o cabecera imposible: devuelve [WireMessage.Garbage] y se resincroniza buscando
 *   `5A5A`/`!BIN` byte a byte (QDLink descarta 16 bytes a ciegas).
 *
 * hql: con [blockSize] > 0, trama por bloques del modo USB ([BlockFraming]):
 * - cada `read()` pide un múltiplo de [blockSize] y nunca pasa del final (con relleno) del mensaje en curso: el primero
 *   de un mensaje, un bloque (como QDLink); el resto del cuerpo, redondeado a bloques;
 * - tras cada mensaje se saltan los ceros de relleno hasta el siguiente múltiplo de [blockSize] (contados desde el
 *   inicio del mensaje). Es tolerante: si una lectura trae menos, se sigue leyendo; si el emisor no rellena (aparece un
 *   magic donde tocaba relleno), se cuenta en [unpaddedMessages] y se sigue sin perder nada; los ceros de más entre
 *   mensajes se saltan ([strayZeroBytes]): un mensaje nunca empieza por 0, así que el relleno no desincroniza nunca.
 *
 * No es thread-safe: un solo hilo lector.
 */
class FrameReader @JvmOverloads constructor(
    private val input: InputStream,
    private val maxMessageSize: Int = DEFAULT_MAX_MESSAGE_SIZE,
    bufferSize: Int = 64 * 1024,
    /** hql: 0 = flujo continuo (TCP); > 0 = bloques con relleno (USB: [BlockFraming.BLOCK]). */
    val blockSize: Int = 0,
) {
    private val blockMode = blockSize > 0
    private val buf = ByteArray(
        if (blockMode) BlockFraming.paddedSize(maxOf(bufferSize, BinBlock.SIZE + 2 * blockSize), blockSize) else maxOf(bufferSize, BinBlock.SIZE),
    )
    private var pos = 0
    private var lim = 0
    private var eof = false

    /** hql: ceros de relleno que quedan por saltar tras el último mensaje (modo bloques). */
    private var padPending = 0

    /** hql (bloques): mensajes que llegaron con su relleno completo (o que ya medían un múltiplo del bloque). */
    @Volatile
    var paddedMessages: Long = 0
        private set

    /** hql (bloques): mensajes tras los que el emisor no rellenó (llegó otro mensaje donde tocaba relleno). */
    @Volatile
    var unpaddedMessages: Long = 0
        private set

    /** hql (bloques): bytes de relleno saltados. */
    @Volatile
    var paddingBytes: Long = 0
        private set

    /** hql (bloques): ceros de más entre mensajes (más relleno del esperado), saltados sin dar basura. */
    @Volatile
    var strayZeroBytes: Long = 0
        private set

    /** `System.nanoTime()` de la última lectura que devolvió datos (lo usa el watchdog). */
    @Volatile
    var lastActivityNanos: Long = System.nanoTime()
        private set

    @Volatile
    var totalBytesRead: Long = 0
        private set

    /** Siguiente mensaje, o `null` en fin de flujo limpio (entre mensajes). Propaga las `IOException` del stream. */
    @Throws(IOException::class)
    fun next(): WireMessage? {
        if (blockMode) skipPadding()
        if (!ensure(4)) return if (available() == 0) null else takeGarbage(available(), "fin del flujo con ${available()} bytes sueltos")
        val m = when {
            Header.hasMagic(buf, pos) -> readFrame()
            BinBlock.hasMagic(buf, pos) -> readBin()
            else -> resync("magic desconocido")
        }
        if (blockMode) expectPadding(m)
        return m
    }

    /** hql (bloques): el relleno que debería seguir a [m] (de basura no se sabe: la resincronización ya buscó un magic). */
    private fun expectPadding(m: WireMessage) {
        val size = when (m) {
            is WireMessage.Frame -> Header.SIZE + m.body.size
            is WireMessage.Bin -> BinBlock.SIZE + m.block.extraSize
            is WireMessage.Garbage -> return
        }
        padPending = BlockFraming.padding(size, blockSize)
        if (padPending == 0) paddedMessages++
    }

    /**
     * hql (bloques): salta el relleno del mensaje anterior y los ceros de más. Solo lee si no hay nada en el búfer (lo
     * normal en USB es que el relleno llegue en la misma lectura que el final del mensaje); al principio de [next] hay
     * que esperar al siguiente mensaje de todos modos.
     */
    private fun skipPadding() {
        val expected = padPending
        padPending = 0
        if (expected > 0) {
            var skipped = 0
            var unpadded = false
            while (skipped < expected) {
                if (available() == 0 && !fill(expected - skipped)) break
                if (buf[pos] != ZERO) {
                    unpadded = true
                    break
                }
                pos++
                skipped++
            }
            paddingBytes += skipped
            when {
                skipped == expected -> paddedMessages++
                unpadded -> unpaddedMessages++
            }
        }
        while (true) {
            if (available() == 0 && !fill(1)) return
            if (buf[pos] != ZERO) return
            var run = 0
            while (pos + run < lim && buf[pos + run] == ZERO) run++
            pos += run
            strayZeroBytes += run
        }
    }

    private fun readFrame(): WireMessage {
        if (!ensure(Header.SIZE)) return takeGarbage(available(), "flujo cortado dentro de una cabecera 5A5A")
        val header = Header.decode(buf, pos)
        if (header.totalSize < Header.SIZE || header.totalSize > maxMessageSize) {
            return resync("totalSize imposible (${header.totalSize}) en cabecera 5A5A")
        }
        pos += Header.SIZE
        val body = ByteArray(header.totalSize - Header.SIZE)
        val got = readInto(body)
        if (got < body.size) {
            val partial = ByteArray(Header.SIZE + got)
            header.encodeInto(partial, 0)
            body.copyInto(partial, Header.SIZE, 0, got)
            return WireMessage.Garbage(partial.copyOf(minOf(partial.size, MAX_GARBAGE_KEPT)), partial.size.toLong(),
                "flujo cortado dentro de un mensaje 5A5A (${header.describe()}, llegaron $got de ${body.size} bytes)")
        }
        return WireMessage.Frame(header, body)
    }

    private fun readBin(): WireMessage {
        val block = ByteArray(BinBlock.SIZE)
        val got = readInto(block)
        if (got < block.size) return WireMessage.Garbage(block.copyOf(got), got.toLong(), "flujo cortado dentro de un bloque !BIN")
        val extraLen = BinBlock.extraLength(block)
        if (extraLen == null || extraLen > maxMessageSize) {
            // Campos incoherentes: nos quedamos con el bloque y no leemos extra (QDLink lanzaría una excepción).
            return WireMessage.Bin(BinBlock(block))
        }
        val extra = ByteArray(extraLen)
        val gotExtra = readInto(extra)
        if (gotExtra < extraLen) {
            return WireMessage.Garbage(block, (BinBlock.SIZE + gotExtra).toLong(), "flujo cortado en los datos extra de un !BIN")
        }
        return WireMessage.Bin(BinBlock(block, extra))
    }

    /** Descarta bytes hasta el siguiente magic (o el fin del flujo) y los devuelve como basura. */
    private fun resync(reason: String): WireMessage {
        val kept = java.io.ByteArrayOutputStream()
        var total = 0L
        fun skip(n: Int) {
            val keep = minOf(n, MAX_GARBAGE_KEPT - kept.size())
            if (keep > 0) kept.write(buf, pos, keep)
            pos += n
            total += n
        }
        skip(1) // el byte actual no inicia un mensaje válido
        while (true) {
            var i = pos
            while (i + 4 <= lim) {
                if (Header.hasMagic(buf, i) || BinBlock.hasMagic(buf, i)) {
                    skip(i - pos)
                    return WireMessage.Garbage(kept.toByteArray(), total, reason)
                }
                i++
            }
            // No hay magic completo en lo leído: se descarta todo salvo los 3 últimos bytes (un magic partido).
            skip(maxOf(0, lim - pos - 3))
            if (total >= MAX_GARBAGE_SCAN) return WireMessage.Garbage(kept.toByteArray(), total, "$reason (sigue sin aparecer un magic)")
            if (!fill()) {
                skip(lim - pos)
                return WireMessage.Garbage(kept.toByteArray(), total, "$reason (hasta el fin del flujo)")
            }
        }
    }

    private fun takeGarbage(n: Int, reason: String): WireMessage.Garbage {
        val bytes = buf.copyOfRange(pos, pos + n)
        pos += n
        return WireMessage.Garbage(bytes.copyOf(minOf(n, MAX_GARBAGE_KEPT)), n.toLong(), reason)
    }

    private fun available(): Int = lim - pos

    /** Garantiza [n] bytes en el buffer; `false` si el flujo acaba antes. */
    private fun ensure(n: Int): Boolean {
        while (available() < n) {
            if (!fill(n - available())) return false
        }
        return true
    }

    /**
     * Lee más datos al buffer (compactándolo). `false` en fin de flujo. En flujo, todo lo que quepa; en bloques
     * (hql), [want] bytes redondeados a bloques: al principio de un mensaje, un bloque, como QDLink.
     */
    private fun fill(want: Int = 1): Boolean {
        if (eof) return false
        if (pos > 0) {
            System.arraycopy(buf, pos, buf, 0, lim - pos)
            lim -= pos
            pos = 0
        }
        if (lim == buf.size) return true
        val free = buf.size - lim
        val request = if (blockMode) {
            // El búfer mide al menos un !BIN y dos bloques: con lo que se pide en cada caso siempre cabe un bloque.
            val whole = (free / blockSize) * blockSize
            if (whole > 0) minOf(BlockFraming.paddedSize(maxOf(1, want), blockSize), whole) else free
        } else {
            free
        }
        val n = input.read(buf, lim, request)
        if (n < 0) {
            eof = true
            return false
        }
        if (n > 0) touched(n)
        lim += n
        return true
    }

    /** Copia lo que haya en el buffer y lee el resto directamente al destino. Devuelve los bytes conseguidos. */
    private fun readInto(dst: ByteArray): Int {
        val fromBuf = minOf(available(), dst.size)
        System.arraycopy(buf, pos, dst, 0, fromBuf)
        pos += fromBuf
        var done = fromBuf
        if (blockMode) return readIntoBlocks(dst, done)
        while (done < dst.size && !eof) {
            val n = input.read(dst, done, dst.size - done)
            if (n < 0) {
                eof = true
                break
            }
            if (n > 0) touched(n)
            done += n
        }
        return done
    }

    /**
     * hql (bloques): el resto de [dst] desde [from]. Los bloques enteros, directamente al destino; la cola (menos de un
     * bloque), a través del búfer con una petición de un bloque, que trae también el relleno (se salta en [next]).
     * Ninguna petición pasa del final con relleno del mensaje.
     */
    private fun readIntoBlocks(dst: ByteArray, from: Int): Int {
        var done = from
        while (done < dst.size && !eof) {
            val need = dst.size - done
            val whole = (need / blockSize) * blockSize
            if (whole > 0) {
                val n = input.read(dst, done, whole)
                if (n < 0) {
                    eof = true
                    break
                }
                if (n > 0) touched(n)
                done += n
            } else {
                if (!fill(need)) break
                val take = minOf(available(), need)
                System.arraycopy(buf, pos, dst, done, take)
                pos += take
                done += take
            }
        }
        return done
    }

    private fun touched(n: Int) {
        totalBytesRead += n
        lastActivityNanos = System.nanoTime()
    }

    companion object {
        /** Tope de seguridad recomendado por la spec (§3.6). */
        const val DEFAULT_MAX_MESSAGE_SIZE = 8 * 1024 * 1024
        const val MAX_GARBAGE_KEPT = 4096
        const val MAX_GARBAGE_SCAN = 1L shl 20
        private const val ZERO: Byte = 0

        /** Utilidad para tests y herramientas: todos los mensajes de un array. */
        fun readAll(bytes: ByteArray, maxMessageSize: Int = DEFAULT_MAX_MESSAGE_SIZE): List<WireMessage> {
            val r = FrameReader(bytes.inputStream(), maxMessageSize)
            return generateSequence { r.next() }.toList()
        }

        /** Primeros bytes de un mensaje para un log (los int32 de la cabecera incluidos). */
        fun describeStart(bytes: ByteArray): String = if (bytes.size >= 8) {
            "magic='${String(bytes, 0, 4, Charsets.ISO_8859_1)}' u32@4=${BE.getUInt(bytes, 4)}"
        } else {
            Hex.encode(bytes)
        }
    }
}
