package com.headqlink.link

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Salud del encoder al reutilizar el vídeo entre sesiones (qdauto §6): «último frame» sin sesión sigue sano. */
class VideoPipelineHealthTest {
    private val s = 1_000_000_000L
    private val t0 = 100 * s

    @Test
    fun recentOutputIsHealthyWithOrWithoutGate() {
        assertTrue(VideoPipeline.encoderResponsive(t0 + s, t0, t0, false))
        assertTrue(VideoPipeline.encoderResponsive(t0 + s, t0, t0, true))
    }

    @Test
    fun ungatedSourceWithoutOutputIsUnhealthy() {
        // Patrón o app: la fuente dibuja siempre, así que 2 s sin salida es un encoder muerto.
        assertFalse(VideoPipeline.encoderResponsive(t0 + 3 * s, t0, t0 - s, false))
    }

    @Test
    fun gatedEncoderIdleWithoutSessionStaysHealthy() {
        // Último frame dado en t0, su salida y las repeticiones hasta t0+1 s; 30 s después, sin sesión, sigue sano.
        assertTrue(VideoPipeline.encoderResponsive(t0 + 30 * s, t0 + s, t0, true))
        // Ni siquiera se le dio nunca un frame (0): no hay nada que reprocharle.
        assertTrue(VideoPipeline.encoderResponsive(t0 + 30 * s, t0 + s, 0L, true))
    }

    @Test
    fun gatedEncoderThatSwallowsAFrameIsUnhealthy() {
        // Se le dio un frame en t0+5 s (tras su última salida en t0+1 s) y no devolvió nada.
        assertTrue(VideoPipeline.encoderResponsive(t0 + 6 * s, t0 + s, t0 + 5 * s, true)) // aún dentro del plazo
        assertFalse(VideoPipeline.encoderResponsive(t0 + 8 * s, t0 + s, t0 + 5 * s, true))
    }
}
