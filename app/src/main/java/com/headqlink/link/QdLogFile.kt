package com.headqlink.link

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.io.Writer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Escritor del log unificado (qdauto §7.2), port de `qdauto/app/log/LogFileWriter.kt`: un hilo propio
 * (`hql-logfile`, prioridad baja) escribe por lotes con un `BufferedWriter` de 64 KiB; quien registra solo encola (nunca
 * escribe en disco en un hilo de protocolo). La cola está acotada y cuenta las líneas que se pierden.
 *
 * Un fichero por arranque del proceso, `<baseName>.log`, con partes nuevas (`<baseName>-pN.log`) cada [maxPartChars]
 * caracteres; al empezar y en cada parte se aplica la retención ([LogRetention]): ≤ [maxFiles] ficheros que empiecen
 * por [retentionPrefix] y ≤ [maxTotalBytes] en total. Sin Android: se prueba en la JVM.
 */
internal class QdLogFile(
    private val dir: File,
    private val baseName: String,
    /** Cabecera de cada parte (versión, ajustes…). Se evalúa al abrir cada fichero, en el hilo del log. */
    private val header: () -> String,
    private val retentionPrefix: String = "qd-",
    private val maxPartChars: Long = 32L * 1024 * 1024,
    private val maxFiles: Int = 40,
    private val maxTotalBytes: Long = 300L * 1024 * 1024,
    queueCapacity: Int = 200_000,
    /** Se ejecuta al empezar el hilo del log (p. ej. para bajarle la prioridad). */
    private val onThreadStart: () -> Unit = {},
    /** Errores de E/S del propio log (a logcat). */
    private val onError: (String, Throwable?) -> Unit = { _, _ -> },
    /** Marca de tiempo para la línea de "líneas perdidas". */
    private val now: () -> String = { "" },
) {
    private class Flush(val latch: CountDownLatch)
    private class Task(val run: Runnable)

    private val queue = LinkedBlockingQueue<Any>(queueCapacity)
    private val dropped = AtomicLong()
    private var out: Writer? = null
    private var written = 0L
    private var part = 1
    private var thread: Thread? = null

    @Volatile
    var currentFile: File? = null
        private set

    /** Líneas perdidas en total (cola llena). */
    val droppedTotal = AtomicLong()

    @Synchronized
    fun start() {
        if (thread != null) return
        thread = Thread({ run() }, "hql-logfile").apply {
            isDaemon = true
            start()
        }
    }

    /** Encola una línea (o varias separadas por `\n`). No bloquea; `false` si se perdió. */
    fun write(line: String): Boolean {
        if (queue.offer(line)) return true
        dropped.incrementAndGet()
        droppedTotal.incrementAndGet()
        return false
    }

    /** Ejecuta [task] en el hilo del log, en orden con las líneas (para trabajo de registro que puede tocar disco). */
    fun post(task: Runnable): Boolean = queue.offer(Task(task))

    /** Espera (como mucho [timeoutMs]) a que todo lo encolado hasta ahora esté en disco. */
    fun flush(timeoutMs: Long): Boolean {
        if (thread == null) return false
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val latch = CountDownLatch(1)
        return try {
            // Con la cola llena se espera a que haya sitio (dentro del mismo plazo).
            if (!queue.offer(Flush(latch), timeoutMs, TimeUnit.MILLISECONDS)) return false
            latch.await(maxOf(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun run() {
        try {
            onThreadStart()
        } catch (_: Throwable) {
        }
        open()
        val batch = ArrayList<Any>(1024)
        while (true) {
            try {
                batch.add(queue.take())
                queue.drainTo(batch, 4096)
                for (item in batch) {
                    when (item) {
                        is String -> append(item)
                        is Task -> try {
                            item.run.run()
                        } catch (t: Throwable) {
                            onError("tarea del log", t)
                        }
                        is Flush -> {
                            flushOut()
                            item.latch.countDown()
                        }
                    }
                }
                batch.clear()
                val lost = dropped.getAndSet(0)
                if (lost > 0) append("${now()} W/HQL/Log: $lost líneas perdidas (cola del log llena)")
                if (queue.isEmpty()) flushOut()
            } catch (_: InterruptedException) {
                flushOut()
                return
            } catch (t: Throwable) {
                onError("error en el escritor del log", t)
                batch.clear()
            }
        }
    }

    private fun append(line: String) {
        val w = out ?: return
        try {
            w.write(line)
            w.write('\n'.code)
            written += line.length + 1
            if (written > maxPartChars) {
                part++
                w.write("--- continúa en la parte $part ---\n")
                open()
            }
        } catch (e: IOException) {
            onError("no se pudo escribir el log", e)
            open()
        }
    }

    private fun flushOut() {
        try {
            out?.flush()
        } catch (e: IOException) {
            onError("no se pudo vaciar el log", e)
        }
    }

    private fun open() {
        try {
            out?.close()
        } catch (_: IOException) {
        }
        out = null
        try {
            dir.mkdirs()
            val name = if (part == 1) "$baseName.log" else "$baseName-p$part.log"
            val f = File(dir, name)
            LogRetention.prune(dir, retentionPrefix, maxFiles - 1, maxTotalBytes - maxPartChars.coerceAtMost(maxTotalBytes / 2), f)
            val w = BufferedWriter(OutputStreamWriter(FileOutputStream(f, true), Charsets.UTF_8), 64 * 1024)
            out = w
            currentFile = f
            written = f.length()
            w.write(header())
            w.write("\n")
        } catch (e: Exception) {
            onError("no se pudo abrir el log en $dir", e)
        }
    }
}
