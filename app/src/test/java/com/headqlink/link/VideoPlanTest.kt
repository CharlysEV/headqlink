package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Igualdad del plan de vídeo: decide si la pipeline viva se reutiliza (qdauto §4.7, §9.1). */
class VideoPlanTest {
    private val c10 = VideoPlan.Car(1920, 882, 1920, 882, 30, 5_080_320, 3, 3, true)
    private fun plan(
        mode: String = Config.MODE_AA_EXT,
        profile: String = VideoProfile.MAX,
        w: Int = 1920,
        h: Int = 882,
        fps: Int = 60,
        car: VideoPlan.Car = c10,
        settings: String = "huella",
        passthrough: Boolean = false,
    ) = VideoPlan(mode, profile, !passthrough, mode == Config.MODE_AA_EXT, passthrough, w, h, fps, car, settings)

    @Test
    fun sameCarAndSettingsIsTheSamePlan() {
        assertEquals(plan(), plan())
        assertEquals(plan().hashCode(), plan().hashCode())
        assertEquals("", plan().differenceFrom(plan()))
        // Mismo coche con otro arranque (mismos valores): se reutiliza.
        assertEquals(plan(car = VideoPlan.Car(1920, 882, 1920, 882, 30, 5_080_320, 3, 3, true)), plan())
    }

    @Test
    fun relevantDifferencesMakeAnotherPlan() {
        val base = plan()
        assertNotEquals(base, plan(mode = Config.MODE_AA, passthrough = true))
        assertNotEquals(base, plan(profile = VideoProfile.MEDIUM))
        assertNotEquals(base, plan(w = 1280, h = 588))
        assertNotEquals(base, plan(fps = 45))
        assertNotEquals(base, plan(settings = "otra huella"))
        assertNotEquals(base, plan(car = VideoPlan.Car(1920, 1080, 1920, 1080, 30, 5_080_320, 3, 3, true)))
        assertNotEquals(base, plan(car = VideoPlan.Car(1920, 882, 1920, 882, 60, 5_080_320, 3, 3, true)))
        assertNotEquals(base, plan(car = VideoPlan.Car(1920, 882, 0, 0, 0, 0, 0, 0, false)))
    }

    @Test
    fun differenceIsReadable() {
        val d = plan(w = 1280, h = 588, fps = 45).differenceFrom(plan())
        assertTrue(d, d.contains("tamaño 1920x882→1280x588"))
        assertTrue(d, d.contains("fps 60→45"))
        assertTrue(plan().toString().contains("1920x882@60"))
    }
}
