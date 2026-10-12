package com.headqlink.link

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Carga de radares: una consulta fallida no deja la zona «cargada» (antes no se reintentaba hasta los 8 km). */
class RoadInfoRetryTest {
    @Test
    fun firstLoadRightAway() {
        assertTrue(RoadInfo.shouldLoadCameras(Double.NaN, Double.NaN, 40.0, -3.0, Long.MAX_VALUE / 2, false))
    }

    @Test
    fun afterAFailureRetriesSoonInTheSamePlace() {
        // Sin carga buena todavía: a los 20 s se vuelve a probar, aunque no se haya movido.
        assertFalse(RoadInfo.shouldLoadCameras(Double.NaN, Double.NaN, 40.0, -3.0, 10_000, true))
        assertTrue(RoadInfo.shouldLoadCameras(Double.NaN, Double.NaN, 40.0, -3.0, 21_000, true))
        // Lejos de la última carga buena y la recarga falló: también a los 20 s.
        assertTrue(RoadInfo.shouldLoadCameras(40.0, -3.0, 40.1, -3.0, 21_000, true))
    }

    @Test
    fun afterAGoodLoadWaitsForDistance() {
        // Cerca de la carga buena: nada, pase el tiempo que pase.
        assertFalse(RoadInfo.shouldLoadCameras(40.0, -3.0, 40.03, -3.0, 10 * 60_000L, false))
        // A más de 8 km (0,1° de latitud son ~11 km) y pasado el minuto: se recarga.
        assertTrue(RoadInfo.shouldLoadCameras(40.0, -3.0, 40.1, -3.0, 61_000, false))
        assertFalse(RoadInfo.shouldLoadCameras(40.0, -3.0, 40.1, -3.0, 30_000, false))
    }
}
