package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Test

/** La versión de la cabecera: sin el sufijo del motor. */
class VersionLabelTest {
    @Test
    fun theHeaderShowsTheVersionWithoutTheEngineSuffix() {
        assertEquals("v0.2.9", Ui.versionLabel("0.2.9-qdauto"))
        assertEquals("v0.3.0", Ui.versionLabel("0.3.0"))
        assertEquals("", Ui.versionLabel(null))
        assertEquals("", Ui.versionLabel(""))
    }
}
