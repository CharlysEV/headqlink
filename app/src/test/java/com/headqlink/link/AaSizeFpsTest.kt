package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Test

/** Tamaño de Android Auto según la pantalla (T03 a 1024 de ancho) y fps: 30 siempre, 60 con los emuladores. */
class AaSizeFpsTest {
    @Test
    fun smallScreensGetLowerDensity() {
        // C10: los 200 de siempre.
        assertEquals(200, AaSize.dpi(0, 1920, 100))
        // 1024 de ancho (T03): ~1280 dp para AA.
        assertEquals(128, AaSize.dpi(0, 1024, 100))
        assertEquals(160, AaSize.dpi(0, 1280, 100))
        // Más pequeño / más grande sobre el automático.
        assertEquals(109, AaSize.dpi(0, 1024, 85))
        assertEquals(240, AaSize.dpi(0, 1920, 120))
        // El extra aa_dpi por adb manda.
        assertEquals(150, AaSize.dpi(150, 1024, 70))
        // Nunca por debajo del mínimo.
        assertEquals(AaSize.MIN_DPI, AaSize.dpi(0, 800, 70))
    }

    @Test
    fun thirtyExceptInGames() {
        // Por cable el coche pide 60: sin juego, 30; con emulador, 60.
        assertEquals(30, VideoPipeline.contentCap(false, VideoProfile.FLUID_30, 60))
        assertEquals(60, VideoPipeline.contentCap(true, VideoProfile.FLUID_30, 60))
        // Con «Fluidez» a 60, siempre 60.
        assertEquals(60, VideoPipeline.contentCap(false, VideoProfile.FLUID_60, 60))
        // Sesión a 30: nunca más de 30.
        assertEquals(30, VideoPipeline.contentCap(true, VideoProfile.FLUID_30, 30))
    }
}
