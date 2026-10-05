package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tamaño de los IDR del encoder propio: el C10 se cuelga con mensajes de más de ~512 KiB (2026-10-05: IDR de 525, 538 y
 * 593 KB) y el núcleo descarta los de más de 480 KiB. El controlador apunta a 300 KB por IDR.
 */
class IdrSizeControllerTest {
    private val kb = 1024
    private val cap = SessionConfigs.MAX_VIDEO_MESSAGE_BYTES - 48

    @Test
    fun capAndTargetMatchTheCar() {
        assertEquals(480 * kb, SessionConfigs.MAX_VIDEO_MESSAGE_BYTES)
        assertEquals(480 * kb - 48, VideoPipeline.PAYLOAD_CAP)
        assertEquals(300 * kb, IdrSizeController.TARGET_BYTES)
        assertTrue(IdrSizeController.TARGET_BYTES < VideoPipeline.PAYLOAD_CAP)
    }

    @Test
    fun qpStepFollowsSixPerHalving() {
        val c = IdrSizeController(true, cap)
        assertEquals(1, c.stepFor(301 * kb))
        assertEquals(6, c.stepFor(600 * kb))
        assertEquals(6, c.stepFor(538 * kb)) // 6·log2(1,79) = 5,05 → 6
        assertEquals(IdrSizeController.MAX_STEP, c.stepFor(10_000 * kb))
    }

    @Test
    fun anIdrOverTheTargetRaisesQpIMinAndOneUnderItKeepsIt() {
        val c = IdrSizeController(true, cap)
        assertEquals(IdrSizeController.QP_START, c.qpMin())
        val ok = c.onIdr(250 * kb, -1, 0.0)
        assertFalse(ok.qpChanged())
        assertEquals("IDR 250 KB · QP-I mín 24", ok.line())

        val big = c.onIdr(350 * kb, -1, 0.0)
        assertTrue(big.overTarget)
        assertFalse(big.overCap)
        assertEquals(24, big.qpBefore)
        assertEquals(26, big.qpAfter) // 6·log2(350/300) = 1,33 → 2
        assertTrue(big.line(), big.line().startsWith("IDR 350 KB > objetivo 300 KB · QP-I mín 24 → 26"))
        assertEquals("un solo IDR por encima, sin bajar el bitrate", 0.0, c.takeDip(), 0.0)
    }

    @Test
    fun anIdrTheCoreDropsRaisesAtLeastFourAndTheReplacementDipsTheBitrate() {
        val c = IdrSizeController(true, cap)
        // El IDR de 538 390 B del coche (mensaje entero): payload 538 342 B.
        val s = c.onIdr(538_342, -1, 0.0)
        assertTrue(s.overCap)
        assertEquals(24 + 5, s.qpAfter) // 6·log2(538 342 / 307 200) = 4,86 → 5
        assertEquals(IdrSizeController.DIP_START, s.nextDip, 0.0)
        assertTrue(s.line(), s.line().startsWith("IDR 526 KB > tope 480 KB: lo descarta el núcleo y se pide otro · QP-I mín 24 → 29"))
        assertTrue(s.line(), s.line().contains("próximo IDR pedido con el bitrate al 40 %"))
        // El IDR que lo sustituye va con el bitrate al 40 %; el siguiente ya no.
        assertEquals(0.4, c.takeDip(), 0.0)
        assertEquals(0.0, c.takeDip(), 0.0)

        // Por encima del tope por poco (con un objetivo cerca del tope): igualmente al menos +4.
        val d = IdrSizeController(true, cap, 450 * kb)
        assertEquals(24 + IdrSizeController.OVERSIZE_MIN_STEP, d.onIdr(cap + 1, -1, 0.0).qpAfter)
    }

    @Test
    fun theReportedQpIsTheBaseForTheRaiseAndTheCeilingHolds() {
        val c = IdrSizeController(true, cap)
        // El encoder dice QP 30 (> mínimo 24): se sube desde 30.
        assertEquals(30 + 6, c.onIdr(600 * kb, 30, 0.0).qpAfter)
        val top = c.onIdr(5_000 * kb, 36, 0.0)
        assertEquals(IdrSizeController.QP_CEIL, top.qpAfter)
        // Con el mínimo en el techo, el siguiente IDR pedido baja el bitrate.
        assertTrue(c.takeDip() > 0)
    }

