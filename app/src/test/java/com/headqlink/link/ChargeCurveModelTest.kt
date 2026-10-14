package com.headqlink.link

import android.app.Application
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
 * Curva de carga aprendida: se recupera una curva conocida, con pocos datos se queda la de fábrica, lo que limita el
 * cargador no cuenta, la regla de los 400–500 V con datos a favor y en contra, los de 150 kW o más y la mezcla en el plan.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class)
class ChargeCurveModelTest {
    private val c10Peak = 84.0
    private val proMaxPeak = 130.0

    private fun charger(kw: Double, volts: Double = 0.0, net: String = ChargerFilter.OTHER) = RoutePlanner.Charger().apply {
        name = "Cargador de prueba"
        network = net
        maxKw = kw
        maxVolts = volts
        lat = 40.0
        lon = -3.0
    }

    /** Una carga rápida del 10 al 90 % con una lectura cada 0,5 % a la potencia que diga power (con un poco de ruido). */
    private fun session(start: Long, c: RoutePlanner.Charger?, peak: Double, volts: Double, cap: Double,
                        from: Double = 10.0, to: Double = 90.0, power: (Double) -> Double): ChargeSession {
        val s = ChargeSession(start, from, true, c, cap, peak, volts)
        var soc = from
        var t = start
        var i = 0
        while (soc <= to) {
            val noise = if (i % 2 == 0) 0.4 else -0.4
            s.samples.add(ChargeSession.Sample(t, soc, power(soc) + noise))
            soc += 0.5
            t += 30_000
            i++
        }
        s.endMs = t
        return s
    }

    /** El C10 «de verdad» de las pruebas: un 10 % por debajo de la de fábrica en todo el recorrido. */
    private fun truth(pct: Double) = ChargePlanner.carPowerKw(pct, c10Peak) * 0.9

    @Test
    fun aKnownCurveIsRecovered() {
        val list = (0 until 5).map { session(it * 86_400_000L, charger(150.0), c10Peak, 400.0, 69.9) { p -> truth(p) } }
        val curve = ChargeCurveModel.learn(list, c10Peak)
        assertTrue(curve.learned())
        assertEquals(5, curve.totalSessions)
        // Tramos llanos y de bajada: lo aprendido es la curva de verdad (el percentil alto, con el ruido).
        for (pct in listOf(22.5, 32.5, 42.5, 62.5, 72.5)) {
            val b = ChargeCurveModel.binOf(pct)
            assertEquals("tramo $pct", truth(pct), curve.learnedKw(b), 1.5)
        }
        // Sin lecturas por debajo del 10 % ni por encima del 90 %: ahí, la de fábrica.
        assertTrue(curve.learnedKw(0).isNaN())
        assertTrue(curve.learnedKw(19).isNaN())
        assertEquals(ChargePlanner.carPowerKw(2.0, c10Peak), curve.powerKw(2.0, 1.0), 1e-9)
        // Mezcla: con 5 cargas pesa 5/8 lo aprendido.
        val w = 5 / 8.0
        assertEquals(w, curve.weight(ChargeCurveModel.binOf(30.0)), 1e-9)
        assertEquals(c10Peak * (1 + w * (0.9 - 1)), curve.powerKw(30.0, 1.0), 0.5)
    }

    @Test
    fun withFewChargesTheFactoryCurveStays() {
        val list = (0 until 2).map { session(it * 86_400_000L, charger(150.0), c10Peak, 400.0, 69.9) { p -> truth(p) } }
        val curve = ChargeCurveModel.learn(list, c10Peak)
        assertFalse(curve.learned())
        for (pct in listOf(10.0, 45.0, 70.0, 95.0)) {
            assertEquals(ChargePlanner.carPowerKw(pct, c10Peak), curve.powerKw(pct, 1.0), 1e-9)
        }
        // Las de otro coche (otro pico) o sin cargador conocido tampoco cuentan.
        val others = (0 until 5).map { session(it * 86_400_000L, charger(150.0), 48.0, 400.0, 37.3) { p -> truth(p) } } +
                (0 until 5).map { session(it * 86_400_000L, null, c10Peak, 400.0, 69.9) { p -> truth(p) } }
        assertFalse(ChargeCurveModel.learn(others, c10Peak).learned())
        val summary = ChargeCurveModel.analyze(list + others, c10Peak)
        assertEquals(7, summary.dcSessions)
        assertNull(summary.fastTotal())
    }

