package org.orcapod.app.ui.viewport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.orcapod.app.engine.Mesh
import org.orcapod.app.engine.Toolpath

class PlateRendererTest {
    @Test
    fun flatShadingGivesEachTriangleItsFaceNormal() {
        // One counter-clockwise triangle in the z = 0 plane, seen from +z.
        val mesh = Mesh(1, floatArrayOf(0f, 0f, 0f, 2f, 0f, 0f, 0f, 2f, 0f), intArrayOf(0, 1, 2))
        val v = PlateRenderer.flatShaded(mesh)
        assertEquals(18, v.size)
        for (i in 0 until 3) assertArrayEquals(floatArrayOf(0f, 0f, 1f), v.copyOfRange(i * 6 + 3, i * 6 + 6), 1e-6f)
        assertArrayEquals(floatArrayOf(2f, 0f, 0f), v.copyOfRange(6, 9), 0f)
    }

    @Test
    fun toolpathVerticesCarryRoleColours() {
        val tp = Toolpath(
            positions = floatArrayOf(0f, 0f, 0.2f, 10f, 0f, 0.2f),
            roles = byteArrayOf(2), // external perimeter
            layerZ = floatArrayOf(0.2f), layerFirst = intArrayOf(0), layerCount = intArrayOf(1),
            estimatedSeconds = 1f, filamentVolume = 0.0, filamentDensity = 0f, filamentDiameter = 0f,
        )
        val v = PlateRenderer.toolpathVertices(tp)
        assertEquals(12, v.size)
        assertArrayEquals(floatArrayOf(10f, 0f, 0.2f), v.copyOfRange(6, 9), 0f)
        assertEquals(1f, v[3], 1e-6f)
        assertEquals(125 / 255f, v[4], 1e-6f)
        // Unknown roles fall back instead of failing.
        assertEquals(3, PlateRenderer.roleColor(200).size)
    }
}
