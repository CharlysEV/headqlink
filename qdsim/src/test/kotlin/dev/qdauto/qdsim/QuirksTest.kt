package dev.qdauto.qdsim

import dev.qdauto.core.sim.CodecConfigEvent
import dev.qdauto.core.sim.CodecConfigSummary
import dev.qdauto.core.sim.ReceiverHang
import dev.qdauto.core.sim.VideoKind
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuirksTest {
    @Test
    fun messageSizeVerdicts() {
        assertEquals(Level.SKIP, Quirks.messageSize(0, 0, 0, null, null, 512 * 1024).level)
        val ok = Quirks.messageSize(100, 60_000, 0, null, null, 512 * 1024)
        assertEquals(Level.PASS, ok.level)
        assertTrue(ok.text.contains("máximo 60000 B en 100 mensajes"), ok.text)
        val warn = Quirks.messageSize(100, 500 * 1024, 2, null, null, 512 * 1024)
        assertEquals(Level.WARN, warn.level)
        assertTrue(warn.text.contains("2 mensajes de más de 480 KiB"), warn.text)
        val hang = Quirks.messageSize(100, 600 * 1024, 1, ReceiverHang(42, 600 * 1024, 512 * 1024, 10_000), 7_500, 512 * 1024)
        assertEquals(Level.FAIL, hang.level)
        assertTrue(hang.text.contains("se colgó") && hang.text.contains("#42") && hang.text.contains("a los 7500 ms") && hang.text.contains("10000 ms sin leer"), hang.text)
        val noLimit = Quirks.messageSize(100, 600 * 1024, 1, null, null, 0)
        assertEquals(Level.FAIL, noLimit.level)
        assertTrue(noLimit.text.contains("se colgaría"), noLimit.text)
    }

    private fun event(index: Int, first: Boolean, requested: Boolean, followedBy: VideoKind?, since: Long? = 1_000) =
        CodecConfigEvent(index, first, if (first) null else since, requested, sameAsPrevious = !first, followedBy = followedBy)

    @Test
    fun spsRepeatVerdicts() {
        assertEquals(Level.SKIP, Quirks.spsRepeat(0, CodecConfigSummary(emptyList())).level)
        assertEquals(Level.FAIL, Quirks.spsRepeat(10, CodecConfigSummary(emptyList())).level)
        val only = Quirks.spsRepeat(10, CodecConfigSummary(listOf(event(0, true, false, VideoKind.IDR))))
        assertEquals(Level.PASS, only.level)
        val requested = Quirks.spsRepeat(10, CodecConfigSummary(listOf(event(0, true, false, VideoKind.IDR), event(5, false, true, VideoKind.IDR))))
        assertEquals(Level.PASS, requested.level)
        assertTrue(requested.text.contains("1 tras KEY_FRAME_REQ"), requested.text)
        val unrequested = Quirks.spsRepeat(10, CodecConfigSummary(listOf(event(0, true, false, VideoKind.IDR), event(5, false, false, VideoKind.IDR))))
        assertEquals(Level.WARN, unrequested.level)
        assertTrue(unrequested.text.contains("no se pidió"), unrequested.text)
        val noIdr = Quirks.spsRepeat(10, CodecConfigSummary(listOf(event(0, true, false, VideoKind.IDR), event(5, false, true, VideoKind.P), event(7, false, false, VideoKind.IDR))))
        assertEquals(Level.FAIL, noIdr.level)
        assertTrue(noIdr.text.contains("1 sin IDR detrás (mensajes #5 seguido de P)"), noIdr.text)
    }

    @Test
    fun decodeVerdictsAndParsing() {
        val ff = File("ffmpeg.exe")
        assertEquals(Level.PASS, Quirks.decode(DecodeResult(ff, 300, emptyList(), 0, 1_200, null)).level)
        val errs = Quirks.decode(DecodeResult(ff, 300, listOf("[h264 @ 0x1] decode_slice_header error"), 0, 1_200, null))
        assertEquals(Level.FAIL, errs.level)
        assertTrue(errs.text.contains("1 líneas de error: [h264 @ 0x1] decode_slice_header error"), errs.text)
        assertEquals(Level.FAIL, Quirks.decode(DecodeResult(ff, null, emptyList(), 1, 10, null)).level)
        assertEquals(Level.FAIL, Quirks.decode(DecodeResult(ff, null, emptyList(), null, 10, "ffmpeg no terminó en 300 s")).level)
        assertEquals(312, Ffmpeg.parseFrames("frame=10\nprogress=continue\nframe=312\nprogress=end\n"))
        assertNull(Ffmpeg.parseFrames(""))
        assertEquals(listOf("a", "b"), Ffmpeg.errorLines("a\r\n\n b  \n".replace(" b", "b")))
    }

    @Test
    fun locateFfmpeg() {
        val dir = File(System.getProperty("java.io.tmpdir"), "qdsim-ffmpeg-test-${System.nanoTime()}").apply { mkdirs() }
        try {
            val missing = File(dir, "nope.exe")
            val fake = File(dir, if (File.separatorChar == '\\') "ffmpeg.exe" else "ffmpeg").apply { writeText("") }
            assertEquals(fake, Ffmpeg.locate(fake, path = "", default = missing))
            assertNull(Ffmpeg.locate(missing, path = dir.path, default = fake))
            assertEquals(fake, Ffmpeg.locate(null, path = "", default = fake))
            assertEquals(fake, Ffmpeg.locate(null, path = "C:\\no\\existe" + File.pathSeparator + dir.path, default = missing))
            assertNull(Ffmpeg.locate(null, path = "C:\\no\\existe", default = missing))
        } finally {
            dir.deleteRecursively()
        }
    }

    /** Con el ffmpeg de tools/: un Annex-B inventado tiene que dar errores de decodificación. */
    @Test
    fun realFfmpegFlagsGarbage() {
        val ffmpeg = Ffmpeg.locate() ?: return
        val file = File.createTempFile("qdsim-garbage", ".h264")
        try {
            file.outputStream().use { out ->
                out.write(byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0xC0.toByte(), 0x29, 0, 0, 0, 1, 0x68, 0xCE.toByte(), 0x3C, 0x80.toByte()))
                for (i in 0 until 5) out.write(byteArrayOf(0, 0, 0, 1, if (i == 0) 0x65 else 0x41) + ByteArray(2_000) { j -> ((j * 7 + i) % 255 + 1).toByte() })
            }
            val r = Ffmpeg.decode(ffmpeg, file, timeoutMs = 60_000)
            assertNull(r.failure, r.failure)
            assertEquals(Level.FAIL, Quirks.decode(r).level, r.toString())
        } finally {
            file.delete()
        }
    }

    @Test
    fun usbFramingVerdicts() {
        val ok = Quirks.usbFraming(40, 0, 18_000, 0)
        assertEquals(Level.PASS, ok.level)
        assertTrue(ok.text.contains("40 mensajes del móvil rellenos a 512 B, 0 sin rellenar"), ok.text)
        val off = Quirks.usbFraming(3, 25, 0, 0)
        assertEquals(Level.FAIL, off.level)
        assertTrue(off.text.contains("el móvil no rellena") && off.text.contains("Trama del cable USB por Wi-Fi"), off.text)
        assertEquals(Level.FAIL, Quirks.usbFraming(0, 0, 0, 0).level)
        assertTrue(Quirks.usbFraming(5, 0, 100, 1024).text.contains("1024 ceros de más"))
    }

    @Test
    fun usbFramingOption() {
        assertTrue(Options.parse(arrayOf("--scenario", "normal", "--usb-framing")).usbFraming)
        assertTrue(!Options.parse(arrayOf("--scenario", "normal")).usbFraming)
        assertTrue(Options.USAGE.contains("--usb-framing"))
    }
}
