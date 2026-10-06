package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cortes de radio con muestras sintéticas: instantes de INICIO/PROGRESO/FIN, tipos y sin falsos positivos (§7.3). */
class StallDetectorTest {
    private val d = StallDetector(1, { "t$it" }, thresholdMs = 400, freezeMs = 250, progressMs = 1_000)

    private fun s(
        now: Long,
        lastRx: Long,
        writingSince: Long = 0,
        lastWriteEnd: Long = 0,
        outq: Int = 0,
        carWindow: Int = 65_535,
        rwnd: Int = 0,
        retrans: Int = 0,
    ) = StallDetector.Sample(
        nowMs = now, lastReceiveMs = lastRx, lastRxKind = "TOUCH", writingSinceMs = writingSince, writingLabel = "VIDEO_IDR",
        writingBytes = 315_112, lastWriteEndMs = lastWriteEnd, outq = outq, unacked = 41, retrans = retrans, rttMs = 12,
        rttVarMs = 4, cwnd = 10, rwndLimitedMs = rwnd, carWindow = carWindow, context = "zona Wi-Fi swlan0",
    )

    /** Recorre de [from] a [to] cada 100 ms y junta los eventos. */
    private fun run(from: Long, to: Long, sample: (Long) -> StallDetector.Sample): List<StallDetector.Event> {
        val out = ArrayList<StallDetector.Event>()
        var t = from
        while (t <= to) {
            out += d.onSample(sample(t))
            t += 100
        }
        return out
    }

    @Test
    fun idleSessionAndSilentCarWithTxFlowingAreNotStalls() {
        // Sesión ociosa: nada que escribir y el coche callado 10 s (sus heartbeats llegan cada 2,8-14,8 s).
        assertTrue(run(0, 10_000) { t -> s(t, lastRx = 0 + 1, outq = 0) }.isEmpty())
        // Coche callado pero el TX fluye: la cola baja y los write terminan.
        assertTrue(run(10_100, 20_000) { t -> s(t, lastRx = 1, lastWriteEnd = t - 20, outq = ((t / 100) % 5 * 1000).toInt()) }.isEmpty())
        assertEquals(0, d.count)
    }

    @Test
    fun radioStallInBothDirectionsStartsProgressesAndEndsWhenRxReturns() {
        // Como el viaje 1: último RX a 44.392, write del IDR atascado desde 44.420; ráfaga de toques a los ~5 s.
        val lastRx = 44_392L
        val stuckSince = 44_420L
        val events = run(44_400, 49_400) { t -> s(t, lastRx = lastRx, writingSince = stuckSince, outq = 196 * 1024, retrans = ((t - 44_400) / 250).toInt()) }
        val start = events.first()
        assertEquals(StallDetector.Phase.START, start.phase)
        assertEquals(StallDetector.Kind.RADIO, start.kind)
        assertTrue(start.text, start.text.contains("INICIO RADIO: último RX t44392 (TOUCH), TX atascado desde t44420"))
        assertTrue(start.text, start.text.contains("write de VIDEO_IDR 315112 B"))
        assertTrue(start.text, start.text.contains("outq 196 KB"))
        assertTrue(start.text, start.text.contains("zona Wi-Fi swlan0"))
        val progress = events.filter { it.phase == StallDetector.Phase.PROGRESS }
        assertEquals(4, progress.size) // cada segundo
        assertTrue(d.inStall)
        // Vuelve el RX (la ráfaga): FIN.
        val end = d.onSample(s(49_500, lastRx = 49_413, writingSince = stuckSince, outq = 196 * 1024, retrans = 21))
        assertEquals(1, end.size)
        assertEquals(StallDetector.Phase.END, end[0].phase)
        assertTrue(end[0].text, end[0].text.contains("FIN tras 4600 ms: vuelve RX"))
        assertEquals(1, d.count)
        assertEquals(1, d.radio)
        assertEquals(4_600, d.maxMs)
    }

