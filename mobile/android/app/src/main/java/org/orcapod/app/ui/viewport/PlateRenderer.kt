package org.orcapod.app.ui.viewport

import android.opengl.GLES30.*
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import org.orcapod.app.engine.Mesh
import org.orcapod.app.engine.Plate
import org.orcapod.app.engine.Scene
import org.orcapod.app.engine.Toolpath
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.sqrt

/** What the viewport shows; replaced as a whole from the UI thread. */
data class ViewportState(
    val scene: Scene = Scene.EMPTY,
    val selection: Selection? = null,
    /** Live offset of the instance being dragged, before it is committed. */
    val drag: Drag? = null,
    val toolpath: Toolpath? = null,
    /** Highest toolpath layer shown (inclusive). */
    val layer: Int = 0,
    val showToolpath: Boolean = false,
)

data class Selection(val objectId: Long, val instanceId: Long)
data class Drag(val objectId: Long, val instanceId: Long, val dx: Float, val dy: Float)

internal class PlateRenderer(val camera: Camera) : GLSurfaceView.Renderer {
    @Volatile
    var state = ViewportState()

    private lateinit var meshProgram: MeshProgram
    private lateinit var colorProgram: ColorProgram

    private class GpuMesh(val revision: Long, val vbo: Int, val vertexCount: Int)
    private val meshes = HashMap<Long, GpuMesh>()

    private var bedKey: List<Float>? = null
    private var bedFillVbo = 0
    private var bedFillCount = 0
    private var bedLineVbo = 0
    private var bedLineCount = 0

    private var toolpathSource: Toolpath? = null
    private var toolpathVbo = 0

    private val mvp = FloatArray(16)
    private val model = FloatArray(16)
    private val tmp = FloatArray(16)
    private val normal3 = FloatArray(9)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        meshProgram = MeshProgram()
        colorProgram = ColorProgram()
        // A new EGL context drops every buffer; rebuild them lazily.
        meshes.clear()
        bedKey = null
        toolpathSource = null
        glEnable(GL_DEPTH_TEST)
        glClearColor(0.13f, 0.14f, 0.16f, 1f)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        glViewport(0, 0, width, height)
        camera.resize(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val s = state
        camera.update()
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
        drawBed(s.scene.snapshot.plates, s.scene.snapshot.currentPlate)
        if (s.showToolpath && s.toolpath != null) drawToolpath(s.toolpath, s.layer)
        else drawObjects(s)
    }

    // --- bed ---

    private fun drawBed(plates: Array<Plate>, current: Int) {
        val key = plates.flatMap { listOf(it.origin[0], it.origin[1], it.size[0], it.size[1]) } + current.toFloat()
        if (key != bedKey) {
            bedKey = key
            buildBed(plates, current)
        }
        if (bedFillCount == 0) return
        glUseProgram(colorProgram.id)
        glUniformMatrix4fv(colorProgram.mvp, 1, false, camera.viewProjection, 0)
        // The bed surface sits just under z = 0 and never hides what lies on it.
        glEnable(GL_POLYGON_OFFSET_FILL)
        glPolygonOffset(1f, 1f)
        glUniform1f(colorProgram.alpha, 1f)
        bindInterleaved(bedFillVbo)
        glDrawArrays(GL_TRIANGLES, 0, bedFillCount)
        glDisable(GL_POLYGON_OFFSET_FILL)
        bindInterleaved(bedLineVbo)
        glDrawArrays(GL_LINES, 0, bedLineCount)
    }

