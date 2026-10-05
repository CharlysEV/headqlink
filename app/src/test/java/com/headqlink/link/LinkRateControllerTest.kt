package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bitrate según el enlace (viaje 5, 2026-10-05: cola del kernel alta, retransmisiones y rtt de 100-300 ms con la
 * radio floja). El C10 pide 5 080 320 bps a 30 fps.
 */
class LinkRateControllerTest {
    private val car = 5_080_320
    private val kb = 1024

    private fun clean(now: Long, retrans: Int = 10, rtt: Int = 12) = LinkRateController.Sample(now, 4 * kb, 2, retrans, rtt, 0, 0)
    /** Retransmisiones corroboradas: algo de cola y segmentos sin confirmar, pero por debajo del umbral de cola alta. */
    private fun losing(now: Long, retrans: Int) = LinkRateController.Sample(now, 50 * kb, 40, retrans, 12, 0, 0)
    private fun queued(now: Long, outq: Int = 96 * kb, retrans: Int = 10, rtt: Int = 12, waits: Int = 0) =
        LinkRateController.Sample(now, outq, 40, retrans, rtt, waits, 0)

    /** Muestras limpias cada 100 ms desde [from] hasta [to] (incluido). Devuelve los pasos que hubo. */
    private fun LinkRateController.cleanUntil(from: Long, to: Long): List<LinkRateController.Step> {
        val out = ArrayList<LinkRateController.Step>()
        var t = from
        while (t <= to) {
            onSample(clean(t))?.let { out += it }
            t += 100
        }
        return out
    }

    @Test
    fun startsAtTheProfileBitrateAndSessionFps() {
        val c = LinkRateController(car, 30)
        assertEquals(car, c.bitrate())
        assertEquals(car, c.ceiling())
        assertEquals(30, c.fpsCap())
        assertEquals(car, c.minBitrate())
        assertEquals(0, c.congestionEvents())
        assertFalse(c.active())
        assertTrue(c.describe(), c.describe().startsWith("bitrate adaptable al enlace 1.5 Mbit/s-5.1 Mbit/s"))
    }

    @Test
    fun retransmissionsAloneOnACleanLinkDoNotCount() {
        // En casa: outq 0-6 KB, rtt 5-30 ms y +2..+5 retransmisiones por segundo (normales en Wi-Fi).
        val c = LinkRateController(car, 30)
        var retrans = 10
        var t = 0L
        while (t <= 10_000) {
            if (t % 1000 == 0L) retrans += 3
            assertNull("muestra en $t", c.onSample(LinkRateController.Sample(t, 3 * kb, 12, retrans, 12, 0, 0)))
            t += 100
        }
        assertEquals(car, c.bitrate())
        assertEquals(0, c.congestionEvents())
    }

    @Test
    fun retransmissionsWithQueuedDataDoCount() {
        val c = LinkRateController(car, 30)
        // Sin confirmar 40 segmentos y la cola por encima del umbral de corroboración.
        assertNull(c.onSample(LinkRateController.Sample(0, 50 * kb, 40, 10, 12, 0, 0)))
        var step: LinkRateController.Step? = null
        for (t in 100L..1500L step 100) step = step ?: c.onSample(LinkRateController.Sample(t, 50 * kb, 40, 10 + (t / 200).toInt(), 12, 0, 0))
        assertNotNull(step)
        assertTrue(step!!.text, step.congestion)
    }

    @Test
    fun kernelQueueHighFor300msStepsDownOnceWithin500ms() {
        val c = LinkRateController(car, 30)
        // 0, 100 y 200 ms con la cola a 96 KB: aún no (hace falta ≥ 300 ms seguidos).
        assertNull(c.onSample(queued(0)))
        assertNull(c.onSample(queued(100)))
        assertNull(c.onSample(queued(200)))
        val st = c.onSample(queued(300))
        assertNotNull(st)
        assertEquals(car, st!!.bitrateBefore)
        assertEquals(Math.round(car * 0.7).toInt(), st.bitrateAfter)
        assertTrue(st.congestion)
        assertTrue(st.text, st.text.startsWith("congestión (outq 96 KB 300 ms) → bitrate 3.6 Mbit/s"))
        assertEquals(1, c.congestionEvents())
        assertEquals(st.bitrateAfter, c.minBitrate())
        // Sigue congestionado: el siguiente paso no llega antes de 500 ms.
        assertNull(c.onSample(queued(400)))
        assertNull(c.onSample(queued(700)))
        val st2 = c.onSample(queued(800))
        assertNotNull(st2)
        assertEquals(Math.round(st.bitrateAfter * 0.7).toInt(), st2!!.bitrateAfter)
        assertEquals(2, c.congestionEvents())
    }

