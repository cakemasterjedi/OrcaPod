package org.orcapod.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.orcapod.app.engine.BooleanOperation
import org.orcapod.app.engine.HeightRange
import org.orcapod.app.engine.MeshErrors
import org.orcapod.app.engine.PresetKind
import org.orcapod.app.engine.SceneObject
import org.orcapod.app.engine.SceneVolume
import org.orcapod.app.engine.Shape
import org.orcapod.app.engine.VolumeType
import org.orcapod.app.ui.viewport.Selection
import java.util.Locale
import kotlin.math.roundToInt

private val MODEL_TYPES = arrayOf("*/*")

/** Desktop's object list: every object with its parts and modifiers. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ObjectListSheet(vm: AppViewModel, onOpen: (Selection) -> Unit, onDismiss: () -> Unit) {
    var checked by remember { mutableStateOf(setOf<Long>()) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxHeight(0.85f).padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Objects", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                val objects = vm.scene.snapshot.objects
                // Desktop's "Fix model" on the ticked objects, or on every object with errors left.
                val broken = objects.filter { vm.meshErrors[it.id]?.manifold == false }.map { it.id }
                val toRepair = checked.toList().ifEmpty { broken }
                if (toRepair.isNotEmpty()) {
                    TextButton(onClick = { vm.repair(toRepair); checked = emptySet() }) {
                        Text(if (toRepair.size == 1) "Repair 1" else "Repair ${toRepair.size}")
                    }
                }
                if (checked.size >= 2) {
                    TextButton(onClick = { vm.mergeObjects(checked.toList()); checked = emptySet() }) { Text("Assemble ${checked.size}") }
                }
            }
            val objects = vm.scene.snapshot.objects
            if (objects.isEmpty()) Text("The plate is empty.", Modifier.padding(vertical = 16.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(objects.toList(), key = { it.id }) { obj ->
                    val plates = obj.instances.map { it.plate }.distinct().filter { it >= 0 }
                    ListItem(
                        modifier = Modifier.clickable { obj.instances.firstOrNull()?.let { onOpen(Selection(obj.id, it.id)) } },
                        leadingContent = {
                            Checkbox(checked = obj.id in checked, onCheckedChange = { c -> checked = if (c) checked + obj.id else checked - obj.id })
                        },
                        headlineContent = { Text(obj.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            val bits = listOfNotNull(
                                if (obj.instances.size > 1) "${obj.instances.size} copies" else null,
                                plates.takeIf { it.isNotEmpty() }?.joinToString(prefix = "Plate ") { (it + 1).toString() } ?: "Off the plates",
                                obj.volumes.size.takeIf { it > 1 }?.let { "$it parts and modifiers" },
                                obj.overrideCount.takeIf { it > 0 }?.let { "$it settings" },
                                if (obj.instances.none { it.printable }) "Not printable" else null,
                            )
                            Text(bits.joinToString(" · "))
                        },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                MeshWarning(vm.meshErrors[obj.id])
                                FilamentSwatch(vm, obj.extruder)
                            }
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun FilamentSwatch(vm: AppViewModel, extruder: Int, size: Int = 18) {
    val slots = vm.filamentSlots
    if (slots.size <= 1) return
    val slot = (if (extruder <= 0) 1 else extruder).coerceIn(1, slots.size)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(size.dp).background(parseColor(slots[slot - 1].colour) ?: Color.Gray, CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
        )
        Spacer(Modifier.width(4.dp))
        Text("$slot", style = MaterialTheme.typography.labelMedium)
    }
}

private enum class ObjectTab(val title: String) { PARTS("Parts"), SETTINGS("Settings"), RANGES("Layer ranges"), MESH("Copies and mesh") }

/** One object's desktop object-list features: parts and modifiers, settings, layer ranges, copies and mesh tools. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ObjectSheet(vm: AppViewModel, sel: Selection, onDismiss: () -> Unit) {
    val obj = vm.objectOf(sel.objectId)
    if (obj == null) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    var tab by rememberSaveable { mutableStateOf(ObjectTab.PARTS) }
    var target by remember(obj.id) { mutableStateOf(0L) }
    var renaming by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxHeight(0.88f)) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(obj.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                IconButton(onClick = { renaming = true }) { Icon(Icons.Filled.Edit, "Rename") }
                Spacer(Modifier.weight(1f))
                FilamentPicker(vm, obj.extruder, allowDefault = false) { vm.setFilament(obj.id, 0, it) }
            }
            PrimaryScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = 8.dp) {
                for (t in ObjectTab.entries) Tab(selected = t == tab, onClick = { tab = t }, text = { Text(t.title) })
            }
            Box(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                when (tab) {
                    ObjectTab.PARTS -> PartsTab(vm, obj, sel, onSettings = { target = it; tab = ObjectTab.SETTINGS })
                    ObjectTab.SETTINGS -> OverridesTab(vm, obj, target) { target = it }
                    ObjectTab.RANGES -> RangesTab(vm, obj)
                    ObjectTab.MESH -> MeshTab(vm, obj, sel)
                }
            }
        }
    }
    if (renaming) {
        TextDialog("Rename object", obj.name, onDismiss = { renaming = false }) { vm.renameObject(obj.id, it); renaming = false }
    }
}

// --- parts and modifiers ---

@Composable
private fun PartsTab(vm: AppViewModel, obj: SceneObject, sel: Selection, onSettings: (Long) -> Unit) {
    var adding by remember { mutableStateOf(false) }
    var fileType by remember { mutableStateOf<VolumeType?>(null) }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val t = fileType
        if (uri != null && t != null) vm.addVolumeFromFile(obj.id, t, uri)
        fileType = null
    }
    Column(Modifier.fillMaxWidth()) {
        LazyColumn(Modifier.weight(1f)) {
            items(obj.volumes.toList(), key = { it.id }) { v -> VolumeRow(vm, obj, v, onSettings) }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.End) {
            Box {
                FilledTonalButton(onClick = { adding = true }) {
                    Icon(Icons.Filled.Add, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Add")
                }
                DropdownMenu(expanded = adding, onDismissRequest = { adding = false }) {
                    for (type in VolumeType.entries) {
                        Text(type.label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                        for (shape in Shape.entries) {
                            if (shape == Shape.SLAB && type == VolumeType.PART) continue
                            DropdownMenuItem(text = { Text("   ${shape.label}") }, onClick = { adding = false; vm.addVolume(sel, type, shape) })
                        }
                        DropdownMenuItem(text = { Text("   From file…") }, onClick = { adding = false; fileType = type; pickFile.launch(MODEL_TYPES) })
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun VolumeRow(vm: AppViewModel, obj: SceneObject, v: SceneVolume, onSettings: (Long) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var simplifying by remember { mutableStateOf(false) }
    val selected = vm.selectedVolume == v.id
    val partCount = obj.parts.size
    ListItem(
        modifier = Modifier.clickable { vm.selectedVolume = if (selected) null else v.id },
        tonalElevation = if (selected) 4.dp else 0.dp,
        leadingContent = { VolumeBadge(v.type) },
        headlineContent = { Text(v.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(listOfNotNull(v.type.label, v.overrideCount.takeIf { it > 0 }?.let { "$it settings" }).joinToString(" · "))
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (v.type == VolumeType.PART || v.type == VolumeType.MODIFIER) FilamentSwatch(vm, v.extruder.takeIf { it > 0 } ?: obj.extruder)
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Part actions") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (v.type == VolumeType.PART || v.type == VolumeType.MODIFIER) {
                            DropdownMenuItem(text = { Text("Settings") }, onClick = { menu = false; onSettings(v.id) })
                        }
                        DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; renaming = true })
                        for (t in VolumeType.entries) if (t != v.type && !(v.type == VolumeType.PART && partCount <= 1)) {
                            DropdownMenuItem(text = { Text("Change to ${t.label.lowercase()}") }, onClick = { menu = false; vm.setVolumeType(obj.id, v.id, t) })
                        }
                        if (vm.filamentSlots.size > 1 && (v.type == VolumeType.PART || v.type == VolumeType.MODIFIER)) {
                            DropdownMenuItem(text = { Text("Filament: same as object") }, onClick = { menu = false; vm.setFilament(obj.id, v.id, 0) })
                            for ((i, slot) in vm.filamentSlots.withIndex()) {
                                DropdownMenuItem(
                                    text = { Text("Filament ${i + 1}") },
                                    leadingIcon = { Box(Modifier.size(14.dp).background(parseColor(slot.colour) ?: Color.Gray, CircleShape)) },
                                    onClick = { menu = false; vm.setFilament(obj.id, v.id, i + 1) },
                                )
                            }
                        }
                        if (v.type == VolumeType.PART) {
                            DropdownMenuItem(text = { Text("Split to parts") }, onClick = { menu = false; vm.splitToParts(obj.id, v.id) })
                        }
                        DropdownMenuItem(text = { Text("Repair") }, onClick = { menu = false; vm.repair(listOf(obj.id), v.id) })
                        DropdownMenuItem(text = { Text("Simplify…") }, onClick = { menu = false; simplifying = true })
                        DropdownMenuItem(text = { Text("Subdivide") }, onClick = { menu = false; vm.subdivide(obj.id, v.id) })
                        if (!(v.type == VolumeType.PART && partCount <= 1)) {
                            DropdownMenuItem(
                                text = { Text("Delete") },
                                leadingIcon = { Icon(Icons.Filled.Delete, null) },
                                onClick = { menu = false; vm.deleteVolume(obj.id, v.id) },
                            )
                        }
                    }
                }
            }
        },
    )
    HorizontalDivider()
    if (renaming) TextDialog("Rename", v.name, onDismiss = { renaming = false }) { vm.renameVolume(obj.id, v.id, it); renaming = false }
    if (simplifying) {
        val tris = vm.scene.meshes[obj.id]?.let { m ->
            if (v.type == VolumeType.PART) v.mergedTriangleCount else m.volumes[v.id]?.indices?.size?.div(3) ?: 0
        } ?: 0
        SimplifyDialog(tris, onDismiss = { simplifying = false }) { target -> vm.simplify(obj.id, v.id, target); simplifying = false }
    }
}

@Composable
private fun VolumeBadge(type: VolumeType) {
    val (letter, color) = when (type) {
        VolumeType.PART -> "P" to Color(0xFF00AE42)
        VolumeType.NEGATIVE -> "N" to Color(0xFF8A8A8A)
        VolumeType.MODIFIER -> "M" to Color(0xFFE0B020)
        VolumeType.SUPPORT_BLOCKER -> "B" to Color(0xFFE04040)
        VolumeType.SUPPORT_ENFORCER -> "E" to Color(0xFF3C78E0)
    }
    Box(Modifier.size(28.dp).background(color, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
        Text(letter, color = Color.White, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun SimplifyDialog(triangles: Int, onDismiss: () -> Unit, onApply: (Int) -> Unit) {
    var percent by remember { mutableStateOf(50f) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Simplify") },
        text = {
            Column {
                Text("Reduces the mesh to ${percent.roundToInt()}% of its $triangles triangles. Painted areas on it are cleared.")
                Slider(value = percent, onValueChange = { percent = it }, valueRange = 5f..95f)
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply((triangles * percent / 100f).roundToInt().coerceAtLeast(4)) }, enabled = triangles > 4) { Text("Simplify") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// --- per-object and per-part settings ---

/** Desktop "per-object settings": options added on top of the presets for the object or one part. */
@Composable
private fun OverridesTab(vm: AppViewModel, obj: SceneObject, target: Long, onTarget: (Long) -> Unit) {
    val tgt = OverrideTarget(obj.id, target)
    var values by remember(obj.id, target) { mutableStateOf<Map<String, String>?>(null) }
    var adding by remember { mutableStateOf(false) }
    // Re-read whenever the document changes (undo, edits here or elsewhere).
    LaunchedEffect(obj.id, target, vm.scene.snapshot.revision) { values = vm.overrides(tgt) }
    val targets = listOf(0L to "Object") + obj.volumes.filter { it.type == VolumeType.PART || it.type == VolumeType.MODIFIER }
        .map { it.id to it.name }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((id, name) in targets) FilterChip(selected = id == target, onClick = { onTarget(id) }, label = { Text(name, maxLines = 1) })
        }
        val current = values
        val keys = current?.keys.orEmpty().sortedBy { vm.layout.location(PresetKind.PROCESS, it) ?: "~$it" }
        LazyColumn(Modifier.weight(1f)) {
            if (current != null && current.isEmpty()) item("none") {
                Text(
                    "No settings of its own: this ${if (target == 0L) "object" else "part"} prints with the process settings. " +
                        "Add a setting to change it only here.",
                    modifier = Modifier.padding(vertical = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            items(keys, key = { it }) { key ->
                val def = vm.optionDefs[key] ?: return@items
                OptionRow(
                    def = def,
                    value = current?.get(key).orEmpty(),
                    modified = true,
                    onChange = { vm.setOverride(tgt, key, it) },
                    onReset = { vm.eraseOverride(tgt, key) },
                    location = vm.layout.location(PresetKind.PROCESS, key),
                )
                HorizontalDivider()
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.copyOverrides(tgt) }, enabled = !current.isNullOrEmpty()) { Text("Copy") }
            TextButton(onClick = { vm.pasteOverrides(tgt) }, enabled = vm.copiedOverrides != null) { Text("Paste") }
            Spacer(Modifier.weight(1f))
            FilledTonalButton(onClick = { adding = true }) {
                Icon(Icons.Filled.Add, null)
                Spacer(Modifier.width(6.dp))
                Text("Add setting")
            }
        }
    }
    if (adding) {
        AddOverrideDialog(vm, forVolume = target != 0L, existing = values?.keys.orEmpty(), onDismiss = { adding = false }) { key ->
            adding = false
            vm.setOverride(tgt, key, vm.inheritedValue(key))
        }
    }
}

