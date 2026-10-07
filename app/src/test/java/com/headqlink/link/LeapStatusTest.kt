package com.headqlink.link

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * Estado del coche (mergeSignalToNamed de LMB10): una respuesta de ejemplo construida con los nombres y las señales del
 * código Dart, con las conversiones de tipos, las reglas de carga, presiones, puertas y la hora del dato.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class)
class LeapStatusTest {
    /** «data» de /status/get/c10 de ejemplo: casi todo en «signal» (ids numéricos) y algo con nombre arriba. */
    private val sample = """
        {
          "vin": "TESTVIN0000000001",
          "liveRemainingRange": 301,
          "collectTime": 1791297560000,
          "signal": {
            "sts": 1791297500000,
            "2": -2.987649, "3": 43.123456,
            "1204": 72, "100003": "72.4", "2188": 999,
            "1149": 1, "47": "1", "1197": 0, "3736": false, "1200": "95",
            "1177": 396.5, "1178": 19.8,
            "1182": "24", "1186": 1, "1318": 12480, "1319": 0.0, "1349": 25.5,
            "1298": 1, "1277": 0, "1278": "1", "1279": 0, "1280": 0, "1281": true, "1258": "0",
            "6048": 120, "12054": 1,
            "2667": 240, "2653": "241", "2646": 238, "2660": 205,
            "2641": 0, "2648": 1, "2655": 0, "2662": 3
          }
        }
    """.trimIndent()

    @Test
    fun signalsAreMergedAndNamedFieldsWin() {
        val s = LeapStatus.parse(JSONObject(sample))
        assertEquals(72.0, s.soc, 0.0)
        assertEquals(72.4, s.preciseSoc, 1e-9)
        assertEquals(72.4, s.socBest(), 1e-9)
        // liveRemainingRange viene arriba (301) y también en la señal 2188 (999): manda el de arriba, como en LMB10.
        assertEquals(301.0, s.rangeKm, 0.0)
        assertEquals(12480.0, s.odometerKm, 0.0)
        assertEquals(25.5, s.interiorTempC, 1e-9)
        assertEquals(24.0, s.minBatteryTempC, 1e-9)
        assertEquals(95.0, s.chargeRemainMin, 0.0)
        assertEquals(120.0, s.speedLimitKmh, 0.0)
        assertEquals(true, s.speedLimitActive)
    }

    @Test
    fun chargingNeedsStatePlugAndNotCompleted() {
        val s = LeapStatus.parse(JSONObject(sample))
        assertTrue(s.pluggedIn())
        assertTrue(s.charging())
        assertFalse(s.dcPlugged())
        // Carga terminada: enchufado pero ya no «cargando».
        val done = JSONObject(sample).apply { getJSONObject("signal").put("3736", 1) }
        assertFalse(LeapStatus.parse(done).charging())
        // chargeState activo sin enchufar (regeneración): no es una carga.
        val regen = JSONObject(sample).apply { getJSONObject("signal").put("47", 0) }
        assertFalse(LeapStatus.parse(regen).pluggedIn())
        assertFalse(LeapStatus.parse(regen).charging())
        // Potencia = tensión × corriente / 1000 (19,8 A × 396,5 V = 7,85 kW).
        assertEquals(7.851, s.powerKw(), 1e-3)
    }

    @Test
    fun doorsLockBootAndPower() {
        val s = LeapStatus.parse(JSONObject(sample))
        assertEquals(true, s.locked)
        assertEquals(false, s.doorOpen(LeapStatus.FL))
        assertEquals(true, s.doorOpen(LeapStatus.FR))
        assertEquals(true, s.anyDoorOpen())
        assertEquals(true, s.bootOpen)
        assertEquals(false, s.powerOn)
        // Sin señales de puertas: «no se sabe», no «cerradas».
        val none = LeapStatus.parse(JSONObject("""{"signal":{"1204":50}}"""))
        assertNull(none.anyDoorOpen())
        assertNull(none.locked)
        assertTrue(none.powerKw().isNaN())
        assertTrue(none.rangeKm.isNaN())
    }

    @Test
    fun tyresInBarWithLowAndTpmsWarnings() {
        val s = LeapStatus.parse(JSONObject(sample))
        assertEquals(2.40, s.tyreBar(LeapStatus.FL), 1e-9)
        assertEquals(2.41, s.tyreBar(LeapStatus.FR), 1e-9)
        // 2,05 bar: por debajo de 2,1 y 0,35 por debajo de la media de las otras.
        assertTrue(s.tyreLow(LeapStatus.RR))
        assertFalse(s.tyreLow(LeapStatus.FL))
        // Aviso del propio coche (estado > 1); 0 y 1 son normales.
        assertTrue(s.tyreStateAlert(LeapStatus.RR))
        assertFalse(s.tyreStateAlert(LeapStatus.FR))
        assertTrue(s.tyreWarning(LeapStatus.RR))
        assertFalse(s.tyreWarning(LeapStatus.RL))
    }

