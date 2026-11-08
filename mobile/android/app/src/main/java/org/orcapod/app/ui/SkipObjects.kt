package org.orcapod.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.orcapod.app.printhost.BedPoint
import org.orcapod.app.printhost.HostType
import org.orcapod.app.printhost.PrintObject
import java.io.IOException

/**
 * Desktop's "Skip objects" for a running print: the print's objects on a map
 * of the bed and in a list; the ones picked stop printing for the rest of
 * the print while the others carry on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkipObjectsSheet(vm: PrinterViewModel, connectionId: String, onDismiss: () -> Unit) {
    val c = vm.connections.firstOrNull { it.id == connectionId } ?: return
    var list by remember { mutableStateOf<PrinterViewModel.SkipList?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf(emptySet<String>()) }
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Follows the printer, so objects skipped elsewhere and the one printing now stay current.
    LaunchedEffect(connectionId) {
        while (true) {
            try {
                list = vm.printObjects(connectionId)
                error = null
            } catch (e: IOException) {
                error = e.message ?: "The printer did not answer"
            }
            delay(5000)
        }
    }
    val objects = list?.objects.orEmpty()
    fun toggle(o: PrintObject) {
        if (o.skipped) return
        picked = if (o.id in picked) picked - o.id else picked + o.id
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 640.dp).padding(horizontal = 16.dp)) {
            item {
                Text("Skip objects on ${c.name}", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Skipped objects stop printing for the rest of this print; the others carry on.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
            }
            when {
                list == null && error == null -> item { CircularProgressIndicator(Modifier.padding(16.dp)) }
                list == null -> item { Text(error.orEmpty(), color = MaterialTheme.colorScheme.error) }
                objects.isEmpty() -> item { Text(emptyReason(c.type, list!!.sentHere), style = MaterialTheme.typography.bodyMedium) }
                else -> {
                    if (objects.any { it.outline.size >= 3 }) item {
                        ObjectMap(objects, picked, ::toggle)
                        Spacer(Modifier.height(8.dp))
                    }
                    items(objects, key = { it.id }) { o ->
                        ListItem(
                            headlineContent = { Text(o.name) },
                            supportingContent = when {
                                o.skipped -> ({ Text("Skipped") })
                                o.current -> ({ Text("Printing now") })
                                else -> null
                            },
                            leadingContent = {
                                Checkbox(checked = o.skipped || o.id in picked, enabled = !o.skipped, onCheckedChange = { toggle(o) })
                            },
                            modifier = Modifier.clickable(enabled = !o.skipped) { toggle(o) },
                        )
                    }
                    item {
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.End) {
                            Button(onClick = { confirm = true }, enabled = picked.isNotEmpty() && !busy) {
                                Text(if (picked.size == 1) "Skip 1 object" else "Skip ${picked.size} objects")
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
    if (confirm) {
        val chosen = objects.filter { it.id in picked }
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(if (chosen.size == 1) "Skip ${chosen[0].name}?" else "Skip ${chosen.size} objects?") },
            text = { Text("They stop printing now and cannot be brought back in this print.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    busy = true
                    scope.launch {
                        if (vm.skipObjects(connectionId, chosen)) {
                            picked = emptySet()
                            runCatching { list = vm.printObjects(connectionId) }
                        }
                        busy = false
                    }
                }) { Text("Skip") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Keep printing") } },
        )
    }
}

private fun emptyReason(type: HostType, sentHere: Boolean): String = when {
    !sentHere && (type == HostType.OCTOPRINT || type == HostType.BAMBU_LAN) ->
        "This print was not sent from this app, so the app does not know its objects. Skip them on the printer's screen."
    type == HostType.MOONRAKER ->
        "Klipper lists no objects for this print. Turn on Exclude objects (Process › Others › G-code output) and slice " +
            "again, and add an [exclude_object] section to printer.cfg."
    else ->
        "This print has no object labels. Turn on Exclude objects (Process › Others › G-code output) and slice again."
}

/** The objects' outlines seen from above, back of the bed up; tap one to pick it. */
@Composable
private fun ObjectMap(objects: List<PrintObject>, picked: Set<String>, onTap: (PrintObject) -> Unit) {
    val all = objects.flatMap { it.outline }
    val minX = all.minOf { it.first }
    val maxX = all.maxOf { it.first }
    val minY = all.minOf { it.second }
    val maxY = all.maxOf { it.second }
    val colors = MaterialTheme.colorScheme
    // A margin around the objects so small ones are not drawn at the very edge.
    val pad = maxOf(maxX - minX, maxY - minY, 10.0) * 0.08
    val bounds = doubleArrayOf(minX - pad, minY - pad, maxX + pad, maxY + pad)
    Canvas(
        Modifier.fillMaxWidth().aspectRatio(((bounds[2] - bounds[0]) / (bounds[3] - bounds[1])).toFloat().coerceIn(0.75f, 2f))
            .pointerInput(objects) {
                detectTapGestures { tap ->
                    val scale = mapScale(bounds, size.width.toFloat(), size.height.toFloat())
                    val p = fromScreen(tap, bounds, scale, size.height.toFloat())
                    // The smallest outline under the finger, so an object inside another's hull can be picked.
                    objects.filter { it.outline.size >= 3 && contains(it.outline, p) }.minByOrNull { area(it.outline) }?.let(onTap)
                }
            },
    ) {
        val scale = mapScale(bounds, size.width, size.height)
        drawRect(colors.surfaceVariant)
        for (o in objects) {
            if (o.outline.size < 3) continue
            val path = Path().apply {
                o.outline.forEachIndexed { i, pt ->
                    val s = toScreen(pt, bounds, scale, size.height)
                    if (i == 0) moveTo(s.x, s.y) else lineTo(s.x, s.y)
                }
                close()
            }
            val fill = when {
                o.skipped -> colors.outline.copy(alpha = 0.35f)
                o.id in picked -> colors.error.copy(alpha = 0.7f)
                else -> colors.primary.copy(alpha = 0.45f)
            }
            drawPath(path, fill)
            drawPath(path, if (o.current) colors.tertiary else colors.onSurfaceVariant, style = Stroke(width = if (o.current) 3.dp.toPx() else 1.dp.toPx()))
        }
    }
}

private fun mapScale(b: DoubleArray, w: Float, h: Float) = minOf(w / (b[2] - b[0]), h / (b[3] - b[1])).toFloat()

private fun toScreen(p: BedPoint, b: DoubleArray, scale: Float, h: Float) =
    Offset(((p.first - b[0]) * scale).toFloat(), h - ((p.second - b[1]) * scale).toFloat())

private fun fromScreen(o: Offset, b: DoubleArray, scale: Float, h: Float): BedPoint =
    (b[0] + o.x / scale) to (b[1] + (h - o.y) / scale)

private fun contains(poly: List<BedPoint>, p: BedPoint): Boolean {
    var inside = false
    var j = poly.size - 1
    for (i in poly.indices) {
        val (xi, yi) = poly[i]
        val (xj, yj) = poly[j]
        if ((yi > p.second) != (yj > p.second) && p.first < (xj - xi) * (p.second - yi) / (yj - yi) + xi) inside = !inside
        j = i
    }
    return inside
}

private fun area(poly: List<BedPoint>): Double =
    kotlin.math.abs(poly.indices.sumOf { i -> val a = poly[i]; val b = poly[(i + 1) % poly.size]; a.first * b.second - b.first * a.second }) / 2