    @Test
    fun aDipInTheQueueRestartsThe300ms() {
        val c = LinkRateController(car, 30)
        assertNull(c.onSample(queued(0)))
        assertNull(c.onSample(queued(100)))
        assertNull(c.onSample(clean(200)))
        assertNull(c.onSample(queued(300)))
        assertNull(c.onSample(queued(400)))
        assertNull(c.onSample(queued(500)))
        assertNotNull(c.onSample(queued(600)))
    }

    @Test
    fun gateWaitsCountAsHighQueueWhenNetStatIsMissing() {
        val c = LinkRateController(car, 30)
        fun s(now: Long) = LinkRateController.Sample(now, -1, -1, -1, -1, 20, 0)
        assertNull(c.onSample(s(0)))
        assertNull(c.onSample(s(200)))
        val st = c.onSample(s(300))
        assertNotNull(st)
        assertTrue(st!!.text, st.text.contains("enlace cerrado 300 ms"))
    }

    @Test
    fun retransmissionsRisingStepDownAtOnce() {
        val c = LinkRateController(car, 30)
        assertNull(c.onSample(losing(0, retrans = 300)))
        assertNull(c.onSample(losing(100, retrans = 301)))
        val st = c.onSample(losing(200, retrans = 303))
        assertNotNull(st)
        assertTrue(st!!.text, st.text.contains("retrans +3"))
        assertEquals(Math.round(car * 0.7).toInt(), st.bitrateAfter)
    }

    @Test
    fun retransmissionsOlderThanASecondDoNotCount() {
        val c = LinkRateController(car, 30)
        assertNull(c.onSample(clean(0, retrans = 300)))
        assertNull(c.onSample(clean(100, retrans = 301)))
        // 1 s después la ventana ya no contiene la subida: una retransmisión más no llega al umbral.
        var t = 200L
        while (t <= 1_100) {
            assertNull("t=$t", c.onSample(clean(t, retrans = 301)))
            t += 100
        }
        assertNull(c.onSample(clean(1_200, retrans = 302)))
    }

    @Test
    fun rttTwiceTheBaselineStepsDownButSmallRttsNever() {
        val c = LinkRateController(car, 30)
        assertNull(c.onSample(clean(0, rtt = 12)))
        // 30 ms está por debajo de los 80 ms mínimos: ruido.
        assertNull(c.onSample(clean(100, rtt = 30)))
        // Un pico suelto de rtt no cuenta: tiene que durar RTT_HIGH_MS.
        assertNull(c.onSample(clean(200, rtt = 110)))
        assertNull(c.onSample(clean(300, rtt = 12)))
        for (t in 400L..800L step 100) assertNull(c.onSample(clean(t, rtt = 110)))
        val st = c.onSample(clean(900, rtt = 110))
        assertNotNull(st)
        assertTrue(st!!.text, st.text.contains("rtt 110 ms (mín. 12)"))
        // Un enlace lento desde el principio (mínimo 60 ms): 100 ms no es el triple.
        val slow = LinkRateController(car, 30)
        assertNull(slow.onSample(clean(0, rtt = 60)))
        for (t in 100L..1000L step 100) assertNull(slow.onSample(clean(t, rtt = 100)))
        for (t in 1100L..1500L step 100) assertNull(slow.onSample(clean(t, rtt = 200)))
        assertNotNull(slow.onSample(clean(1600, rtt = 200)))
    }

    @Test
    fun lateFlushesStepDown() {
        val c = LinkRateController(car, 30)
        assertNull(c.onSample(clean(0)))
        val st = c.onSample(LinkRateController.Sample(100, 4 * kb, 2, 10, 12, 0, 1))
        assertNotNull(st)
        assertTrue(st!!.text, st.text.contains("vaciados por retraso +1"))
    }

