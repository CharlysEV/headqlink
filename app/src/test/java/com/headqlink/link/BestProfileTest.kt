package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Perfil Máximo (cable): fps del coche, bitrate, H.264 High, sin tope de 30, y el SPS de baja latencia. */
class BestProfileTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun bestFollowsTheCarFpsWithMoreBitrate() {
        val p = VideoProfile.of(VideoProfile.BEST)
        assertTrue(p.best())
        // Cable: 60; Wi-Fi: 30; sin VIDEO_ARGS: 60.
        assertEquals(60, p.fpsFor(60))
        assertEquals(30, p.fpsFor(30))
        assertEquals(60, p.fpsFor(0))
        assertEquals(VideoProfile.BEST_CABLE_BPS, p.startBitrate(5_080_320, 60, 1920))
        assertEquals(VideoProfile.BEST_WIFI_BPS, p.startBitrate(5_080_320, 30, 1920))
        assertTrue(p.adaptiveBitrate())
        assertEquals("high", p.h264())
        // Siempre a resolución completa (la protección térmica no la baja a 720p).
        assertFalse(ThermalResolution.applies(p))
        assertEquals(1920, p.videoSize(1920, 882)[0])
    }

    @Test
    fun onlyCarAt30CapsTheContent() {
        assertTrue(VideoProfile.of(VideoProfile.CAR).capsContentTo30())
        assertFalse(VideoProfile.of(VideoProfile.CAR).withFluidity(VideoProfile.FLUID_60).capsContentTo30())
        assertFalse(VideoProfile.of(VideoProfile.BEST).capsContentTo30())
        assertFalse(VideoProfile.of(VideoProfile.MAX).capsContentTo30())
        assertNull(VideoProfile.of(VideoProfile.CAR).h264())
    }

    @Test
    fun sixtyOnlyWhileTouching() {
        // Máximo por cable: sin toques, 30; tocando o con juego, 60. Los demás perfiles, sin cambios.
        assertEquals(30, VideoPipeline.interactiveCap(true, false, false, 60))
        assertEquals(60, VideoPipeline.interactiveCap(true, false, true, 60))
        assertEquals(60, VideoPipeline.interactiveCap(true, true, false, 60))
        assertEquals(60, VideoPipeline.interactiveCap(false, false, false, 60))
        // Por Wi-Fi (30) no cambia nada; el tope térmico, aparte.
        assertEquals(30, VideoPipeline.interactiveCap(true, false, true, 30))
    }

    @Test
    fun lowDelaySpsOnlyWhenNeeded() {
        // El SPS+PPS del c2.qti del S25 (Baseline 4.2, 1920x882): ya dice reorder 0 y búfer 1, no se toca.
        val phone = hex("000000016742802ada01e01c7e226a0202020da1426a0000000168ce06f2")
        assertNull(H264SpsCrop.lowDelayIfNeeded(phone, 1920, 882))
        assertEquals(0, H264SpsCrop.lastReorder)
        assertEquals(1, H264SpsCrop.lastBuffering)
        // El mismo sin VUI: se le añade, y el resultado ya no necesita cambios.
        val bare = hex("000000016742802ada01e01c7e210000000168ce06f2")
        val fixed = H264SpsCrop.lowDelayIfNeeded(bare, 1920, 882)
        assertNotNull(fixed)
        assertTrue(fixed!!.size > bare.size)
        assertNull(H264SpsCrop.lowDelayIfNeeded(fixed, 1920, 882))
        assertEquals(0, H264SpsCrop.lastReorder)
        // Un High que declara reordenación (B-frames): no se toca (forzar 0 los mostraría desordenados).
        val reorders = hex("000000016764002aaccac078071f88806d044229c00000000168ce06f2")
        assertNull(H264SpsCrop.lowDelayIfNeeded(reorders, 1920, 882))
        assertEquals(1, H264SpsCrop.lastReorder)
        // El PPS sigue al final, intacto.
        val tail = fixed.copyOfRange(fixed.size - 8, fixed.size)
        assertTrue(tail.contentEquals(hex("0000000168ce06f2")))
    }
}
