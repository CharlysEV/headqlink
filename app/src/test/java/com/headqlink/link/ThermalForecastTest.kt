package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Anticipación térmica con el margen de Android: baja el vídeo antes del aviso de grave, y vuelve despacio. */
class ThermalForecastTest {
    @Test
    fun stagesByHeadroom() {
        assertEquals(0, ThermalForecast.stageFor(0.70f))
        assertEquals(1, ThermalForecast.stageFor(0.88f))
        assertEquals(2, ThermalForecast.stageFor(0.95f))
        assertEquals(0, ThermalForecast.stageFor(Float.NaN))
    }

    @Test
    fun entersAfterSustainedHeadroomOneStageAtATime() {
        val f = ThermalForecast()
        // Un pico suelto no basta.
        assertFalse(f.update(0.95f, 0))
        assertFalse(f.update(0.95f, 10_000))
        assertEquals(0, f.stage())
        // A los 20 s, una etapa; otros 20 s, la siguiente.
        assertTrue(f.update(0.95f, 20_000))
        assertEquals(1, f.stage())
        assertFalse(f.update(0.95f, 30_000))
        assertTrue(f.update(0.95f, 40_000))
        assertEquals(2, f.stage())
        // Un bajón que no llega a 0,78 no la quita.
        assertFalse(f.update(0.80f, 50_000))
        assertEquals(2, f.stage())
    }

    @Test
    fun leavesSlowlyWhenCool() {
        val f = ThermalForecast()
        f.update(0.90f, 0)
        f.update(0.90f, 20_000)
        assertEquals(1, f.stage())
        // Por debajo de 0,78 un minuto seguido: vuelve.
        assertFalse(f.update(0.70f, 30_000))
        assertFalse(f.update(0.70f, 80_000))
        assertTrue(f.update(0.70f, 90_000))
        assertEquals(0, f.stage())
        // Si vuelve a subir en medio, la cuenta empieza de nuevo.
        val g = ThermalForecast()
        g.update(0.90f, 0)
        g.update(0.90f, 20_000)
        g.update(0.70f, 30_000)
        g.update(0.82f, 60_000)
        assertFalse(g.update(0.70f, 95_000))
        assertEquals(1, g.stage())
    }

    @Test
    fun chargeTipOnlyWhenChargingHotAboveEighty() {
        assertTrue(ChargeHeatTip.shouldTip(true, 85, 41.2, "2026-10-12", ""))
        // Una vez al día.
        assertFalse(ChargeHeatTip.shouldTip(true, 85, 41.2, "2026-10-12", "2026-10-12"))
        // Con «Proteger batería» no pasa del 80 %; sin cargar o fresco, nada.
        assertFalse(ChargeHeatTip.shouldTip(true, 80, 41.2, "2026-10-12", ""))
        assertFalse(ChargeHeatTip.shouldTip(false, 90, 41.2, "2026-10-12", ""))
        assertFalse(ChargeHeatTip.shouldTip(true, 90, 37.0, "2026-10-12", ""))
    }

    @Test
    fun caps() {
        // A 60 fps: 45 y 30; a 30 fps no toca los fps.
        assertEquals(45, ThermalForecast.fpsCap("suave", 1, 60))
        assertEquals(30, ThermalForecast.fpsCap("normal", 2, 60))
        assertEquals(30, ThermalForecast.fpsCap("suave", 2, 30))
        assertEquals(60, ThermalForecast.fpsCap("suave", 0, 60))
        assertEquals(9_000_000, ThermalForecast.bitrateCap("suave", 1, 10_000_000))
        assertEquals(8_000_000, ThermalForecast.bitrateCap("normal", 2, 10_000_000))
        // Protección apagada: nada.
        assertEquals(60, ThermalForecast.fpsCap("apagada", 2, 60))
        assertEquals(10_000_000, ThermalForecast.bitrateCap("apagada", 2, 10_000_000))
    }
}
