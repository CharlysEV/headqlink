package dev.qdauto.core.session

import kotlin.test.Test
import kotlin.test.assertEquals

class MirrorGeometryTest {
    /** Tabla de spec §8.6 (reproducción exacta de la aritmética float de QDLink). */
    @Test
    fun matchesSpecTable() {
        data class Row(val pS: Int, val pL: Int, val carW: Int, val carH: Int, val inCar: String, val mirror: String, val outVer: String, val temp: String)
        val rows = listOf(
            Row(1080, 2340, 800, 480, "800x480", "800x370", "222x480", "2340x1404"),
            Row(1080, 2340, 1280, 720, "1280x720", "1280x590", "332x720", "2340x1316"),
            Row(1080, 2340, 1920, 720, "1920x720", "1560x720", "332x720", "2340x877"),
            Row(1080, 2340, 1920, 1080, "1920x1080", "1920x886", "498x1080", "2340x1316"),
            Row(1080, 2340, 1920, 1200, "1920x1200", "1920x886", "554x1200", "2340x1462"),
            Row(1080, 2340, 2560, 1440, "2560x1440", "2560x1182", "664x1440", "2340x1316"),
            Row(1080, 2340, 1919, 1079, "1920x1080", "1920x886", "498x1080", "2340x1315"),
            Row(1440, 3120, 1920, 1080, "1920x1080", "1920x886", "498x1080", "3120x1755"),
            Row(1440, 3120, 2560, 1440, "2560x1440", "2560x1182", "664x1440", "3120x1755"),
            Row(1080, 2400, 1920, 720, "1920x720", "1600x720", "324x720", "2400x900"),
            Row(1080, 2400, 1920, 1080, "1920x1080", "1920x864", "486x1080", "2400x1350"),
        )
        for (r in rows) {
            val g = MirrorGeometry.compute(r.pL, r.pS, r.carW, r.carH)
            val label = "teléfono ${r.pS}x${r.pL}, coche ${r.carW}x${r.carH}"
            assertEquals(r.inCar, "${g.inCarWidth}x${g.inCarHeight}", label)
            assertEquals(r.mirror, "${g.mirrorWidth}x${g.mirrorHeight}", label)
            assertEquals(r.outVer, "${g.outVerWidth}x${g.outVerHeight}", label)
            assertEquals(r.temp, "${g.tempLong}x${g.tempShort}", label)
            // El orden de los lados del teléfono no importa.
            assertEquals(g, MirrorGeometry.compute(r.pS, r.pL, r.carW, r.carH))
        }
    }

    @Test
    fun zeroSizeFallsBackTo800x480() {
        assertEquals(MirrorGeometry.compute(2340, 1080, 800, 480), MirrorGeometry.forCarInfo(2340, 1080, 0, 0))
    }

    @Test
    fun portraitCarIsLandscapedAndNeverCrashes() {
        val g = MirrorGeometry.compute(2340, 1080, 1080, 1920)
        assertEquals(1920, g.inCarWidth)
        assertEquals(1080, g.inCarHeight)
        assertEquals(0, g.outVerWidth)
        // Entradas degeneradas: sin excepciones.
        MirrorGeometry.compute(2340, 1080, 1920, 0)
        MirrorGeometry.compute(0, 0, 0, 0)
        MirrorGeometry.compute(1, 1, 7, 7)
    }
}