    @Test
    fun floorIs1_5MbitThenFpsDropTo24ThenNothingMore() {
        val c = LinkRateController(car, 30)
        var t = 0L
        val steps = ArrayList<LinkRateController.Step>()
        // Congestión sostenida: 5.08 → 3.56 → 2.49 → 1.74 → 1.5 (suelo) → 24 fps → nada más.
        while (t <= 4_000) {
            c.onSample(queued(t))?.let { steps += it }
            t += 100
        }
        val bitrates = steps.filter { it.bitrateChanged() }.map { it.bitrateAfter }
        assertEquals(listOf(3_556_224, 2_489_357, 1_742_550, 1_500_000), bitrates)
        assertTrue(steps[3].text, steps[3].text.endsWith("(suelo)"))
        val fps = steps.filter { it.fpsChanged() }
        assertEquals(1, fps.size)
        assertEquals(30, fps[0].fpsBefore)
        assertEquals(24, fps[0].fpsAfter)
        assertTrue(fps[0].text, fps[0].text.contains("con el bitrate en el suelo (1.5 Mbit/s) → 24 fps"))
        // Una línea más («no hay más que bajar») y después silencio aunque la congestión siga.
        val last = steps.last()
        assertFalse(last.bitrateChanged())
        assertFalse(last.fpsChanged())
        assertTrue(last.text, last.text.contains("no hay más que bajar"))
        assertEquals(6, steps.size)
        assertEquals(1_500_000, c.minBitrate())
        assertEquals(24, c.fpsCap())
        assertEquals(6, c.congestionEvents())
        assertTrue(c.active())
    }

    @Test
    fun recoveryRestoresFpsFirstThen15PercentEvery5sUpToTheProfile() {
        val c = LinkRateController(car, 30)
        var t = 0L
        while (t <= 4_000) {
            c.onSample(queued(t))
            t += 100
        }
        assertEquals(1_500_000, c.bitrate())
        assertEquals(24, c.fpsCap())
        // 4,9 s limpios: nada todavía.
        assertTrue(c.cleanUntil(4_100, 9_000).isEmpty())
        // A los 5 s limpios vuelven los fps; 5 s después, +15 %.
        val s1 = c.onSample(clean(9_100))
        assertNotNull(s1)
        assertEquals(30, s1!!.fpsAfter)
        assertEquals(1_500_000, s1.bitrateAfter)
        assertFalse(s1.congestion)
        assertTrue(s1.text, s1.text.startsWith("enlace limpio 5 s → 30 fps"))
        assertTrue(c.cleanUntil(9_200, 14_000).isEmpty())
        val s2 = c.onSample(clean(14_100))
        assertNotNull(s2)
        assertEquals(1_725_000, s2!!.bitrateAfter)
        assertTrue(s2.text, s2.text.startsWith("enlace limpio 5 s → bitrate 1.7 Mbit/s"))
        // Hasta el techo, nunca por encima; después, silencio.
        var ups = 1
        var now = 14_200L
        var last = s2.bitrateAfter
        while (last < car) {
            val steps = c.cleanUntil(now, now + 5_000)
            assertEquals(1, steps.size)
            assertTrue(steps[0].bitrateAfter > last)
            assertTrue(steps[0].bitrateAfter <= car)
            last = steps[0].bitrateAfter
            now += 5_100
            ups++
        }
        assertEquals(car, c.bitrate())
        assertTrue(c.cleanUntil(now, now + 12_000).isEmpty())
        // De 1,5 a 5,08 Mbit/s con +15 %: 9 pasos (1.7, 2.0, 2.3, 2.6, 3.0, 3.5, 4.0, 4.6 y el techo).
        assertEquals(9, ups)
        // El mínimo de la sesión se queda en el suelo para el resumen.
        assertEquals(1_500_000, c.minBitrate())
    }

    @Test
    fun aCongestedSampleRestartsTheCleanCount() {
        val c = LinkRateController(car, 30)
        c.onSample(queued(0))
        c.onSample(queued(100))
        c.onSample(queued(200))
        assertNotNull(c.onSample(queued(300)))
        assertTrue(c.cleanUntil(400, 4_000).isEmpty())
        // Retransmisiones a los 4,1 s: no sube; bajaría otra vez (han pasado más de 500 ms del paso anterior).
        val st = c.onSample(losing(4_100, retrans = 20))
        assertNotNull(st)
        assertTrue(st!!.congestion)
        assertTrue(c.cleanUntil(4_200, 9_100).isEmpty())
        assertNotNull(c.onSample(clean(9_200)))
    }

