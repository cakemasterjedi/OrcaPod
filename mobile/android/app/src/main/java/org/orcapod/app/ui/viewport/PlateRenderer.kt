package org.orcapod.app.ui.viewport

import android.opengl.GLES30.*
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import org.orcapod.app.engine.Mesh
import org.orcapod.app.engine.Plate
import org.orcapod.app.engine.Scene
import org.orcapod.app.engine.Toolpath
import org.orcapod.app.engine.TriMesh
import org.orcapod.app.engine.VolumeType
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
    /** Layers and moves of the toolpath shown. */
    val range: PreviewRange = PreviewRange(),
    /** Colours and visibility of the toolpath's moves. */
    val previewStyle: PreviewStyle = PreviewStyle(),
    val showToolpath: Boolean = false,
    /** Index of the plate the toolpath was sliced from; its G-code is in bed coordinates. */
    val toolpathPlate: Int = 0,
    /** The part or modifier highlighted in the object list. */
    val selectedVolume: Long? = null,
    /** Filament slot colours (#rrggbb); objects are drawn in their filament's colour when there are several. */
    val filamentColours: List<String> = emptyList(),
    /** The cut plane's four corners (12 floats), drawn translucent over the objects. */
    val cutQuad: FloatArray? = null,
    /** Cut connector centres, three floats each. */
    val cutPoints: FloatArray? = null,
    /** Painted triangles of the paint tool's layer: interleaved world position + colour, three vertices each. */
    val paintOverlay: FloatArray? = null,
    /** Measure tool lines (GL_LINES) and points: interleaved world position + colour. */
    val measureLines: FloatArray? = null,
    val measurePoints: FloatArray? = null,
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
    /** Modifier, negative part and support blocker/enforcer meshes, by volume id. */
    private val volumeMeshes = HashMap<Long, GpuMesh>()

    private var bedKey: List<Float>? = null
    private var bedFillVbo = 0
    private var bedFillCount = 0
    private var bedLineVbo = 0
    private var bedLineCount = 0

    private var toolpathSource: Pair<Toolpath, PreviewStyle>? = null
    private var toolpathGeometry: ToolpathGeometry? = null
    /** The toolpath's lines, [LINES_PER_VBO] to a buffer: one buffer of a big print's may not be allocatable. */
    private var toolpathVbos = IntArray(0)
    private var pointVbo = 0
    private var toolVbo = 0
    private var overlayVbo = 0

    private val mvp = FloatArray(16)
    private val model = FloatArray(16)
    private val tmp = FloatArray(16)
    private val normal3 = FloatArray(9)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        meshProgram = MeshProgram()
        colorProgram = ColorProgram()
        // A new EGL context drops every buffer; rebuild them lazily.
        meshes.clear()
        volumeMeshes.clear()
        bedKey = null
        toolpathSource = null
        toolpathGeometry = null
        toolpathVbos = IntArray(0)
        pointVbo = 0
        toolVbo = 0
        overlayVbo = 0
        glEnable(GL_DEPTH_TEST)
        glClearColor(0.043f, 0.071f, 0.125f, 1f)
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
        if (s.showToolpath && s.toolpath != null) {
            val plates = s.scene.snapshot.plates
            val p = plates.getOrNull(s.toolpathPlate)
            val base = plates.firstOrNull()
            val dx = if (p != null && base != null) p.origin[0] - base.origin[0] else 0f
            val dy = if (p != null && base != null) p.origin[1] - base.origin[1] else 0f
            drawToolpath(s.toolpath, s.previewStyle, s.range, dx, dy)
        } else {
            drawObjects(s)
            drawPaint(s)
            drawCut(s)
            drawMeasure(s)
        }
    }

    /** Uploads [data] (interleaved position + colour) into the shared overlay buffer and draws it. */
    private fun drawOverlay(data: FloatArray, mode: Int, alpha: Float) {
        if (data.isEmpty()) return
        if (overlayVbo == 0) overlayVbo = IntArray(1).also { glGenBuffers(1, it, 0) }[0]
        glBindBuffer(GL_ARRAY_BUFFER, overlayVbo)
        glBufferData(GL_ARRAY_BUFFER, data.size * 4, Gl.floatBuffer(data), GL_DYNAMIC_DRAW)
        glUseProgram(colorProgram.id)
        glUniformMatrix4fv(colorProgram.mvp, 1, false, camera.viewProjection, 0)
        glUniform1f(colorProgram.alpha, alpha)
        bindInterleaved(overlayVbo)
        glDrawArrays(mode, 0, data.size / 6)
    }

    private fun drawPaint(s: ViewportState) {
        val data = s.paintOverlay ?: return
        // Painted facets lie on the object's own faces; pull them towards the eye.
        glEnable(GL_POLYGON_OFFSET_FILL)
        glPolygonOffset(-1f, -2f)
        glDepthFunc(GL_LEQUAL)
        drawOverlay(data, GL_TRIANGLES, 1f)
        glDepthFunc(GL_LESS)
        glDisable(GL_POLYGON_OFFSET_FILL)
    }

    private fun drawCut(s: ViewportState) {
        val q = s.cutQuad ?: return
        val quad = FloatArray(36)
        for ((i, corner) in intArrayOf(0, 1, 2, 0, 2, 3).withIndex()) {
            for (k in 0 until 3) quad[i * 6 + k] = q[corner * 3 + k]
            quad[i * 6 + 3] = CUT_PLANE[0]; quad[i * 6 + 4] = CUT_PLANE[1]; quad[i * 6 + 5] = CUT_PLANE[2]
        }
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glDepthMask(false)
        drawOverlay(quad, GL_TRIANGLES, 0.35f)
        glDepthMask(true)
        glDisable(GL_BLEND)
        val p = s.cutPoints ?: return
        val pts = FloatArray(p.size * 2)
        for (i in 0 until p.size / 3) {
            for (k in 0 until 3) pts[i * 6 + k] = p[i * 3 + k]
            pts[i * 6 + 3] = CONNECTOR[0]; pts[i * 6 + 4] = CONNECTOR[1]; pts[i * 6 + 5] = CONNECTOR[2]
        }
        glDisable(GL_DEPTH_TEST)
        drawOverlay(pts, GL_POINTS, 1f)
        glEnable(GL_DEPTH_TEST)
    }

    /** Measured features and the measurement line, over everything as on desktop. */
    private fun drawMeasure(s: ViewportState) {
        glDisable(GL_DEPTH_TEST)
        s.measureLines?.let { glLineWidth(3f); drawOverlay(it, GL_LINES, 1f); glLineWidth(1f) }
        s.measurePoints?.let { drawOverlay(it, GL_POINTS, 1f) }
        glEnable(GL_DEPTH_TEST)
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
            val (r, g, b) = if (i == current) Triple(0.13f, 0.83f, 0.93f) else Triple(0.5f, 0.5f, 0.5f)
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

        val liveVolumes = snap.objects.flatMap { o -> o.volumes.map { it.id } }.toSet()
        volumeMeshes.keys.filter { it !in liveVolumes }.forEach { Gl.deleteVbo(volumeMeshes.remove(it)!!.vbo) }
        val colours = s.filamentColours.map { parseHex(it) }

        glUseProgram(meshProgram.id)
        glUniform3f(meshProgram.light, 0.3f, -0.5f, 0.81f)
        for (obj in snap.objects) {
            val mesh = s.scene.meshes[obj.id] ?: continue
            val gpu = gpuMesh(obj.id, mesh)
            if (gpu.vertexCount == 0) continue
            bindInterleaved(gpu.vbo)
            val parts = obj.volumes.filter { it.type == VolumeType.PART }
            for (inst in obj.instances) {
                setInstance(s, obj.id, inst.id, inst.matrix)
                val selected = s.selection?.let { it.objectId == obj.id && it.instanceId == inst.id } == true
                fun partColour(extruder: Int): FloatArray = when {
                    !inst.printable -> GREY
                    inst.plate < 0 -> OFF_PLATE
                    colours.size > 1 -> colours.getOrElse((if (extruder > 0) extruder else 1) - 1) { DEFAULT }
                    else -> DEFAULT
                }
                // Parts own triangle ranges of the merged mesh, so each draws in its filament's colour.
                if (parts.size <= 1 && obj.volumes.none { it.extruder > 0 }) {
                    colour(if (selected) SELECTED else partColour(obj.extruder))
                    glDrawArrays(GL_TRIANGLES, 0, gpu.vertexCount)
                } else for (part in parts) {
                    val first = part.mergedTriangleStart * 3
                    val count = minOf(part.mergedTriangleCount * 3, gpu.vertexCount - first)
                    if (count <= 0) continue
                    val c = when {
                        selected && s.selectedVolume == part.id -> HIGHLIGHT
                        selected && s.selectedVolume == null -> SELECTED
                        else -> partColour(part.extruder.takeIf { it > 0 } ?: obj.extruder)
                    }
                    colour(c)
                    glDrawArrays(GL_TRIANGLES, first, count)
                }
            }
        }
        drawVolumes(s, mesh = { s.scene.meshes[it] })
    }

    /** Modifiers, negative parts and support blockers/enforcers, translucent over the parts like desktop. */
    private fun drawVolumes(s: ViewportState, mesh: (Long) -> org.orcapod.app.engine.Mesh?) {
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glDepthMask(false)
        for (obj in s.scene.snapshot.objects) {
            val m = mesh(obj.id) ?: continue
            for (v in obj.volumes) {
                if (v.type == VolumeType.PART) continue
                val tri = m.volumes[v.id] ?: continue
                val gpu = volumeMesh(v.id, m.revision, tri)
                if (gpu.vertexCount == 0) continue
                bindInterleaved(gpu.vbo)
                val base = when (v.type) {
                    VolumeType.NEGATIVE -> NEGATIVE
                    VolumeType.MODIFIER -> MODIFIER
                    VolumeType.SUPPORT_BLOCKER -> BLOCKER
                    else -> ENFORCER
                }
                val c = if (s.selectedVolume == v.id) floatArrayOf(HIGHLIGHT[0], HIGHLIGHT[1], HIGHLIGHT[2], 0.6f) else base
                for (inst in obj.instances) {
                    setInstance(s, obj.id, inst.id, inst.matrix)
                    colour(c)
                    glDrawArrays(GL_TRIANGLES, 0, gpu.vertexCount)
                }
            }
        }
        glDepthMask(true)
        glDisable(GL_BLEND)
    }

    private fun setInstance(s: ViewportState, objectId: Long, instanceId: Long, matrix: FloatArray) {
        System.arraycopy(matrix, 0, model, 0, 16)
        val drag = s.drag
        if (drag != null && drag.objectId == objectId && drag.instanceId == instanceId) {
            model[12] += drag.dx
            model[13] += drag.dy
        }
        Matrix.multiplyMM(mvp, 0, camera.viewProjection, 0, model, 0)
        glUniformMatrix4fv(meshProgram.mvp, 1, false, mvp, 0)
        normalMatrix(model)
        glUniformMatrix3fv(meshProgram.normalMat, 1, false, normal3, 0)
    }

    private fun colour(c: FloatArray) = glUniform4f(meshProgram.color, c[0], c[1], c[2], c.getOrElse(3) { 1f })

    private fun volumeMesh(id: Long, revision: Long, tri: TriMesh): GpuMesh {
        volumeMeshes[id]?.let { if (it.revision == revision) return it else Gl.deleteVbo(it.vbo) }
        val data = flatShaded(tri.positions, tri.indices)
        val gpu = GpuMesh(revision, if (data.isEmpty()) 0 else Gl.vbo(data), data.size / 6)
        volumeMeshes[id] = gpu
        return gpu
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

    private fun drawToolpath(tp: Toolpath, style: PreviewStyle, range: PreviewRange, dx: Float, dy: Float) {
        val key = tp to style
        if (key != toolpathSource) {
            deleteToolpathBuffers()
            toolpathSource = key
            toolpathGeometry = uploadToolpath(tp, style)
        }
        val g = toolpathGeometry ?: return
        if (tp.layerTotal == 0) return
        glUseProgram(colorProgram.id)
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, dx, dy, 0f)
        Matrix.multiplyMM(mvp, 0, camera.viewProjection, 0, model, 0)
        glUniformMatrix4fv(colorProgram.mvp, 1, false, mvp, 0)
        glUniform1f(colorProgram.alpha, 1f)
        val lines = g.lineRange(tp, range)
        if (!lines.isEmpty()) {
            glLineWidth(2f)
            for ((k, vbo) in toolpathVbos.withIndex()) {
                val base = k * LINES_PER_VBO
                val first = maxOf(lines.first, base)
                val last = minOf(lines.last, base + LINES_PER_VBO - 1)
                if (first > last) continue
                bindInterleaved(vbo)
                glDrawArrays(GL_LINES, (first - base) * 2, (last - first + 1) * 2)
            }
            glLineWidth(1f)
        }
        val points = g.pointRange(tp, range)
        if (pointVbo != 0 && !points.isEmpty()) {
            bindInterleaved(pointVbo)
            glDrawArrays(GL_POINTS, points.first, points.last - points.first + 1)
        }
        // Desktop tool marker: where the nozzle is at the move slider's position.
        val hi = range.high.coerceIn(0, tp.layerTotal - 1)
        if (range.moves in 1 until tp.layerCount[hi]) {
            val s = (tp.layerFirst[hi] + range.moves - 1) * 6
            val p = tp.positions
            val tool = floatArrayOf(p[s + 3], p[s + 4], p[s + 5], 1f, 1f, 1f)
            if (toolVbo == 0) toolVbo = Gl.vbo(tool) else {
                glBindBuffer(GL_ARRAY_BUFFER, toolVbo)
                glBufferSubData(GL_ARRAY_BUFFER, 0, 24, Gl.floatBuffer(tool))
            }
            glDisable(GL_DEPTH_TEST)
            bindInterleaved(toolVbo)
            glDrawArrays(GL_POINTS, 0, 1)
            glEnable(GL_DEPTH_TEST)
        }
    }

    private fun deleteToolpathBuffers() {
        for (vbo in toolpathVbos) Gl.deleteVbo(vbo)
        Gl.deleteVbo(pointVbo)
        toolpathVbos = IntArray(0)
        pointVbo = 0
        toolpathGeometry = null
    }

    /**
     * Builds [tp]'s geometry for [style] and uploads it, a run of lines at a
     * time, so the Java heap never holds a copy of every vertex. Returns null,
     * drawing no toolpath rather than ending the app from the render thread,
     * when the print is too large for the heap or the GPU.
     */
    private fun uploadToolpath(tp: Toolpath, style: PreviewStyle): ToolpathGeometry? {
        try {
            val g = ToolpathGeometry.build(tp, style)
            repeat(8) { glGetError() } // clear earlier errors, so the check below is this upload's
            val run = FloatArray(UPLOAD_LINES * ToolpathGeometry.FLOATS_PER_LINE)
            val staging = java.nio.ByteBuffer.allocateDirect(run.size * 4)
                .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
            val buffers = (g.lineCount + LINES_PER_VBO - 1) / LINES_PER_VBO
            toolpathVbos = IntArray(buffers)
            if (buffers > 0) glGenBuffers(buffers, toolpathVbos, 0)
            for ((k, vbo) in toolpathVbos.withIndex()) {
                val base = k * LINES_PER_VBO
                val count = minOf(LINES_PER_VBO, g.lineCount - base)
                glBindBuffer(GL_ARRAY_BUFFER, vbo)
                glBufferData(GL_ARRAY_BUFFER, count * ToolpathGeometry.FLOATS_PER_LINE * 4, null, GL_STATIC_DRAW)
                var done = 0
                while (done < count) {
                    val n = minOf(UPLOAD_LINES, count - done)
                    g.writeLines(base + done, n, run)
                    staging.clear()
                    staging.put(run, 0, n * ToolpathGeometry.FLOATS_PER_LINE)
                    staging.position(0)
                    glBufferSubData(
                        GL_ARRAY_BUFFER, done * ToolpathGeometry.FLOATS_PER_LINE * 4,
                        n * ToolpathGeometry.FLOATS_PER_LINE * 4, staging,
                    )
                    done += n
                }
            }
            glBindBuffer(GL_ARRAY_BUFFER, 0)
            if (g.points.isNotEmpty()) pointVbo = Gl.vbo(g.points)
            val error = glGetError()
            if (error == GL_OUT_OF_MEMORY) {
                android.util.Log.w("OrcaPod", "toolpath of ${g.lineCount} lines does not fit in GPU memory")
                deleteToolpathBuffers()
                return null
            }
            return g
        } catch (ex: OutOfMemoryError) {
            android.util.Log.w("OrcaPod", "toolpath too large to draw", ex)
            deleteToolpathBuffers()
            return null
        }
    }

    companion object {
        /** Lines per toolpath buffer: 12 MB of vertices. */
        private const val LINES_PER_VBO = 1 shl 18
        /** Lines written and uploaded at a time. */
        private const val UPLOAD_LINES = 1 shl 14

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

        private val DEFAULT = floatArrayOf(0.2f, 0.72f, 0.42f, 1f)
        private val SELECTED = floatArrayOf(1f, 0.62f, 0.15f, 1f)
        private val HIGHLIGHT = floatArrayOf(1f, 0.85f, 0.2f, 1f)
        private val GREY = floatArrayOf(0.45f, 0.45f, 0.45f, 1f)
        private val OFF_PLATE = floatArrayOf(0.75f, 0.3f, 0.3f, 1f)
        private val NEGATIVE = floatArrayOf(0.6f, 0.6f, 0.6f, 0.45f)
        private val MODIFIER = floatArrayOf(0.95f, 0.8f, 0.25f, 0.35f)
        private val BLOCKER = floatArrayOf(0.9f, 0.25f, 0.25f, 0.4f)
        private val ENFORCER = floatArrayOf(0.25f, 0.45f, 0.95f, 0.4f)
        private val CUT_PLANE = floatArrayOf(0.95f, 0.55f, 0.2f)
        private val CONNECTOR = floatArrayOf(1f, 1f, 1f)

        internal fun parseHex(hex: String): FloatArray {
            val v = hex.trim().removePrefix("#").take(6).toIntOrNull(16) ?: return DEFAULT
            return floatArrayOf(((v shr 16) and 0xFF) / 255f, ((v shr 8) and 0xFF) / 255f, (v and 0xFF) / 255f, 1f)
        }

        internal fun flatShaded(mesh: Mesh): FloatArray = flatShaded(mesh.positions, mesh.indices)

        /** De-indexes a mesh into interleaved position + face-normal vertices, keeping triangle order. */
        internal fun flatShaded(p: FloatArray, idx: IntArray): FloatArray {
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
