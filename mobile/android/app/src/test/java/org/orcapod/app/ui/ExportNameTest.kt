package org.orcapod.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExportNameTest {
    @Test
    fun printerModelDropsTheNozzle() {
        assertEquals("Bambu Lab A1", printerModel("Bambu Lab A1 0.4 nozzle"))
        assertEquals("Bambu Lab P1S", printerModel("Bambu Lab P1S 0.6 nozzle"))
        assertEquals("Voron 2.4 350", printerModel("Voron 2.4 350 0.4 nozzle"))
        assertEquals("My printer", printerModel("My printer"))
    }

    @Test
    fun exportFileNameKeepsReadableNames() {
        assertEquals("Fan duct v2", exportFileName("Fan duct v2"))
        assertEquals("a_b_c", exportFileName("a/b:c"))
        assertEquals("cube", exportFileName("  cube. "))
        assertNull(exportFileName("///"))
    }
}
