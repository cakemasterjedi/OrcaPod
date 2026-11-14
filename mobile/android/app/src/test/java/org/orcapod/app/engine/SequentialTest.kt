package org.orcapod.app.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SequentialTest {
    @Test
    fun readsTheOutlinesAndTheMessage() {
        val clearance = SequentialClearance.parse(
            """{"by_object": true, "message": "Too close",
                "outlines": [{"object": 3, "instance": 4, "colliding": true, "points": [0, 0, 10, 0, 10, 10.5]}]}""",
        )
        assertTrue(clearance.byObject)
        assertEquals("Too close", clearance.message)
        val outline = clearance.outlines.single()
        assertEquals(3L, outline.objectId)
        assertEquals(4L, outline.instanceId)
        assertTrue(outline.colliding)
        assertArrayEquals(floatArrayOf(0f, 0f, 10f, 0f, 10f, 10.5f), outline.points, 0f)
    }

    @Test
    fun printingByLayerHasNoOutlines() {
        val clearance = SequentialClearance.parse("""{"by_object": false, "message": "", "outlines": []}""")
        assertFalse(clearance.byObject)
        assertTrue(clearance.outlines.isEmpty())
    }
}
