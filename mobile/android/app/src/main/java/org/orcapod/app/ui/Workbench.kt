package org.orcapod.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.orcapod.app.engine.PaintLayer
import org.orcapod.app.engine.PresetKind
import org.orcapod.app.ui.viewport.Selection
import java.util.Locale

// The phone's Workbench layout: profile chips and stage tabs on top, a tool
// column on the left, option panels on the right, and the object strip above
// a fixed Slice bar at the bottom. Large screens keep the side-pane layout.

/** The stages the Workbench tabs switch between. */
enum class Stage(val label: String) { PREPARE("Prepare"), PAINT("Paint"), PREVIEW("Preview"), DEVICE("Device") }

/** Menu button, the printer, filament and process chips, undo and redo. */
@Composable
fun WorkbenchBar(
    vm: AppViewModel,
    showUndo: Boolean,
    onMenu: () -> Unit,
    onFilaments: () -> Unit,
    onPick: (PresetKind) -> Unit,
) {
    val slots = vm.filamentSlots.map { it.colour }
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onMenu) { Icon(Icons.Filled.Menu, "Menu") }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ProfileChip(vm.printer, Modifier.weight(1.25f), icon = AppIcons.Print) { onPick(PresetKind.PRINTER) }
            if (slots.size > 1) {
                ProfileChip("${slots.size} filaments", Modifier.weight(1f), swatches = slots, onClick = onFilaments)
            } else {
                ProfileChip(vm.filament, Modifier.weight(1f), swatches = slots.take(1), onLongClick = onFilaments) { onPick(PresetKind.FILAMENT) }
            }
            ProfileChip(vm.process, Modifier.weight(1f), icon = AppIcons.Tune) { onPick(PresetKind.PROCESS) }
        }
        if (showUndo) {
            IconButton(onClick = vm::undo, enabled = vm.scene.snapshot.undoCount > 0, modifier = Modifier.size(40.dp)) {
                Icon(AppIcons.Undo, "Undo", Modifier.size(20.dp))
            }
            IconButton(onClick = vm::redo, enabled = vm.scene.snapshot.redoCount > 0, modifier = Modifier.size(40.dp)) {
                Icon(AppIcons.Redo, "Redo", Modifier.size(20.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProfileChip(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    swatches: List<String> = emptyList(),
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier.height(34.dp).clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(5.dp))
        }
        for ((i, c) in swatches.take(4).withIndex()) {
            Box(
                Modifier.size(10.dp)
                    .background(parseColor(c) ?: Color.Gray, CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
            )
            Spacer(Modifier.width(if (i == swatches.take(4).lastIndex) 5.dp else 1.dp))
        }
        Text(
            text.ifEmpty { "—" },
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Prepare, Paint, Preview and Device; a tab that has nothing to show yet is greyed out. */
@Composable
fun StageTabRow(stage: Stage, canPaint: Boolean, canPreview: Boolean, onStage: (Stage) -> Unit) {
    TabRow(
        selectedTabIndex = stage.ordinal,
        containerColor = MaterialTheme.colorScheme.background,
        divider = { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) },
    ) {
        for (s in Stage.entries) {
            val enabled = when (s) {
                Stage.PAINT -> canPaint
                Stage.PREVIEW -> canPreview
                else -> true
            }
            Tab(
                selected = s == stage,
                onClick = { onStage(s) },
                enabled = enabled,
                selectedContentColor = MaterialTheme.colorScheme.primary,
                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                text = { Text(s.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1) },
            )
        }
    }
}

/** One tool of the left column. */
data class ColumnTool(
    val label: String,
    val icon: ImageVector,
    val enabled: Boolean = true,
    val active: Boolean = false,
    val menu: List<Pair<String, () -> Unit>> = emptyList(),
    val onClick: () -> Unit = {},
)

/** The vertical tool column at the left edge of the plate. */
@Composable
fun ToolColumn(tools: List<ColumnTool>, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.94f))
            .verticalScroll(rememberScrollState()).padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (t in tools) ColumnToolButton(t)
    }
}

@Composable
private fun ColumnToolButton(t: ColumnTool) {
    var open by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Box {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(9.dp))
                .background(if (t.active) colors.primary.copy(alpha = 0.16f) else Color.Transparent)
                .clickable(enabled = t.enabled) { if (t.menu.isNotEmpty()) open = true else t.onClick() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                t.icon,
                t.label,
                Modifier.size(21.dp),
                tint = when {
                    !t.enabled -> colors.onSurface.copy(alpha = 0.3f)
                    t.active -> colors.primary
                    else -> colors.onSurface
                },
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(t.label, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium, color = colors.primary)
            for ((label, action) in t.menu) {
                DropdownMenuItem(text = { Text(label) }, onClick = { open = false; action() })
            }
        }
    }
}