    @Test
    fun samplesLimitedByTheChargerAreIgnored() {
        // En uno de 50 kW, hasta el ~80 % manda el cargador (47 kW): eso no dice nada del coche.
        val list = (0 until 5).map { session(it * 86_400_000L, charger(50.0), c10Peak, 400.0, 69.9) { p -> minOf(47.0, truth(p)) } }
        val curve = ChargeCurveModel.learn(list, c10Peak)
        assertTrue(curve.learnedKw(ChargeCurveModel.binOf(30.0)).isNaN())
        assertTrue(curve.learnedKw(ChargeCurveModel.binOf(65.0)).isNaN())
        // Arriba ya manda el coche: se aprende.
        val b = ChargeCurveModel.binOf(86.0)
        assertEquals(truth(ChargeCurveModel.center(b)), curve.learnedKw(b), 1.5)
        assertEquals(ChargePlanner.carPowerKw(30.0, c10Peak), curve.powerKw(30.0, 1.0), 1e-9)
    }

    private fun tesla() = charger(250.0, 500.0, "tesla")

    @Test
    fun theLowVoltsRuleHoldsWhenTheDataSaysHalf() {
        val list = (0 until 4).map {
            session(it * 86_400_000L, tesla(), proMaxPeak, 800.0, 81.9) { p -> ChargePlanner.carPowerKw(p, proMaxPeak) * 0.5 }
        }
        val lv = ChargeCurveModel.lowVolts(list, proMaxPeak, null)
        assertEquals(4, lv.sessions)
        assertEquals(0.5, lv.factor, 0.02)
        assertEquals(1.0, lv.ratio(), 0.04)
        assertEquals(ChargePlanner.LOW_VOLTS_FACTOR, lv.planFactor(), 0.0)
        // Esas cargas no forman la curva (cargan a otra parte del pico).
        assertFalse(ChargeCurveModel.learn(list, proMaxPeak).learned())
    }

    @Test
    fun theLowVoltsFactorIsLearnedWhenTheDataContradictsIt() {
        val list = (0 until 4).map {
            session(it * 86_400_000L, tesla(), proMaxPeak, 800.0, 81.9) { p -> ChargePlanner.carPowerKw(p, proMaxPeak) * 0.75 }
        }
        val lv = ChargeCurveModel.lowVolts(list, proMaxPeak, null)
        assertEquals(0.75, lv.factor, 0.02)
        assertEquals(0.75, lv.planFactor(), 0.02)
        // Con solo dos cargas no se cambia la regla.
        assertEquals(ChargePlanner.LOW_VOLTS_FACTOR, ChargeCurveModel.lowVolts(list.take(2), proMaxPeak, null).planFactor(), 0.0)
        // Ni si se queda cerca de la mitad.
        val near = (0 until 4).map {
            session(it * 86_400_000L, tesla(), proMaxPeak, 800.0, 81.9) { p -> ChargePlanner.carPowerKw(p, proMaxPeak) * 0.55 }
        }
        assertEquals(ChargePlanner.LOW_VOLTS_FACTOR, ChargeCurveModel.lowVolts(near, proMaxPeak, null).planFactor(), 0.0)
    }

