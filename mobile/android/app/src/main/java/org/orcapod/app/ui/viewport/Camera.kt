package org.orcapod.app.ui.viewport

import android.opengl.Matrix
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/** Orbit camera around a target point, engine z-up world. Angles in radians. */
class Camera {
    var targetX = 0f
    var targetY = 0f
    var targetZ = 0f
    var yaw = Math.toRadians(-60.0).toFloat()
    var pitch = Math.toRadians(35.0).toFloat()
    var distance = 400f
    private val fovY = 40f

    val view = FloatArray(16)
    val projection = FloatArray(16)
    val viewProjection = FloatArray(16)
    private val inverse = FloatArray(16)
    var width = 1
        private set
    var height = 1
        private set

    fun resize(w: Int, h: Int) {
        width = max(w, 1)
        height = max(h, 1)
    }

    /** Frames a bed of the given size, looking at its centre from the front-left. */
    fun frame(cx: Float, cy: Float, size: Float) {
        targetX = cx
        targetY = cy
        targetZ = 0f
        distance = size * 1.9f
        yaw = Math.toRadians(-90.0).toFloat()
        pitch = Math.toRadians(40.0).toFloat()
    }

    fun orbit(dxPixels: Float, dyPixels: Float) {
        yaw -= dxPixels * 0.008f
        pitch = (pitch + dyPixels * 0.008f).coerceIn(Math.toRadians(-89.0).toFloat(), Math.toRadians(89.0).toFloat())
    }

    fun zoom(factor: Float) {
        distance = (distance / factor).coerceIn(10f, 5000f)
    }

    /** Pans so the target follows the fingers in the view plane. */
    fun pan(dxPixels: Float, dyPixels: Float) {
        val worldPerPixel = 2f * distance * tan(Math.toRadians(fovY / 2.0).toFloat()) / height
        val rightX = -sin(yaw)
        val rightY = cos(yaw)
        // Camera up projected: -sin(pitch) * forward-ish + cos(pitch) * z.
        val upX = -sin(pitch) * cos(yaw)
        val upY = -sin(pitch) * sin(yaw)
        val upZ = cos(pitch)
        targetX += (-dxPixels * rightX + dyPixels * upX) * worldPerPixel
        targetY += (-dxPixels * rightY + dyPixels * upY) * worldPerPixel
        targetZ += (dyPixels * upZ) * worldPerPixel
    }

    fun update() {
        val ex = targetX + distance * cos(pitch) * cos(yaw)
        val ey = targetY + distance * cos(pitch) * sin(yaw)
        val ez = targetZ + distance * sin(pitch)
        Matrix.setLookAtM(view, 0, ex, ey, ez, targetX, targetY, targetZ, 0f, 0f, 1f)
        val near = max(distance / 200f, 0.5f)
        val far = distance * 20f + 2000f
        Matrix.perspectiveM(projection, 0, fovY, width.toFloat() / height, near, min(far, 100000f))
        Matrix.multiplyMM(viewProjection, 0, projection, 0, view, 0)
        Matrix.invertM(inverse, 0, viewProjection, 0)
    }

    /** World-space ray through a screen pixel: origin xyz, direction xyz (not normalized). */
    fun ray(px: Float, py: Float): FloatArray {
        update()
        val nx = 2f * px / width - 1f
        val ny = 1f - 2f * py / height
        val near = unproject(nx, ny, -1f)
        val far = unproject(nx, ny, 1f)
        return floatArrayOf(near[0], near[1], near[2], far[0] - near[0], far[1] - near[1], far[2] - near[2])
    }

    private fun unproject(x: Float, y: Float, z: Float): FloatArray {
        val out = FloatArray(4)
        Matrix.multiplyMV(out, 0, inverse, 0, floatArrayOf(x, y, z, 1f), 0)
        return floatArrayOf(out[0] / out[3], out[1] / out[3], out[2] / out[3])
    }
}
