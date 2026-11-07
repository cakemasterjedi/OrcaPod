package org.orcapod.app.ui.viewport

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import org.orcapod.app.engine.Mesh
import org.orcapod.app.engine.Scene
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.hypot

/** What a tap or drag in the viewport asks the app to do. */
interface ViewportListener {
    /** Tap: null when the tap hit no object. */
    fun onSelect(selection: Selection?)
    /** An object was dragged across the bed by (dx, dy) millimetres. */
    fun onMoved(selection: Selection, dx: Double, dy: Double)
    /** "Lay on face" tap: the world-space normal of the tapped face. */
    fun onFacePicked(selection: Selection, nx: Double, ny: Double, nz: Double)
    /** A tap while [PlateView.rayTaps] is set: the world ray under the finger. */
    fun onRay(origin: FloatArray, direction: FloatArray) {}
    /**
     * A paint dab on [PlateView.paintObject]: the part under the finger, the
     * world hit point and the viewing direction. [first] marks a new stroke.
     */
    fun onPaint(objectId: Long, volumeId: Long, hit: FloatArray, view: FloatArray, first: Boolean) {}
    /** A tap on [PlateView.measureObject]: the merged-mesh triangle and the object-space point under the finger. */
    fun onMeasure(selection: Selection, triangle: Int, local: FloatArray) {}
}

/**
 * The 3D build plate: one finger on an object drags it over the bed, one
 * finger elsewhere orbits, two fingers pinch to zoom and pan.
 */
@SuppressLint("ViewConstructor")
class PlateView(context: Context, private val listener: ViewportListener) : GLSurfaceView(context) {
    private val camera = Camera()
    private val renderer = PlateRenderer(camera)
    private var framedBed: List<Float>? = null

