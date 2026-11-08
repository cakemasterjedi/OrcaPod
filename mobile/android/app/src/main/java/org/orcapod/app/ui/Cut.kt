package org.orcapod.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.orcapod.app.engine.ConnectorShape
import org.orcapod.app.engine.ConnectorStyle
import org.orcapod.app.engine.ConnectorType
import org.orcapod.app.engine.CutConnector
import org.orcapod.app.engine.CutKeep
import org.orcapod.app.engine.CutPlane
import org.orcapod.app.ui.viewport.Selection
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Desktop cut gizmo state: a plane through the object at [height] (world z),
 * tilted by [tiltX] and [tiltY] degrees, the part to keep, and connectors
 * placed on the plane. Connectors are kept in plane coordinates, so they
 * follow the plane as it moves, as on desktop, until the cut is performed.
 */
data class CutState(
    val selection: Selection,
    /** World bounding box of the instance: min xyz, max xyz. */
    val bounds: FloatArray,
    /** Instance offset; connector positions are relative to it. */
    val offset: DoubleArray,
    val height: Float,
    val tiltX: Float = 0f,
    val tiltY: Float = 0f,
    val keep: CutKeep = CutKeep.OBJECTS,
    /** Connector centres in plane coordinates (u, v), millimetres. */
    val connectors: List<Pair<Double, Double>> = emptyList(),
    val placing: Boolean = false,
    val type: ConnectorType = ConnectorType.PLUG,
    val style: ConnectorStyle = ConnectorStyle.PRISM,
    val shape: ConnectorShape = ConnectorShape.CIRCLE,
    /** Desktop defaults: 2.5 mm across, 3 mm deep, 0.1 mm depth clearance. */
    val size: Float = 2.5f,
    val depth: Float = 3f,
    val sizeTolerance: Float = 0f,
    val depthTolerance: Float = 0.1f,
) {
    val centerX get() = (bounds[0] + bounds[3]) / 2.0
    val centerY get() = (bounds[1] + bounds[4]) / 2.0

    /** Unit normal: +z tilted about x, then about y. */
    val normal: DoubleArray
        get() {
            val ax = Math.toRadians(tiltX.toDouble())
            val ay = Math.toRadians(tiltY.toDouble())
            // Ry(ay) * Rx(ax) * (0, 0, 1)
            val y = -sin(ax)
            val z0 = cos(ax)
            return doubleArrayOf(sin(ay) * z0, y, cos(ay) * z0)
        }

    val plane: CutPlane
        get() {
            val n = normal
            return CutPlane(centerX, centerY, height.toDouble(), n[0], n[1], n[2])
        }

    /** Two unit vectors spanning the plane. */
    private fun basis(): Pair<DoubleArray, DoubleArray> {
        val n = normal
        // u: world x projected onto the plane (y when the plane stands on x).
        val ref = if (kotlin.math.abs(n[0]) > 0.9) doubleArrayOf(0.0, 1.0, 0.0) else doubleArrayOf(1.0, 0.0, 0.0)
        val d = ref[0] * n[0] + ref[1] * n[1] + ref[2] * n[2]
        val u = DoubleArray(3) { ref[it] - d * n[it] }
        val lu = sqrt(u[0] * u[0] + u[1] * u[1] + u[2] * u[2])
        for (k in 0 until 3) u[k] /= lu
        val v = doubleArrayOf(n[1] * u[2] - n[2] * u[1], n[2] * u[0] - n[0] * u[2], n[0] * u[1] - n[1] * u[0])
        return u to v
    }

    fun worldOf(uv: Pair<Double, Double>): DoubleArray {
        val (u, v) = basis()
        val c = doubleArrayOf(centerX, centerY, height.toDouble())
        return DoubleArray(3) { c[it] + uv.first * u[it] + uv.second * v[it] }
    }

    /** Where the ray (origin, direction) meets the plane, in plane coordinates; null when it misses. */
    fun hit(o: FloatArray, d: FloatArray): Pair<Double, Double>? {
        val n = normal
        val c = doubleArrayOf(centerX, centerY, height.toDouble())
        val denom = n[0] * d[0] + n[1] * d[1] + n[2] * d[2]
        if (kotlin.math.abs(denom) < 1e-9) return null
        val t = ((c[0] - o[0]) * n[0] + (c[1] - o[1]) * n[1] + (c[2] - o[2]) * n[2]) / denom
        if (t < 0) return null
        val p = DoubleArray(3) { o[it] + t * d[it] - c[it] }
        val (u, v) = basis()
        return (p[0] * u[0] + p[1] * u[1] + p[2] * u[2]) to (p[0] * v[0] + p[1] * v[1] + p[2] * v[2])
    }

    /** Half the plane's side: enough to cover the object from any tilt. */
    val extent: Double
        get() {
            val dx = bounds[3] - bounds[0]
            val dy = bounds[4] - bounds[1]
            val dz = bounds[5] - bounds[2]
            return 0.6 * sqrt((dx * dx + dy * dy + dz * dz).toDouble())
        }

    /** The plane quad's corners, 12 floats, for the viewport. */
    fun quad(): FloatArray {
        val e = extent
        val out = FloatArray(12)
        for ((i, uv) in listOf(-e to -e, e to -e, e to e, -e to e).withIndex()) {
            val w = worldOf(uv)
            for (k in 0 until 3) out[i * 3 + k] = w[k].toFloat()
        }
        return out
    }

    fun connectorPoints(): FloatArray {
        val out = FloatArray(connectors.size * 3)
        for ((i, uv) in connectors.withIndex()) {
            val w = worldOf(uv)
            for (k in 0 until 3) out[i * 3 + k] = w[k].toFloat()
        }
        return out
    }

    /** The engine's connectors: world point on the plane minus the instance offset, as desktop stores them. */
    fun engineConnectors(): List<CutConnector> = connectors.map { uv ->
        val w = worldOf(uv)
        CutConnector(
            w[0] - offset[0], w[1] - offset[1], w[2] - offset[2],
            radius = size / 2, depth = depth, radiusTolerance = sizeTolerance / 2, depthTolerance = depthTolerance,
            type = type, style = style, shape = if (type == ConnectorType.SNAP) ConnectorShape.CIRCLE else shape,
        )
    }

    override fun equals(other: Any?) = other is CutState && selection == other.selection && bounds.contentEquals(other.bounds) &&
        offset.contentEquals(other.offset) && height == other.height && tiltX == other.tiltX && tiltY == other.tiltY &&
        keep == other.keep && connectors == other.connectors && placing == other.placing && type == other.type &&
        style == other.style && shape == other.shape && size == other.size && depth == other.depth &&
        sizeTolerance == other.sizeTolerance && depthTolerance == other.depthTolerance

    override fun hashCode() = selection.hashCode() * 31 + height.hashCode() + connectors.hashCode()
}

