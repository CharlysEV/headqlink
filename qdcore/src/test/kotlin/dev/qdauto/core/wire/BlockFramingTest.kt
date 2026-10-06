package dev.qdauto.core.wire

import dev.qdauto.core.json.JsonObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Trama por bloques del USB de QDLink (spec 01 §7, 04 §3.5 y §8.4): relleno a 512 B al escribir y lectura en bloques
 * tolerante (lecturas cortas, `!BIN` de 512 B, emisor que no rellena, ceros de más) y segura para USB (peticiones
 * múltiplo de 512 que no pasan del final del mensaje).
 */
class BlockFramingTest {
    private val block = BlockFraming.BLOCK

    // ------------------------------------------------------------------ tamaños

    @Test
    fun paddedSizes() {
        assertEquals(0, BlockFraming.paddedSize(0))
        assertEquals(512, BlockFraming.paddedSize(1))
        assertEquals(512, BlockFraming.paddedSize(16))
        assertEquals(512, BlockFraming.paddedSize(511))
        assertEquals(512, BlockFraming.paddedSize(512))
        assertEquals(1024, BlockFraming.paddedSize(513))
        // Vídeo: 48 B de cabeceras + N; un IDR de 300 000 B → 300 048 → 300 544 (587 bloques).
        assertEquals(300_544, BlockFraming.paddedSize(VideoMessage.HEADER_SIZE + 300_000))
        // El tope de 480 KiB es múltiplo de 512: un mensaje justo en el tope no lleva relleno.
        assertEquals(480 * 1024, BlockFraming.paddedSize(480 * 1024))
        assertEquals(0, BlockFraming.padding(1024))
        assertEquals(1, BlockFraming.padding(1023))
        assertEquals(64, BlockFraming.paddedSize(1, 64))
        assertFailsWith<IllegalArgumentException> { BlockFraming.paddedSize(-1) }
        assertFailsWith<IllegalArgumentException> { BlockFraming.paddedSize(10, 0) }
    }

    @Test
    fun padKeepsTheHeaderAndAddsZeros() {
        val hb = PhoneMessages.heartbeat()
        val padded = BlockFraming.pad(hb)
        assertEquals(512, padded.size)
        assertContentEquals(hb, padded.copyOf(hb.size))
        assertTrue(padded.copyOfRange(hb.size, padded.size).all { it == 0.toByte() })
        // totalSize sigue siendo el tamaño real, no el rellenado.
        assertEquals(hb.size, Header.decode(padded).totalSize)

        val video = VideoMessage.build(VideoParams(1920, 880, 30, 5_000_000, 3, 3), ByteArray(1000) { 7 })
        val pv = BlockFraming.pad(video)
        assertEquals(1536, pv.size)
        assertEquals(VideoMessage.HEADER_SIZE + 1000, Header.decode(pv).totalSize)

        // Ya alineado (el AppStatus !BIN mide 512): el mismo array, sin copia.
        val app = BinBlock.appStatus(36)
        assertSame(app, BlockFraming.pad(app))
    }

    // ------------------------------------------------------------------ lector

    /** Mensajes del coche de todos los tamaños: pequeños, `!BIN` de 512, justo 1024, varios bloques y uno grande. */
    private fun carMessages(): List<ByteArray> = listOf(
        CarMessages.heartbeat(),
        CarMessages.carInfo(JsonObject.of("CarWidth" to 1920, "CarHeight" to 882, "CarType" to "2D4")),
        TouchCodec.build(TouchCodec.ACTION_DOWN, listOf(TouchPointer.down(0, 100f, 200f))),
        BinBlock.legacyHeartbeatRequest(),
        exactly(1024),
        CarMessages.control("TEST", JsonObject.of("s" to "x".repeat(1500))),
        BinBlock.legacyHeartbeatRequest(),
        VideoMessage.build(VideoParams(1920, 880, 30, 5_000_000, 3, 3), ByteArray(70_000) { (it % 251 + 1).toByte() }),
        CarMessages.heartbeat(),
        exactly(512),
        TouchCodec.build(TouchCodec.ACTION_UP, listOf(TouchPointer.up(0, 100f, 200f))),
    )

