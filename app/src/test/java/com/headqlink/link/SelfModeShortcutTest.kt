package com.headqlink.link

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Arranque de AA: directo al servidor de head unit solo con la versión de AA con la que el aviso no contestó. */
class SelfModeShortcutTest {
    private val aa177 = 177663654L

    @Test
    fun directOnlyWithTheSameAaVersion() {
        assertTrue(SelfModeShortcut.direct(aa177, aa177))
        // AA se actualizó: se vuelve a probar el aviso.
        assertFalse(SelfModeShortcut.direct(aa177, 178012345L))
        // Nada aprendido, o sin saber la versión de AA.
        assertFalse(SelfModeShortcut.direct(-1, aa177))
        assertFalse(SelfModeShortcut.direct(aa177, -1))
        assertFalse(SelfModeShortcut.direct(-1, -1))
    }
}
