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

    private fun twoMoves() = Toolpath(
        positions = floatArrayOf(0f, 0f, 0.2f, 10f, 0f, 0.2f, 10f, 0f, 0.2f, 10f, 10f, 0.2f),
        moveTypes = byteArrayOf(Toolpath.MOVE_EXTRUDE.toByte(), Toolpath.MOVE_TRAVEL.toByte()),
        roles = byteArrayOf(2, 0), // external perimeter, then a travel
        extruders = byteArrayOf(0, 0),
        attrs = FloatArray(2 * Toolpath.Attr.COUNT) { if (it == 0) 40f else 1f },
        times = floatArrayOf(0.25f, 0.1f),
        layerZ = floatArrayOf(0.2f), layerFirst = intArrayOf(0), layerCount = intArrayOf(2), layerTime = floatArrayOf(0.35f),
        pointPositions = floatArrayOf(10f, 0f, 0.2f), pointTypes = byteArrayOf(Toolpath.MOVE_SEAM.toByte()), pointLayers = intArrayOf(0),
        totals = floatArrayOf(0.35f, 0f), volumes = doubleArrayOf(0.0), densities = floatArrayOf(0f),
        diameters = floatArrayOf(0f), costs = floatArrayOf(0f),
    )

    @Test
    fun toolpathGeometryCarriesRoleColoursAndOptions() {
        val tp = twoMoves()
        val g = ToolpathGeometry.build(tp, PreviewStyle())
        assertEquals(12, g.lines.size)
        assertArrayEquals(floatArrayOf(10f, 0f, 0.2f), g.lines.copyOfRange(6, 9), 0f)
        assertEquals(1f, g.lines[3], 1e-6f)
        assertEquals(125 / 255f, g.lines[4], 1e-6f)
        assertEquals(0, g.points.size)
        assertEquals(0..0, g.lineRange(tp, PreviewRange(0, 0)))

        val all = ToolpathGeometry.build(tp, PreviewStyle(options = setOf(PreviewOption.TRAVEL, PreviewOption.SEAM)))
        assertEquals(24, all.lines.size)
        assertEquals(56 / 255f, all.lines[15], 1e-6f)
        assertEquals(6, all.points.size)
        // The move slider stops after the first move.
        assertEquals(0..0, all.lineRange(tp, PreviewRange(0, 0, 1)))
        assertEquals(0..1, all.lineRange(tp, PreviewRange(0, 0)))
        assertEquals(0..0, all.pointRange(tp, PreviewRange(0, 0)))
        // Unknown roles fall back instead of failing.
        assertEquals(3, PlateRenderer.roleColor(200).size)
    }

    @Test
    fun rangeColoursFollowTheDesktopPalette() {
        val range = floatArrayOf(10f, 110f)
        assertArrayEquals(ToolpathColours.RANGE_COLORS[0], ToolpathColours.rangeColor(0f, range, false))
        assertArrayEquals(ToolpathColours.RANGE_COLORS[5], ToolpathColours.rangeColor(60f, range, false))
        assertArrayEquals(ToolpathColours.RANGE_COLORS[10], ToolpathColours.rangeColor(500f, range, false))
        assertEquals(11, ToolpathColours.rangeValues(range, false).size)
        assertEquals(110f, ToolpathColours.rangeValues(range, true).last(), 1e-3f)
        assertEquals(listOf(5f), ToolpathColours.rangeValues(floatArrayOf(5f, 5f), false))
    }
}
