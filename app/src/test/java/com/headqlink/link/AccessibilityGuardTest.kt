package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Test

/** La vigilancia de la accesibilidad: el fabricante y la lista de servicios que se vuelve a escribir. */
class AccessibilityGuardTest {
    @Test
    fun brand() {
        assertEquals(AccessibilityGuard.Brand.SAMSUNG, AccessibilityGuard.brand("samsung", "samsung"))
        assertEquals(AccessibilityGuard.Brand.HONOR, AccessibilityGuard.brand("HONOR", "HONOR"))
        assertEquals(AccessibilityGuard.Brand.HONOR, AccessibilityGuard.brand("HUAWEI", "HUAWEI"))
        assertEquals(AccessibilityGuard.Brand.XIAOMI, AccessibilityGuard.brand("Xiaomi", "Redmi"))
        assertEquals(AccessibilityGuard.Brand.XIAOMI, AccessibilityGuard.brand("Xiaomi", "POCO"))
        assertEquals(AccessibilityGuard.Brand.OTHER, AccessibilityGuard.brand("Google", "google"))
        assertEquals(AccessibilityGuard.Brand.OTHER, AccessibilityGuard.brand(null, null))
    }

    @Test
    fun withService() {
        val me = "com.headqlink.app/com.headqlink.link.TouchService"
        assertEquals(me, AccessibilityGuard.withService(null, me))
        assertEquals(me, AccessibilityGuard.withService("", me))
        assertEquals("a/b:$me", AccessibilityGuard.withService("a/b", me))
        // Ya estaba: no se repite.
        assertEquals("a/b:$me", AccessibilityGuard.withService("a/b:$me", me))
    }
}
