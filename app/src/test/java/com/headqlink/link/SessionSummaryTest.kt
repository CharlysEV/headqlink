package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Resumen de sesión, `sessions.csv` (cabecera, recorte a N filas) y resumen del viaje (qdauto §7.4). */
class SessionSummaryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun record(sid: Int, close: String = "EOF", reconnectMs: Long = -1, verdict: String = "CREADO") = SessionSummary.Record(
        sid = sid, startWallMs = 1_000_000L, endWallMs = 1_077_000L, engine = "qdauto", link = "hotspot", videoMode = "aa_ext",
        profile = "alto", video = "1920x882", carIp = "10.212.226.80", carPort = 40736, carName = "LeapMotor-A750",
        local = "10.212.226.210:18883", iface = "swlan0 (zona Wi-Fi)", closeKind = close, closeDetail = "el coche cerró, la conexión",
        reachedVideo = true, tCarInfoMs = 13, tVideoCtrlMs = 41, tFirstFrameMs = 95, tFirstIdrMs = 95, carKeyframeRequests = 1,
        frames = 2240, bytes = 48_000_000, idr = 19, dropped = 0, flushes = 0, maxWriteMs = 46, maxLagMs = 7, carHeartbeats = 25,
        heartbeatMinMs = 2_800, heartbeatMaxMs = 14_800, touches = 1310, maxCarGapMs = 14_800, stalls = 0, maxStallMs = 0,
        retrans = 3, radio = "sin cortes · retrans +3 · outq máx. 61 KB", reconnectMs = reconnectMs,
        reconnect = if (reconnectMs >= 0) "$reconnectMs ms desde el fin de S${sid - 1}" else "", videoVerdict = verdict, aaCycles = 0,
    )

    @Test
    fun blockHasTheDesignedLines() {
        val b = SessionSummary.block(record(2, close = "LOCAL", reconnectMs = 330, verdict = "REUTILIZADO"))
        val lines = b.split('\n')
        assertEquals(7, lines.size)
        assertTrue(lines[0], lines[0].startsWith("==== S2 · ") && lines[0].contains("fin LOCAL: el coche cerró, la conexión"))
        assertTrue(lines[1], lines[1].contains("coche 10.212.226.80:40736 «LeapMotor-A750» · local 10.212.226.210:18883 swlan0"))
        assertTrue(lines[2], lines[2].startsWith("handshake: CAR_INFO +13 ms · VIDEO_CTRL{1} +41 ms"))
        assertTrue(lines[3], lines[3].startsWith("vídeo: 2240 frames"))
        assertTrue(lines[4], lines[4].contains("25 heartbeats") && lines[4].contains("1310 toques"))
        assertTrue(lines[5], lines[5].startsWith("radio: sin cortes"))
        assertTrue(lines[6], lines[6].startsWith("reconexión: 330 ms desde el fin de S1") && lines[6].endsWith("vídeo REUTILIZADO"))
    }

    @Test
    fun csvHasTheHeaderOnceQuotesCommasAndKeepsTheLastRows() {
        val dir = tmp.newFolder("logs")
        for (i in 1..5) SessionSummary.appendCsv(dir, SessionSummary.csvRow(record(i)), maxRows = 3)
        val lines = File(dir, SessionSummary.CSV_NAME).readLines().filter { it.isNotEmpty() }
        assertEquals(SessionSummary.CSV_HEADER, lines[0])
        assertEquals(4, lines.size) // cabecera + 3 filas
        assertEquals(40, SessionSummary.CSV_HEADER.split(',').size)
        assertTrue(lines[1].split(',')[3] == "3") // se conservan las últimas: S3, S4, S5
        assertTrue(lines[3].contains("\"el coche cerró, la conexión\""))
        assertEquals(listOf("4", "5"), SessionSummary.lastRows(dir, 2).map { it.split(',')[3] })
    }

    @Test
    fun tripSummaryAggregatesSessions() {
        // El contador de arranques de AA es del proceso: 3 de un viaje anterior, 1 en este.
        SessionSummary.startTrip(1_000_000L, aaLaunches = 3)
        SessionSummary.noteSession(record(1))
        SessionSummary.noteSession(record(2, reconnectMs = 300, verdict = "REUTILIZADO"))
        SessionSummary.noteSession(record(3, close = "SUPERSEDED", reconnectMs = 500, verdict = "REUTILIZADO"))
        val t = SessionSummary.tripSummary(1_600_000L, aaLaunches = 4)
        assertTrue(t, t.contains("3 sesiones"))
        assertTrue(t, t.contains("reconexiones: 2 · hueco mín. 300 ms, medio 400 ms, máx. 500 ms · vídeo reutilizado 2"))
        assertTrue(t, t.contains("EOF 2") && t.contains("SUPERSEDED 1"))
        assertTrue(t, t.contains("arranques de AA (Self-Mode): 1"))
    }

    @Test
    fun csvCellsThatLookLikeFormulasAreDefused() {
        assertEquals("'=1+2", SessionSummary.csv("=1+2"))
        assertEquals("'+34 600", SessionSummary.csv("+34 600"))
        assertEquals("'@SUMA(A1)", SessionSummary.csv("@SUMA(A1)"))
        assertEquals("'-x", SessionSummary.csv("-x"))
        assertEquals("'\tx", SessionSummary.csv("\tx"))
        assertEquals("\"'=A1,B1\"", SessionSummary.csv("=A1,B1"))
        // Los números (también los -1 de «no pasó») se quedan como números.
        assertEquals("-1", SessionSummary.csv("-1"))
        assertEquals("-3.5", SessionSummary.csv("-3.5"))
        assertEquals("1310", SessionSummary.csv("1310"))
        assertEquals("", SessionSummary.csv(""))
        // El nombre del coche llega del broadcast UDP: en la fila va desactivado.
        val r = record(1)
        val evil = SessionSummary.Record(
            sid = r.sid, startWallMs = r.startWallMs, endWallMs = r.endWallMs, engine = r.engine, link = r.link,
            videoMode = r.videoMode, profile = r.profile, video = r.video, carIp = r.carIp, carPort = r.carPort,
            carName = "=cmd|' /C calc'!A0", local = r.local, iface = r.iface, closeKind = r.closeKind, closeDetail = "",
            reachedVideo = r.reachedVideo, tCarInfoMs = r.tCarInfoMs, tVideoCtrlMs = r.tVideoCtrlMs,
            tFirstFrameMs = r.tFirstFrameMs, tFirstIdrMs = -1, carKeyframeRequests = r.carKeyframeRequests, frames = r.frames,
            bytes = r.bytes, idr = r.idr, dropped = r.dropped, flushes = r.flushes, maxWriteMs = r.maxWriteMs,
            maxLagMs = r.maxLagMs, carHeartbeats = r.carHeartbeats, heartbeatMinMs = r.heartbeatMinMs,
            heartbeatMaxMs = r.heartbeatMaxMs, touches = r.touches, maxCarGapMs = r.maxCarGapMs, stalls = r.stalls,
            maxStallMs = r.maxStallMs, retrans = r.retrans, radio = r.radio, reconnectMs = -1, reconnect = "",
            videoVerdict = r.videoVerdict, aaCycles = r.aaCycles,
        )
        val cells = SessionSummary.csvRow(evil).split(',')
        assertEquals(40, cells.size)
        assertEquals("'=cmd|' /C calc'!A0", cells[9])
        assertEquals("-1", cells[17])
        assertEquals("-1", cells[33])
        // Sin estado térmico conocido.
        assertEquals(listOf("-1", "-1", "0", "0"), cells.takeLast(4))
    }

    @Test
    fun thermalStateAndFpsCapAreInTheBlockTheCsvAndTheTrip() {
        val r = record(4)
        val hot = SessionSummary.Record(
            sid = r.sid, startWallMs = r.startWallMs, endWallMs = r.endWallMs, engine = r.engine, link = r.link,
            videoMode = r.videoMode, profile = "coche", video = r.video, carIp = r.carIp, carPort = r.carPort,
            carName = r.carName, local = r.local, iface = r.iface, closeKind = r.closeKind, closeDetail = r.closeDetail,
            reachedVideo = r.reachedVideo, tCarInfoMs = r.tCarInfoMs, tVideoCtrlMs = r.tVideoCtrlMs,
            tFirstFrameMs = r.tFirstFrameMs, tFirstIdrMs = r.tFirstIdrMs, carKeyframeRequests = r.carKeyframeRequests,
            frames = r.frames, bytes = r.bytes, idr = r.idr, dropped = r.dropped, flushes = r.flushes,
            maxWriteMs = r.maxWriteMs, maxLagMs = r.maxLagMs, carHeartbeats = r.carHeartbeats,
            heartbeatMinMs = r.heartbeatMinMs, heartbeatMaxMs = r.heartbeatMaxMs, touches = r.touches,
            maxCarGapMs = r.maxCarGapMs, stalls = r.stalls, maxStallMs = r.maxStallMs, retrans = r.retrans, radio = r.radio,
            reconnectMs = -1, reconnect = "", videoVerdict = r.videoVerdict, aaCycles = r.aaCycles,
            thermalEnd = 1, thermalMax = 3, fpsCapEnd = 30, fpsCapMin = 20,
        )
        val lines = SessionSummary.block(hot).split('\n')
        assertEquals(7, lines.size)
        assertTrue(lines[3], lines[3].contains("térmico 1 (máx. 3) · tope 30 fps (mín. 20)"))
        val cells = SessionSummary.csvRow(hot).split(',')
        assertEquals(listOf("1", "3", "30", "20"), cells.takeLast(4))
        assertEquals(listOf("termico_fin", "termico_max", "tope_fps_fin", "tope_fps_min"), SessionSummary.CSV_HEADER.split(',').takeLast(4))
        SessionSummary.startTrip(1_000_000L, aaLaunches = 0)
        SessionSummary.noteSession(record(3))
        SessionSummary.noteSession(hot)
        val t = SessionSummary.tripSummary(1_600_000L, aaLaunches = 0)
        assertTrue(t, t.contains("térmico: máx. 3 · tope mín. 20 fps"))
    }
}
