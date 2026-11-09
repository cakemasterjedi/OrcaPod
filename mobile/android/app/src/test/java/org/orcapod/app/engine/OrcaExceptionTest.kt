package org.orcapod.app.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class OrcaExceptionTest {
    @Test
    fun messageDropsTheBridgeFunctionName() {
        val ex = OrcaException(OrcaException.NOT_FOUND, "orca_plater_volume_paint_fill: the paint position does not hit the volume")
        assertEquals("The paint position does not hit the volume", ex.message)
        assertEquals("orca_plater_volume_paint_fill: the paint position does not hit the volume", ex.diagnostic)
    }

    @Test
    fun messageWithoutAFunctionNameIsKept() {
        assertEquals("Engine call failed (status 3)", OrcaException(3, "engine call failed (status 3)").message)
    }
}