    @Test
    fun lowTyreRule() {
        val even = doubleArrayOf(2.5, 2.5, 2.48, 2.52)
        for (i in 0..3) assertFalse(LeapStatus.tyreLow(even, i))
        // 0,3 bar por debajo de la media de las otras: baja (aunque esté por encima de 2,1).
        assertTrue(LeapStatus.tyreLow(doubleArrayOf(2.5, 2.5, 2.5, 2.2), 3))
        assertFalse(LeapStatus.tyreLow(doubleArrayOf(2.5, 2.5, 2.5, 2.25), 3))
        // Por debajo del mínimo, aunque las cuatro estén igual de bajas.
        assertTrue(LeapStatus.tyreLow(doubleArrayOf(2.0, 2.0, 2.0, 2.0), 0))
        // Sin dato no hay aviso, y las que faltan no cuentan para la media.
        assertFalse(LeapStatus.tyreLow(doubleArrayOf(Double.NaN, 2.5, 2.5, 2.5), 0))
        assertTrue(LeapStatus.tyreLow(doubleArrayOf(Double.NaN, 2.5, Double.NaN, 2.15), 3))
    }

    @Test
    fun conversionsLikeDart() {
        assertEquals(72, LeapStatus.asIntObj(72.9))
        assertEquals(72, LeapStatus.asIntObj("72"))
        assertEquals(72, LeapStatus.asIntObj(" +72 "))
        assertNull(LeapStatus.asIntObj("72.5"))
        assertNull(LeapStatus.asIntObj(null))
        assertEquals(72.5, LeapStatus.asDouble("72.5"), 0.0)
        assertTrue(LeapStatus.asDouble("x").isNaN())
        assertEquals(true, LeapStatus.asBool("TRUE"))
        assertEquals(true, LeapStatus.asBool(2))
        assertEquals(false, LeapStatus.asBool("yes"))
        assertNull(LeapStatus.asBool(null))
        assertNull(LeapStatus.asBoolStrict("yes"))
        assertEquals(false, LeapStatus.asBoolStrict("0"))
    }

    @Test
    fun dataTimeFromCollectTimeOrSignal() {
        // collectTime (ms) manda sobre signal.sts.
        assertEquals(1791297560000L, LeapStatus.parse(JSONObject(sample)).carTimeMs)
        // Sin collectTime: signal.sts; sin sts: la señal 1.
        assertEquals(1791297500000L, LeapStatus.parse(JSONObject("""{"signal":{"sts":1791297500000}}""")).carTimeMs)
        assertEquals(1791297500000L, LeapStatus.parse(JSONObject("""{"signal":{"1":"1791297500000"}}""")).carTimeMs)
        // En segundos.
        assertEquals(1791297500000L, LeapStatus.parse(JSONObject("""{"reportTime":1791297500}""")).carTimeMs)
        // Nada: 0.
        assertEquals(0L, LeapStatus.parse(JSONObject("""{"signal":{"1204":10}}""")).carTimeMs)
        assertEquals(0L, LeapStatus.toEpochMs("ayer"))
    }

    @Test
    fun logLineHasNoVinNorPosition() {
        val line = LeapStatus.parse(JSONObject(sample)).logLine()
        assertTrue(line, line.startsWith("SoC 72.4 %, autonomía 301 km, cargando CA"))
        assertFalse(line.contains("TESTVIN"))
        assertFalse(line.contains("43.12"))
        assertFalse(line.contains("2.98"))
    }

    @Test
    fun reevFuelSignals() {
        val bev = LeapStatus.parse(JSONObject(sample))
        assertFalse(bev.reev())
        assertTrue(bev.fuelLiters.isNaN())
        val reev = LeapStatus.parse(JSONObject("""{"signal":{"1204":24,"100003":"24.6","3235":"62.4","3263":29640,
            "3259":"463","3261":530,"2188":31}}"""))
        assertTrue(reev.reev())
        assertEquals(62.4, reev.fuelPct, 1e-9)
        assertEquals(29.64, reev.fuelLiters, 1e-9) // la señal 3263 va en mililitros
        assertEquals(463.0, reev.fuelRangeKm, 1e-9)
        assertEquals(530.0, reev.combinedRangeKm, 1e-9)
        val line = reev.logLine()
        assertTrue(line, line.contains("gasolina 62.4 % (29.64 L), 463 km con gasolina, 530 km en total"))
    }

    @Test
    fun onlyReadsTheSignalsItShows() {
        // Ni latitud ni longitud (señales 2/3 y 3724/3725/2190/2191) están en la tabla: no se leen.
        for (id in listOf("2", "3", "3724", "3725", "2190", "2191")) assertFalse(LeapStatus.SIGNALS.containsKey(id))
        val m = LeapStatus.merge(JSONObject(sample))
        assertFalse(m.containsKey("latitude"))
        assertFalse(m.containsKey("longitude"))
    }
}
