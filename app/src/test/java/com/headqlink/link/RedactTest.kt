package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lo que identifica un coche o un móvil sale enmascarado en los logs; versiones, fechas y medidas, tal cual. */
class RedactTest {
    @Test
    fun carIdentifiersKeepOnlyTheTail() {
        val json = """<- {"CMD":"CAR_INFO","PARA":{"Version":"4.9","ProjectID":"AB12CD34EF56","CarUUID":"XYZ0000000000000000000000000098"}}"""
        val r = Redact.line(json)
        assertTrue(r, r.contains("\"ProjectID\":\"…EF56\""))
        assertTrue(r, r.contains("\"CarUUID\":\"…0098\""))
        assertTrue(r, r.contains("\"Version\":\"4.9\""))
        val kv = Redact.line("CAR_INFO: CarInfo(version=4.9, carType=2D4, projectId=AB12CD34EF56, carUuid=XYZ123456789)")
        assertEquals("CAR_INFO: CarInfo(version=4.9, carType=2D4, projectId=…EF56, carUuid=…6789)", kv)
    }

    @Test
    fun ipAddressesKeepTheLastTwoNumbers() {
        assertEquals(
            "pares: broadcast de *.*.53.80 (LeapMotor-A750): aceptado — interfaz swlan0 *.*.53.202/24",
            Redact.line("pares: broadcast de 10.218.53.80 (LeapMotor-A750): aceptado — interfaz swlan0 10.218.53.202/24"),
        )
        assertEquals("TCP /*.*.109.9:49998 → local /*.*.109.148:43427", Redact.line("TCP /192.168.109.9:49998 → local /192.168.109.148:43427"))
        // Lo que no identifica a nadie, tal cual.
        assertEquals("lo 127.0.0.1/8 (loopback)", Redact.line("lo 127.0.0.1/8 (loopback)"))
        assertEquals("Android Auto 17.8.663874-release · 0.2.63", Redact.line("Android Auto 17.8.663874-release · 0.2.63"))
        assertEquals("rtt 5 ms · 29.7 fps · 1.5 MB", Redact.line("rtt 5 ms · 29.7 fps · 1.5 MB"))
        assertFalse(Redact.line("versión 17.7.663654.0").contains("*"))
    }
}