/** A small floating panel for a tool's options, beside the tool column. */
@Composable
fun SidePanel(title: String? = null, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.widthIn(max = 180.dp).clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.96f)).padding(10.dp),
    ) {
        if (title != null) {
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
        }
        content()
    }
}

/** Position and uniform scale of the selected instance, with copy, delete and close. */
@Composable
fun TransformPanel(vm: AppViewModel, sel: Selection, onClose: () -> Unit) {
    val obj = vm.scene.snapshot.objects.firstOrNull { it.id == sel.objectId } ?: return
    val t = obj.instances.firstOrNull { it.id == sel.instanceId }?.transform ?: return
    SidePanel(modifier = Modifier.width(172.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                obj.name, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.Filled.Close, "Close", Modifier.size(18.dp).clickable(onClick = onClose), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(6.dp))
        NumberField(fmt(t.offsetX), Modifier.fillMaxWidth(), label = "X", suffix = "mm") {
            vm.setTransform(sel, t.copy(offsetX = it.toDouble()), "Move")
        }
        NumberField(fmt(t.offsetY), Modifier.fillMaxWidth(), label = "Y", suffix = "mm") {
            vm.setTransform(sel, t.copy(offsetY = it.toDouble()), "Move")
        }
        NumberField(fmt(t.scaleX * 100), Modifier.fillMaxWidth(), label = "Scale", suffix = "%") {
            val f = it.toDouble() / 100
            if (f > 0) {
                // Uniform scale keeps the instance's axis ratios.
                val k = f / t.scaleX
                vm.setTransform(sel, t.copy(scaleX = f, scaleY = t.scaleY * k, scaleZ = t.scaleZ * k), "Scale")
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            IconButton(onClick = { vm.duplicate(sel) }) { Icon(AppIcons.ContentCopy, "Copy", Modifier.size(20.dp)) }
            IconButton(onClick = { vm.scaleToFit(sel) }) { Icon(AppIcons.ViewInAr, "Scale to fit", Modifier.size(20.dp)) }
            IconButton(onClick = { vm.delete(sel) }) { Icon(Icons.Filled.Delete, "Delete", Modifier.size(20.dp)) }
        }
    }
}

/** The plate's objects as a scrolling strip; with an empty plate, the ways to add one. */
@Composable
fun ObjectStrip(
    vm: AppViewModel,
    onImport: () -> Unit,
    onLibrary: () -> Unit,
    onShape: () -> Unit,
    onOpenProject: () -> Unit,
) {
    val objects = vm.scene.snapshot.objects
    var addMenu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (objects.isEmpty()) {
            StripButton("Import", AppIcons.FileOpen, accent = true, onClick = onImport)
            StripButton("Library", AppIcons.CloudDownload, onClick = onLibrary)
            StripButton("Shape", AppIcons.Category, onClick = onShape)
            StripButton("Project", AppIcons.FolderOpen, onClick = onOpenProject)
        } else {
            for (obj in objects) {
                val inst = obj.instances.firstOrNull() ?: continue
                val selected = vm.selection?.objectId == obj.id
                val plates = obj.instances.map { it.plate }.distinct().filter { it >= 0 }
                Column(
                    Modifier.width(118.dp).height(52.dp).clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.96f))
                        .border(
                            1.5.dp,
                            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            RoundedCornerShape(10.dp),
                        )
                        .clickable { vm.selection = if (selected) null else Selection(obj.id, inst.id) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    Text(obj.name, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(
                            if (obj.instances.size > 1) "×${obj.instances.size}" else null,
                            if (selected) "selected" else plates.takeIf { it.isNotEmpty() }?.joinToString(prefix = "Plate ") { (it + 1).toString() } ?: "Off the plates",
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            Box {
                Box(
                    Modifier.size(52.dp).clip(RoundedCornerShape(10.dp))
                        .border(1.5.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                        .clickable { addMenu = true },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Add, "Add", tint = MaterialTheme.colorScheme.primary) }
                DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                    DropdownMenuItem(text = { Text("Import models") }, leadingIcon = { Icon(AppIcons.FileOpen, null) }, onClick = { addMenu = false; onImport() })
                    DropdownMenuItem(text = { Text("Online model library") }, leadingIcon = { Icon(AppIcons.CloudDownload, null) }, onClick = { addMenu = false; onLibrary() })
                    DropdownMenuItem(text = { Text("Add shape") }, leadingIcon = { Icon(AppIcons.Category, null) }, onClick = { addMenu = false; onShape() })
                }
            }
        }
    }
}

@Composable
private fun StripButton(label: String, icon: ImageVector, accent: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.height(48.dp).clip(RoundedCornerShape(10.dp))
            .background(if (accent) colors.primary.copy(alpha = 0.14f) else colors.surfaceContainer.copy(alpha = 0.96f))
            .border(1.dp, if (accent) colors.primary else colors.outlineVariant, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = if (accent) colors.primary else colors.onSurface)
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (accent) colors.primary else colors.onSurface)
    }
}

