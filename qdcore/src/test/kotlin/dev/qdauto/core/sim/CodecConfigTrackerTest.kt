package dev.qdauto.core.sim

import dev.qdauto.core.h264.NalType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Manía del C10: reinicia el decodificador con cada SPS/PPS. El tracker juzga cada uno por lo que lo rodea. */
class CodecConfigTrackerTest {
    private var nowNs = 0L
    private val tracker = CodecConfigTracker { nowNs }
    private var index = 0
    private val sps = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0, 0, 0, 1, 0x68, 0x1)

    private fun video(kind: VideoKind, bytes: ByteArray? = null): VideoFrameInfo {
        val nals = when (kind) {
            VideoKind.CONFIG -> listOf(NalType.SPS, NalType.PPS)
            VideoKind.IDR -> listOf(NalType.IDR)
            VideoKind.P -> listOf(NalType.SLICE)
            VideoKind.OTHER -> listOf(NalType.SEI)
        }
        return VideoFrameInfo(index++, kind, null, bytes?.size ?: 1000, nals, emptyList()).also { tracker.onVideo(it, bytes) }
    }

    private fun advance(ms: Long) {
        nowNs += ms * 1_000_000
    }

    @Test
    fun firstConfigIsAlwaysFine() {
        video(VideoKind.CONFIG, sps)
        video(VideoKind.IDR)
        repeat(5) { video(VideoKind.P) }
        val s = tracker.summary()
        assertEquals(1, s.count)
        assertEquals(listOf(CodecConfigVerdict.FIRST), s.events.map { it.verdict })
        assertFalse(s.failed)
        assertFalse(s.warned)
        assertEquals("1 SPS/PPS", s.describe())
    }

    @Test
    fun configBeforeARequestedIdrIsCorrect() {
        video(VideoKind.CONFIG, sps)
        video(VideoKind.IDR)
        video(VideoKind.P)
        advance(2_000)
        tracker.noteKeyframeRequest()
        video(VideoKind.CONFIG, sps)
        video(VideoKind.IDR)
        val s = tracker.summary()
        assertEquals(listOf(CodecConfigVerdict.FIRST, CodecConfigVerdict.REQUESTED), s.events.map { it.verdict })
        assertEquals(listOf(2_000L), s.intervalsMs)
        assertEquals(1, s.identical)
        assertTrue(s.events[1].requested)
        assertTrue(s.events[1].sameAsPrevious)
        assertFalse(s.failed)
        assertFalse(s.warned)
        assertEquals("2 SPS/PPS, cada 2000-2000 ms, 1 idéntico al anterior, 1 tras KEY_FRAME_REQ", s.describe())
    }

    @Test
    fun unrequestedConfigBeforeAnIdrIsAWarning() {
        video(VideoKind.CONFIG, sps)
        video(VideoKind.IDR)
        advance(500)
        video(VideoKind.CONFIG, byteArrayOf(1, 2, 3))
        video(VideoKind.IDR)
        val s = tracker.summary()
        assertEquals(CodecConfigVerdict.UNREQUESTED, s.events[1].verdict)
        assertFalse(s.events[1].sameAsPrevious)
        assertFalse(s.failed)
        assertTrue(s.warned)
        assertTrue(s.describe().contains("1 delante de un IDR que no se pidió"), s.describe())
    }

    @Test
    fun configNotFollowedByAnIdrFails() {
        video(VideoKind.CONFIG, sps)
        video(VideoKind.IDR)
        tracker.noteKeyframeRequest()
        video(VideoKind.CONFIG, sps) // pedido, pero detrás viene un P: el decodificador se reinicia sin referencia
        video(VideoKind.P)
        video(VideoKind.CONFIG, sps)
        video(VideoKind.CONFIG, sps) // dos seguidos: el primero tampoco tiene IDR detrás
        video(VideoKind.IDR)
        val s = tracker.summary()
        assertEquals(
            listOf(CodecConfigVerdict.FIRST, CodecConfigVerdict.NO_IDR, CodecConfigVerdict.NO_IDR, CodecConfigVerdict.UNREQUESTED),
            s.events.map { it.verdict },
        )
        assertTrue(s.failed)
        assertFalse(s.warned)
        assertEquals(listOf(2, 4), s.noIdr.map { it.messageIndex })
        assertTrue(s.describe().contains("2 sin IDR detrás (mensajes #2 seguido de P, #4 seguido de CONFIG)"), s.describe())
    }

    @Test
    fun aKeyframeRequestIsConsumedByTheNextConfigOrIdr() {
        video(VideoKind.CONFIG, sps)
        video(VideoKind.IDR)
        tracker.noteKeyframeRequest()
        video(VideoKind.IDR) // el teléfono contestó solo con el IDR: la petición queda consumida
        video(VideoKind.CONFIG, sps)
        video(VideoKind.IDR)
        assertEquals(CodecConfigVerdict.UNREQUESTED, tracker.summary().events[1].verdict)
    }

    @Test
    fun lastConfigWithoutAnythingBehindIsPending() {
        video(VideoKind.CONFIG, sps)
        video(VideoKind.IDR)
        video(VideoKind.CONFIG, sps)
        val s = tracker.summary()
        assertEquals(CodecConfigVerdict.PENDING, s.events[1].verdict)
        assertFalse(s.failed)
        assertFalse(s.warned)
        assertTrue(s.describe().endsWith("1 al final sin nada detrás"), s.describe())
    }
}
