package org.orcapod.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
fun ObjectPanel(vm: AppViewModel, sel: Selection, layFlat: Boolean, onLayFlat: () -> Unit, onEdit: () -> Unit) {
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
                                (if (inst.plate < 0) " · off the plates" else "") +
                                (if (obj.volumes.size > 1) " · ${obj.volumes.size} parts" else "") +
                                (if (obj.overrideCount > 0) " · ${obj.overrideCount} settings" else ""),
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
            // The everyday actions as labelled buttons; the rarer tools sit under "More".
            var rotateMenu by remember { mutableStateOf(false) }
            var moreMenu by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Box {
                    ToolButton("Rotate", AppIcons.Rotate) { rotateMenu = true }
                    DropdownMenu(expanded = rotateMenu, onDismissRequest = { rotateMenu = false }) {
                        DropdownMenuItem(text = { Text("Turn 90° around Z") }, onClick = { rotateMenu = false; vm.rotate(sel, 2, 90.0) })
                        DropdownMenuItem(text = { Text("Tip 90° around X") }, onClick = { rotateMenu = false; vm.rotate(sel, 0, 90.0) })
                        DropdownMenuItem(text = { Text("Tip 90° around Y") }, onClick = { rotateMenu = false; vm.rotate(sel, 1, 90.0) })
                        DropdownMenuItem(text = { Text("Auto orient") }, onClick = { rotateMenu = false; vm.orient(sel) })
                    }
                }
                ToolButton(if (layFlat) "Tap a face" else "Lay flat", AppIcons.LayFlat, onClick = onLayFlat)
                ToolButton("Copy", AppIcons.ContentCopy) { vm.duplicate(sel) }
                ToolButton("Settings", AppIcons.Tune, onClick = onEdit)
                ToolButton("Delete", Icons.Filled.Delete) { vm.delete(sel) }
                Box {
                    ToolButton("More", Icons.Filled.MoreVert) { moreMenu = true }
                    DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Scale to fit") },
                            leadingIcon = { Icon(AppIcons.ViewInAr, null) },
                            onClick = { moreMenu = false; vm.scaleToFit(sel) },
                        )
                        DropdownMenuItem(text = { Text("Cut") }, leadingIcon = { Icon(AppIcons.Cut, null) }, onClick = { moreMenu = false; vm.startCut(sel) })
                        DropdownMenuItem(text = { Text("Paint") }, leadingIcon = { Icon(AppIcons.Brush, null) }, onClick = { moreMenu = false; vm.startPaint(sel.objectId) })
                        DropdownMenuItem(
                            text = { Text("Layer heights") },
                            leadingIcon = { Icon(AppIcons.Layers, null) },
                            onClick = { moreMenu = false; vm.startLayerHeights(sel) },
                        )
                        DropdownMenuItem(text = { Text("Measure") }, leadingIcon = { Icon(AppIcons.Ruler, null) }, onClick = { moreMenu = false; vm.startMeasure(sel.objectId) })
                        DropdownMenuItem(text = { Text("Parts and settings") }, leadingIcon = { Icon(AppIcons.Tune, null) }, onClick = { moreMenu = false; onEdit() })
                    }
                }
            }
        }
    }
}

internal fun duration(seconds: Double): String {
    val s = seconds.roundToInt()
    val h = s / 3600
    val m = (s % 3600) / 60
    return when {
        h > 0 -> "${h} h ${m} min"
        m > 0 -> "$m min ${s % 60} s"
        else -> "$s s"
    }
}

internal fun sizeText(bytes: Long): String = when {
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