/** The fixed bar at the bottom: a status line on the left and the main action on the right. */
@Composable
fun BottomBar(content: @Composable RowScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 10.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) { content() }
        }
    }
}

/** The Slice bar: objects on the plate (or the last result) and the Slice button. */
@Composable
fun SliceBar(
    vm: AppViewModel,
    done: SliceState.Done?,
    onSlice: (allPlates: Boolean) -> Unit,
    onShowResult: () -> Unit,
) {
    val snap = vm.scene.snapshot
    val count = snap.objects.size
    var sliceMenu by remember { mutableStateOf(false) }
    BottomBar {
        Column(Modifier.weight(1f)) {
            Text(
                when (count) {
                    0 -> "No objects"
                    1 -> "1 object"
                    else -> "$count objects"
                } + if (snap.plates.size > 1) " · Plate ${snap.currentPlate + 1} of ${snap.plates.size}" else "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (done != null) {
                Text(
                    "Sliced · " + duration(done.report.timeEstimateSeconds),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onShowResult),
                )
            }
        }
        if (done != null) {
            IconButton(onClick = onShowResult) { Icon(AppIcons.Layers, "Show preview") }
        }
        Box {
            Button(
                onClick = { if (snap.plates.size > 1) sliceMenu = true else onSlice(false) },
                enabled = count > 0,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.height(48.dp),
            ) {
                Icon(Icons.Filled.PlayArrow, null)
                Spacer(Modifier.width(4.dp))
                Text("Slice", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
            DropdownMenu(expanded = sliceMenu, onDismissRequest = { sliceMenu = false }) {
                DropdownMenuItem(text = { Text("Slice this plate") }, onClick = { sliceMenu = false; onSlice(false) })
                DropdownMenuItem(text = { Text("Slice all plates") }, onClick = { sliceMenu = false; onSlice(true) })
            }
        }
    }
}

/** The paint layers as the tool column: supports, seams, colour (with several filaments) and fuzzy skin. */
fun paintLayerTools(vm: AppViewModel, paint: PaintState): List<ColumnTool> =
    PaintLayer.entries.filter { it != PaintLayer.MMU || vm.filamentSlots.size >= 2 }.map { l ->
        ColumnTool(
            l.label,
            when (l) {
                PaintLayer.SUPPORT -> AppIcons.Support
                PaintLayer.SEAM -> AppIcons.Seam
                PaintLayer.MMU -> AppIcons.Palette
                PaintLayer.FUZZY -> AppIcons.Fuzzy
            },
            active = paint.layer == l,
        ) { vm.choosePaintLayer(l) }
    }

/** What the painter paints and with which tool, beside the plate. */
@Composable
fun PaintSidePanel(vm: AppViewModel, paint: PaintState) {
    val colors = MaterialTheme.colorScheme
    SidePanel(paint.layer.label, Modifier.width(156.dp)) {
        for ((state, label) in paintStates(paint.layer, vm.filamentSlots.size)) {
            PanelRow(label, selected = paint.state == state, onClick = { vm.paint = paint.copy(state = state) }) {
                if (state != 0) {
                    val c = paintColour(paint.layer, state, vm.filamentSlots.map { it.colour })
                    Box(Modifier.size(11.dp).background(Color(c[0], c[1], c[2]), CircleShape))
                } else {
                    Icon(AppIcons.Eraser, null, Modifier.size(14.dp))
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 6.dp), color = colors.outlineVariant)
        for (t in PaintTool.entries) {
            val icon = when (t) {
                PaintTool.BRUSH -> AppIcons.Brush
                PaintTool.SMART_FILL -> AppIcons.SmartFill
                PaintTool.BUCKET -> AppIcons.Bucket
            }
            PanelRow(t.label, selected = paint.tool == t, onClick = { vm.paint = paint.copy(tool = t) }) {
                Icon(icon, null, Modifier.size(14.dp))
            }
        }
        if (paint.layer == PaintLayer.SUPPORT && vm.paintSupportsOff) {
            Spacer(Modifier.height(6.dp))
            Text("Supports are off for this object.", style = MaterialTheme.typography.labelSmall, color = colors.error)
            TextButton(onClick = { vm.enableSupportsForPaint() }) { Text("Turn on", style = MaterialTheme.typography.labelMedium) }
        }
    }
}

@Composable
private fun PanelRow(label: String, selected: Boolean, onClick: () -> Unit, lead: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp))
            .background(if (selected) colors.primary.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) { lead() }
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) colors.primary else colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The painter's bottom bar: the brush size or fill angle, clear and done. */
@Composable
fun PaintBottomBar(vm: AppViewModel, paint: PaintState) {
    BottomBar {
        when (paint.tool) {
            PaintTool.BRUSH -> {
                Text(String.format(Locale.US, "%.1f mm", paint.radius), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(52.dp))
                Slider(value = paint.radius, onValueChange = { vm.paint = paint.copy(radius = it) }, valueRange = 0.5f..15f, modifier = Modifier.weight(1f))
            }
            PaintTool.SMART_FILL -> {
                Text(String.format(Locale.US, "%.0f°", paint.angle), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(52.dp))
                Slider(value = paint.angle, onValueChange = { vm.paint = paint.copy(angle = it) }, valueRange = 1f..90f, modifier = Modifier.weight(1f))
            }
            PaintTool.BUCKET -> Text(
                "Tap a face to fill its area",
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = { vm.clearPaint() }) { Text("Clear") }
        Button(onClick = { vm.paint = null }, shape = RoundedCornerShape(12.dp)) { Text("Done", fontWeight = FontWeight.Bold) }
    }
}

/** Time, filament, cost and layer as tiles above the preview's bottom bar. */
@Composable
fun PreviewStats(vm: AppViewModel, done: SliceState.Done) {
    val tp = done.toolpath
    val tiles = buildList {
        add(duration(done.report.timeEstimateSeconds) to "time")
        if (tp != null && tp.filamentGrams > 0) add(String.format(Locale.US, "%.1f g", tp.filamentGrams) to "filament")
        if (tp != null && tp.filamentCost > 0) add(String.format(Locale.US, "%.2f", tp.filamentCost) to "cost")
        if (tp != null && tp.layerTotal > 0) {
            val hi = vm.previewRange.high.coerceIn(0, tp.layerTotal - 1)
            add("${hi + 1} / ${tp.layerTotal}" to "layer")
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((value, label) in tiles) {
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.96f)).padding(vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(value, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1, textAlign = TextAlign.Center)
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The vertical top-layer slider at the right edge of the preview. */
@Composable
fun LayerColumnSlider(vm: AppViewModel, done: SliceState.Done, modifier: Modifier = Modifier) {
    val tp = done.toolpath ?: return
    val last = tp.layerTotal - 1
    if (last <= 0) return
    val r = vm.previewRange
    val lo = r.low.coerceIn(0, last)
    val hi = r.high.coerceIn(lo, last)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("${hi + 1}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        VerticalSlider(
            value = hi.toFloat(),
            onValueChange = { v ->
                val nhi = v.toInt().coerceIn(lo, last)
                // A new top layer shows all of its moves again, as desktop's slider resets.
                vm.previewRange = r.copy(low = lo, high = nhi, moves = Int.MAX_VALUE)
            },
            range = 0f..last.toFloat(),
            modifier = Modifier.weight(1f),
        )
        Text("1", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The preview's bottom bar: details, export and print. */
@Composable
fun PreviewBottomBar(
    detailsOpen: Boolean,
    onDetails: () -> Unit,
    exports: List<Pair<String, () -> Unit>>,
    onPrint: () -> Unit,
) {
    var exportMenu by remember { mutableStateOf(false) }
    BottomBar {
        IconButton(onClick = onDetails) {
            Icon(AppIcons.Tune, if (detailsOpen) "Hide details" else "Show details", tint = if (detailsOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        }
        Box(Modifier.weight(1f)) {
            Row(
                Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable { exportMenu = true },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AppIcons.Export, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Export", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }
            DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }) {
                for ((label, action) in exports) DropdownMenuItem(text = { Text(label) }, onClick = { exportMenu = false; action() })
            }
        }
        Button(onClick = onPrint, shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1.2f).height(48.dp)) {
            Icon(AppIcons.Print, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Print", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }
    }
}

/** A Material slider turned upright: the bottom is the start of [range]. */
@Composable
private fun VerticalSlider(value: Float, onValueChange: (Float) -> Unit, range: ClosedFloatingPointRange<Float>, modifier: Modifier = Modifier) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = range,
        modifier = modifier
            .graphicsLayer { rotationZ = 270f; transformOrigin = TransformOrigin(0f, 0f) }
            .layout { measurable, constraints ->
                // Measure with width and height swapped, then place it rotated about its top-left corner.
                val placeable = measurable.measure(
                    Constraints(
                        minWidth = constraints.minHeight,
                        maxWidth = constraints.maxHeight,
                        minHeight = constraints.minWidth,
                        maxHeight = constraints.maxWidth,
                    ),
                )
                layout(placeable.height, placeable.width) { placeable.place(-placeable.width, 0) }
            },
    )
}

/**
 * The phone's plate screen below the tabs: the status and plate strip, the tool
 * column with its option panel, and the stage's bottom area over the viewport.
 */
@Composable
fun WorkbenchPhone(
    vm: AppViewModel,
    done: SliceState.Done?,
    previewing: Boolean,
    layFlat: Boolean,
    onLayFlat: () -> Unit,
    transformOpen: Boolean,
    onTransform: (Boolean) -> Unit,
    previewDetails: Boolean,
    onPreviewDetails: () -> Unit,
    status: @Composable () -> Unit,
    previewPanel: @Composable () -> Unit,
    onEditObject: (Selection) -> Unit,
    onImport: () -> Unit,
    onShape: () -> Unit,
    onOpenProject: () -> Unit,
    exports: (SliceState.Done) -> List<Pair<String, () -> Unit>>,
    onPrint: () -> Unit,
) {
    val paint = vm.paint
    val sel = vm.selection?.takeIf { vm.transformOf(it) != null }
    val toolOpen = vm.cut != null || vm.measure != null || vm.layerHeights != null
    val slicing = vm.slice as? SliceState.Running
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(8.dp)) { status() }
        Row(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp)) {
            when {
                previewing && done != null -> {
                    Spacer(Modifier.weight(1f))
                    if (!previewDetails) LayerColumnSlider(vm, done, Modifier.width(48.dp).fillMaxHeight().padding(vertical = 4.dp))
                }
                paint != null -> {
                    ToolColumn(paintLayerTools(vm, paint))
                    Spacer(Modifier.weight(1f))
                    PaintSidePanel(vm, paint)
                }
                toolOpen || slicing != null -> Unit
                else -> {
                    ToolColumn(prepareTools(vm, sel, layFlat, onLayFlat, transformOpen, onTransform, onEditObject))
                    Spacer(Modifier.weight(1f))
                    if (sel != null && transformOpen) TransformPanel(vm, sel) { onTransform(false) }
                }
            }
        }
        val bottom = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp).navigationBarsPadding()
        when {
            previewing && done != null -> {
                if (previewDetails) Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) { previewPanel() }
                Spacer(Modifier.height(6.dp))
                PreviewStats(vm, done)
                Spacer(Modifier.height(8.dp))
                PreviewBottomBar(previewDetails, onPreviewDetails, exports(done), onPrint)
            }
            slicing != null -> Box(bottom) { previewPanel() }
            vm.cut != null -> Box(bottom) { CutPanel(vm, vm.cut!!) }
            paint != null -> PaintBottomBar(vm, paint)
            vm.layerHeights != null -> Box(bottom) { LayerHeightPanel(vm, vm.layerHeights!!) }
            vm.measure != null -> Box(bottom) { MeasurePanel(vm, vm.measure!!) }
            else -> {
                if (vm.calibration != null) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) { CalibrationBanner(vm) }
                    Spacer(Modifier.height(8.dp))
                }
                ObjectStrip(vm, onImport = onImport, onLibrary = { vm.openLibrary() }, onShape = onShape, onOpenProject = onOpenProject)
                Spacer(Modifier.height(8.dp))
                SliceBar(vm, done, onSlice = { vm.startSlice(allPlates = it) }, onShowResult = { vm.showPreview = true })
            }
        }
    }
}