/** Searchable list of the options a target accepts, grouped like the process tab. */
@Composable
private fun AddOverrideDialog(vm: AppViewModel, forVolume: Boolean, existing: Set<String>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val allowed = vm.overrideKeys[if (forVolume) 1 else 0] - existing
    val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val matching = allowed.filter { key ->
        val def = vm.optionDefs[key] ?: return@filter false
        if (words.isEmpty()) def.visibleAt(vm.settingsMode) else words.all { def.title.contains(it, true) || key.contains(it, true) }
    }.toSet()
    val pages = vm.layout.filtered(PresetKind.PROCESS, matching)
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxHeight(0.85f).padding(16.dp)) {
                Text("Add setting", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    placeholder = { Text("Search") },
                )
                LazyColumn(Modifier.weight(1f)) {
                    for (page in pages) for (group in page.groups) {
                        item("h:${page.title}:${group.title}") {
                            Text("${page.title} › ${group.title}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                        }
                        items(group.keys, key = { "k:${page.title}:${group.title}:$it" }) { key ->
                            Text(
                                vm.optionDefs[key]?.title ?: key,
                                modifier = Modifier.fillMaxWidth().clickable { onPick(key) }.padding(vertical = 10.dp),
                            )
                        }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Cancel") }
            }
        }
    }
}

// --- height range modifiers ---

@Composable
private fun RangesTab(vm: AppViewModel, obj: SceneObject) {
    var ranges by remember(obj.id) { mutableStateOf<List<HeightRange>?>(null) }
    LaunchedEffect(obj.id, vm.scene.snapshot.revision) { ranges = vm.heightRanges(obj.id) }
    val height = (obj.bboxMax[2] - obj.bboxMin[2]).toDouble()
    val layer = vm.valueOf(PresetKind.PROCESS, "layer_height").toDoubleOrNull() ?: 0.2
    val list = ranges.orEmpty()
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Text(
            "Height range modifiers print a band of the object with its own layer height " +
                String.format(Locale.US, "(object height %.2f mm).", height),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        for ((i, r) in list.withIndex()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NumberField(fmt(r.minZ), Modifier.weight(1f), label = "From", suffix = "mm") { v ->
                    vm.setHeightRanges(obj.id, list.toMutableList().also { it[i] = r.copy(minZ = v.toDouble()) })
                }
                NumberField(fmt(r.maxZ), Modifier.weight(1f), label = "To", suffix = "mm") { v ->
                    vm.setHeightRanges(obj.id, list.toMutableList().also { it[i] = r.copy(maxZ = v.toDouble()) })
                }
                NumberField(fmt(r.layerHeight), Modifier.weight(1f), label = "Layer", suffix = "mm") { v ->
                    vm.setHeightRanges(obj.id, list.toMutableList().also { it[i] = r.copy(layerHeight = v.toDouble()) })
                }
                IconButton(onClick = { vm.setHeightRanges(obj.id, list.filterIndexed { k, _ -> k != i }) }) { Icon(Icons.Filled.Delete, "Delete range") }
            }
        }
        val start = list.maxOfOrNull { it.maxZ } ?: 0.0
        OutlinedButton(
            onClick = { vm.setHeightRanges(obj.id, list + HeightRange(start, minOf(start + 2.0, height), layer)) },
            enabled = start < height - 1e-3,
            modifier = Modifier.padding(vertical = 8.dp),
        ) {
            Icon(Icons.Filled.Add, null)
            Spacer(Modifier.width(6.dp))
            Text("Add range")
        }
    }
}