    /** Un mensaje de control de exactamente [size] bytes (sin relleno al alinearlo). */
    private fun exactly(size: Int): ByteArray {
        val base = CarMessages.control("TEST", JsonObject.of("s" to "")).size
        val m = CarMessages.control("TEST", JsonObject.of("s" to "y".repeat(size - base)))
        assertEquals(size, m.size)
        return m
    }

    private fun stream(messages: List<ByteArray>, pad: Boolean = true): ByteArray {
        val out = ByteArrayOutputStream()
        for (m in messages) out.write(if (pad) BlockFraming.pad(m) else m)
        return out.toByteArray()
    }

    private fun readAll(r: FrameReader): List<WireMessage> = generateSequence { r.next() }.toList()

    private fun assertSameMessages(expected: List<ByteArray>, got: List<WireMessage>) {
        assertEquals(expected.size, got.size, "mensajes: ${got.map { it.javaClass.simpleName }}")
        for ((i, m) in got.withIndex()) {
            val bytes = when (m) {
                is WireMessage.Frame -> m.wireBytes()
                is WireMessage.Bin -> m.block.bytes
                is WireMessage.Garbage -> throw AssertionError("basura en el mensaje $i: ${m.reason}")
            }
            assertContentEquals(expected[i], bytes, "mensaje $i")
        }
    }

