package com.headqlink.link

import com.headqlink.link.AaVersions.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** La tabla de versiones de Android Auto probadas con HeadQLink y sus textos (log, Comprobación, sessions.csv). */
class AaVersionsTest {
    @Test
    fun seriesFromTheVersionName() {
        assertEquals("17.7", AaVersions.series("17.7.663654-release"))
        assertEquals("17.8", AaVersions.series("17.8.661234"))
        assertEquals("17.10", AaVersions.series("17.10.1-beta"))
        assertEquals("18.0", AaVersions.series("18.0"))
        assertNull(AaVersions.series(null))
        assertNull(AaVersions.series("?"))
        assertNull(AaVersions.series("17"))
        assertNull(AaVersions.series("x.y.z"))
    }

    @Test
    fun only177IsVerified() {
        assertEquals(State.VERIFIED, AaVersions.state("17.7.663654-release"))
        assertEquals(State.VERIFIED, AaVersions.state("17.7.1"))
        assertEquals("2026-10", AaVersions.verifiedDate("17.7.663654-release"))
        for (v in listOf("17.8.661234-release", "17.9.1", "17.6.1", "17.70.1", "18.7.1", "1.2", "?")) {
            assertEquals(v, State.UNTESTED, AaVersions.state(v))
            assertNull(v, AaVersions.verifiedDate(v))
        }
        assertEquals(State.MISSING, AaVersions.state(null))
        assertEquals("17.7.x", AaVersions.verifiedList())
    }

    @Test
    fun describeForTheLog() {
        assertEquals(
            "17.7.663654-release (código 177663654): probada con HeadQLink (2026-10)",
            AaVersions.describe("17.7.663654-release", 177663654),
        )
        val d178 = AaVersions.describe("17.8.661234-release", 178661234)
        assertTrue(d178, d178.startsWith("17.8.661234-release (código 178661234): sin probar todavía con HeadQLink (probadas: 17.7.x"))
        assertTrue(d178, d178.contains("Open Headunit #985"))
        val d179 = AaVersions.describe("17.9.1", -1)
        assertTrue(d179, d179.startsWith("17.9.1: sin probar todavía") && d179.contains("helper"))
        assertEquals("18.0.1: sin probar todavía con HeadQLink (probadas: 17.7.x)", AaVersions.describe("18.0.1", -1))
        assertEquals("no instalado", AaVersions.describe(null, -1))
    }

    @Test
    fun csvCell() {
        assertEquals("17.7.663654-release", AaVersions.csvValue("17.7.663654-release"))
        assertEquals("", AaVersions.csvValue(null))
    }
}
