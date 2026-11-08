package org.orcapod.app.ui

import android.graphics.Bitmap
import org.orcapod.app.engine.Scene
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The plate picture stored in a saved project (desktop's Metadata/plate_1.png)
 * and shown in the recent projects list: the first plate's objects seen from
 * the front right, flat shaded in their filament colours on a transparent
 * background. A small software renderer, so it needs no GL context.
 */
class PlateThumbnail private constructor(val size: Int, private val argb: IntArray) {
    fun bitmap(): Bitmap = Bitmap.createBitmap(argb, size, size, Bitmap.Config.ARGB_8888)

    /** Top-down RGBA8 rows, as the engine's project writer takes them. */
    fun rgba(): ByteArray {
        val out = ByteArray(argb.size * 4)
        for (i in argb.indices) {
            val c = argb[i]
            out[i * 4] = (c shr 16).toByte()
            out[i * 4 + 1] = (c shr 8).toByte()
            out[i * 4 + 2] = c.toByte()
            out[i * 4 + 3] = (c ushr 24).toByte()
        }
        return out
    }

    companion object {
        // Camera basis: looking from the front right, above the bed (engine z-up).
        private val forward = normalize(floatArrayOf(-0.55f, 1f, -0.75f))
        private val right = normalize(cross(forward, floatArrayOf(0f, 0f, 1f)))
        private val up = cross(right, forward)

        /** Null when the scene has nothing to draw. */
        fun render(scene: Scene, colours: List<String>, size: Int = 256): PlateThumbnail? {
            val objects = scene.snapshot.objects
            val onFirst = objects.any { o -> o.instances.any { it.plate == 0 } }
            // Screen-space triangles: x, y, depth per corner, plus the shade colour.
            val tris = ArrayList<FloatArray>()
            val shades = ArrayList<Int>()
            var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
            var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            for (o in objects) {
                val mesh = scene.meshes[o.id] ?: continue
                val base = parseColour(colours.getOrNull(max(o.extruder, 1) - 1))
                val p = mesh.positions
                val idx = mesh.indices
                for (inst in o.instances) {
                    if (onFirst && inst.plate != 0) continue
                    val m = inst.matrix
                    val world = FloatArray(p.size)
                    for (v in 0 until p.size / 3) {
                        val x = p[v * 3]; val y = p[v * 3 + 1]; val z = p[v * 3 + 2]
                        world[v * 3] = m[0] * x + m[4] * y + m[8] * z + m[12]
                        world[v * 3 + 1] = m[1] * x + m[5] * y + m[9] * z + m[13]
                        world[v * 3 + 2] = m[2] * x + m[6] * y + m[10] * z + m[14]
                    }
                    var t = 0
                    while (t + 2 < idx.size) {
                        val a = idx[t] * 3; val b = idx[t + 1] * 3; val c = idx[t + 2] * 3
                        t += 3
                        val n = cross(
                            floatArrayOf(world[b] - world[a], world[b + 1] - world[a + 1], world[b + 2] - world[a + 2]),
                            floatArrayOf(world[c] - world[a], world[c + 1] - world[a + 1], world[c + 2] - world[a + 2]),
                        )
                        val len = sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2])
                        if (len == 0f) continue
                        // Two-sided: winding flips under mirroring.
                        val light = abs(n[0] * forward[0] + n[1] * forward[1] + n[2] * forward[2]) / len
                        val tri = FloatArray(9)
                        for ((k, v) in intArrayOf(a, b, c).withIndex()) {
                            val sx = world[v] * right[0] + world[v + 1] * right[1] + world[v + 2] * right[2]
                            val sy = world[v] * up[0] + world[v + 1] * up[1] + world[v + 2] * up[2]
                            tri[k * 3] = sx
                            tri[k * 3 + 1] = sy
                            tri[k * 3 + 2] = world[v] * forward[0] + world[v + 1] * forward[1] + world[v + 2] * forward[2]
                            minX = min(minX, sx); maxX = max(maxX, sx)
                            minY = min(minY, sy); maxY = max(maxY, sy)
                        }
                        tris += tri
                        shades += shade(base, 0.35f + 0.65f * light)
                    }
                }
            }
            if (tris.isEmpty()) return null

            val margin = size * 0.06f
            val scale = (size - 2 * margin) / max(maxX - minX, maxY - minY).coerceAtLeast(1e-3f)
            val offX = (size - (maxX - minX) * scale) / 2
            val offY = (size - (maxY - minY) * scale) / 2
            val argb = IntArray(size * size)
            val depth = FloatArray(size * size) { Float.MAX_VALUE }
            val x = FloatArray(3); val y = FloatArray(3); val z = FloatArray(3)
            for ((i, tri) in tris.withIndex()) {
                for (k in 0..2) {
                    x[k] = offX + (tri[k * 3] - minX) * scale
                    y[k] = size - (offY + (tri[k * 3 + 1] - minY) * scale) // image rows run downward
                    z[k] = tri[k * 3 + 2]
                }
                val area = (x[1] - x[0]) * (y[2] - y[0]) - (x[2] - x[0]) * (y[1] - y[0])
                if (abs(area) < 1e-6f) continue
                val x0 = max(floor(min(x[0], min(x[1], x[2]))).toInt(), 0)
                val x1 = min(ceil(max(x[0], max(x[1], x[2]))).toInt(), size - 1)
                val y0 = max(floor(min(y[0], min(y[1], y[2]))).toInt(), 0)
                val y1 = min(ceil(max(y[0], max(y[1], y[2]))).toInt(), size - 1)
                for (py in y0..y1) {
                    val cy = py + 0.5f
                    for (px in x0..x1) {
                        val cx = px + 0.5f
                        val w0 = ((x[1] - cx) * (y[2] - cy) - (x[2] - cx) * (y[1] - cy)) / area
                        val w1 = ((x[2] - cx) * (y[0] - cy) - (x[0] - cx) * (y[2] - cy)) / area
                        val w2 = 1f - w0 - w1
                        if (w0 < 0f || w1 < 0f || w2 < 0f) continue
                        val d = w0 * z[0] + w1 * z[1] + w2 * z[2]
                        val at = py * size + px
                        if (d < depth[at]) {
                            depth[at] = d
                            argb[at] = shades[i]
                        }
                    }
                }
            }
            return PlateThumbnail(size, argb)
        }

        private fun shade(rgb: Int, f: Float): Int {
            val r = ((rgb shr 16 and 0xff) * f).toInt().coerceIn(0, 255)
            val g = ((rgb shr 8 and 0xff) * f).toInt().coerceIn(0, 255)
            val b = ((rgb and 0xff) * f).toInt().coerceIn(0, 255)
            return (0xff shl 24) or (r shl 16) or (g shl 8) or b
        }

        /** "#rrggbb" (or "#rrggbbaa") to 0xrrggbb; Orca's default green otherwise. */
        internal fun parseColour(colour: String?): Int {
            val hex = colour?.removePrefix("#")?.take(6)
            return hex?.takeIf { it.length == 6 }?.toIntOrNull(16) ?: 0x009688
        }

        private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(
            a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0],
        )

        private fun normalize(v: FloatArray): FloatArray {
            val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
            return floatArrayOf(v[0] / l, v[1] / l, v[2] / l)
        }
    }
}
