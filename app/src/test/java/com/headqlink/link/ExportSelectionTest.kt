package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** «Exportar log»: qué entra, en qué orden, el tope de bytes y las exclusiones (qdauto §7.5, §9.1). */
class ExportSelectionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var clock = 1_700_000_000_000L

    private fun file(dir: File, name: String, size: Int): File = File(dir, name).apply {
        parentFile?.mkdirs()
        writeBytes(ByteArray(size) { 'x'.code.toByte() })
        clock += 1_000
        setLastModified(clock)
    }

    private fun base(): File {
        val base = tmp.newFolder("files")
        file(File(base, "logs"), "qd-20261004-170000.log", 100)
        file(File(base, "logs"), "qd-20261004-180000.log", 100)
        file(File(base, "logs"), "sessions.csv", 10)
        for (i in 1..12) file(File(base, "car"), "car-$i.log", 10)
        for (i in 1..25) file(File(base, "perf"), "perf-$i.csv", 10)
        for (i in 1..5) file(File(base, "logcat"), "logcat-$i.log", 10)
        for (i in 1..2) file(File(base, "crash"), "crash-$i.txt", 10)
        for (i in 1..12) file(base, "headqlink-$i.log", 10)
        file(File(base, "trips"), "trip-1.json", 10)
        file(base, "ui-preview-car.png", 10)
        return base
    }

    @Test
    fun picksTheNewestOfEachGroupAndExcludesTripsAndCaptures() {
        val sel = HqlLogExport.select(base())
        val names = sel.included.map { it.first }
        assertEquals(3, names.count { it.startsWith("logs/") })
        assertEquals(10, names.count { it.startsWith("car/") })
        assertEquals(20, names.count { it.startsWith("perf/") })
        assertEquals(3, names.count { it.startsWith("logcat/") })
        assertEquals(2, names.count { it.startsWith("crash/") })
        assertEquals(10, names.count { it.startsWith("headqlink-") })
        assertTrue(names.none { it.contains("trip") || it.contains("ui-preview") })
        // Orden: logs/ primero; dentro de cada grupo, del más nuevo al más viejo.
        assertTrue(names.first().startsWith("logs/"))
        assertEquals(listOf("car/car-12.log", "car/car-11.log"), names.filter { it.startsWith("car/") }.take(2))
        assertFalse(names.contains("car/car-2.log"))
        assertTrue(sel.skipped.any { it.startsWith("car/car-2.log") && it.contains("10 más nuevos") })
        assertTrue(sel.skipped.any { it.startsWith("trips/") })
        assertTrue(sel.skipped.any { it.startsWith("ui-preview-*") })
    }

    @Test
    fun respectsTheByteCap() {
        val base = base()
        // 250 B: los dos logs (200) y sessions.csv (10) entran; después solo caben 4 ficheros de 10 B.
        val sel = HqlLogExport.select(base, maxBytes = 250)
        assertTrue(sel.totalBytes <= 250)
        assertEquals(3, sel.included.count { it.first.startsWith("logs/") })
        assertEquals(7, sel.included.size)
        assertTrue(sel.skipped.any { it.contains("pasaría de") })
    }

    @Test
    fun emptyDirectoriesAreFine() {
        val sel = HqlLogExport.select(tmp.newFolder("vacio"))
        assertTrue(sel.included.isEmpty())
        assertTrue(sel.skipped.isEmpty())
    }
}