/** Prepare's tool column; the object tools wait for a selected object. */
private fun prepareTools(
    vm: AppViewModel,
    sel: Selection?,
    layFlat: Boolean,
    onLayFlat: () -> Unit,
    transformOpen: Boolean,
    onTransform: (Boolean) -> Unit,
    onEditObject: (Selection) -> Unit,
): List<ColumnTool> {
    val has = sel != null
    return listOf(
        ColumnTool("Move and scale", AppIcons.Move, enabled = has, active = has && transformOpen) { onTransform(!transformOpen) },
        ColumnTool(
            "Rotate", AppIcons.Rotate, enabled = has,
            menu = if (sel == null) emptyList() else listOf(
                "Turn 90° around Z" to { vm.rotate(sel, 2, 90.0) },
                "Tip 90° around X" to { vm.rotate(sel, 0, 90.0) },
                "Tip 90° around Y" to { vm.rotate(sel, 1, 90.0) },
                "Auto orient" to { vm.orient(sel) },
            ),
        ),
        ColumnTool("Lay flat", AppIcons.LayFlat, enabled = has, active = layFlat, onClick = onLayFlat),
        ColumnTool("Arrange", AppIcons.GridView, enabled = vm.scene.snapshot.objects.isNotEmpty()) { vm.arrange() },
        ColumnTool("Cut", AppIcons.Cut, enabled = has) { sel?.let(vm::startCut) },
        ColumnTool("Paint", AppIcons.Brush, enabled = has) { sel?.let { vm.startPaint(it.objectId) } },
        ColumnTool("Measure", AppIcons.Ruler, enabled = has) { sel?.let { vm.startMeasure(it.objectId) } },
        ColumnTool("Layer heights", AppIcons.Layers, enabled = has) { sel?.let(vm::startLayerHeights) },
        ColumnTool("Parts and settings", AppIcons.Tune, enabled = has) { sel?.let(onEditObject) },
    )
}
