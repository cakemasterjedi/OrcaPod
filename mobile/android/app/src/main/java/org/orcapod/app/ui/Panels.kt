package org.orcapod.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.orcapod.app.engine.Mesh
import org.orcapod.app.ui.viewport.Selection
import java.util.Locale
import kotlin.math.roundToInt

/** Position, rotation and scale of the selected instance, plus the object actions. */
@Composable
fun ObjectPanel(vm: AppViewModel, sel: Selection, layFlat: Boolean, onLayFlat: () -> Unit) {
    val obj = vm.scene.snapshot.objects.firstOrNull { it.id == sel.objectId } ?: return
    val inst = obj.instances.firstOrNull { it.id == sel.instanceId } ?: return
    val t = inst.transform
    val mesh = vm.scene.meshes[obj.id]
    val size = remember(mesh, inst.matrix) { mesh?.let { worldSize(it, inst.matrix) } }

    Card {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(obj.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (size != null) {
                        Text(
                            String.format(Locale.US, "%.1f × %.1f × %.1f mm", size[0], size[1], size[2]) +
                                if (inst.plate < 0) " · off the plate" else "",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                IconButton(onClick = { vm.selection = null }) { Icon(Icons.Filled.Close, "Deselect") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(fmt(t.offsetX), Modifier.weight(1f), label = "X", suffix = "mm") {
                    vm.setTransform(sel, t.copy(offsetX = it.toDouble()), "Move")
                }
                NumberField(fmt(t.offsetY), Modifier.weight(1f), label = "Y", suffix = "mm") {
                    vm.setTransform(sel, t.copy(offsetY = it.toDouble()), "Move")
                }
                NumberField(fmt(t.scaleX * 100), Modifier.weight(1f), label = "Scale", suffix = "%") {
                    val f = it.toDouble() / 100
                    if (f > 0) {
                        // Uniform scale keeps the instance's axis ratios.
                        val k = f / t.scaleX
                        vm.setTransform(sel, t.copy(scaleX = f, scaleY = t.scaleY * k, scaleZ = t.scaleZ * k), "Scale")
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(onClick = { vm.rotate(sel, 2, 90.0) }, label = { Text("Z 90°") }, leadingIcon = { Icon(AppIcons.Rotate, null) })
                AssistChip(onClick = { vm.rotate(sel, 0, 90.0) }, label = { Text("X 90°") }, leadingIcon = { Icon(AppIcons.Rotate, null) })
                AssistChip(onClick = { vm.rotate(sel, 1, 90.0) }, label = { Text("Y 90°") }, leadingIcon = { Icon(AppIcons.Rotate, null) })
                FilterChip(selected = layFlat, onClick = onLayFlat, label = { Text("Lay on face") })
                AssistChip(onClick = { vm.orient(sel) }, label = { Text("Auto orient") })
                AssistChip(onClick = { vm.scaleToFit(sel) }, label = { Text("Scale to fit") })
                AssistChip(onClick = { vm.duplicate(sel) }, label = { Text("Duplicate") }, leadingIcon = { Icon(AppIcons.ContentCopy, null) })
                AssistChip(onClick = { vm.delete(sel) }, label = { Text("Delete") }, leadingIcon = { Icon(Icons.Filled.Delete, null) })
            }
        }
    }
}

/** Slice result: estimates, warnings, the layer slider and G-code export. */
@Composable
fun PreviewPanel(vm: AppViewModel, done: SliceState.Done, onShare: () -> Unit, onSave: () -> Unit) {
    var warningsOpen by remember { mutableStateOf(false) }
    val tp = done.toolpath
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.showPreview = false }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to plate") }
                Column(Modifier.weight(1f)) {
                    Text(duration(done.report.timeEstimateSeconds), style = MaterialTheme.typography.titleMedium)
                    val filament = tp?.takeIf { it.filamentVolume > 0 }?.let {
                        String.format(Locale.US, "%.1f m · %.0f g", it.filamentMetres, it.filamentGrams)
                    }
                    Text(
                        listOfNotNull(filament, sizeText(done.report.gcodeBytes)).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (done.report.warnings.isNotEmpty()) {
                    TextButton(onClick = { warningsOpen = !warningsOpen }) {
                        Icon(Icons.Filled.Warning, null)
                        Spacer(Modifier.width(4.dp))
                        Text("${done.report.warnings.size}")
                    }
                }
            }
            if (warningsOpen) {
                for (w in done.report.warnings.take(8)) Text("• ${w.text}", style = MaterialTheme.typography.bodySmall)
            }
            if (tp != null && tp.layerTotal > 0) {
                val layer = vm.previewLayer.coerceIn(0, tp.layerTotal - 1)
                Text(
                    String.format(Locale.US, "Layer %d of %d · %.2f mm", layer + 1, tp.layerTotal, tp.layerZ[layer]),
                    style = MaterialTheme.typography.labelMedium,
                )
                Slider(
                    value = layer.toFloat(),
                    onValueChange = { vm.previewLayer = it.roundToInt() },
                    valueRange = 0f..(tp.layerTotal - 1).coerceAtLeast(1).toFloat(),
                )
            } else {
                Text("The toolpath preview is not available for this G-code.", style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = onSave) { Text("Save") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onShare) {
                    Icon(Icons.Filled.Share, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Send")
                }
            }
        }
    }
}

private fun fmt(v: Double) = String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')

private fun duration(seconds: Double): String {
    val s = seconds.roundToInt()
    val h = s / 3600
    val m = (s % 3600) / 60
    return when {
        h > 0 -> "${h} h ${m} min"
        m > 0 -> "$m min ${s % 60} s"
        else -> "$s s"
    }
}

private fun sizeText(bytes: Long): String = when {
    bytes >= 1 shl 20 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
    else -> String.format(Locale.US, "%.0f kB", bytes / 1024.0)
}

/** World-space bounding box size of a mesh under an instance matrix. */
private fun worldSize(mesh: Mesh, matrix: FloatArray): FloatArray {
    val min = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
    val max = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
    val m = matrix
    val p = mesh.positions
    // Every vertex, not the box corners, so rotated objects report their true extent.
    var i = 0
    while (i < p.size) {
        val x = p[i]; val y = p[i + 1]; val z = p[i + 2]
        for (k in 0 until 3) {
            val w = m[k] * x + m[4 + k] * y + m[8 + k] * z + m[12 + k]
            if (w < min[k]) min[k] = w
            if (w > max[k]) max[k] = w
        }
        i += 3
    }
    return if (p.isEmpty()) floatArrayOf(0f, 0f, 0f) else floatArrayOf(max[0] - min[0], max[1] - min[1], max[2] - min[2])
}