    @Test
    fun carNotReadingIsReportedEvenIfItKeepsTalking() {
        // El coche sigue mandando heartbeats/toques, pero su ventana está a 0: es la app del coche la que no lee.
        val events = run(0, 2_000) { t -> s(t, lastRx = t - 50, writingSince = 100, outq = 60_000, carWindow = 0) }
        val start = events.first { it.phase == StallDetector.Phase.START }
        assertEquals(StallDetector.Kind.COCHE_NO_LEE, start.kind)
        assertTrue(start.text, start.text.contains("el coche sigue hablando"))
        assertTrue(start.text, start.text.contains("ventana del coche 0 B"))
        // Termina cuando el TX avanza (el write acaba y la cola baja), no porque llegue RX.
        val end = d.onSample(s(2_100, lastRx = 2_050, writingSince = 0, lastWriteEnd = 2_090, outq = 0, carWindow = 30_000))
        assertEquals(StallDetector.Phase.END, end.single().phase)
        assertTrue(end.single().text.contains("el TX avanza"))
        assertEquals(1, d.peer)
    }

    @Test
    fun growingReceiverLimitedTimeAlsoMeansTheCarIsNotReading() {
        var rwnd = 0
        val events = run(0, 1_500) { t ->
            rwnd += 90
            s(t, lastRx = t - 30, writingSince = 50, outq = 40_000, carWindow = 4_096, rwnd = rwnd)
        }
        assertEquals(StallDetector.Kind.COCHE_NO_LEE, events.first().kind)
    }

    @Test
    fun uplinkOnlyStallDoesNotEndJustBecauseTheCarTalks() {
        val events = run(0, 1_000) { t -> s(t, lastRx = t - 20, writingSince = 50, outq = 80_000) }
        assertEquals(StallDetector.Kind.RADIO, events.first().kind)
        assertTrue(events.first().text.contains("el coche sigue hablando"))
        assertTrue(d.onSample(s(1_100, lastRx = 1_090, writingSince = 50, outq = 80_000)).none { it.phase == StallDetector.Phase.END })
        assertTrue(d.inStall)
    }

    @Test
    fun aFrozenMonitorIsReported() {
        d.onSample(s(1_000, lastRx = 990))
        val ev = d.onSample(s(1_600, lastRx = 1_590))
        assertEquals(StallDetector.Kind.MOVIL_CONGELADO, ev.single().kind)
        assertTrue(ev.single().text.contains("600 ms"))
        assertEquals(1, d.freezes)
        assertTrue(d.onSample(s(1_700, lastRx = 1_690)).isEmpty())
    }

    @Test
    fun closingTheSessionEndsTheStall() {
        run(0, 1_000) { t -> s(t, lastRx = 0 + 1, writingSince = 10, outq = 100_000) }
        assertTrue(d.inStall)
        val end = d.onClose(1_300, "EOF: el coche cerró la conexión")!!
        assertTrue(end.text, end.text.contains("sesión cerrada (EOF"))
        assertNull(d.onClose(1_400, "otra vez"))
        assertTrue(d.summary().startsWith("1 cortes"))
    }

    @Test
    fun cableStallsSayTheCarIsNotReadingInsteadOfRadio() {
        val cable = StallDetector(4, { "t$it" }, thresholdMs = 400, freezeMs = 250, progressMs = 1_000, cable = true)
        val out = ArrayList<StallDetector.Event>()
        var t = 1_000L
        while (t <= 3_000L) {
            // Sin socket: sin NetStat (-1); un write de vídeo atascado desde 1,5 s y el coche callado desde 1,2 s.
            out += cable.onSample(
                StallDetector.Sample(
                    nowMs = t, lastReceiveMs = 1_200, lastRxKind = "HEARTBEAT", writingSinceMs = if (t >= 1_500) 1_500 else 0,
                    writingLabel = "VIDEO_P", writingBytes = 20_000, lastWriteEndMs = 1_400,
                ),
            )
            t += 100
        }
        val start = out.first { it.phase == StallDetector.Phase.START }
        assertEquals(StallDetector.Kind.RADIO, start.kind)
        assertTrue(start.text, start.text.startsWith("Corte S4 INICIO CABLE: el coche no lee · último RX t1200 (HEARTBEAT)"))
        assertTrue(start.text, start.text.contains("write de VIDEO_P 20000 B"))
        val progress = out.first { it.phase == StallDetector.Phase.PROGRESS }
        assertTrue(progress.text, progress.text.contains("(CABLE: el coche no lee)"))
        assertTrue(cable.summary(), cable.summary().startsWith("1 cortes (cable 1,"))
        // Por Wi-Fi, como siempre.
        assertTrue(StallDetector(1, { "t$it" }).summary().startsWith("sin cortes"))
    }
}