    @Test
    fun wellBelowTheTargetRelaxesSlowlyDownToTheFloor() {
        val c = IdrSizeController(true, cap)
        c.onIdr(600 * kb, -1, 0.0) // 24 → 30
        assertEquals(30, c.qpMin())
        c.onIdr(100 * kb, -1, 0.0)
        c.onIdr(100 * kb, -1, 0.0)
        assertEquals("hasta RELAX_AFTER no baja", 30, c.qpMin())
        val third = c.onIdr(100 * kb, -1, 0.0)
        assertEquals(30, third.qpBefore)
        assertEquals(29, third.qpAfter)
        assertTrue(third.line(), third.line().contains("QP-I mín 30 → 29"))
        // Uno entre medias (ni muy por debajo ni por encima) reinicia la cuenta.
        c.onIdr(100 * kb, -1, 0.0)
        c.onIdr(200 * kb, -1, 0.0)
        c.onIdr(100 * kb, -1, 0.0)
        c.onIdr(100 * kb, -1, 0.0)
        assertEquals(29, c.qpMin())
        repeat(200) { c.onIdr(10 * kb, -1, 0.0) }
        assertEquals(IdrSizeController.QP_FLOOR, c.qpMin())
    }

    @Test
    fun anEncoderThatIgnoresTheQpFallsBackToTheBitrateDip() {
        // El QP medio informado queda por debajo del mínimo: no hace caso.
        val c = IdrSizeController(true, cap)
        val s = c.onIdr(200 * kb, 15, 0.0)
        assertTrue(s.note, s.note!!.contains("no respeta el QP-I mínimo"))
        assertTrue(c.dipAlways())
        assertEquals(0.4, c.takeDip(), 0.0)
        assertEquals("en cada IDR pedido", 0.4, c.takeDip(), 0.0)

        // Sin QP informado: tres IDR seguidos por encima del objetivo sin bajar de tamaño.
        val d = IdrSizeController(true, cap)
        d.onIdr(400 * kb, -1, 0.0)
        assertFalse(d.dipAlways())
        d.onIdr(410 * kb, -1, 0.0)
        assertTrue("dos seguidos: el siguiente pedido baja el bitrate", d.takeDip() > 0)
        assertFalse(d.dipAlways())
        val third = d.onIdr(405 * kb, -1, 0.0)
        assertTrue(third.note, third.note!!.contains("el QP-I no basta"))
        assertTrue(d.dipAlways())
    }

    @Test
    fun withoutQpKeysEveryRequestedIdrDipsAndTheDipAdapts() {
        val c = IdrSizeController(false, cap)
        assertTrue(c.dipAlways())
        assertTrue(c.describe(), c.describe().contains("sin claves de QP"))
        assertEquals(0.4, c.takeDip(), 0.0)
        val s = c.onIdr(350 * kb, -1, 0.4)
        assertFalse(s.qpChanged())
        assertTrue(s.line(), s.line().contains("con el bitrate al 40 %") && s.line().contains("sin QP-I"))
        // Con la bajada y aun así por encima: bajada más fuerte, nunca por debajo de DIP_MIN.
        assertEquals(0.3, c.takeDip(), 1e-9)
        repeat(5) { c.onIdr(350 * kb, -1, c.takeDip()) }
        assertEquals(IdrSizeController.DIP_MIN, c.takeDip(), 1e-9)
        // Muy por debajo con la bajada: se suaviza poco a poco hasta DIP_START.
        repeat(60) { c.onIdr(50 * kb, -1, c.takeDip()) }
        assertEquals(IdrSizeController.DIP_START, c.takeDip(), 1e-9)
    }

    @Test
    fun theFirstCarScenarioConvergesUnderTheTarget() {
        // Modelo sencillo de un encoder que respeta el mínimo: 540 KB a QP 24, la mitad cada +6.
        val c = IdrSizeController(true, cap)
        fun size(qp: Int) = (540 * kb / Math.pow(2.0, (qp - 24) / 6.0)).toInt()
        var dropped = 0
        var last = 0
        repeat(10) {
            last = size(c.qpMin())
            if (c.onIdr(last, -1, 0.0).overCap) dropped++
        }
        assertEquals("solo el primero pasa del tope", 1, dropped)
        assertTrue("$last", last <= IdrSizeController.TARGET_BYTES)
    }
}