    private fun buildBed(plates: Array<Plate>, current: Int) {
        Gl.deleteVbo(bedFillVbo)
        Gl.deleteVbo(bedLineVbo)
        val fill = ArrayList<Float>()
        val lines = ArrayList<Float>()
        fun v(list: ArrayList<Float>, x: Float, y: Float, r: Float, g: Float, b: Float) {
            list.add(x); list.add(y); list.add(0f); list.add(r); list.add(g); list.add(b)
        }
        for ((i, p) in plates.withIndex()) {
            val x0 = p.origin[0]
            val y0 = p.origin[1]
            val x1 = x0 + p.size[0]
            val y1 = y0 + p.size[1]
            val shade = if (i == current) 0.24f else 0.19f
            for ((x, y) in listOf(x0 to y0, x1 to y0, x1 to y1, x0 to y0, x1 to y1, x0 to y1))
                v(fill, x, y, shade, shade + 0.01f, shade + 0.03f)
            val grid = 0.32f
            var gx = x0 + 10f
            while (gx < x1) { v(lines, gx, y0, grid, grid, grid); v(lines, gx, y1, grid, grid, grid); gx += 10f }
            var gy = y0 + 10f
            while (gy < y1) { v(lines, x0, gy, grid, grid, grid); v(lines, x1, gy, grid, grid, grid); gy += 10f }
            val (r, g, b) = if (i == current) Triple(0f, 0.68f, 0.26f) else Triple(0.5f, 0.5f, 0.5f)
            for ((a, c) in listOf((x0 to y0) to (x1 to y0), (x1 to y0) to (x1 to y1), (x1 to y1) to (x0 to y1), (x0 to y1) to (x0 to y0))) {
                v(lines, a.first, a.second, r, g, b); v(lines, c.first, c.second, r, g, b)
            }
        }
        bedFillCount = fill.size / 6
        bedLineCount = lines.size / 6
        bedFillVbo = if (bedFillCount > 0) Gl.vbo(fill.toFloatArray()) else 0
        bedLineVbo = if (bedLineCount > 0) Gl.vbo(lines.toFloatArray()) else 0
    }

    // --- objects ---

    private fun drawObjects(s: ViewportState) {
        val snap = s.scene.snapshot
        val live = snap.objects.map { it.id }.toSet()
        meshes.keys.filter { it !in live }.forEach { Gl.deleteVbo(meshes.remove(it)!!.vbo) }

        glUseProgram(meshProgram.id)
        glUniform3f(meshProgram.light, 0.3f, -0.5f, 0.81f)
        for (obj in snap.objects) {
            val mesh = s.scene.meshes[obj.id] ?: continue
            val gpu = gpuMesh(obj.id, mesh)
            if (gpu.vertexCount == 0) continue
            bindInterleaved(gpu.vbo)
            for (inst in obj.instances) {
                System.arraycopy(inst.matrix, 0, model, 0, 16)
                val drag = s.drag
                if (drag != null && drag.objectId == obj.id && drag.instanceId == inst.id) {
                    model[12] += drag.dx
                    model[13] += drag.dy
                }
                Matrix.multiplyMM(mvp, 0, camera.viewProjection, 0, model, 0)
                glUniformMatrix4fv(meshProgram.mvp, 1, false, mvp, 0)
                normalMatrix(model)
                glUniformMatrix3fv(meshProgram.normalMat, 1, false, normal3, 0)
                val selected = s.selection?.let { it.objectId == obj.id && it.instanceId == inst.id } == true
                when {
                    selected -> glUniform4f(meshProgram.color, 1f, 0.62f, 0.15f, 1f)
                    !inst.printable -> glUniform4f(meshProgram.color, 0.45f, 0.45f, 0.45f, 1f)
                    inst.plate < 0 -> glUniform4f(meshProgram.color, 0.75f, 0.3f, 0.3f, 1f)
                    else -> glUniform4f(meshProgram.color, 0.2f, 0.72f, 0.42f, 1f)
                }
                glDrawArrays(GL_TRIANGLES, 0, gpu.vertexCount)
            }
        }
    }

    private fun normalMatrix(m: FloatArray) {
        Matrix.invertM(tmp, 0, m, 0)
        // Inverse-transpose: row i of the inverse becomes column i.
        for (r in 0 until 3) for (c in 0 until 3) normal3[c * 3 + r] = tmp[r * 4 + c]
    }

    private fun gpuMesh(id: Long, mesh: Mesh): GpuMesh {
        meshes[id]?.let { if (it.revision == mesh.revision) return it else Gl.deleteVbo(it.vbo) }
        val data = flatShaded(mesh)
        val gpu = GpuMesh(mesh.revision, if (data.isEmpty()) 0 else Gl.vbo(data), data.size / 6)
        meshes[id] = gpu
        return gpu
    }

    // --- toolpath ---

