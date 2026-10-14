package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Test

/** El T03 visto desde arriba: sus colores oficiales, el siguiente al tocar y las sombras de la carrocería. */
class CarArtTest {
    @Test
    fun theT03PaintIsOneOfItsThreeOrTheWhite() {
        assertEquals(CarArt.T03_PAINTS[0], CarArt.t03Paint(""))
        assertEquals(CarArt.T03_PAINTS[0], CarArt.t03Paint(null))
        assertEquals(CarArt.T03_PAINTS[0], CarArt.t03Paint("#08261c")) // el verde del C10: no es del T03
        assertEquals(CarArt.T03_PAINTS[1], CarArt.t03Paint("#2e76be"))
        assertEquals(CarArt.T03_PAINTS[1], CarArt.t03Paint("#2E76BE"))
        assertEquals(CarArt.T03_PAINTS[2], CarArt.t03Paint("#4c5056"))
    }

    @Test
    fun tappingCyclesThroughTheThreePaints() {
        assertEquals(CarArt.T03_PAINTS[1], CarArt.t03NextPaint(""))
        assertEquals(CarArt.T03_PAINTS[2], CarArt.t03NextPaint("#2e76be"))
        assertEquals(CarArt.T03_PAINTS[0], CarArt.t03NextPaint("#4c5056"))
        assertEquals("#2e76be", CarArt.hex(CarArt.T03_PAINTS[1]))
        // Ida y vuelta por el texto guardado (Config.carColor pide #rrggbb en minúsculas).
        assertEquals(CarArt.T03_PAINTS[2], CarArt.t03Paint(CarArt.hex(CarArt.T03_PAINTS[2])))
    }

    @Test
    fun shadeMultipliesEachChannelAndClamps() {
        assertEquals(0xFF000000.toInt(), CarArt.shade(0xFF80C0FF.toInt(), 0f))
        assertEquals(0xFF406080.toInt(), CarArt.shade(0xFF80C0FF.toInt(), 0.5f))
        assertEquals(0xFFFFFFFF.toInt(), CarArt.shade(0xFFC0C0C0.toInt(), 2f))
    }
}
