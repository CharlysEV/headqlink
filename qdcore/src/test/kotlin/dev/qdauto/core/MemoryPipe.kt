package dev.qdauto.core

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Tubo en memoria de un sentido, para sesiones teléfono↔coche sin red (como el accesorio USB). Hace lo de
 * `PipedInputStream`/`PipedOutputStream` sin sus comprobaciones de «hilo escritor vivo» (dan «Write end dead» en cuanto
 * termina un hilo que escribió una vez, como el `carsim-main` tras el handshake) y con lo que hace falta para probar la
 * trama por bloques:
 * - [maxChunk]: cada `read()` devuelve como mucho esto (lecturas cortas);
 * - [writeSizes]: el tamaño de cada `write()` (un mensaje = un `write()`, con su relleno);
 * - [capacity]: con el tubo lleno, `write()` se bloquea (un coche que deja de leer);
 * - cerrar la entrada despierta al lector bloqueado con una `IOException` (como cerrar el descriptor del accesorio).
 */
class MemoryPipe(private val capacity: Int = 1 shl 20, private val maxChunk: Int = Int.MAX_VALUE) {
    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()
    private val notFull = lock.newCondition()
    private val buf = ByteArray(capacity)
    private var head = 0
    private var size = 0
    private var writerClosed = false
    private var readerClosed = false

    /** Tamaño de cada `write()` completo, en orden. */
    val writeSizes: MutableList<Int> = CopyOnWriteArrayList()

    val input: InputStream = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            lock.withLock {
                while (size == 0) {
                    if (readerClosed) throw IOException("tubo cerrado")
                    if (writerClosed) return -1
                    notEmpty.await()
                }
                if (readerClosed) throw IOException("tubo cerrado")
                val n = minOf(len, size, maxChunk)
                for (i in 0 until n) b[off + i] = buf[(head + i) % capacity]
                head = (head + n) % capacity
                size -= n
                notFull.signalAll()
                return n
            }
        }

        override fun available(): Int = lock.withLock { size }

        override fun close() = lock.withLock {
            readerClosed = true
            notEmpty.signalAll()
            notFull.signalAll()
        }
    }

    val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            var done = 0
            lock.withLock {
                while (done < len) {
                    while (size == capacity) {
                        if (readerClosed || writerClosed) throw IOException("tubo cerrado")
                        notFull.await()
                    }
                    if (readerClosed || writerClosed) throw IOException("tubo cerrado")
                    val n = minOf(len - done, capacity - size)
                    for (i in 0 until n) buf[(head + size + i) % capacity] = b[off + done + i]
                    size += n
                    done += n
                    notEmpty.signalAll()
                }
            }
            writeSizes += len
        }

        override fun close() = lock.withLock {
            writerClosed = true
            notEmpty.signalAll()
            notFull.signalAll()
        }
    }

    /** Bytes en el tubo sin leer. */
    fun pending(): Int = lock.withLock { size }
}