    /** When set, a tap on an object reports the tapped face instead of selecting. */
    var pickFaces = false
    /** When set, a tap reports its world ray (cut connector placement) instead of selecting. */
    var rayTaps = false
    /** When set, objects cannot be dragged (a tool such as the cut is open). */
    var lockDrag = false
    /** The object being painted: a finger on it paints instead of orbiting. */
    var paintObject: Long? = null
    /** The object being measured: a tap on it picks a feature instead of selecting. */
    var measureObject: Long? = null

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 24, 0)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    var state: ViewportState
        get() = renderer.state
        set(value) {
            val plates = value.scene.snapshot.plates
            val bed = plates.firstOrNull()?.let { listOf(it.origin[0], it.origin[1], it.size[0], it.size[1]) }
            if (bed != null && bed != framedBed) {
                framedBed = bed
                camera.frame(bed[0] + bed[2] / 2, bed[1] + bed[3] / 2, maxOf(bed[2], bed[3]))
            }
            renderer.state = value
            requestRender()
        }

    /** Re-frames the camera on the first plate. */
    fun resetView() {
        framedBed = null
        state = state
    }

    // --- gestures ---

    private enum class Mode { NONE, ORBIT, DRAG_OBJECT, MULTI, PAINT }

    private var mode = Mode.NONE
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var dragTarget: Selection? = null
    private var dragStart: FloatArray? = null // bed-plane point under the finger at touch down
    private var dragPlaneZ = 0f
    private var lastFocusX = 0f
    private var lastFocusY = 0f

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            camera.zoom(detector.scaleFactor)
            requestRender()
            return true
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y
                moved = false
                val hit = pick(e.x, e.y)
                val sel = state.selection
                if (paintObject != null && hit != null && hit.selection.objectId == paintObject) {
                    mode = Mode.PAINT
                    paintAt(hit, e.x, e.y, first = true)
                } else if (!pickFaces && !lockDrag && hit != null && sel != null && hit.selection == sel) {
                    // Drag the already selected object over a plane at the touched height.
                    mode = Mode.DRAG_OBJECT
                    dragTarget = hit.selection
                    dragPlaneZ = hit.point[2]
                    dragStart = planePoint(e.x, e.y, dragPlaneZ)
                } else {
                    mode = Mode.ORBIT
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (mode == Mode.DRAG_OBJECT) cancelDrag()
                lastPaint = null
                mode = Mode.MULTI
                lastFocusX = focusX(e); lastFocusY = focusY(e)
                moved = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!moved && hypot(e.x - downX, e.y - downY) > touchSlop) moved = true
                when (mode) {
                    Mode.ORBIT -> if (moved) {
                        camera.orbit(e.x - lastX, e.y - lastY)
                        requestRender()
                    }
                    Mode.DRAG_OBJECT -> if (moved) {
                        val start = dragStart
                        val now = planePoint(e.x, e.y, dragPlaneZ)
                        val t = dragTarget
                        if (start != null && now != null && t != null) {
                            renderer.state = state.copy(drag = Drag(t.objectId, t.instanceId, now[0] - start[0], now[1] - start[1]))
                            requestRender()
                        }
                    }
                    Mode.PAINT -> {
                        val last = lastPaint
                        if (last == null || hypot(e.x - last[0], e.y - last[1]) > PAINT_SPACING_PX) {
                            pick(e.x, e.y)?.takeIf { it.selection.objectId == paintObject }?.let { paintAt(it, e.x, e.y, first = false) }
                        }
                    }
                    Mode.MULTI -> if (e.pointerCount >= 2) {
                        val fx = focusX(e); val fy = focusY(e)
                        camera.pan(fx - lastFocusX, fy - lastFocusY)
                        lastFocusX = fx; lastFocusY = fy
                        requestRender()
                    }
                    Mode.NONE -> Unit
                }
                lastX = e.x; lastY = e.y
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // Keep panning with the remaining fingers; after the last, nothing until a new touch.
                if (e.pointerCount > 2) {
                    lastFocusX = focusX(e, e.actionIndex); lastFocusY = focusY(e, e.actionIndex)
                } else {
                    mode = Mode.NONE
                }
            }
            MotionEvent.ACTION_UP -> {
                if (mode == Mode.PAINT) { mode = Mode.NONE; lastPaint = null; return true }
                if (!moved) tap(e.x, e.y)
                else if (mode == Mode.DRAG_OBJECT) finishDrag()
                mode = Mode.NONE
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelDrag()
                mode = Mode.NONE
            }
        }
        return true
    }

    private var lastPaint: FloatArray? = null

    private fun paintAt(hit: Hit, x: Float, y: Float, first: Boolean) {
        val obj = state.scene.snapshot.objects.firstOrNull { it.id == hit.selection.objectId } ?: return
        val part = obj.parts.firstOrNull { hit.triangle >= it.mergedTriangleStart && hit.triangle < it.mergedTriangleStart + it.mergedTriangleCount }
            ?: return
        val r = camera.ray(x, y)
        listener.onPaint(obj.id, part.id, hit.point, floatArrayOf(r[3], r[4], r[5]), first)
        lastPaint = floatArrayOf(x, y)
    }

    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private fun focusX(e: MotionEvent, skip: Int = -1): Float = focus(e, skip) { e.getX(it) }
    private fun focusY(e: MotionEvent, skip: Int = -1): Float = focus(e, skip) { e.getY(it) }
    private inline fun focus(e: MotionEvent, skip: Int, coord: (Int) -> Float): Float {
        var sum = 0f
        var n = 0
        for (i in 0 until e.pointerCount) if (i != skip) { sum += coord(i); n++ }
        return if (n == 0) 0f else sum / n
    }

    private fun tap(x: Float, y: Float) {
        if (rayTaps) {
            val r = camera.ray(x, y)
            listener.onRay(r.copyOfRange(0, 3), r.copyOfRange(3, 6))
            return
        }
        val hit = pick(x, y)
        if (measureObject != null) {
            if (hit != null && hit.selection.objectId == measureObject) listener.onMeasure(hit.selection, hit.triangle, hit.local)
            return
        }
        if (pickFaces && hit != null) {
            listener.onFacePicked(hit.selection, hit.normal[0].toDouble(), hit.normal[1].toDouble(), hit.normal[2].toDouble())
        } else {
            listener.onSelect(hit?.selection)
        }
    }

    private fun finishDrag() {
        val d = renderer.state.drag
        if (d != null && (abs(d.dx) > 0.01f || abs(d.dy) > 0.01f)) {
            // Keep drawing the dragged position until the engine's snapshot replaces it.
            listener.onMoved(Selection(d.objectId, d.instanceId), d.dx.toDouble(), d.dy.toDouble())
        } else {
            cancelDrag()
        }
    }

    private fun cancelDrag() {
        if (renderer.state.drag != null) {
            renderer.state = renderer.state.copy(drag = null)
            requestRender()
        }
    }

    private fun planePoint(x: Float, y: Float, z: Float): FloatArray? {
        val r = camera.ray(x, y)
        if (abs(r[5]) < 1e-6f) return null
        val t = (z - r[2]) / r[5]
        if (t < 0f) return null
        return floatArrayOf(r[0] + t * r[3], r[1] + t * r[4], z)
    }

    // --- picking ---

    private class Hit(
        val selection: Selection, val t: Float, val point: FloatArray, val normal: FloatArray, val triangle: Int,
        /** The hit in object space. */
        val local: FloatArray,
    )

    private val boundsCache = WeakHashMap<Mesh, FloatArray>()

    private fun pick(x: Float, y: Float): Hit? {
        val scene: Scene = state.scene
        val ray = camera.ray(x, y)
        val inv = FloatArray(16)
        val o = FloatArray(4)
        val d = FloatArray(4)
        var best: Hit? = null
        for (obj in scene.snapshot.objects) {
            val mesh = scene.meshes[obj.id] ?: continue
            val bounds = boundsCache.getOrPut(mesh) { bounds(mesh) }
            for (inst in obj.instances) {
                if (!Matrix.invertM(inv, 0, inst.matrix, 0)) continue
                // The ray in object space; t stays comparable with the world ray.
                Matrix.multiplyMV(o, 0, inv, 0, floatArrayOf(ray[0], ray[1], ray[2], 1f), 0)
                Matrix.multiplyMV(d, 0, inv, 0, floatArrayOf(ray[3], ray[4], ray[5], 0f), 0)
                if (!hitsBox(o, d, bounds, best?.t ?: Float.MAX_VALUE)) continue
                val tri = intersect(mesh, o, d, best?.t ?: Float.MAX_VALUE) ?: continue
                val t = tri.first
                val point = floatArrayOf(ray[0] + t * ray[3], ray[1] + t * ray[4], ray[2] + t * ray[5])
                val local = floatArrayOf(o[0] + t * d[0], o[1] + t * d[1], o[2] + t * d[2])
                best = Hit(Selection(obj.id, inst.id), t, point, worldNormal(mesh, tri.second, inv), tri.second, local)
            }
        }
        return best
    }

    private fun bounds(mesh: Mesh): FloatArray {
        val p = mesh.positions
        val b = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (i in p.indices step 3) for (k in 0 until 3) {
            if (p[i + k] < b[k]) b[k] = p[i + k]
            if (p[i + k] > b[k + 3]) b[k + 3] = p[i + k]
        }
        return b
    }

    private fun hitsBox(o: FloatArray, d: FloatArray, b: FloatArray, maxT: Float): Boolean {
        var t0 = 0f
        var t1 = maxT
        for (k in 0 until 3) {
            if (abs(d[k]) < 1e-12f) {
                if (o[k] < b[k] || o[k] > b[k + 3]) return false
                continue
            }
            var a = (b[k] - o[k]) / d[k]
            var c = (b[k + 3] - o[k]) / d[k]
            if (a > c) { val s = a; a = c; c = s }
            if (a > t0) t0 = a
            if (c < t1) t1 = c
            if (t0 > t1) return false
        }
        return true
    }

    /** Möller–Trumbore over every triangle; returns (t, triangle index) of the nearest hit. */
    private fun intersect(mesh: Mesh, o: FloatArray, d: FloatArray, maxT: Float): Pair<Float, Int>? {
        val p = mesh.positions
        val idx = mesh.indices
        var bestT = maxT
        var bestTri = -1
        for (t in 0 until idx.size / 3) {
            val a = idx[3 * t] * 3
            val b = idx[3 * t + 1] * 3
            val c = idx[3 * t + 2] * 3
            val e1x = p[b] - p[a]; val e1y = p[b + 1] - p[a + 1]; val e1z = p[b + 2] - p[a + 2]
            val e2x = p[c] - p[a]; val e2y = p[c + 1] - p[a + 1]; val e2z = p[c + 2] - p[a + 2]
            val px = d[1] * e2z - d[2] * e2y
            val py = d[2] * e2x - d[0] * e2z
            val pz = d[0] * e2y - d[1] * e2x
            val det = e1x * px + e1y * py + e1z * pz
            if (abs(det) < 1e-12f) continue
            val inv = 1f / det
            val sx = o[0] - p[a]; val sy = o[1] - p[a + 1]; val sz = o[2] - p[a + 2]
            val u = (sx * px + sy * py + sz * pz) * inv
            if (u < 0f || u > 1f) continue
            val qx = sy * e1z - sz * e1y
            val qy = sz * e1x - sx * e1z
            val qz = sx * e1y - sy * e1x
            val v = (d[0] * qx + d[1] * qy + d[2] * qz) * inv
            if (v < 0f || u + v > 1f) continue
            val dist = (e2x * qx + e2y * qy + e2z * qz) * inv
            if (dist > 0f && dist < bestT) {
                bestT = dist
                bestTri = t
            }
        }
        return if (bestTri < 0) null else bestT to bestTri
    }

    /** Face normal in world space: inverse-transpose of the instance matrix applied to the object normal. */
    private fun worldNormal(mesh: Mesh, tri: Int, inv: FloatArray): FloatArray {
        val p = mesh.positions
        val a = mesh.indices[3 * tri] * 3
        val b = mesh.indices[3 * tri + 1] * 3
        val c = mesh.indices[3 * tri + 2] * 3
        val ux = p[b] - p[a]; val uy = p[b + 1] - p[a + 1]; val uz = p[b + 2] - p[a + 2]
        val vx = p[c] - p[a]; val vy = p[c + 1] - p[a + 1]; val vz = p[c + 2] - p[a + 2]
        val n = floatArrayOf(uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx)
        val w = FloatArray(3)
        for (r in 0 until 3) w[r] = inv[r * 4] * n[0] + inv[r * 4 + 1] * n[1] + inv[r * 4 + 2] * n[2]
        val len = hypot(hypot(w[0], w[1]), w[2])
        if (len > 0f) for (k in 0 until 3) w[k] /= len
        return w
    }

    private companion object {
        /** Finger travel between brush dabs; each dab is one engine stroke. */
        const val PAINT_SPACING_PX = 24f
    }
}
