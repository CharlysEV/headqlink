package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bitrate según el enlace (viaje 5, 2026-10-05: cola del kernel alta y rtt de 100-300 ms con la radio floja; viaje 6,
 * 2026-10-06: las retransmisiones sueltas no son congestión). El C10 pide 5 080 320 bps a 30 fps.
 */
class LinkRateControllerTest {
    private val car = 5_080_320
    private val floor = 2_540_160
    private val kb = 1024

    private fun clean(now: Long, retrans: Int = 10, rtt: Int = 12) = LinkRateController.Sample(now, 4 * kb, 2, retrans, rtt, 0, 0)
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

    /** Congestión sostenida (cola a 96 KB) cada 100 ms desde [from] hasta [to]. Devuelve los pasos. */
    private fun LinkRateController.queuedUntil(from: Long, to: Long): List<LinkRateController.Step> {
        val out = ArrayList<LinkRateController.Step>()
        var t = from
        while (t <= to) {
            onSample(queued(t))?.let { out += it }
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
        assertTrue(c.describe(), c.describe().startsWith("bitrate adaptable al enlace 2.5 Mbit/s-5.1 Mbit/s"))
    }

    @Test
    fun retransmissionsAloneOnACleanLinkDoNotCount() {
        // En casa: outq 0-6 KB, rtt 5-30 ms y +2..+9 retransmisiones por segundo (normales en Wi-Fi).
        val c = LinkRateController(car, 30)
        var retrans = 10
        var t = 0L
        while (t <= 10_000) {
            if (t % 1000 == 0L) retrans += 5
            assertNull("muestra en $t", c.onSample(LinkRateController.Sample(t, 3 * kb, 12, retrans, 12, 0, 0)))
            t += 100
        }
        assertEquals(car, c.bitrate())
        assertEquals(0, c.congestionEvents())
    }

    @Test
    fun retransmissionsWhileAnIdrDrainsDoNotCount() {
        // Viaje 6: un IDR de 100-150 KB deja la cola por encima de 48 KB unos 200-300 ms y a la vez hay alguna
        // retransmisión. No es congestión: la cola baja sola.
        val c = LinkRateController(car, 30)
        var retrans = 100
        var t = 0L
        while (t <= 20_000) {
            val idr = t % 2_000 < 400
            if (idr) retrans += 2
            val s = LinkRateController.Sample(t, if (idr) 120 * kb else 4 * kb, 30, retrans, 15, 0, 0)
            assertNull("muestra en $t", c.onSample(s))
            t += 100
        }
        assertEquals(car, c.bitrate())
        assertEquals(0, c.congestionEvents())
    }

    @Test
    fun kernelQueueHighFor500msStepsDownOnceWithin500ms() {
        val c = LinkRateController(car, 30)
        // De 0 a 400 ms con la cola a 96 KB: aún no (hace falta ≥ 500 ms seguidos).
        for (t in 0L..400L step 100) assertNull("t=$t", c.onSample(queued(t)))
        val st = c.onSample(queued(500))
        assertNotNull(st)
        assertEquals(car, st!!.bitrateBefore)
        assertEquals(3_810_240, st.bitrateAfter)
        assertTrue(st.congestion)
        assertTrue(st.text, st.text.startsWith("congestión (outq 96 KB 500 ms) → bitrate 3.8 Mbit/s"))
        assertEquals(1, c.congestionEvents())
        assertEquals(st.bitrateAfter, c.minBitrate())
        // Sigue congestionado: el siguiente paso no llega antes de 500 ms.
        for (t in 600L..900L step 100) assertNull("t=$t", c.onSample(queued(t)))
        val st2 = c.onSample(queued(1_000))
        assertNotNull(st2)
        assertEquals(2_857_680, st2!!.bitrateAfter)
        assertEquals(2, c.congestionEvents())
    }

    @Test
    fun aDipInTheQueueRestartsThe500ms() {
        val c = LinkRateController(car, 30)
        assertNull(c.onSample(queued(0)))
        assertNull(c.onSample(queued(100)))
        assertNull(c.onSample(clean(200)))
        for (t in 300L..700L step 100) assertNull("t=$t", c.onSample(queued(t)))
        assertNotNull(c.onSample(queued(800)))
    }

    @Test
    fun gateWaitsCountAsHighQueueWhenNetStatIsMissing() {
        val c = LinkRateController(car, 30)
        fun s(now: Long) = LinkRateController.Sample(now, -1, -1, -1, -1, 20, 0)
        for (t in 0L..400L step 100) assertNull("t=$t", c.onSample(s(t)))
        val st = c.onSample(s(500))
        assertNotNull(st)
        assertTrue(st!!.text, st.text.contains("enlace cerrado 500 ms"))
    }

    @Test
    fun rttTripleTheBaselineSustainedStepsDownButSmallRttsNever() {
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
    fun floorIsHalfTheCarBitrateThenFpsDropTo24ThenNothingMore() {
        val c = LinkRateController(car, 30)
        // Congestión sostenida: 5.08 → 3.81 → 2.86 → 2.54 (suelo) → 24 fps → nada más.
        val steps = c.queuedUntil(0, 4_000)
        val bitrates = steps.filter { it.bitrateChanged() }.map { it.bitrateAfter }
        assertEquals(listOf(3_810_240, 2_857_680, floor), bitrates)
        assertTrue(steps[2].text, steps[2].text.endsWith("(suelo)"))
        val fps = steps.filter { it.fpsChanged() }
        assertEquals(1, fps.size)
        assertEquals(30, fps[0].fpsBefore)
        assertEquals(24, fps[0].fpsAfter)
        assertTrue(fps[0].text, fps[0].text.contains("con el bitrate en el suelo (2.5 Mbit/s) → 24 fps"))
        // Una línea más («no hay más que bajar») y después silencio aunque la congestión siga.
        val last = steps.last()
        assertFalse(last.bitrateChanged())
        assertFalse(last.fpsChanged())
        assertTrue(last.text, last.text.contains("no hay más que bajar"))
        assertEquals(5, steps.size)
        assertEquals(floor, c.minBitrate())
        assertEquals(24, c.fpsCap())
        assertEquals(5, c.congestionEvents())
        assertTrue(c.active())
    }

    @Test
    fun recoveryRestoresFpsFirstThen25PercentEvery3sUpToTheProfile() {
        val c = LinkRateController(car, 30)
        c.queuedUntil(0, 4_000)
        assertEquals(floor, c.bitrate())
        assertEquals(24, c.fpsCap())
        // 2,9 s limpios: nada todavía.
        assertTrue(c.cleanUntil(4_100, 7_000).isEmpty())
        // A los 3 s limpios vuelven los fps; 3 s después, +25 %.
        val s1 = c.onSample(clean(7_100))
        assertNotNull(s1)
        assertEquals(30, s1!!.fpsAfter)
        assertEquals(floor, s1.bitrateAfter)
        assertFalse(s1.congestion)
        assertTrue(s1.text, s1.text.startsWith("enlace limpio 3 s → 30 fps"))
        assertTrue(c.cleanUntil(7_200, 10_000).isEmpty())
        val s2 = c.onSample(clean(10_100))
        assertNotNull(s2)
        assertEquals(3_175_200, s2!!.bitrateAfter)
        assertTrue(s2.text, s2.text.startsWith("enlace limpio 3 s → bitrate 3.2 Mbit/s"))
        // Hasta el techo, nunca por encima; después, silencio.
        val rest = c.cleanUntil(10_200, 20_000)
        assertEquals(listOf(3_969_000, 4_961_250, car), rest.map { it.bitrateAfter })
        assertTrue(rest.last().text, rest.last().text.endsWith("(techo)"))
        assertEquals(car, c.bitrate())
        assertTrue(c.cleanUntil(20_100, 32_000).isEmpty())
        // Del suelo al techo en 12 s; el mínimo de la sesión se queda en el suelo para el resumen.
        assertEquals(floor, c.minBitrate())
    }

    @Test
    fun aCongestedSampleRestartsTheCleanCount() {
        val c = LinkRateController(car, 30)
        assertNotNull(c.queuedUntil(0, 500).singleOrNull())
        assertTrue(c.cleanUntil(600, 3_500).isEmpty())
        // Frames tirados por retraso a los 3,6 s: no sube; baja otra vez (han pasado más de 500 ms del paso anterior).
        val st = c.onSample(LinkRateController.Sample(3_600, 4 * kb, 2, 10, 12, 0, 1))
        assertNotNull(st)
        assertTrue(st!!.congestion)
        assertTrue(c.cleanUntil(3_700, 6_600).isEmpty())
        assertNotNull(c.onSample(clean(6_700)))
    }

    @Test
    fun thermalCeilingCapsAndRecoveryNeverPassesIt() {
        val c = LinkRateController(car, 30)
        // Calor moderado: techo ×0,7; el bitrate actual (el del perfil) se recorta en el acto.
        assertTrue(c.setCeiling(3_556_224))
        assertEquals(3_556_224, c.bitrate())
        assertEquals(3_556_224, c.minBitrate())
        // Congestión: baja desde el techo térmico.
        val st = c.queuedUntil(0, 500).single()
        assertEquals(2_667_168, st.bitrateAfter)
        // Limpio: sube hasta el techo térmico y ahí se queda.
        val up = c.cleanUntil(600, 7_000)
        assertEquals(listOf(3_333_960, 3_556_224), up.map { it.bitrateAfter })
        assertTrue(up.last().text, up.last().text.endsWith("(techo)"))
        assertTrue(c.cleanUntil(7_100, 13_000).isEmpty())
        // Se enfría: el techo vuelve al perfil y la subida continúa.
        assertFalse(c.setCeiling(car))
        val more = c.cleanUntil(13_100, 16_100)
        assertEquals(4_445_280, more.single().bitrateAfter)
        // El techo nunca pasa del perfil ni baja del suelo.
        c.setCeiling(20_000_000)
        assertEquals(car, c.ceiling())
        c.setCeiling(100)
        assertEquals(floor, c.ceiling())
        assertEquals(floor, c.bitrate())
    }

    @Test
    fun fluidity60StartsAt8MbitAndDropsTo24Fps() {
        val c = LinkRateController(8_000_000, 60)
        assertEquals(60, c.fpsCap())
        // Hasta 5 s: a los 5,1 s (3 s más de congestión en el suelo y a 24 fps) bajaría del suelo normal (emergencia).
        val fpsStep = c.queuedUntil(0, 5_000).firstOrNull { it.fpsChanged() }
        assertNotNull(fpsStep)
        assertEquals(24, fpsStep!!.fpsAfter)
        // Suelo: la mitad de 8 Mbit/s.
        assertEquals(4_000_000, c.bitrate())
        assertEquals(60, c.sessionFps())
    }

    @Test
    fun aProfileAlreadyAtTheAbsoluteFloorNeverGoesDown() {
        // Un perfil de 1 Mbit/s se sube al suelo absoluto (1,5) y con congestión solo puede avisar.
        val c = LinkRateController(1_000_000, 20)
        assertEquals(1_500_000, c.bitrate())
        val steps = c.queuedUntil(0, 1_500)
        assertTrue(steps.none { it.bitrateChanged() })
        // Sesión a 20 fps: tampoco hay fps que bajar; una sola línea.
        assertEquals(1, steps.size)
        assertEquals(20, c.fpsCap())
    }

    @Test
    fun beginSessionResetsToTheCeilingAndClearsStats() {
        val c = LinkRateController(car, 30)
        c.queuedUntil(0, 4_000)
        assertEquals(floor, c.bitrate())
        c.setCeiling(3_556_224)
        c.beginSession()
        assertEquals(3_556_224, c.bitrate())
        assertEquals(30, c.fpsCap())
        assertEquals(0, c.congestionEvents())
        assertEquals(3_556_224, c.minBitrate())
        assertFalse(c.active())
        assertTrue(c.statsLine(), c.statsLine().startsWith("enlace: bitrate 3.6 Mbit/s (mín. 3.6 Mbit/s, techo 3.6 Mbit/s) · congestiones 0"))
    }

    @Test
    fun withoutNetStatTheSessionQueueLagCountsAsHighQueue() {
        // Cable USB: sin socket no hay cola del kernel ni rtt; el atasco es el retraso del vídeo en la cola de la sesión.
        val c = LinkRateController(car, 30)
        fun usb(now: Long, lag: Long) = LinkRateController.Sample(now, -1, -1, -1, -1, 0, 0, lag)
        // Un frame en la cola (< 66 ms) es lo normal: nunca cuenta.
        for (t in 0L..2_000L step 100) assertNull("t=$t", c.onSample(usb(t, 40)))
        // Retraso sostenido 500 ms: un paso, con el motivo.
        for (t in 2_100L..2_500L step 100) assertNull("t=$t", c.onSample(usb(t, 120)))
        val st = c.onSample(usb(2_600, 120))
        assertNotNull(st)
        assertTrue(st!!.text, st.text.contains("cola de la sesión 120 ms de retraso, 500 ms"))
        assertTrue(st.bitrateAfter < car)
        // Con NetStat el retraso de la cola no cuenta (ya lo dicen la cola del kernel y el rtt).
        val tcp = LinkRateController(car, 30)
        for (t in 0L..3_000L step 100) {
            assertNull("t=$t", tcp.onSample(LinkRateController.Sample(t, 4 * kb, 2, 10, 12, 0, 0, 500)))
        }
        // Y sin dato (-1), tampoco.
        val unknown = LinkRateController(car, 30)
        for (t in 0L..3_000L step 100) assertNull("t=$t", unknown.onSample(usb(t, -1)))
    }

    @Test
    fun withKeepsTheQueueLag() {
        val s = LinkRateController.Sample(100, -1, -1, -1, -1, 0, 0, 90).with(3, 2)
        assertEquals(90L, s.queueLagMs)
        assertEquals(3, s.linkWaits)
        assertEquals(2L, s.lateFlushes)
        assertEquals(-1L, LinkRateController.Sample(100, 1, 1, 1, 1).queueLagMs)
    }

    // ---------------------------------------------------------------- emergencia (viaje del 2026-10-07)

    @Test
    fun threeMoreSecondsCongestedAtTheFloorAnd24FpsGoBelowTheFloorTo1_2MbitThen20Fps() {
        val c = LinkRateController(car, 30)
        // Hasta 4 s, como siempre: suelo normal, 24 fps (a los 2 s) y «no hay más que bajar» (2,5 s).
        assertEquals(5, c.queuedUntil(0, 4_000).size)
        assertEquals(floor, c.bitrate())
        assertFalse(c.inEmergency())
        // La congestión en el suelo y a 24 fps empezó a contar a los 2,1 s: a los 5,1 s baja del suelo normal.
        assertTrue(c.queuedUntil(4_100, 5_000).isEmpty())
        val first = c.onSample(queued(5_100))
        assertNotNull(first)
        assertTrue(first!!.emergency)
        assertTrue(first.congestion)
        assertEquals(floor, first.bitrateBefore)
        assertEquals(1_905_120, first.bitrateAfter)
        assertTrue(
            first.text,
            first.text.startsWith("enlace muy congestionado: bajo del suelo normal (2.5 Mbit/s) tras 3.0 s de congestión en él a 24 fps (outq 96 KB"),
        )
        assertTrue(first.text, first.text.endsWith("→ bitrate 1.9 Mbit/s"))
        assertTrue(c.inEmergency())
        // Un paso cada 500 ms: 1,43 → 1,2 (suelo de emergencia) → 20 fps → «no hay más que bajar» → silencio.
        val rest = c.queuedUntil(5_200, 9_000)
        assertTrue(rest.all { it.emergency && it.congestion })
        assertEquals(listOf(1_428_840, 1_200_000), rest.filter { it.bitrateChanged() }.map { it.bitrateAfter })
        assertTrue(rest[1].text, rest[1].text.endsWith("(suelo de emergencia)"))
        val fps = rest.single { it.fpsChanged() }
        assertEquals(24, fps.fpsBefore)
        assertEquals(20, fps.fpsAfter)
        assertTrue(fps.text, fps.text.contains("con el bitrate en el suelo de emergencia (1.2 Mbit/s) → 20 fps"))
        assertTrue(rest.last().text, rest.last().text.endsWith("y 20 fps: no hay más que bajar"))
        assertEquals(4, rest.size)
        assertEquals(1_200_000, c.minBitrate())
        assertEquals(20, c.fpsCap())
        assertEquals(1, c.emergencies())
        assertEquals(10, c.congestionEvents())
        assertTrue(c.statsLine(), c.statsLine().contains("· 20 fps") && c.statsLine().endsWith("· emergencia (suelo normal 2.5 Mbit/s)"))
    }

    @Test
    fun shortCleanGapsKeepCountingButARecoveryRestartsTheThreeSeconds() {
        // Huecos limpios de menos de 3 s (como entre los cortes de S26) no reinician la cuenta.
        val gaps = LinkRateController(car, 30)
        gaps.queuedUntil(0, 4_000)
        assertTrue(gaps.cleanUntil(4_100, 4_400).isEmpty())
        val st = gaps.queuedUntil(4_500, 5_500).single()
        assertTrue(st.text, st.emergency && st.text.startsWith("enlace muy congestionado: bajo del suelo normal"))
        // Con 3 s limpios vuelven los 30 fps: la próxima congestión en el suelo vuelve a pasar por 24 fps y espera 3 s más.
        val c = LinkRateController(car, 30)
        c.queuedUntil(0, 4_000)
        assertEquals(30, c.cleanUntil(4_100, 7_100).single().fpsAfter)
        val again = c.queuedUntil(7_200, 10_700)
        assertTrue(again.none { it.emergency })
        assertEquals(24, again.first().fpsAfter)
        assertEquals(floor, c.bitrate())
        // 24 fps a los 7,7 s; la cuenta empieza a los 7,8 s y llega a los 10,8 s.
        assertTrue(c.onSample(queued(10_800))!!.emergency)
    }

    @Test
    fun aRadioCutIsCongestionAtOnce() {
        val c = LinkRateController(car, 30)
        assertNull(c.onSample(clean(0)))
        val st = c.onSample(clean(100).withRadioCut())
        assertNotNull(st)
        assertTrue(st!!.text, st.text.startsWith("congestión (corte de radio) → bitrate 3.8 Mbit/s"))
        assertEquals(3_810_240, c.bitrate())
        // Otro corte dentro de los 500 ms no baja otra vez, pero reinicia la calma.
        assertNull(c.onSample(clean(300).withRadioCut()))
        assertTrue(c.cleanUntil(400, 3_300).isEmpty())
        assertNotNull(c.onSample(clean(3_400)))
        // Por el cable (sin NetStat) se llama «corte del cable»; `with` conserva la marca.
        val usb = LinkRateController(car, 30)
        val s = LinkRateController.Sample(0, -1, -1, -1, -1, 0, 0, 10).withRadioCut().with(0, 0)
        assertTrue(s.radioCut)
        assertTrue(usb.onSample(s)!!.text.contains("corte del cable"))
        assertFalse(LinkRateController.Sample(0, 1, 1, 1, 1).radioCut)
    }

    @Test
    fun emergencyRearmsOnlyAfter30sCleanAtOrAboveTheNormalFloor() {
        val c = LinkRateController(car, 30)
        c.queuedUntil(0, 8_000)
        assertEquals(1_200_000, c.bitrate())
        assertEquals(20, c.fpsCap())
        // Limpio: primero los fps, luego +25 % cada 3 s; al pasar del suelo normal sale de la emergencia.
        val up = c.cleanUntil(8_100, 30_000)
        assertEquals(30, up[0].fpsAfter)
        assertEquals(listOf(1_500_000, 1_875_000, 2_343_750, 2_929_688, 3_662_110, 4_577_638), up.drop(1).map { it.bitrateAfter })
        assertTrue(up[4].text, up[4].text.endsWith("· fuera de la emergencia (suelo normal 2.5 Mbit/s)"))
        assertFalse(c.inEmergency())
        // Congestión antes de 30 s limpios en el suelo normal: baja como siempre (suelo, 24 fps) y no pasa de ahí.
        val down = c.queuedUntil(30_100, 40_000)
        assertTrue(down.none { it.emergency })
        assertEquals(floor, c.bitrate())
        assertEquals(24, c.fpsCap())
        assertEquals(1, c.emergencies())
        // 30 s limpios en el suelo normal o por encima: rearmada (una línea, o al final de la subida si coincide).
        val back = c.cleanUntil(40_100, 70_000)
        assertTrue(back.none { it.text.contains("rearmada") })
        assertEquals(car, c.bitrate())
        val rearm = c.onSample(clean(70_100))
        assertNotNull(rearm)
        assertFalse(rearm!!.bitrateChanged() || rearm.fpsChanged() || rearm.congestion)
        assertEquals("emergencia rearmada (30 s limpio con 5.1 Mbit/s)", rearm.text)
        // Y vuelve a servir.
        val again = c.queuedUntil(70_200, 76_000)
        assertTrue(again.any { it.emergency && it.text.startsWith("enlace muy congestionado: bajo del suelo normal") })
        assertEquals(2, c.emergencies())
    }

    @Test
    fun aCongestedSampleRestartsThe30sRearmCount() {
        val c = LinkRateController(car, 30)
        c.queuedUntil(0, 8_000)
        c.cleanUntil(8_100, 23_100)
        assertFalse(c.inEmergency())
        // Limpio desde los 23,2 s, un vaciado suelto a los 50 s y otros 30 s: la línea llega a los 80,1 s, no a los 53,2.
        c.cleanUntil(23_200, 49_900)
        assertNotNull(c.onSample(LinkRateController.Sample(50_000, 4 * kb, 2, 10, 12, 0, 1)))
        assertTrue(c.cleanUntil(50_100, 80_000).none { it.text.contains("rearmada") })
        assertTrue(c.cleanUntil(80_100, 80_200).single().text.contains("emergencia rearmada"))
    }

    @Test
    fun aNewSessionRearmsTheEmergency() {
        val c = LinkRateController(car, 30)
        c.queuedUntil(0, 8_000)
        assertTrue(c.inEmergency())
        c.beginSession()
        assertFalse(c.inEmergency())
        assertEquals(0, c.emergencies())
        assertEquals(car, c.bitrate())
        assertTrue(c.queuedUntil(10_000, 18_000).any { it.emergency })
    }

    @Test
    fun congestedNowFollowsTheLastSampleEvenDuringTheHoldBetweenSteps() {
        // Lo usa el tope de los P-frames (PFrameSizeController): la congestión cuenta aunque el bitrate no se mueva.
        val c = LinkRateController(car, 30)
        assertFalse(c.congestedNow())
        assertNotNull(c.onSample(clean(100).withRadioCut()))
        assertTrue(c.congestedNow())
        assertNull(c.onSample(clean(200).withRadioCut())) // espera de 500 ms entre pasos
        assertTrue(c.congestedNow())
        c.onSample(clean(300))
        assertFalse(c.congestedNow())
        c.onSample(clean(400).withRadioCut())
        c.beginSession()
        assertFalse(c.congestedNow())
    }
}
