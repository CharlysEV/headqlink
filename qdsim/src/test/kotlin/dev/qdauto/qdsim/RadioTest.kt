package dev.qdauto.qdsim

import dev.qdauto.core.sim.RxStall
import dev.qdauto.core.sim.VideoKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Radio floja simulada: opciones (`--rx-kbps`, `--rx-stall`, `radio-mala`) y el informe del vídeo recibido. */
class RadioTest {
    @Test
    fun radioMalaDefaultsAndOverrides() {
        val o = Options.parse(arrayOf("--scenario", "radio-mala", "--local-phone"))
        assertEquals(1_500, o.rx.kbps)
        assertEquals(RxStall(15_000, 1_200), o.rx.stall)
        assertEquals(120, o.durationS)
        assertTrue(o.rx.active)
        val custom = Options.parse(arrayOf("--scenario", "radio-mala", "--rx-kbps", "800", "--rx-stall", "every=20s,for=1500ms", "--duration", "60"))
        assertEquals(800, custom.rx.kbps)
        assertEquals(RxStall(20_000, 1_500), custom.rx.stall)
        assertEquals(60, custom.durationS)
        // En los demás escenarios, sin límite salvo que se pida.
        val normal = Options.parse(arrayOf("--scenario", "normal"))
        assertTrue(!normal.rx.active)
        val limited = Options.parse(arrayOf("--scenario", "normal", "--rx-kbps", "2000"))
        assertEquals(2_000, limited.rx.kbps)
        assertNull(limited.rx.stall)
        assertFailsWith<IllegalArgumentException> { Options.parse(arrayOf("--scenario", "normal", "--rx-kbps", "rápido")) }
        assertFailsWith<IllegalArgumentException> { Options.parse(arrayOf("--scenario", "normal", "--rx-stall", "every=1s")) }
        assertTrue(Options.USAGE.contains("radio-mala") && Options.USAGE.contains("--rx-kbps") && Options.USAGE.contains("--rx-stall"))
    }

    @Test
    fun videoFlowSummary() {
        val f = VideoFlow(30)
        assertEquals(0, f.summary().frames)
        var t = 0L
        f.onFrame(t, VideoKind.CONFIG, 30)
        f.onFrame(t, VideoKind.IDR, 100_000)
        // 3 s a 30 fps con P-frames de 10 KB, un hueco de 1,2 s (parón) y una ráfaga de 263 KB.
        repeat(90) {
            t += 33
            f.onFrame(t, VideoKind.P, 10_000)
        }
        t += 1_200
        f.onFrame(t, VideoKind.P, 263_150)
        repeat(90) {
            t += 33
            f.onFrame(t, VideoKind.P, 10_000)
        }
        val s = f.summary()
        assertEquals(182, s.frames)
        assertEquals(1, s.idr)
        assertEquals(181, s.pFrames)
        assertEquals(263_150, s.pMaxBytes)
        assertEquals(1, s.pBursts)
        assertEquals(1_200, s.worstGapMs)
        assertEquals(2_970, s.worstGapAtMs)
        assertTrue(s.lowSeconds >= 1, "segundos flojos ${s.lowSeconds}")
        assertTrue(s.minFps < 15, "peor segundo ${s.minFps}")
        assertTrue(s.describe().contains("peor hueco 1200 ms (a los 3.0 s)"), s.describe())
        assertTrue(s.describe().contains("máx. 257 KB"), s.describe())
    }
}
