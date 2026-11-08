package org.orcapod.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.orcapod.app.engine.Mesh
import org.orcapod.app.engine.Scene
import org.orcapod.app.engine.SceneInstance
import org.orcapod.app.engine.SceneObject
import org.orcapod.app.engine.SceneSnapshot

class PlateThumbnailTest {
    private fun cube(id: Long, extruder: Int, offsetX: Float, plate: Int): Pair<SceneObject, Mesh> {
        val p = floatArrayOf(0f, 0f, 0f, 10f, 0f, 0f, 10f, 10f, 0f, 0f, 10f, 0f, 0f, 0f, 10f, 10f, 0f, 10f, 10f, 10f, 10f, 0f, 10f, 10f)
        val i = intArrayOf(0, 2, 1, 0, 3, 2, 4, 5, 6, 4, 6, 7, 0, 1, 5, 0, 5, 4, 2, 3, 7, 2, 7, 6, 0, 4, 7, 0, 7, 3, 1, 2, 6, 1, 6, 5)
        val matrix = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, offsetX, 0f, 0f, 1f)
        val inst = SceneInstance(id * 10, DoubleArray(9) { if (it >= 6) 1.0 else 0.0 }, matrix, false, true, plate)
        val obj = SceneObject(id, 1, "cube", null, null, floatArrayOf(0f, 0f, 0f), floatArrayOf(10f, 10f, 10f), arrayOf(inst), emptyArray(), 0, extruder)
        return obj to Mesh(1, p, i)
    }

    private fun scene(vararg cubes: Pair<SceneObject, Mesh>) = Scene(
        SceneSnapshot(1, cubes.map { it.first }.toTypedArray(), emptyArray(), 0, 0, 0, "", ""),
        cubes.associate { it.first.id to it.second },
    )

    @Test
    fun emptySceneHasNoThumbnail() {
        assertNull(PlateThumbnail.render(Scene.EMPTY, emptyList()))
    }

    @Test
    fun drawsObjectsInTheirFilamentColourOnATransparentBackground() {
        val t = PlateThumbnail.render(scene(cube(1, 2, 0f, 0)), listOf("#FF0000", "#0000FF"), 64)
        assertNotNull(t)
        val rgba = t!!.rgba()
        assertEquals(64 * 64 * 4, rgba.size)
        assertEquals("corner is transparent", 0, rgba[3].toInt())
        val centre = (32 * 64 + 32) * 4
        assertEquals("centre is drawn", 255, rgba[centre + 3].toInt() and 0xff)
        assertEquals("slot 2 is blue", 0, rgba[centre].toInt() and 0xff)
        assertTrue(rgba[centre + 2].toInt() and 0xff > 50)
    }

    @Test
    fun drawsOnlyTheFirstPlate() {
        // The object on plate 2 lies far to the right; were it drawn, the first cube would shrink to a corner.
        val t = PlateThumbnail.render(scene(cube(1, 1, 0f, 0), cube(2, 1, 500f, 1)), listOf("#00FF00"), 64)!!
        val centre = (32 * 64 + 32) * 4
        assertEquals(255, t.rgba()[centre + 3].toInt() and 0xff)
    }

    @Test
    fun parsesFilamentColours() {
        assertEquals(0x2850E0, PlateThumbnail.parseColour("#2850E0"))
        assertEquals(0x2850E0, PlateThumbnail.parseColour("#2850E0FF"))
        assertEquals(0x009688, PlateThumbnail.parseColour("bad"))
        assertEquals(0x009688, PlateThumbnail.parseColour(null))
    }
}
