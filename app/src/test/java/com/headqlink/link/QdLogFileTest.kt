package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Log unificado: escritura, rotación por tamaño, retención por número y bytes, y líneas perdidas (qdauto §7.2, §9.1). */
class QdLogFileTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun logFile(dir: File, maxPartChars: Long = 32L * 1024 * 1024, maxFiles: Int = 40, maxTotal: Long = 300L * 1024 * 1024, capacity: Int = 200_000) =
        QdLogFile(dir, "qd-20261004-180000", { "CABECERA" }, "qd-", maxPartChars, maxFiles, maxTotal, capacity)

    @Test
    fun writesHeaderAndLinesAndFlushesOnDemand() {
        val dir = tmp.newFolder("logs")
        val f = logFile(dir)
        f.start()
        assertTrue(f.write("línea 1"))
        assertTrue(f.write("línea 2\n  segunda parte"))
        assertTrue(f.flush(2_000))
        val text = f.currentFile!!.readText()
        assertTrue(text.startsWith("CABECERA\n"))
        assertTrue(text.contains("línea 1\nlínea 2\n  segunda parte\n"))
        assertEquals("qd-20261004-180000.log", f.currentFile!!.name)
    }

    @Test
    fun rotatesIntoPartsWithTheHeaderAndAContinuationLine() {
        val dir = tmp.newFolder("logs")
        val f = logFile(dir, maxPartChars = 1_000)
        f.start()
        repeat(50) { f.write("x".repeat(99)) } // 50 × 100 caracteres = 5 partes de ~1000
        assertTrue(f.flush(2_000))
        val parts = dir.listFiles()!!.map { it.name }.sorted()
        assertTrue(parts.toString(), parts.size >= 5)
        assertTrue(parts.contains("qd-20261004-180000.log"))
        assertTrue(parts.contains("qd-20261004-180000-p2.log"))
        val first = File(dir, "qd-20261004-180000.log").readText()
        assertTrue(first.contains("--- continúa en la parte 2 ---"))
        assertTrue(File(dir, "qd-20261004-180000-p2.log").readText().startsWith("CABECERA\n"))
        assertTrue(f.currentFile!!.name.startsWith("qd-20261004-180000-p"))
    }

    @Test
    fun retentionKeepsTheNewestByCountAndBytes() {
        val dir = tmp.newFolder("logs")
        for (i in 1..10) {
            File(dir, "qd-2026100${i % 10}-old$i.log").apply {
                writeText("y".repeat(100))
                setLastModified(1_000_000L * i)
            }
        }
        File(dir, "otro.log").writeText("no se toca")
        val f = logFile(dir, maxPartChars = 10_000, maxFiles = 5)
        f.start()
        f.write("hola")
        assertTrue(f.flush(2_000))
        val left = dir.listFiles()!!.filter { it.name.startsWith("qd-") }.map { it.name }.toSet()
        assertEquals(5, left.size)
        assertTrue(left.containsAll(listOf("qd-20261007-old7.log", "qd-20261008-old8.log", "qd-20261009-old9.log", "qd-20261000-old10.log")))
        assertTrue(left.contains("qd-20261004-180000.log"))
        assertTrue(File(dir, "otro.log").exists())

        // Por bytes: con 250 B de tope solo cabe el actual y el más nuevo de los viejos.
        val dir2 = tmp.newFolder("logs2")
        for (i in 1..5) File(dir2, "qd-a$i.log").apply {
            writeText("z".repeat(100))
            setLastModified(1_000_000L * i)
        }
        val removed = LogRetention.prune(dir2, "qd-", 40, 250)
        assertEquals(3, removed.size)
        assertEquals(setOf("qd-a4.log", "qd-a5.log"), dir2.listFiles()!!.map { it.name }.toSet())
    }

    @Test
    fun retentionNeverDeletesTheFileInUse() {
        val dir = tmp.newFolder("logs")
        val current = File(dir, "qd-current.log").apply {
            writeText("w".repeat(1000))
            setLastModified(1_000L)
        }
        File(dir, "qd-newer.log").apply {
            writeText("w".repeat(10))
            setLastModified(5_000L)
        }
        LogRetention.prune(dir, "qd-", 1, 100, current)
        assertTrue(current.exists())
    }

    @Test
    fun aFullQueueCountsLostLinesAndReportsThem() {
        val dir = tmp.newFolder("logs")
        val f = logFile(dir, capacity = 10)
        var accepted = 0
        repeat(25) { if (f.write("l$it")) accepted++ }
        assertEquals(10, accepted)
        assertEquals(15, f.droppedTotal.get())
        assertFalse(f.flush(100)) // sin arrancar
        f.start()
        // La cola sigue llena hasta que el hilo la vacía: flush espera a que haya sitio.
        assertTrue("sin volcado", f.flush(5_000))
        val text = f.currentFile!!.readText()
        assertTrue(text.contains("l9\n"))
        assertFalse(text.contains("l10\n"))
        assertTrue(text, text.contains("15 líneas perdidas"))
    }

    @Test
    fun postedTasksRunInOrderWithTheLines() {
        val dir = tmp.newFolder("logs")
        val f = logFile(dir)
        val order = java.util.Collections.synchronizedList(ArrayList<String>())
        f.write("antes")
        f.post { order += "tarea:" + f.currentFile!!.readText().contains("antes") }
        f.start()
        assertTrue(f.flush(2_000))
        assertEquals(listOf("tarea:false"), order) // la tarea corre tras encolar la línea, antes del volcado
    }
}