/** The cut tool's controls: plane height and tilt, what to keep, connectors. */
@Composable
fun CutPanel(vm: AppViewModel, cut: CutState) {
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp).heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            Text("Cut", style = MaterialTheme.typography.titleMedium)
            val lo = cut.bounds[2]
            val hi = cut.bounds[5]
            LabeledSlider(String.format(Locale.US, "Height %.2f mm", cut.height - lo), cut.height, lo..hi) {
                vm.cut = cut.copy(height = it)
            }
            LabeledSlider(String.format(Locale.US, "Tilt X %.0f°", cut.tiltX), cut.tiltX, -90f..90f) {
                vm.cut = cut.copy(tiltX = it)
            }
            LabeledSlider(String.format(Locale.US, "Tilt Y %.0f°", cut.tiltY), cut.tiltY, -90f..90f) {
                vm.cut = cut.copy(tiltY = it)
            }
            Text("Keep", style = MaterialTheme.typography.labelMedium)
            ChipRow(CutKeep.entries, cut.keep, { it.label }) { vm.cut = cut.copy(keep = it) }

            Spacer(Modifier.padding(top = 4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Connectors: ${cut.connectors.size}", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                if (cut.connectors.isNotEmpty()) TextButton(onClick = { vm.cut = cut.copy(connectors = emptyList()) }) { Text("Remove all") }
                FilterChip(
                    selected = cut.placing,
                    onClick = { vm.cut = cut.copy(placing = !cut.placing) },
                    label = { Text(if (cut.placing) "Tap the plane" else "Add") },
                )
            }
            if (cut.placing || cut.connectors.isNotEmpty()) {
                ChipRow(ConnectorType.entries, cut.type, { it.label }) { vm.cut = cut.copy(type = it) }
                ChipRow(ConnectorStyle.entries, cut.style, { it.label }) { vm.cut = cut.copy(style = it) }
                if (cut.type != ConnectorType.SNAP) ChipRow(ConnectorShape.entries, cut.shape, { it.label }) { vm.cut = cut.copy(shape = it) }
                LabeledSlider(String.format(Locale.US, "Size %.1f mm", cut.size), cut.size, 1f..20f) { vm.cut = cut.copy(size = it) }
                LabeledSlider(String.format(Locale.US, "Depth %.1f mm", cut.depth), cut.depth, 1f..20f) { vm.cut = cut.copy(depth = it) }
                LabeledSlider(String.format(Locale.US, "Size clearance %.2f mm", cut.sizeTolerance), cut.sizeTolerance, 0f..1f) {
                    vm.cut = cut.copy(sizeTolerance = it)
                }
                LabeledSlider(String.format(Locale.US, "Depth clearance %.2f mm", cut.depthTolerance), cut.depthTolerance, 0f..1f) {
                    vm.cut = cut.copy(depthTolerance = it)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = { vm.cut = null }) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { vm.performCut() }) { Text("Perform cut") }
            }
        }
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Text(label, style = MaterialTheme.typography.labelMedium)
    Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range)
}

@Composable
private fun <T> ChipRow(items: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        for (item in items) {
            FilterChip(selected = item == selected, onClick = { onPick(item) }, label = { Text(label(item)) }, modifier = Modifier.padding(end = 6.dp))
        }
    }
}
