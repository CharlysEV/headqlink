package com.headqlink.link

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Carga en directo: «ya puedes seguir» una vez, carga lenta (sin confundir la mitad de los 400 V), fin, hora de lista e historial. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class)
class ChargeSessionTest {
    private val min = 60_000L

    private fun charger(net: String, kw: Double, volts: Double) = RoutePlanner.Charger().apply {
        name = "Zunder Épila"
        network = net
        maxKw = kw
        maxVolts = volts
        lat = 41.54
        lon = -1.24
    }

    /** C10 de 81,9 kWh (800 V, ~130 kW de pico) en un cargador. */
    private fun session(c: RoutePlanner.Charger?, soc0: Double = 30.0) = ChargeSession(0, soc0, true, c, 81.9, 130.0, 800.0)

    @Test
    fun readyFiresOnceWhenTheTargetIsReached() {
        val s = session(charger("zunder", 250.0, 920.0))
        s.setTarget(60.0, 12.0, "Zunder Lleida")
        assertEquals(62.0, s.targetPct, 1e-9) // con el margen
        assertTrue(s.observe(0, 30.0, 125.0, true, false, 40.0, 0).isEmpty())
        assertTrue(s.observe(10 * min, 50.0, 128.0, true, false, 30.0, 10 * min).isEmpty())
        assertEquals(listOf(ChargeSession.Event.READY), s.observe(18 * min, 62.0, 100.0, true, false, 20.0, 18 * min))
        assertTrue(s.observe(19 * min, 63.0, 98.0, true, false, 19.0, 19 * min).isEmpty())
        assertTrue(s.readyFired)
    }

    @Test
    fun theReadyTimeFollowsTheRealPaceAlongTheCarCurve() {
        val s = session(charger("zunder", 250.0, 920.0))
        s.setTarget(60.0, 12.0, "Lleida")
        s.observe(0, 30.0, 100.0, true, false, Double.NaN, 0)
        s.observe(5 * min, 40.0, 100.0, true, false, Double.NaN, 5 * min)
        // 2 %/min ≈ 98 kW de verdad frente a los 130 de la curva: tarda 130/98 veces lo que diría el modelo.
        val rate = s.rateKw()
        assertEquals(2 * 81.9 / 100 * 60, rate, 0.01)
        val model = ChargePlanner.minutes(40.0, 62.0, 250.0, 130.0, 81.9)
        assertEquals(model * 130 / rate, s.minutesTo(62.0), 0.01)
        assertEquals(5 * min + s.minutesTo(62.0) * 60_000, s.readyAtMs(), 1.0)
        // Sin objetivo (sin ruta): lo que dice el coche.
        val home = session(null)
        home.observe(0, 30.0, 7.0, true, false, 300.0, 0)
        assertEquals(300.0 * 60_000, home.readyAtMs(), 1.0)
    }

    @Test
    fun slowChargingIsDetectedButNotTheExpectedHalfOfA500VoltCharger() {
        // Supercharger (470 V) con el coche de 800 V: se esperan ~65 kW (la mitad); 60 kW no es lento.
        val sc = session(charger("tesla", 250.0, 470.0))
        assertTrue(sc.lowVolts())
        assertEquals(65.0, sc.expectedKw(40.0), 1e-9)
        for (m in 0..6) assertFalse(sc.observe(m * min, 30.0 + m, 60.0, true, false, Double.NaN, m * min).contains(ChargeSession.Event.SLOW))
        // En uno de 920 V se esperan 130: 45 kW dos veces seguidas (pasados 3 min) sí es lento, y avisa una vez.
        val hpc = session(charger("zunder", 250.0, 920.0))
        val events = mutableListOf<ChargeSession.Event>()
        for (m in 0..8) events += hpc.observe(m * min, 30.0 + m * 0.5, 45.0, true, false, Double.NaN, m * min)
        assertEquals(1, events.count { it == ChargeSession.Event.SLOW })
        assertEquals(45.0, hpc.slowKw, 1e-9)
        assertEquals(130.0, hpc.slowExpectedKw, 1e-9)
    }

    @Test
    fun itEndsWhenUnpluggedOrWithoutData() {
        val s = session(null)
        s.observe(0, 30.0, 50.0, true, false, Double.NaN, 0)
        s.observe(10 * min, 45.0, 50.0, true, false, Double.NaN, 10 * min)
        assertEquals(listOf(ChargeSession.Event.ENDED), s.observe(11 * min, 45.0, 0.0, false, false, Double.NaN, 11 * min))
        assertEquals(45.0, s.endSoc, 1e-9)
        assertEquals(10 * min, s.endMs)
        assertTrue(s.observe(12 * min, 46.0, 0.0, false, false, Double.NaN, 12 * min).isEmpty())
        // Sin datos nuevos en 30 min.
        val q = session(null)
        q.observe(0, 30.0, 50.0, true, false, Double.NaN, 0)
        assertTrue(q.observe(0, 30.0, 50.0, true, false, Double.NaN, 29 * min).isEmpty())
        assertEquals(listOf(ChargeSession.Event.ENDED), q.observe(0, 30.0, 50.0, true, false, Double.NaN, 31 * min))
    }

    @Test
    fun theSessionSurvivesTheHistory() {
        val s = session(charger("zunder", 250.0, 920.0))
        s.setTarget(60.0, 12.0, "Lleida")
        s.observe(0, 30.0, 120.0, true, false, Double.NaN, 0)
        s.observe(5 * min, 40.0, 121.0, true, false, Double.NaN, 5 * min)
        s.observe(6 * min, 41.0, Double.NaN, true, false, Double.NaN, 6 * min)
        s.observe(7 * min, 41.0, 0.0, false, false, Double.NaN, 7 * min)
        val back = ChargeSession.fromJson(JSONObject(s.toJson().toString()))
        assertEquals(3, back.samples.size)
        assertTrue(back.samples[2].kw.isNaN())
        assertEquals("Zunder Épila", back.charger!!.name)
        assertEquals(920.0, back.charger!!.maxVolts, 1e-9)
        assertEquals(62.0, back.targetPct, 1e-9)
        assertEquals(41.0, back.endSoc, 1e-9)
        // El historial guarda las últimas MAX y aguanta un fichero roto.
        var json = ""
        repeat(205) { json = ChargeLog.append(json, s, 200) }
        assertEquals(200, ChargeLog.read(json).size)
        assertTrue(ChargeLog.read("roto").isEmpty())
    }
}