    /** Devuelve como mucho [chunk] bytes por lectura (o un número al azar entre 1 y [chunk]). */
    private class ShortReads(private val data: ByteArray, private val chunk: Int, private val random: Random? = null) : InputStream() {
        private var pos = 0
        override fun read(): Int = if (pos < data.size) data[pos++].toInt() and 0xFF else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= data.size) return -1
            val max = random?.nextInt(1, chunk + 1) ?: chunk
            val n = minOf(len, max, data.size - pos)
            System.arraycopy(data, pos, b, off, n)
            pos += n
            return n
        }
    }

    @Test
    fun paddedStreamWithWholeReads() {
        val msgs = carMessages()
        val r = FrameReader(ByteArrayInputStream(stream(msgs)), blockSize = block)
        assertSameMessages(msgs, readAll(r))
        assertEquals(msgs.size.toLong(), r.paddedMessages)
        assertEquals(0L, r.unpaddedMessages)
        assertEquals(msgs.sumOf { BlockFraming.padding(it.size) }.toLong(), r.paddingBytes)
        assertEquals(0L, r.strayZeroBytes)
    }

    @Test
    fun shortReadsNeverDesync() {
        val msgs = carMessages()
        val bytes = stream(msgs)
        for (chunk in listOf(1, 3, 7, 16, 100, 511, 513)) {
            val r = FrameReader(ShortReads(bytes, chunk), blockSize = block)
            assertSameMessages(msgs, readAll(r))
            assertEquals(0L, r.unpaddedMessages, "trozos de $chunk B")
        }
        val rnd = Random(42)
        repeat(20) {
            val r = FrameReader(ShortReads(bytes, 900, rnd), blockSize = block)
            assertSameMessages(msgs, readAll(r))
        }
    }

    @Test
    fun legacyBinBlockNeedsNoPadding() {
        val bin = BinBlock.legacyHeartbeatRequest()
        assertEquals(512, bin.size)
        val msgs = listOf(bin, CarMessages.heartbeat(), bin, bin)
        val r = FrameReader(ShortReads(stream(msgs), 5), blockSize = block)
        val got = readAll(r)
        assertSameMessages(msgs, got)
        assertIs<WireMessage.Bin>(got[0])
        assertEquals(BinBlock.CMD_HEARTBEAT, (got[3] as WireMessage.Bin).block.cmd)
        assertEquals(4L, r.paddedMessages)
    }

    @Test
    fun senderThatDoesNotPadIsTolerated() {
        // Un coche que escribiera sin relleno: donde tocaba relleno aparece el magic del siguiente mensaje.
        val msgs = carMessages()
        val r = FrameReader(ShortReads(stream(msgs, pad = false), 13), blockSize = block)
        assertSameMessages(msgs, readAll(r))
        val needPadding = msgs.dropLast(1).count { BlockFraming.padding(it.size) > 0 }
        assertEquals(needPadding.toLong(), r.unpaddedMessages)
        assertEquals(0L, r.paddingBytes)
    }

    @Test
    fun extraZerosBetweenMessagesAreSkipped() {
        val msgs = listOf(CarMessages.heartbeat(), CarMessages.heartbeat(), BinBlock.legacyHeartbeatRequest())
        val out = ByteArrayOutputStream()
        out.write(BlockFraming.pad(msgs[0]))
        out.write(ByteArray(1024)) // dos bloques de ceros de más
        out.write(BlockFraming.pad(msgs[1]))
        out.write(ByteArray(3))
        out.write(msgs[2])
        out.write(ByteArray(700)) // relleno al final del flujo
        val r = FrameReader(ShortReads(out.toByteArray(), 64), blockSize = block)
        assertSameMessages(msgs, readAll(r))
        assertEquals(1024L + 3 + 700, r.strayZeroBytes)
        assertNull(r.next())
    }

    @Test
    fun streamModeIsUnchanged() {
        // Sin bloques, el relleno de un emisor USB sería basura: el lector de siempre no lo salta.
        val msgs = listOf(CarMessages.heartbeat(), CarMessages.heartbeat())
        val got = FrameReader.readAll(stream(msgs, pad = false))
        assertSameMessages(msgs, got)
        val padded = FrameReader.readAll(stream(msgs, pad = true))
        assertTrue(padded.any { it is WireMessage.Garbage })
    }

    /**
     * Accesorio USB de mentira: cada mensaje del coche es una transferencia *bulk* (con su relleno: paquetes llenos y sin
     * ZLP). Una lectura nunca pasa de la transferencia en curso; anota las peticiones que no son múltiplo de 512 (el
     * núcleo tiraría el resto del paquete) y las que piden más de lo que queda de la transferencia (en un USB real se
     * quedarían esperando al mensaje siguiente).
     */
    private class UsbLikeInput(private val transfers: List<ByteArray>) : InputStream() {
        val violations = ArrayList<String>()
        val requests = ArrayList<Int>()
        private var t = 0
        private var off = 0

        override fun read(): Int = throw AssertionError("lectura de un byte")

        override fun read(b: ByteArray, o: Int, len: Int): Int {
            requests += len
            if (len % 512 != 0) violations += "petición de $len B (no es múltiplo de 512)"
            while (t < transfers.size && off == transfers[t].size) {
                t++
                off = 0
            }
            if (t >= transfers.size) return -1
            val left = transfers[t].size - off
            if (len > left) violations += "petición de $len B con $left B en la transferencia ${t + 1}"
            val n = minOf(len, left)
            System.arraycopy(transfers[t], off, b, o, n)
            off += n
            return n
        }
    }

    @Test
    fun usbSafeRequests() {
        val msgs = carMessages()
        val usb = UsbLikeInput(msgs.map { BlockFraming.pad(it) })
        val r = FrameReader(usb, blockSize = block)
        assertSameMessages(msgs, readAll(r))
        assertEquals(emptyList(), usb.violations)
        // El primer read() de cada mensaje es un bloque, como QDLink.
        assertEquals(512, usb.requests.first())
        assertEquals(msgs.size.toLong(), r.paddedMessages)
    }

    @Test
    fun largeBodiesUseBlockReads() {
        // 300 KB de vídeo: los bloques enteros van directos al destino y la cola, con su relleno, por el búfer.
        val big = VideoMessage.build(VideoParams(1920, 880, 30, 5_000_000, 3, 3), ByteArray(300_000) { (it % 253 + 1).toByte() })
        val usb = UsbLikeInput(listOf(BlockFraming.pad(big), BlockFraming.pad(CarMessages.heartbeat())))
        val r = FrameReader(usb, blockSize = block)
        val got = readAll(r)
        assertSameMessages(listOf(big, CarMessages.heartbeat()), got)
        assertEquals(emptyList(), usb.violations)
        assertEquals(BlockFraming.paddedSize(big.size) + 512, usb.requests.sum() - 512) // la última petición da -1
    }
}
