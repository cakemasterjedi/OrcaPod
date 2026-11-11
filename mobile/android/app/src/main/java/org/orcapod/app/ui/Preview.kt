package org.orcapod.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.orcapod.app.engine.CustomGcode
import org.orcapod.app.engine.CustomGcodeType
import org.orcapod.app.engine.Toolpath
import org.orcapod.app.ui.viewport.PlateRenderer
import org.orcapod.app.ui.viewport.PreviewOption
import org.orcapod.app.ui.viewport.PreviewRange
import org.orcapod.app.ui.viewport.ROLE_NAMES
import org.orcapod.app.ui.viewport.ToolpathColours
import org.orcapod.app.ui.viewport.ViewMode
import org.orcapod.app.ui.viewport.roleTimes
import org.orcapod.app.ui.viewport.travelTime
import java.util.Locale
import kotlin.math.roundToInt

private enum class PreviewSection(val label: String) { LEGEND("Legend"), STATS("Summary"), LAYER_GCODE("Layer G-code") }

/**
 * Desktop preview: estimates, the colour scheme and its legend, move options,
 * the layer range and move sliders, per-layer custom G-code and G-code export.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewPanel(vm: AppViewModel, done: SliceState.Done, onShare: () -> Unit, onSave: () -> Unit, onPrint: () -> Unit) {
    var warningsOpen by remember { mutableStateOf(false) }
    var section by remember { mutableStateOf<PreviewSection?>(null) }
    val tp = done.toolpath
    // The engine reports some checks twice (an old and a new error number).
    val warnings = remember(done) { done.report.warnings.map { it.text }.distinct() }
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.showPreview = false }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to plate") }
                Column(Modifier.weight(1f)) {
                    Text(duration(done.report.timeEstimateSeconds), style = MaterialTheme.typography.titleMedium)
                    val filament = tp?.takeIf { it.filamentGrams > 0 }?.let {
                        String.format(Locale.US, "%.1f m · %.0f g", it.filamentMetres, it.filamentGrams)
                    }
                    Text(
                        listOfNotNull(filament, sizeText(done.report.gcodeBytes)).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (warnings.isNotEmpty()) {
                    TextButton(onClick = { warningsOpen = !warningsOpen }) {
                        Icon(Icons.Filled.Warning, null)
                        Spacer(Modifier.width(4.dp))
                        Text("${warnings.size}")
                    }
                }
            }
            SlicedPlateChips(vm, done)
            if (!done.printable) {
                Text(AppViewModel.COLLISION_BLOCKS_OUTPUT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (warningsOpen) {
                for (w in warnings.take(8)) Text("• $w", style = MaterialTheme.typography.bodySmall)
            }
            if (vm.sliceStale) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Slice again to apply the layer G-code changes.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { vm.startSlice() }) { Text("Slice") }
                }
            }
            if (tp != null && tp.layerTotal > 0) {
                Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    ViewModePicker(vm)
                    OptionsPicker(vm)
                    for (s in PreviewSection.entries) {
                        FilterChip(
                            selected = section == s,
                            onClick = { section = if (section == s) null else s },
                            label = { Text(s.label) },
                            modifier = Modifier.padding(end = 6.dp),
                        )
                    }
                }
                section?.let { s ->
                    Column(Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                        when (s) {
                            PreviewSection.LEGEND -> Legend(vm, tp)
                            PreviewSection.STATS -> Summary(vm, tp)
                            PreviewSection.LAYER_GCODE -> LayerGcodes(vm, tp)
                        }
                    }
                }
                if (tp.simplified) {
                    Text(
                        "Short moves are joined in this preview so it fits in memory. The G-code itself is unchanged.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Sliders(vm, tp)
            } else {
                Text("The toolpath preview is not available for this G-code.", style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onShare, enabled = done.printable) { Icon(Icons.Filled.Share, "Share G-code") }
                OutlinedButton(onClick = onSave, enabled = done.printable) { Text("Save") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onPrint, enabled = done.printable) {
                    Icon(AppIcons.Print, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Print")
                }
            }
        }
    }
}

@Composable
private fun ViewModePicker(vm: AppViewModel) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.padding(end = 6.dp)) { Text(vm.previewMode.label) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (m in ViewMode.entries) {
                DropdownMenuItem(text = { Text(m.label) }, onClick = { open = false; vm.choosePreviewMode(m) })
            }
        }
    }
}

@Composable
private fun OptionsPicker(vm: AppViewModel) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.padding(end = 6.dp)) { Text("Show") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (o in PreviewOption.entries) {
                DropdownMenuItem(
                    text = { Text(o.label) },
                    leadingIcon = { Checkbox(checked = o in vm.previewOptions, onCheckedChange = null) },
                    trailingIcon = { Swatch(o.color) },
                    onClick = { vm.togglePreviewOption(o) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Sliders(vm: AppViewModel, tp: Toolpath) {
    val last = tp.layerTotal - 1
    val r = vm.previewRange
    val lo = r.low.coerceIn(0, last)
    val hi = r.high.coerceIn(lo, last)
    Text(
        String.format(
            Locale.US, "Layers %d–%d of %d · %.2f mm · %s", lo + 1, hi + 1, tp.layerTotal, tp.layerZ[hi],
            duration(tp.layerTime[hi].toDouble()),
        ),
        style = MaterialTheme.typography.labelMedium,
    )
    if (last > 0) {
        RangeSlider(
            value = lo.toFloat()..hi.toFloat(),
            onValueChange = { v ->
                val nlo = v.start.roundToInt()
                val nhi = v.endInclusive.roundToInt()
                // A new top layer shows all of its moves again, as desktop's horizontal slider resets.
                vm.previewRange = PreviewRange(nlo, nhi, if (nhi != hi) Int.MAX_VALUE else r.moves)
            },
            valueRange = 0f..last.toFloat(),
        )
    }
    val count = tp.layerCount[hi]
    if (count > 1) {
        val moves = r.moves.coerceIn(1, count)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Move $moves of $count", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(110.dp))
            Slider(
                value = moves.toFloat(),
                onValueChange = { vm.previewRange = r.copy(low = lo, high = hi, moves = it.roundToInt()) },
                valueRange = 1f..count.toFloat(),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun Swatch(rgb: IntArray, size: Int = 14) {
    Box(
        Modifier.size(size.dp).background(Color(rgb[0], rgb[1], rgb[2]), RoundedCornerShape(3.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(3.dp)),
    )
}

@Composable
private fun LegendRow(color: IntArray, label: String, detail: String = "", dimmed: Boolean = false, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().alpha(if (dimmed) 0.4f else 1f)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Swatch(color)
        Spacer(Modifier.width(8.dp))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall)
    }
}

private fun hexRgb(hex: String): IntArray {
    val c = PlateRenderer.parseHex(hex)
    return intArrayOf((c[0] * 255).roundToInt(), (c[1] * 255).roundToInt(), (c[2] * 255).roundToInt())
}

@Composable
private fun Legend(vm: AppViewModel, tp: Toolpath) {
    val mode = vm.previewMode
    when {
        mode == ViewMode.LINE_TYPE -> {
            val times = remember(tp) { roleTimes(tp) }
            val total = times.values.sum().coerceAtLeast(1e-3f)
            Text("Tap a line type to hide or show it.", style = MaterialTheme.typography.labelSmall)
            for ((role, t) in times) {
                LegendRow(
                    PlateRenderer.roleColor(role), ROLE_NAMES.getOrElse(role) { "Role $role" },
                    String.format(Locale.US, "%s  %.1f%%", duration(t.toDouble()), 100f * t / total),
                    dimmed = role in vm.hiddenRoles,
                ) { vm.toggleRole(role) }
            }
        }
        mode == ViewMode.FILAMENT -> {
            for (e in tp.volumes.indices) {
                val colour = vm.filamentSlots.getOrNull(e)?.colour ?: "#404040"
                LegendRow(hexRgb(colour), "Filament ${e + 1}", String.format(Locale.US, "%.2f m · %.1f g", tp.metres(e), tp.grams(e)))
            }
        }
        else -> {
            val range = remember(tp, mode, vm.hiddenRoles) { ToolpathColours.attrRange(tp, mode.attr!!, vm.hiddenRoles) }
            val values = ToolpathColours.rangeValues(range, mode.log)
            // Highest value on top, as desktop lists it.
            for (v in values.reversed()) {
                LegendRow(ToolpathColours.rangeColor(v, range, mode.log), formatValue(v, mode))
            }
        }
    }
    val travel = PreviewOption.TRAVEL in vm.previewOptions
    if (travel) LegendRow(PreviewOption.TRAVEL.color, "Travel", duration(travelTime(tp).toDouble()))
}

private fun formatValue(v: Float, mode: ViewMode): String {
    val digits = when (mode) {
        ViewMode.HEIGHT, ViewMode.WIDTH, ViewMode.PRESSURE_ADVANCE -> 3
        ViewMode.FLOW, ViewMode.ACTUAL_FLOW -> 2
        ViewMode.LAYER_TIME, ViewMode.LAYER_TIME_LOG -> 1
        else -> 0
    }
    val n = String.format(Locale.US, "%.${digits}f", v)
    return if (mode.unit.isEmpty()) n else "$n ${mode.unit}"
}

@Composable
private fun Summary(vm: AppViewModel, tp: Toolpath) {
    val style = MaterialTheme.typography.bodySmall
    @Composable
    fun line(label: String, value: String) = Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, Modifier.weight(1f), style = style)
        Text(value, style = style)
    }
    line("Total time", duration(tp.estimatedSeconds.toDouble()))
    if (tp.silentSeconds > 0f && tp.silentSeconds != tp.estimatedSeconds) line("Silent mode", duration(tp.silentSeconds.toDouble()))
    line("Travel time", duration(travelTime(tp).toDouble()))
    line("Layers", "${tp.layerTotal}")
    for (e in tp.volumes.indices) {
        if (tp.volumes[e] <= 0.0) continue
        val prefix = if (tp.volumes.size > 1) "Filament ${e + 1}" else "Filament"
        val cost = tp.cost(e)
        line(
            prefix,
            String.format(Locale.US, "%.2f m · %.1f g", tp.metres(e), tp.grams(e)) +
                (if (cost > 0) String.format(Locale.US, " · %.2f", cost) else ""),
        )
    }
    if (tp.volumes.size > 1) {
        val cost = tp.filamentCost
        line("Total filament", String.format(Locale.US, "%.2f m · %.1f g", tp.filamentMetres, tp.filamentGrams) +
            (if (cost > 0) String.format(Locale.US, " · %.2f", cost) else ""))
    }
}

/** Desktop layer-slider ticks: colour changes, pauses and custom G-code before a layer. */
@Composable
private fun LayerGcodes(vm: AppViewModel, tp: Toolpath) {
    val hi = vm.previewRange.high.coerceIn(0, tp.layerTotal - 1)
    val z = tp.layerZ[hi].toDouble()
    var menu by remember { mutableStateOf(false) }
    var pause by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            String.format(Locale.US, "Before layer %d (%.2f mm)", hi + 1, z),
            Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
        )
        Box {
            TextButton(onClick = { menu = true }) {
                Icon(Icons.Filled.Add, null)
                Spacer(Modifier.width(4.dp))
                Text("Add")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Add pause") }, onClick = { menu = false; pause = true })
                DropdownMenuItem(text = { Text("Add custom G-code") }, onClick = { menu = false; custom = true })
                // Desktop offers filament changes only with several filaments.
                val slots = vm.filamentSlots
                if (slots.size > 1) {
                    for ((i, slot) in slots.withIndex()) {
                        DropdownMenuItem(
                            text = { Text("Change to filament ${i + 1}") },
                            leadingIcon = { Box(Modifier.size(14.dp).background(parseColor(slot.colour) ?: Color.Gray, CircleShape)) },
                            onClick = { menu = false; vm.addCustomGcode(z, CustomGcodeType.TOOL_CHANGE, i + 1) },
                        )
                    }
                }
            }
        }
    }
    if (vm.customGcodes.isEmpty()) {
        Text("No layer G-code on this plate.", style = MaterialTheme.typography.labelSmall)
    }
    for (item in vm.customGcodes) {
        val layer = tp.layerZ.indexOfFirst { it >= item.printZ - 1e-4 }
        InputChip(
            selected = layer == hi,
            onClick = { if (layer >= 0) vm.previewRange = PreviewRange(vm.previewRange.low.coerceAtMost(layer), layer) },
            label = { Text(describe(item, layer), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = vm.filamentSlots.getOrNull(item.extruder - 1)?.takeIf { item.type == CustomGcodeType.TOOL_CHANGE }?.let { slot ->
                { Box(Modifier.size(14.dp).background(parseColor(slot.colour) ?: Color.Gray, CircleShape)) }
            },
            trailingIcon = {
                Icon(Icons.Filled.Close, "Delete", Modifier.size(18.dp).clickable { vm.deleteCustomGcode(item.printZ) })
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (pause) {
        TextDialog("Pause message", "", onDismiss = { pause = false }) { msg ->
            pause = false
            vm.addCustomGcode(z, CustomGcodeType.PAUSE, extra = msg)
        }
    }
    if (custom) {
        CodeEditorDialog("Custom G-code before layer ${hi + 1}", "", onDismiss = { custom = false }) { code ->
            custom = false
            if (code.isNotBlank()) vm.addCustomGcode(z, CustomGcodeType.CUSTOM, extra = code)
        }
    }
}

private fun describe(item: CustomGcode, layer: Int): String {
    val at = if (layer >= 0) "Layer ${layer + 1}" else String.format(Locale.US, "%.2f mm", item.printZ)
    return when (item.type) {
        CustomGcodeType.COLOR_CHANGE -> "$at: colour change (filament ${item.extruder})"
        CustomGcodeType.PAUSE -> "$at: pause" + if (item.extra.isNotBlank()) " – ${item.extra}" else ""
        CustomGcodeType.TOOL_CHANGE -> "$at: change to filament ${item.extruder}"
        CustomGcodeType.TEMPLATE -> "$at: template"
        CustomGcodeType.CUSTOM -> "$at: ${item.extra.lineSequence().first()}"
    }
}

/** A slice of every plate: picks the plate whose layers the preview shows. */
@Composable
fun SlicedPlateChips(vm: AppViewModel, done: SliceState.Done, modifier: Modifier = Modifier) {
    if (done.plates.size < 2) return
    Row(modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        for (p in done.plates) {
            val name = vm.scene.snapshot.plates.getOrNull(p.plate)?.name.orEmpty().ifEmpty { "Plate ${p.plate + 1}" }
            FilterChip(
                selected = p.plate == done.plate,
                onClick = { vm.previewSlicedPlate(p.plate) },
                enabled = !vm.loadingPlatePreview,
                label = { Text(name, maxLines = 1) },
                modifier = Modifier.padding(end = 6.dp),
            )
        }
    }
}
