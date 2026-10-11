package com.headqlink.link

import android.media.MediaCodecInfo.CodecProfileLevel
import android.view.KeyEvent
import dev.qdauto.core.wire.CarKey
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RoboConfig
import org.robolectric.annotation.ConscryptMode

/**
 * Imagen y conexión (0.2.48): qué dice el SPS/PPS, el nivel H.264 de Main/High, el bitrate cuando el coche pide 60 fps
 * (por el cable) y las teclas del coche hacia Android Auto.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@RoboConfig(sdk = [35], application = android.app.Application::class)
class VideoLinkTweaksTest {
    private fun bytes(hex: String): ByteArray = hex.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun spsPpsOfTheCarSession() {
        // El de una sesión real por cable (registro del 2026-10-11): Baseline, nivel 4.2, CAVLC.
        val csd = bytes("00000001 6742802a da01e01c 7e226a02 02020da1 426a0000 000168ce 06f2")
        assertEquals("perfil 66 (Baseline) · nivel 4.2 · CAVLC", H264Info.describe(csd))
    }

    @Test
    fun highProfileWithCabac() {
        // SPS High 4.0 y un PPS con pic/seq id 0 y entropy_coding_mode_flag 1 (bits 1 1 1 …).
        val csd = bytes("00000001 67640028 ac2b40 00000001 68ee3cb0")
        assertEquals("perfil 100 (High) · nivel 4.0 · CABAC", H264Info.describe(csd))
        assertEquals("", H264Info.describe(bytes("00000001 65888000")))
        assertEquals("", H264Info.describe(null))
    }

    @Test
    fun levelBySizeAndRate() {
        // 1920x882 son 6720 macrobloques: más de los 5120 del 3.2.
        assertEquals(CodecProfileLevel.AVCLevel4, VideoEncoder.levelForSize(1920, 882, 30))
        assertEquals(CodecProfileLevel.AVCLevel42, VideoEncoder.levelForSize(1920, 882, 60))
        assertEquals(CodecProfileLevel.AVCLevel31, VideoEncoder.levelForSize(1280, 588, 30))
    }

    @Test
    fun carBitrateWhenTheCarAsksSixty() {
        val car = VideoProfile.of(VideoProfile.CAR)
        // Por Wi-Fi el C10 pide 30 fps: lo suyo.
        assertEquals(5_080_320, car.startBitrate(5_080_320, 30, 1920))
        // Por cable pide 60 con el mismo bitrate: 8 Mbit/s (como Fluidez 60), con tope de 12.
        assertEquals(8_000_000, car.startBitrate(5_080_320, 60, 1920))
        assertEquals(12_000_000, car.startBitrate(16_000_000, 60, 1920))
        // Los perfiles fijos no cambian.
        assertEquals(10_000_000, VideoProfile.of(VideoProfile.HIGH).startBitrate(5_080_320, 60, 1920))
    }

    @Test
    fun carKeysToAndroidAuto() {
        assertEquals(KeyEvent.KEYCODE_HOME, carKeyToAndroid(CarKey.HOME))
        assertEquals(KeyEvent.KEYCODE_BACK, carKeyToAndroid(CarKey.BACK))
        assertEquals(KeyEvent.KEYCODE_MEDIA_NEXT, carKeyToAndroid(CarKey.MEDIA_NEXT))
        assertEquals(KeyEvent.KEYCODE_MEDIA_PREVIOUS, carKeyToAndroid(CarKey.MEDIA_PREVIOUS))
        assertEquals(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, carKeyToAndroid(CarKey.MEDIA_PLAY_PAUSE))
        assertEquals(0, carKeyToAndroid(CarKey.RECENTS))
        assertEquals(0, carKeyToAndroid(CarKey.MUTE_TOGGLE))
    }
}