    @Test
    fun thermalCeilingCapsAndRecoveryNeverPassesIt() {
        val c = LinkRateController(car, 30)
        // Calor moderado: techo ×0,7; el bitrate actual (el del perfil) se recorta en el acto.
        assertTrue(c.setCeiling(3_556_224))
        assertEquals(3_556_224, c.bitrate())
        assertEquals(3_556_224, c.minBitrate())
        // Congestión: baja desde el techo térmico.
        c.onSample(queued(0))
        c.onSample(queued(100))
        c.onSample(queued(200))
        val st = c.onSample(queued(300))
        assertEquals(2_489_357, st!!.bitrateAfter)
        // Limpio: sube hasta el techo térmico y ahí se queda.
        var now = 400L
        var steps = c.cleanUntil(now, now + 5_000)
        assertEquals(2_862_761, steps.single().bitrateAfter)
        now += 5_100
        steps = c.cleanUntil(now, now + 5_000)
        assertEquals(3_292_175, steps.single().bitrateAfter)
        now += 5_100
        steps = c.cleanUntil(now, now + 5_000)
        assertEquals(3_556_224, steps.single().bitrateAfter)
        assertTrue(steps.single().text, steps.single().text.endsWith("(techo)"))
        now += 5_100
        assertTrue(c.cleanUntil(now, now + 6_000).isEmpty())
        // Se enfría: el techo vuelve al perfil y la subida continúa.
        assertFalse(c.setCeiling(car))
        now += 6_100
        steps = c.cleanUntil(now, now + 5_000)
        assertEquals(4_089_658, steps.single().bitrateAfter)
        // El techo nunca pasa del perfil ni baja del suelo.
        c.setCeiling(20_000_000)
        assertEquals(car, c.ceiling())
        c.setCeiling(100)
        assertEquals(1_500_000, c.ceiling())
        assertEquals(1_500_000, c.bitrate())
    }

    @Test
    fun fluidity60StartsAt8MbitAndDropsTo24Fps() {
        val c = LinkRateController(8_000_000, 60)
        assertEquals(60, c.fpsCap())
        var t = 0L
        var fpsStep: LinkRateController.Step? = null
        while (t <= 6_000 && fpsStep == null) {
            val st = c.onSample(queued(t, retrans = 10 + (t / 100).toInt() * 3))
            if (st != null && st.fpsChanged()) fpsStep = st
            t += 100
        }
        assertNotNull(fpsStep)
        assertEquals(24, fpsStep!!.fpsAfter)
        assertEquals(1_500_000, c.bitrate())
        assertEquals(60, c.sessionFps())
    }

    @Test
    fun aProfileAlreadyUnderTheFloorNeverGoesDown() {
        // Muy bajo (2,5 Mbit/s) baja hasta 1,5; uno por debajo del suelo no se toca y solo cambia los fps.
        val c = LinkRateController(1_000_000, 20)
        assertEquals(1_500_000, c.bitrate())
        var t = 0L
        val steps = ArrayList<LinkRateController.Step>()
        while (t <= 1_500) {
            c.onSample(queued(t))?.let { steps += it }
            t += 100
        }
        assertTrue(steps.none { it.bitrateChanged() })
        // Sesión a 20 fps: tampoco hay fps que bajar; una sola línea.
        assertEquals(1, steps.size)
        assertEquals(20, c.fpsCap())
    }

    @Test
    fun beginSessionResetsToTheCeilingAndClearsStats() {
        val c = LinkRateController(car, 30)
        var t = 0L
        while (t <= 4_000) {
            c.onSample(queued(t))
            t += 100
        }
        assertEquals(1_500_000, c.bitrate())
        c.setCeiling(3_556_224)
        c.beginSession()
        assertEquals(3_556_224, c.bitrate())
        assertEquals(30, c.fpsCap())
        assertEquals(0, c.congestionEvents())
        assertEquals(3_556_224, c.minBitrate())
        assertFalse(c.active())
        assertTrue(c.statsLine(), c.statsLine().startsWith("enlace: bitrate 3.6 Mbit/s (mín. 3.6 Mbit/s, techo 3.6 Mbit/s) · congestiones 0"))
    }
}