    @Test
    fun fastChargersAreComparedWithWhatTheyCouldGive() {
        val ionity = (0 until 3).map {
            session(it * 86_400_000L, charger(350.0, 920.0, "ionity"), proMaxPeak, 800.0, 81.9) { p -> ChargePlanner.carPowerKw(p, proMaxPeak) * 0.9 }
        }
        val slow = (3 until 5).map {
            session(it * 86_400_000L, charger(150.0, 920.0, "zunder"), proMaxPeak, 800.0, 81.9) { p -> minOf(78.0, ChargePlanner.carPowerKw(p, proMaxPeak)) }
        }
        val fast = ChargeCurveModel.fastChargers(ionity + slow, proMaxPeak, null, ChargePlanner.LOW_VOLTS_FACTOR)
        val total = fast[0]
        assertNull(total.network)
        assertEquals(5, total.sessions)
        assertEquals((3 * (117 + 0.4) + 2 * 78.4) / 5, total.peakKw, 0.5)
        // Por red, el que menos rinde primero: 78 de 130 que admite el coche (no de 150 anunciados).
        assertEquals(3, fast.size)
        assertEquals("zunder", fast[1].network)
        assertEquals(78.4 / 130, fast[1].ratio, 0.01)
        assertEquals("ionity", fast[2].network)
        assertEquals(0.9, fast[2].ratio, 0.01)
        // En el resumen, el total sale con 3 cargas o más; con menos, no.
        assertEquals(5, ChargeCurveModel.analyze(ionity + slow, proMaxPeak).fastTotal()!!.sessions)
        assertNull(ChargeCurveModel.analyze(slow, proMaxPeak).fastTotal())
    }

    @Test
    fun thePlannerBlendsTheLearnedCurve() {
        val list = (0 until 5).map { session(it * 86_400_000L, charger(150.0), c10Peak, 400.0, 69.9) { p -> truth(p) } }
        val curve = ChargeCurveModel.learn(list, c10Peak)
        val factory = ChargePlanner.Settings().apply { capacityKwh = 69.9 }
        val learned = ChargePlanner.Settings().apply {
            capacityKwh = 69.9
            this.curve = curve
        }
        val a = ChargePlanner.chargeMinutes(20.0, 45.0, 150.0, factory)
        val b = ChargePlanner.chargeMinutes(20.0, 45.0, 150.0, learned)
        // Con 5 cargas a un 90 %: la mezcla da un 93,75 % de la potencia de fábrica, algo más de tiempo.
        assertEquals(a / (1 + 5 / 8.0 * (0.9 - 1)), b, a * 0.01)
        // Sin curva aprendida, como siempre.
        assertEquals(ChargePlanner.minutes(20.0, 45.0, 150.0, c10Peak, 69.9), a, 1e-9)
        // Con un cargador lento manda él, aprendida o no.
        assertEquals(ChargePlanner.chargeMinutes(20.0, 45.0, 22.0, factory), ChargePlanner.chargeMinutes(20.0, 45.0, 22.0, learned), 1e-9)
    }

    @Test
    fun thePlannerUsesTheLearnedLowVoltsFactor() {
        val s = ChargePlanner.Settings().apply {
            capacityKwh = 81.9
            carPeakKw = proMaxPeak
            carVolts = 800.0
        }
        val half = ChargePlanner.chargeMinutes(20.0, 60.0, tesla(), s)
        assertEquals(proMaxPeak * 0.5, ChargePlanner.effKw(tesla(), s), 1e-9)
        s.lowVoltsFactor = 0.75
        val learned = ChargePlanner.chargeMinutes(20.0, 60.0, tesla(), s)
        assertEquals(half * 0.5 / 0.75, learned, half * 0.01)
        assertEquals(proMaxPeak * 0.75, ChargePlanner.effKw(tesla(), s), 1e-9)
        // La forma de la curva sigue siendo la del C10 (no la de un coche pequeño) aunque el pico quede por debajo de 60 kW.
        s.lowVoltsFactor = 0.4
        assertEquals(proMaxPeak * 0.4, ChargePlanner.carPowerKw(40.0, proMaxPeak, 0.4, null), 1e-9)
    }

    @Test
    fun percentileInterpolates() {
        assertEquals(8.2, ChargeCurveModel.percentile(listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0), 0.8), 1e-9)
        assertTrue(ChargeCurveModel.percentile(emptyList(), 0.8).isNaN())
    }
}