// --- copies and mesh ---

@Composable
private fun MeshTab(vm: AppViewModel, obj: SceneObject, sel: Selection) {
    var cloning by remember { mutableStateOf(false) }
    var boolean by remember { mutableStateOf(false) }
    val exportStl = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("model/stl")) { uri ->
        if (uri != null) vm.exportStl(obj.id, uri)
    }
    val printable = obj.instances.any { it.printable }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Copies on the plate", Modifier.weight(1f))
            IconButton(onClick = { vm.setInstanceCount(obj.id, obj.instances.size - 1) }, enabled = obj.instances.size > 1) { Text("−", style = MaterialTheme.typography.titleLarge) }
            Text("${obj.instances.size}", style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = { vm.setInstanceCount(obj.id, obj.instances.size + 1) }) { Text("+", style = MaterialTheme.typography.titleLarge) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Printable", Modifier.weight(1f))
            Switch(checked = printable, onCheckedChange = { vm.setPrintable(obj.id, 0, it) })
        }
        Spacer(Modifier.height(8.dp))
        Text("Arrange", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AssistChip(onClick = { cloning = true }, label = { Text("Clone…") })
            AssistChip(onClick = { vm.fillBed(obj.id) }, label = { Text("Fill plate") })
            if (obj.instances.size > 1) AssistChip(onClick = { vm.instanceToObject(sel) }, label = { Text("Set as separate object") })
            AssistChip(onClick = { vm.drop(obj.id) }, label = { Text("Drop to bed") })
        }
        Spacer(Modifier.height(8.dp))
        Text("Mirror", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((axis, name) in listOf(0 to "X", 1 to "Y", 2 to "Z")) AssistChip(onClick = { vm.mirror(sel, axis) }, label = { Text("Along $name") })
        }
        Spacer(Modifier.height(8.dp))
        Text("Mesh", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Text(meshErrorText(vm.meshErrors[obj.id]), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AssistChip(onClick = { vm.repair(listOf(obj.id)) }, label = { Text("Repair") })
            if (obj.volumes.size > 1) AssistChip(onClick = { boolean = true }, label = { Text("Boolean…") })
            AssistChip(onClick = { vm.splitToObjects(obj.id) }, label = { Text("Split to objects") })
            if (obj.parts.size == 1) AssistChip(onClick = { vm.splitToParts(obj.id, obj.parts[0].id) }, label = { Text("Split to parts") })
            AssistChip(onClick = { exportStl.launch(obj.name.substringBeforeLast('.') + ".stl") }, label = { Text("Export STL") })
        }
        Spacer(Modifier.height(8.dp))
        Text("Units", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AssistChip(onClick = { vm.convertUnits(obj.id, 0) }, label = { Text("Inches to mm") })
            AssistChip(onClick = { vm.convertUnits(obj.id, 1) }, label = { Text("mm to inches") })
            AssistChip(onClick = { vm.convertUnits(obj.id, 2) }, label = { Text("Metres to mm") })
            AssistChip(onClick = { vm.convertUnits(obj.id, 3) }, label = { Text("mm to metres") })
        }
    }
    if (boolean) BooleanDialog(vm, obj, onDismiss = { boolean = false })
    if (cloning) {
        TextDialog("Clone", "1", numeric = true, onDismiss = { cloning = false }) { text ->
            cloning = false
            text.toIntOrNull()?.takeIf { it in 1..100 }?.let { n -> vm.cloneObject(obj.id, n) }
        }
    }
}

