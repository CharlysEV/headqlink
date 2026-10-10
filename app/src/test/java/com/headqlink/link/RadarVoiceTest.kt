package com.headqlink.link

import com.andrerinas.openheadunit.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Avisos de radar por voz: uno por radar al acercarse, y otro si se va por encima del límite junto a él. */
class RadarVoiceTest {
    @Test
    fun announcesEachCameraOnceWhenItComesWithinReach() {
        val v = RadarVoice()
        assertNull(v.onTick(1200.0, 80, 40.0, -3.0, 90.0))
        val a = v.onTick(680.0, 80, 40.0, -3.0, 70.0)!!
        assertEquals(R.string.hql_voice_camera_limit, a.text)
        assertEquals(listOf(700, 80), a.args.toList())
        // El mismo radar, más cerca y por debajo del límite: nada más.
        assertNull(v.onTick(500.0, 80, 40.0, -3.0, 70.0))
        assertNull(v.onTick(150.0, 80, 40.0, -3.0, 82.0))
        // Otro radar: se anuncia; sin límite conocido, solo la distancia.
        val b = v.onTick(300.0, -1, 40.01, -3.0, 70.0)!!
        assertEquals(R.string.hql_voice_camera, b.text)
        assertEquals(listOf(300), b.args.toList())
    }

    @Test
    fun warnsOnceWhenOverTheLimitNearTheCamera() {
        val v = RadarVoice()
        v.onTick(650.0, 50, 40.0, -3.0, 70.0)
        // Lejos aún (más de 400 m): no.
        assertNull(v.onTick(450.0, 50, 40.0, -3.0, 70.0))
        val w = v.onTick(380.0, 50, 40.0, -3.0, 66.4)!!
        assertEquals(R.string.hql_voice_over_limit, w.text)
        assertEquals(listOf(66, 50), w.args.toList())
        assertNull(v.onTick(200.0, 50, 40.0, -3.0, 70.0))
        // Dentro del margen (50 + 4): no avisa.
        val v2 = RadarVoice()
        v2.onTick(650.0, 50, 40.0, -3.0, 50.0)
        assertNull(v2.onTick(300.0, 50, 40.0, -3.0, 54.0))
    }
}
