package dev.qdauto.core.wire

import dev.qdauto.core.TestSupport.ChunkedInputStream
import dev.qdauto.core.util.BE
import java.io.ByteArrayOutputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FrameReaderTest {
    private fun stream(vararg parts: ByteArray): ByteArray = ByteArrayOutputStream().apply { parts.forEach { write(it) } }.toByteArray()

    private fun bigVideo(size: Int): ByteArray {
        val payload = ByteArray(size).also { Random(1).nextBytes(it) }
        payload[0] = 0; payload[1] = 0; payload[2] = 0; payload[3] = 1; payload[4] = 0x65
        return VideoMessage.build(VideoParams(1920, 1080, 30, 4_000_000, 1, 3), payload)
    }

    private fun readAll(input: java.io.InputStream, max: Int = FrameReader.DEFAULT_MAX_MESSAGE_SIZE): List<WireMessage> {
        val r = FrameReader(input, max)
        return generateSequence { r.next() }.toList()
    }

    @Test
    fun reassemblesFromOneByteChunks() {
        val video = bigVideo(200_000)
        val bytes = stream(
            PhoneMessages.appStatus(36),
            PhoneMessages.heartbeat(),
            CarMessages.carInfo(dev.qdauto.core.json.JsonObject.of("CarWidth" to 1920)),
            video,
            TouchCodec.build(0, listOf(TouchPointer.down(0, 1f, 2f))),
            BinBlock.legacyHeartbeatRequest(),
        )
        for (chunk in listOf(1, 3, 7, 16, 4096, Int.MAX_VALUE)) {
            val msgs = readAll(ChunkedInputStream(bytes, chunk))
            assertEquals(6, msgs.size, "trozos de $chunk")
            assertIs<WireMessage.Bin>(msgs[0])
            assertEquals(1, msgs[0].let { (it as WireMessage.Bin).block.cmd })
            val hb = assertIs<WireMessage.Frame>(msgs[1])
            assertEquals(PhoneMessages.HEARTBEAT_JSON, hb.payloadText())
            assertEquals("CAR_INFO", ControlMessage.parse((msgs[2] as WireMessage.Frame).header, (msgs[2] as WireMessage.Frame).payloadText()).cmd)
            val v = assertIs<WireMessage.Frame>(msgs[3])
            assertContentEquals(video, v.wireBytes())
            assertEquals(MsgType.TOUCH, (msgs[4] as WireMessage.Frame).header.msgType)
            assertTrue((msgs[5] as WireMessage.Bin).block.isLegacyHeartbeatRequest)
        }
    }

    @Test
    fun badMagicIsReportedAndResynced() {
        val junk = "HELLO, NOT A FRAME 5A5".toByteArray() // acaba en un magic a medias
        val bytes = stream(junk, PhoneMessages.heartbeat(), "xx".toByteArray(), PhoneMessages.heartbeat())
        for (chunk in listOf(1, 5, Int.MAX_VALUE)) {
            val msgs = readAll(ChunkedInputStream(bytes, chunk))
            assertEquals(4, msgs.size, "trozos de $chunk: $msgs")
            val g1 = assertIs<WireMessage.Garbage>(msgs[0])
            assertContentEquals(junk, g1.bytes)
            assertEquals(junk.size.toLong(), g1.totalBytes)
            assertTrue(g1.reason.contains("magic"))
            assertIs<WireMessage.Frame>(msgs[1])
            val g2 = assertIs<WireMessage.Garbage>(msgs[2])
            assertContentEquals("xx".toByteArray(), g2.bytes)
            assertIs<WireMessage.Frame>(msgs[3])
        }
    }

    @Test
    fun impossibleTotalSizeResyncs() {
        val tooSmall = PhoneMessages.heartbeat().also { BE.putInt(it, 4, 15) }
        val tooBig = PhoneMessages.heartbeat().also { BE.putInt(it, 4, 9 * 1024 * 1024) }
        val negative = PhoneMessages.heartbeat().also { BE.putInt(it, 4, -5) }
        for (bad in listOf(tooSmall, tooBig, negative)) {
            val msgs = readAll(ChunkedInputStream(stream(bad, PhoneMessages.heartbeat()), 2))
            // La cabecera mala y su JSON acaban como basura; el heartbeat siguiente se lee bien.
            assertTrue(msgs.first() is WireMessage.Garbage, "$msgs")
            assertTrue(msgs.dropLast(1).all { it is WireMessage.Garbage })
            assertEquals(PhoneMessages.HEARTBEAT_JSON, (msgs.last() as WireMessage.Frame).payloadText())
            assertEquals(bad.size.toLong(), msgs.dropLast(1).sumOf { (it as WireMessage.Garbage).totalBytes })
        }
    }

    @Test
    fun maxSizeGuardIsConfigurable() {
        val video = bigVideo(5_000)
        val msgs = readAll(video.inputStream(), max = 1_000)
        assertTrue(msgs.all { it is WireMessage.Garbage })
        assertEquals(video.size.toLong(), msgs.sumOf { (it as WireMessage.Garbage).totalBytes })
    }

    @Test
    fun extLenLargerThanBodyIsKeptButFlagged() {
        val msg = PhoneMessages.heartbeat().also { BE.putShort(it, 8, 100) }
        val f = assertIs<WireMessage.Frame>(readAll(msg.inputStream()).single())
        assertEquals(false, f.extValid)
        assertEquals(0, f.payloadLength)
    }

    @Test
    fun eofInsideAMessage() {
        val video = bigVideo(10_000)
        val msgs = readAll(ChunkedInputStream(video.copyOf(5_000), 999))
        val g = assertIs<WireMessage.Garbage>(msgs.single())
        assertEquals(5_000L, g.totalBytes)
        assertTrue(g.reason.contains("cortado"))
        assertEquals(1, readAll(byteArrayOf(0x35, 0x41).inputStream()).size)
        assertNull(FrameReader(ByteArray(0).inputStream()).next())
    }

    @Test
    fun legacyBlocksWithExtraData() {
        // dataType 3 (HU) con action 1: lee totalsize − headersize bytes más.
        val hu = BinBlock.legacyHeartbeatRequest().also {
            BE.putInt(it, 4, BinBlock.DATA_TYPE_HU)
            BE.putInt(it, 8, 512 + 10)
            BE.putInt(it, 64, 10)
        }
        // dataType 12 (voz): totalsize − 512.
        val speech = BinBlock.legacyHeartbeatRequest().also {
            BE.putInt(it, 4, BinBlock.DATA_TYPE_SPEECH)
            BE.putInt(it, 8, 512 + 4)
            BE.putInt(it, 28, 2)
        }
        // dataType 3 con action 2: no lee nada más.
        val huAction2 = hu.copyOf().also { BE.putInt(it, 28, 2) }
        val extra10 = ByteArray(10) { it.toByte() }
        val extra4 = byteArrayOf(9, 8, 7, 6)
        val msgs = readAll(ChunkedInputStream(stream(hu, extra10, speech, extra4, huAction2, PhoneMessages.heartbeat()), 3))
        assertEquals(4, msgs.size)
        assertContentEquals(extra10, (msgs[0] as WireMessage.Bin).block.extra)
        assertContentEquals(extra4, (msgs[1] as WireMessage.Bin).block.extra)
        assertEquals(0, (msgs[2] as WireMessage.Bin).block.extraSize)
        assertIs<WireMessage.Frame>(msgs[3])
    }

    @Test
    fun tracksActivity() {
        val r = FrameReader(PhoneMessages.heartbeat().inputStream())
        val before = r.lastActivityNanos
        Thread.sleep(2)
        r.next()
        assertTrue(r.lastActivityNanos > before)
        assertEquals(35L, r.totalBytesRead)
    }
}
