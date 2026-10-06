package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cable USB: espera creciente entre aperturas del accesorio (solo la reinicia una sesión con CAR_INFO) y accesorio
 * desaparecido con ENODEV/EIO (no se reabre hasta que el coche lo vuelva a poner en modo accesorio).
 */
class UsbReopenPolicyTest {
    private val watchdog = "WATCHDOG: sin recibir nada del coche en 10000 ms"
    private val enodev = "WRITE_ERROR: write failed (android.system.ErrnoException: write failed: ENODEV (No such device))"

    @Test
    fun silentSessionsKeepGrowingTheWait() {
        val p = UsbReopenPolicy()
        // Lo del log real: el coche no habla, el watchdog cierra a los 10 s (≥ 10 s: antes reiniciaba la espera).
        val delays = (1..8).map { p.onSessionEnd(0, false, 10_050, watchdog) }
        assertEquals(listOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L, 60_000L, 60_000L, 60_000L), delays.map { it.delayMs })
        assertTrue(delays.all { it.reopen })
        assertTrue(delays[0].text, delays[0].text.contains("sin ningún mensaje del coche"))
        assertTrue(delays[0].text, delays[0].text.contains("no reinicia la espera"))
        assertEquals(8, p.failures)
    }

    @Test
    fun onlyASessionWithCarInfoResetsTheWait() {
        val p = UsbReopenPolicy()
        repeat(3) { p.onSessionEnd(0, false, 10_050, watchdog) }
        // Mensajes, pero sin CAR_INFO: sigue creciendo.
        val noInfo = p.onSessionEnd(12, false, 45_000, watchdog)
        assertEquals(10_000L, noInfo.delayMs)
        assertTrue(noInfo.text, noInfo.text.contains("sin CAR_INFO del coche (12 mensajes)"))
        // CAR_INFO pero 3 s (el coche saluda y se va): sigue creciendo.
        val quick = p.onSessionEnd(30, true, 3_000, "EOF: el coche cerró la conexión")
        assertEquals(30_000L, quick.delayMs)
        // Una sesión de verdad: vuelta a empezar.
        val good = p.onSessionEnd(5_000, true, 600_000, watchdog)
        assertEquals(1_000L, good.delayMs)
        assertEquals(0, p.failures)
        assertTrue(good.text, good.text.contains("reiniciada"))
        assertEquals(listOf(1_000L, 2_000L), (1..2).map { p.onSessionEnd(0, false, 10_050, watchdog).delayMs })
    }

    @Test
    fun twentyMinutesOfASilentHostOpenFarFewerSessions() {
        // Antes: una sesión cada ~11 s (115 en 20 min). Ahora la espera llega a 60 s.
        val p = UsbReopenPolicy()
        var t = 0L
        var sessions = 0
        while (t < 20 * 60_000L) {
            sessions++
            t += 10_050
            t += p.onSessionEnd(0, false, 10_050, watchdog).delayMs
        }
        assertTrue("$sessions sesiones", sessions <= 25)
    }

    @Test
    fun enodevOrEioMeansTheAccessoryIsGoneUntilItComesBack() {
        val p = UsbReopenPolicy()
        val d = p.onSessionEnd(40, true, 120_000, enodev)
        assertFalse(d.reopen)
        assertEquals(-1L, d.delayMs)
        assertTrue(d.text, d.text.contains("accesorio desaparecido (ENODEV"))
        assertFalse(p.mayOpen())
        assertTrue(p.accessoryGone)
        // Hasta que el coche lo vuelva a poner (USB_STATE accessory=true nuevo o USB_ACCESSORY_ATTACHED).
        assertTrue(p.onAccessoryArrived())
        assertTrue(p.mayOpen())
        assertEquals(0, p.failures)
        assertFalse("ya no estaba fuera", p.onAccessoryArrived())

        val eio = UsbReopenPolicy()
        assertFalse(eio.onSessionEnd(0, false, 20, "READ_ERROR: read failed (java.io.IOException: read failed: EIO (I/O error))").reopen)
        assertFalse(eio.mayOpen())
    }

    @Test
    fun goneDetection() {
        assertTrue(UsbReopenPolicy.isAccessoryGone(enodev))
        assertTrue(UsbReopenPolicy.isAccessoryGone("WRITE_ERROR: write failed: EIO"))
        assertTrue(UsbReopenPolicy.isAccessoryGone("READ_ERROR: java.io.IOException: No such device"))
        assertTrue(UsbReopenPolicy.isAccessoryGone("READ_ERROR | java.io.IOException: I/O error"))
        assertFalse(UsbReopenPolicy.isAccessoryGone(watchdog))
        assertFalse(UsbReopenPolicy.isAccessoryGone("WRITE_STALL: un write() (VIDEO_IDR) lleva 20000 ms bloqueado"))
        assertFalse(UsbReopenPolicy.isAccessoryGone("LOCAL: cable USB desconectado"))
        assertFalse(UsbReopenPolicy.isAccessoryGone("EOF: rodeio"))
    }
}