// --- repair and booleans ---

/** Desktop's object list warning icon: errors left, or errors fixed on import. */
@Composable
fun MeshWarning(errors: MeshErrors?) {
    if (errors == null || !errors.hasWarning) return
    Icon(
        Icons.Filled.Warning,
        contentDescription = meshErrorText(errors),
        tint = if (errors.manifold) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFFE0A000),
        modifier = Modifier.size(18.dp).padding(end = 2.dp),
    )
}

/** Desktop ObjectList::get_mesh_errors_info, as one line. */
fun meshErrorText(errors: MeshErrors?): String {
    if (errors == null) return ""
    val parts = mutableListOf<String>()
    if (!errors.manifold) parts += if (errors.openEdges == 1) "1 non-manifold edge" else "${errors.openEdges} non-manifold edges"
    if (errors.repaired) parts += if (errors.repairedErrors == 1) "1 error repaired on import" else "${errors.repairedErrors} errors repaired on import"
    return if (parts.isEmpty()) "No mesh errors" else parts.joinToString(" · ")
}

/**
 * Desktop Mesh Boolean gizmo: union, difference or intersection of two parts of this object.
 * The result replaces the first part; the tool goes away for a union, or when "Delete input" is on.
 */
@Composable
private fun BooleanDialog(vm: AppViewModel, obj: SceneObject, onDismiss: () -> Unit) {
    val volumes = obj.volumes.toList()
    var operation by remember { mutableStateOf(BooleanOperation.UNION) }
    var source by remember { mutableStateOf(vm.selectedVolume?.takeIf { id -> volumes.any { it.id == id } } ?: volumes[0].id) }
    var tool by remember { mutableStateOf(volumes.first { it.id != source }.id) }
    var deleteTool by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mesh boolean") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (op in BooleanOperation.entries) FilterChip(selected = op == operation, onClick = { operation = op }, label = { Text(op.label) })
                }
                VolumePicker("Object", volumes, source) { picked ->
                    if (picked == tool) tool = source
                    source = picked
                }
                VolumePicker(if (operation == BooleanOperation.DIFFERENCE) "Subtract" else "Tool", volumes, tool) { picked ->
                    if (picked == source) source = tool
                    tool = picked
                }
                if (operation == BooleanOperation.UNION) {
                    Text("Both parts are replaced by their union.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        Checkbox(checked = deleteTool, onCheckedChange = { deleteTool = it })
                        Text("Delete input")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDismiss(); vm.meshBoolean(obj.id, source, tool, operation, deleteTool) }) { Text(operation.label) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun VolumePicker(label: String, volumes: List<SceneVolume>, current: Long, onPick: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val v = volumes.firstOrNull { it.id == current }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        Text(label, Modifier.width(72.dp), style = MaterialTheme.typography.labelLarge)
        Box(Modifier.weight(1f)) {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                if (v != null) VolumeBadge(v.type)
                Spacer(Modifier.width(8.dp))
                Text(v?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for (item in volumes) {
                    DropdownMenuItem(
                        text = { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { VolumeBadge(item.type) },
                        onClick = { open = false; onPick(item.id) },
                    )
                }
            }
        }
    }
}

// --- shared bits ---

/** Picks a filament slot (1-based); [allowDefault] adds "Object's" (0). Hidden with one filament. */
@Composable
fun FilamentPicker(vm: AppViewModel, current: Int, allowDefault: Boolean, onPick: (Int) -> Unit) {
    val slots = vm.filamentSlots
    if (slots.size <= 1) return
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            FilamentSwatch(vm, current)
            Spacer(Modifier.width(4.dp))
            Text("Filament")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (allowDefault) DropdownMenuItem(text = { Text("Same as object") }, onClick = { open = false; onPick(0) })
            for ((i, slot) in slots.withIndex()) {
                DropdownMenuItem(
                    text = { Text("${i + 1}: ${slot.preset}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Box(Modifier.size(16.dp).background(parseColor(slot.colour) ?: Color.Gray, CircleShape)) },
                    onClick = { open = false; onPick(i + 1) },
                )
            }
        }
    }
}

@Composable
fun TextDialog(title: String, initial: String, numeric: Boolean = false, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = if (numeric) androidx.compose.ui.text.input.KeyboardType.Number else androidx.compose.ui.text.input.KeyboardType.Text,
                ),
            )
        },
        confirmButton = { TextButton(onClick = { onDone(text.trim()) }, enabled = text.isNotBlank()) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

internal fun fmt(v: Double) = String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
