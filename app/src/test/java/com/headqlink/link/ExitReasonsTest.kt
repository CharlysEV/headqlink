package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Motivos de salida de la app (ApplicationExitInfo) en palabras y su fila en exits.csv. */
class ExitReasonsTest {
    @Test
    fun reasonsInWords() {
        assertEquals("bloqueo (la app no respondía)", ExitReasons.describe(6))
        assertEquals("falta de memoria (Android la cerró para liberar RAM)", ExitReasons.describe(3))
        assertEquals("motivo desconocido (99)", ExitReasons.describe(99))
        // Lo normal (actualizar, cerrarse ella misma) no se marca como problema; el cierre forzado sí, y avisa.
        assertFalse(ExitReasons.abnormal(16))
        assertFalse(ExitReasons.abnormal(1))
        assertTrue(ExitReasons.abnormal(10))
        assertTrue(ExitReasons.forced(10))
        assertFalse(ExitReasons.forced(3))
        assertEquals("servicio en primer plano", ExitReasons.importance(125))
        assertEquals("en caché", ExitReasons.importance(400))
    }

    @Test
    fun csvRowQuotesWhenNeeded() {
        assertEquals(
            "2026-10-14 08:03:00,falta de memoria,3,\"a la vista, en coche\",512,640,\"dice \"\"hola\"\"\"",
            ExitReasons.csvRow("2026-10-14 08:03:00", "falta de memoria", 3, "a la vista, en coche", 512, 640, "dice \"hola\""),
        )
    }
}
