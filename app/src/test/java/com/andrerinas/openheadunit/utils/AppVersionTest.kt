package com.andrerinas.openheadunit.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {

    @Test
    fun testVersionParsing() {
        val v1 = AppVersion.parse("3.5.0-beta2")
        assertNotNull(v1)
        assertEquals(3, v1!!.major)
        assertEquals(5, v1.minor)
        assertEquals(0, v1.patch)
        assertEquals(1, v1.preReleaseStage) // beta
        assertEquals(2, v1.preReleaseNumber)

        val v2 = AppVersion.parse("v.3.5.0-beta1")
        assertNotNull(v2)
        assertEquals(1, v2!!.preReleaseNumber)

        val v3 = AppVersion.parse("v3.4.0")
        assertNotNull(v3)
        assertEquals(3, v3!!.major)
        assertEquals(4, v3.minor)
        assertEquals(0, v3.patch)
        assertEquals(3, v3.preReleaseStage) // stable

        val v4 = AppVersion.parse("v.3.1.1-alpha")
        assertNotNull(v4)
        assertEquals(0, v4!!.preReleaseStage) // alpha
        assertEquals(0, v4.preReleaseNumber)
    }

    @Test
    fun testVersionComparisons() {
        val beta1 = AppVersion.parse("3.5.0-beta1")!!
        val beta2 = AppVersion.parse("3.5.0-beta2")!!
        val stable = AppVersion.parse("3.5.0")!!
        val nextPatch = AppVersion.parse("3.5.1")!!
        val oldStable = AppVersion.parse("3.4.0")!!

        assertTrue(beta2 > beta1)
        assertTrue(stable > beta2)
        assertTrue(nextPatch > stable)
        assertTrue(beta1 > oldStable)
        assertFalse(beta1 > beta2)
        assertFalse(oldStable > beta1)
        assertEquals(0, stable.compareTo(AppVersion.parse("v.3.5.0")!!))
    }
}