    private fun drawToolpath(tp: Toolpath, layer: Int) {
        if (tp !== toolpathSource) {
            Gl.deleteVbo(toolpathVbo)
            toolpathVbo = if (tp.segmentCount > 0) Gl.vbo(toolpathVertices(tp)) else 0
            toolpathSource = tp
        }
        if (toolpathVbo == 0 || tp.layerTotal == 0) return
        val l = layer.coerceIn(0, tp.layerTotal - 1)
        val below = tp.layerFirst[l]
        val total = below + tp.layerCount[l]
        glUseProgram(colorProgram.id)
        glUniformMatrix4fv(colorProgram.mvp, 1, false, camera.viewProjection, 0)
        bindInterleaved(toolpathVbo)
        glLineWidth(2f)
        // Layers below the current one dimmed, the current one at full strength.
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glUniform1f(colorProgram.alpha, 0.55f)
        if (below > 0) glDrawArrays(GL_LINES, 0, below * 2)
        glDisable(GL_BLEND)
        glUniform1f(colorProgram.alpha, 1f)
        glDrawArrays(GL_LINES, below * 2, (total - below) * 2)
        glLineWidth(1f)
    }

    companion object {
        /** Desktop preview colours (libvgcode defaults), indexed by engine ExtrusionRole. */
        private val ROLE_COLORS = arrayOf(
            intArrayOf(230, 179, 179), // None
            intArrayOf(255, 230, 77),  // Perimeter
            intArrayOf(255, 125, 56),  // ExternalPerimeter
            intArrayOf(31, 31, 255),   // OverhangPerimeter
            intArrayOf(176, 48, 41),   // InternalInfill
            intArrayOf(150, 84, 204),  // SolidInfill
            intArrayOf(240, 64, 64),   // TopSolidInfill
            intArrayOf(102, 92, 199),  // BottomSurface
            intArrayOf(255, 140, 105), // Ironing
            intArrayOf(77, 128, 186),  // BridgeInfill
            intArrayOf(77, 128, 186),  // InternalBridgeInfill
            intArrayOf(255, 255, 255), // GapFill
            intArrayOf(0, 135, 110),   // Skirt
            intArrayOf(0, 59, 110),    // Brim
            intArrayOf(0, 255, 0),     // SupportMaterial
            intArrayOf(0, 128, 0),     // SupportMaterialInterface
            intArrayOf(0, 64, 0),      // SupportTransition
            intArrayOf(179, 227, 171), // WipeTower
            intArrayOf(94, 209, 148),  // Custom
            intArrayOf(128, 128, 128), // Mixed
        )

        fun roleColor(role: Int): IntArray = ROLE_COLORS.getOrElse(role) { ROLE_COLORS[0] }

        internal fun toolpathVertices(tp: Toolpath): FloatArray {
            val out = FloatArray(tp.segmentCount * 12)
            val p = tp.positions
            for (i in 0 until tp.segmentCount) {
                val c = roleColor(tp.roles[i].toInt() and 0xFF)
                val r = c[0] / 255f
                val g = c[1] / 255f
                val b = c[2] / 255f
                val o = i * 12
                val s = i * 6
                out[o] = p[s]; out[o + 1] = p[s + 1]; out[o + 2] = p[s + 2]
                out[o + 3] = r; out[o + 4] = g; out[o + 5] = b
                out[o + 6] = p[s + 3]; out[o + 7] = p[s + 4]; out[o + 8] = p[s + 5]
                out[o + 9] = r; out[o + 10] = g; out[o + 11] = b
            }
            return out
        }

        /** De-indexes a mesh into interleaved position + face-normal vertices. */
        internal fun flatShaded(mesh: Mesh): FloatArray {
            val p = mesh.positions
            val idx = mesh.indices
            val tris = idx.size / 3
            val out = FloatArray(tris * 18)
            for (t in 0 until tris) {
                val a = idx[3 * t] * 3
                val b = idx[3 * t + 1] * 3
                val c = idx[3 * t + 2] * 3
                val ux = p[b] - p[a]; val uy = p[b + 1] - p[a + 1]; val uz = p[b + 2] - p[a + 2]
                val vx = p[c] - p[a]; val vy = p[c + 1] - p[a + 1]; val vz = p[c + 2] - p[a + 2]
                var nx = uy * vz - uz * vy
                var ny = uz * vx - ux * vz
                var nz = ux * vy - uy * vx
                val len = sqrt(nx * nx + ny * ny + nz * nz)
                if (len > 0f) { nx /= len; ny /= len; nz /= len }
                var o = t * 18
                for (v in intArrayOf(a, b, c)) {
                    out[o] = p[v]; out[o + 1] = p[v + 1]; out[o + 2] = p[v + 2]
                    out[o + 3] = nx; out[o + 4] = ny; out[o + 5] = nz
                    o += 6
                }
            }
            return out
        }
    }
}
