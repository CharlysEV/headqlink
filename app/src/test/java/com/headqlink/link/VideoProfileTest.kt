package com.headqlink.link

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Perfil recomendado y perfil Coche (lo que pide el coche en VIDEO_ARGS), viaje del 2026-10-05. */
class VideoProfileTest {
    @Test
    fun aCapablePhoneGetsTheCarProfile() {
        // S25 Ultra: codifica y decodifica 1080p30 por hardware, 12 GB. Antes salía Muy alto (60 fps).
        assertEquals(VideoProfile.CAR, VideoProfile.recommend(true, true, true, 12))
        assertEquals(VideoProfile.CAR, VideoProfile.recommend(true, true, false, 4))
    }

    @Test
    fun weakerPhonesKeepTheLighterProfiles() {
        assertEquals(VideoProfile.MEDIUM, VideoProfile.recommend(false, true, true, 8))
        assertEquals(VideoProfile.MEDIUM, VideoProfile.recommend(true, false, true, 8))
        assertEquals(VideoProfile.MEDIUM, VideoProfile.recommend(true, true, true, 3))
        assertEquals(VideoProfile.BASIC, VideoProfile.recommend(true, true, true, 2))
        assertEquals(VideoProfile.BASIC, VideoProfile.recommend(false, false, false, 8))
    }

    @Test
    fun carProfileFollowsVideoArgs() {
        val p = VideoProfile.of(VideoProfile.CAR)
        assertEquals(VideoProfile.CAR, p.id)
        assertTrue(p.reencode)
        assertTrue(p.followsCar)
        assertFalse("sin optimizaciones de latencia ni relojes al máximo", p.boost)
        assertFalse(p.adaptiveBitrate())
        assertFalse(p.fixedRate)
        // C10: FrameRate 30, BitRate 5080320, a la resolución de su pantalla.
        assertEquals(30, p.fpsFor(30))
        assertEquals(5_080_320, p.startBitrate(5_080_320, 1920))
        assertArrayEquals(intArrayOf(1920, 882), p.videoSize(1920, 882))
        // Sin VIDEO_ARGS: 30 fps y 5 Mbps; valores raros, acotados.
        assertEquals(30, p.fpsFor(0))
        assertEquals(5_000_000, p.startBitrate(0, 1920))
        assertEquals(25, p.fpsFor(25))
        assertEquals(60, p.fpsFor(120))
        assertEquals(10, p.fpsFor(5))
    }

    @Test
    fun fluidity60GivesSixtyFpsAndEightToTwelveMbps() {
        val car = VideoProfile.of(VideoProfile.CAR)
        assertEquals(VideoProfile.FLUID_30, car.fluidity)
        // 30: el mismo objeto (sin cambios).
        assertTrue(car.withFluidity(VideoProfile.FLUID_30) === car)
        val p = car.withFluidity(VideoProfile.FLUID_60)
        assertEquals(VideoProfile.CAR, p.id)
        assertEquals(VideoProfile.FLUID_60, p.fluidity)
        assertTrue(p.followsCar)
        assertTrue(p.reencode)
        assertFalse(p.boost)
        assertFalse(p.fixedRate)
        assertFalse(p.adaptiveBitrate())
        // 60 fps pida lo que pida el coche (la cabecera de vídeo sigue con su FrameRate).
        assertEquals(60, p.fps)
        assertEquals(60, p.fpsFor(30))
        assertEquals(60, p.fpsFor(0))
        // Bitrate: max(coche, 8 Mbit/s) con tope 12.
        assertEquals(8_000_000, p.startBitrate(5_080_320, 1920))
        assertEquals(8_000_000, p.startBitrate(0, 1920))
        assertEquals(10_000_000, p.startBitrate(10_000_000, 1920))
        assertEquals(12_000_000, p.startBitrate(16_000_000, 1920))
        // Barras de «En directo»: 60 fps y 8 Mbit/s.
        assertEquals(8_000_000, maxOf(p.maxBitrate, p.bitrate))
        // Resolución completa, como en Coche.
        assertArrayEquals(intArrayOf(1920, 882), p.videoSize(1920, 882))
        // Vuelta a 30: otra vez lo del coche.
        val back = p.withFluidity(VideoProfile.FLUID_30)
        assertEquals(30, back.fpsFor(30))
        assertEquals(5_080_320, back.startBitrate(5_080_320, 1920))
        // Valores raros: cualquier cosa que no sea 60 es 30.
        assertEquals(VideoProfile.FLUID_30, car.withFluidity(45).fluidity)
        assertEquals(VideoProfile.FLUID_60, car.withFluidity(120).fluidity)
    }

    @Test
    fun fluidityOnlyCountsForTheCarProfile() {
        for (id in listOf(VideoProfile.MAX, VideoProfile.HIGH, VideoProfile.MEDIUM, VideoProfile.BASIC, VideoProfile.LOW)) {
            val p = VideoProfile.of(id)
            assertTrue(id, p.withFluidity(VideoProfile.FLUID_60) === p)
        }
        assertEquals(45, VideoProfile.of(VideoProfile.MEDIUM).withFluidity(VideoProfile.FLUID_60).fpsFor(30))
        assertEquals(20, VideoProfile.of(VideoProfile.LOW).withFluidity(VideoProfile.FLUID_60).fpsFor(30))
    }

    @Test
    fun oldProfilesAreUnchangedAndStillSelectable() {
        assertEquals(VideoProfile.CAR, VideoProfile.ALL[0])
        assertTrue(VideoProfile.ALL.toList().containsAll(listOf("muy_alto", "alto", "medio", "basico", "muy_bajo")))
        val max = VideoProfile.of(VideoProfile.MAX)
        assertEquals(VideoProfile.MAX, max.id)
        assertEquals(60, max.fpsFor(30))
        assertEquals(10_000_000, max.startBitrate(5_080_320, 1920))
        assertTrue(max.adaptiveBitrate())
        assertTrue(max.boost)
        assertEquals(45, VideoProfile.of(VideoProfile.MEDIUM).fpsFor(30))
        assertEquals(20, VideoProfile.of(VideoProfile.LOW).fpsFor(30))
        assertEquals(2_500_000, VideoProfile.of(VideoProfile.LOW).startBitrate(5_080_320, 1280))
        // Básico recodificando (ajuste manual): 5 Mbps a 720p, 8 a resolución completa.
        assertEquals(5_000_000, VideoProfile.of(VideoProfile.BASIC).startBitrate(5_080_320, 1280))
        assertEquals(8_000_000, VideoProfile.of(VideoProfile.BASIC).startBitrate(5_080_320, 1920))
    }

    @Test
    fun unknownIdIsTheCarProfile() {
        assertEquals(VideoProfile.CAR, VideoProfile.of("perfil_que_ya_no_existe").id)
        assertEquals(VideoProfile.CAR, VideoProfile.of(null).id)
    }
}
