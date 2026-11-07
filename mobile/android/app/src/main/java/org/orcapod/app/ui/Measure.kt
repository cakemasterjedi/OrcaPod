package org.orcapod.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.orcapod.app.engine.FeatureType
import org.orcapod.app.engine.Measurement
import org.orcapod.app.engine.SurfaceFeature
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Desktop's measure tool on [objectId]: up to two picked features, in world
 * space, and the engine's measurement between them.
 */
data class MeasureState(
    val objectId: Long,
    val first: SurfaceFeature? = null,
    val second: SurfaceFeature? = null,
    val measurement: Measurement? = null,
) {
    /** The next pick: the second feature, or a new first one once both are set. */
    fun pick(feature: SurfaceFeature): MeasureState =
        if (first == null || second != null) MeasureState(objectId, feature) else copy(second = feature)

    /** Feature outlines and the measurement line, interleaved position + colour for GL_LINES. */
    fun lines(): FloatArray? {
        val out = ArrayList<Float>()
        first?.let { outline(it, FIRST, out) }
        second?.let { outline(it, SECOND, out) }
        val m = measurement
        if (m?.from != null && m.to != null) segment(m.from, m.to, LINE, out)
        return if (out.isEmpty()) null else out.toFloatArray()
    }

    /** Feature anchors, interleaved for GL_POINTS. */
    fun points(): FloatArray? {
        val out = ArrayList<Float>()
        for ((f, c) in listOf(first to FIRST, second to SECOND)) if (f != null) vertex(f.a, c, out)
        return if (out.isEmpty()) null else out.toFloatArray()
    }

    companion object {
        /** Desktop's first and second selection colours and the dimension line. */
        val FIRST = floatArrayOf(1f, 0.6f, 0.1f)
        val SECOND = floatArrayOf(0.2f, 0.8f, 1f)
        val LINE = floatArrayOf(1f, 1f, 0.2f)

        private fun vertex(p: DoubleArray, c: FloatArray, out: MutableList<Float>) {
            out += p[0].toFloat(); out += p[1].toFloat(); out += p[2].toFloat()
            out += c[0]; out += c[1]; out += c[2]
        }

        private fun segment(a: DoubleArray, b: DoubleArray, c: FloatArray, out: MutableList<Float>) {
            vertex(a, c, out); vertex(b, c, out)
        }

        private fun outline(f: SurfaceFeature, c: FloatArray, out: MutableList<Float>) {
            when (f.type) {
                FeatureType.POINT -> Unit
                FeatureType.EDGE -> segment(f.a, f.b, c, out)
                FeatureType.CIRCLE -> {
                    val (u, v) = basis(f.normal)
                    val steps = 48
                    fun at(i: Int): DoubleArray {
                        val t = 2 * Math.PI * i / steps
                        return DoubleArray(3) { f.a[it] + f.radius * (cos(t) * u[it] + sin(t) * v[it]) }
                    }
                    for (i in 0 until steps) segment(at(i), at(i + 1), c, out)
                }
                // The plane's normal from its anchor, as a short stalk.
                FeatureType.PLANE -> segment(f.a, DoubleArray(3) { f.a[it] + 5 * f.normal[it] }, c, out)
            }
        }

        private fun basis(n: DoubleArray): Pair<DoubleArray, DoubleArray> {
            val ref = if (kotlin.math.abs(n[0]) < 0.9) doubleArrayOf(1.0, 0.0, 0.0) else doubleArrayOf(0.0, 1.0, 0.0)
            val u = doubleArrayOf(n[1] * ref[2] - n[2] * ref[1], n[2] * ref[0] - n[0] * ref[2], n[0] * ref[1] - n[1] * ref[0])
            val l = sqrt(u[0] * u[0] + u[1] * u[1] + u[2] * u[2])
            for (k in 0 until 3) u[k] /= l
            val v = doubleArrayOf(n[1] * u[2] - n[2] * u[1], n[2] * u[0] - n[0] * u[2], n[0] * u[1] - n[1] * u[0])
            return u to v
        }
    }
}

/** A feature as desktop's measure panel names it. */
fun featureText(f: SurfaceFeature): String = when (f.type) {
    FeatureType.POINT -> String.format(Locale.US, "Point (%.2f, %.2f, %.2f)", f.a[0], f.a[1], f.a[2])
    FeatureType.EDGE -> {
        val l = sqrt((0 until 3).sumOf { (f.b[it] - f.a[it]).let { d -> d * d } })
        String.format(Locale.US, "Edge, length %.2f mm", l)
    }
    FeatureType.CIRCLE -> String.format(Locale.US, "Circle, diameter %.2f mm", 2 * f.radius)
    FeatureType.PLANE -> String.format(Locale.US, "Plane, area %.1f mm²", f.area)
}

@Composable
fun MeasurePanel(vm: AppViewModel, m: MeasureState) {
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text("Measure", style = MaterialTheme.typography.titleMedium)
            Text(
                when {
                    m.first == null -> "Tap a point, edge, circle or face of the object."
                    m.second == null -> "Tap a second feature to measure between them."
                    else -> "Tap again to start a new measurement."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            m.first?.let { Text("1: ${featureText(it)}", style = MaterialTheme.typography.bodyMedium) }
            m.second?.let { Text("2: ${featureText(it)}", style = MaterialTheme.typography.bodyMedium) }
            val r = m.measurement
            if (r != null) {
                r.distance?.let { Text(String.format(Locale.US, "Distance %.3f mm", it), style = MaterialTheme.typography.titleSmall) }
                r.xyz?.let {
                    Text(
                        String.format(Locale.US, "X %.3f  Y %.3f  Z %.3f mm", kotlin.math.abs(it[0]), kotlin.math.abs(it[1]), kotlin.math.abs(it[2])),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                r.angleDegrees?.let { Text(String.format(Locale.US, "Angle %.2f°", it), style = MaterialTheme.typography.bodyMedium) }
            } else if (m.second != null) {
                Text("No measurement between these features.", style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = { vm.measure = MeasureState(m.objectId) }, enabled = m.first != null) { Text("Restart") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { vm.endMeasure() }) { Text("Done") }
            }
        }
    }
}
