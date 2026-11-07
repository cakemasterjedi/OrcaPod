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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.orcapod.app.engine.BedTypeState
import org.orcapod.app.engine.Plate
import org.orcapod.app.engine.PlateSettings
import org.orcapod.app.engine.PresetKind

/** Desktop's plate tabs: tap selects a plate, tapping the selected one opens its menu, "+" adds a plate. */
@Composable
fun PlateStrip(vm: AppViewModel) {
    val snap = vm.scene.snapshot
    var menuFor by remember { mutableStateOf<Plate?>(null) }
    var renaming by remember { mutableStateOf<Plate?>(null) }
    var settingsFor by remember { mutableStateOf<Plate?>(null) }
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for ((i, p) in snap.plates.withIndex()) {
            Box {
                FilterChip(
                    selected = i == snap.currentPlate,
                    onClick = { if (i != snap.currentPlate) vm.selectPlate(p.id) else menuFor = p },
                    label = { Text(p.name.ifEmpty { "Plate ${i + 1}" } + if (p.objectCount > 0) " · ${p.objectCount}" else "", maxLines = 1) },
                    leadingIcon = if (p.locked) ({ Icon(Icons.Filled.Lock, "Locked", Modifier.size(16.dp)) }) else null,
                )
                DropdownMenu(expanded = menuFor?.id == p.id, onDismissRequest = { menuFor = null }) {
                    DropdownMenuItem(text = { Text("Plate settings…") }, onClick = { menuFor = null; settingsFor = p })
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menuFor = null; renaming = p })
                    DropdownMenuItem(text = { Text(if (p.locked) "Unlock" else "Lock") }, onClick = { menuFor = null; vm.lockPlate(p.id, !p.locked) })
                    DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menuFor = null; vm.duplicatePlate(p.id) })
                    if (i > 0) DropdownMenuItem(text = { Text("Move to front") }, onClick = { menuFor = null; vm.movePlateToFront(p.id) })
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        enabled = snap.plates.size > 1 && p.objectCount == 0,
                        onClick = { menuFor = null; vm.deletePlate(p.id) },
                    )
                }
            }
        }
        IconButton(onClick = vm::addPlate, enabled = snap.plates.size < 36) { Icon(Icons.Filled.Add, "Add plate") }
    }
    renaming?.let { p ->
        TextDialog("Rename plate", p.name, onDismiss = { renaming = null }) { vm.renamePlate(p.id, it); renaming = null }
    }
    settingsFor?.let { p -> PlateSettingsDialog(vm, p) { settingsFor = null } }
}

/** Desktop plate settings: plate type, print sequence, spiral vase and filament order for this plate only. */
@Composable
private fun PlateSettingsDialog(vm: AppViewModel, plate: Plate, onDismiss: () -> Unit) {
    var settings by remember { mutableStateOf<PlateSettings?>(null) }
    LaunchedEffect(plate.id) { settings = vm.plateSettings(plate.id) }
    val s = settings ?: return
    val bed = vm.bedType
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings of ${plate.name.ifEmpty { "plate ${plate.index + 1}" }}") },
        text = {
            Column {
                if (bed != null && bed.selectable) {
                    Choice(
                        "Plate type",
                        listOf(null to "Same as project") + (1 until bed.count).filter { bed.supported(it) }.map { it to BedTypeState.NAMES.getOrElse(it) { "Plate $it" } },
                        s.bedType,
                    ) { settings = s.copy(bedType = it) }
                }
                Choice("Print sequence", listOf(null to "Same as project", false to "By layer", true to "By object"), s.printByObject) {
                    settings = s.copy(printByObject = it)
                }
                Choice("Spiral vase", listOf(null to "Same as project", false to "Off", true to "On"), s.spiralMode) {
                    settings = s.copy(spiralMode = it)
                }
                if (vm.filamentSlots.size > 1) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (s.firstLayerOrder.isEmpty()) "First layer filament order: automatic"
                        else "First layer filament order: ${s.firstLayerOrder.joinToString(" → ")}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row {
                        TextButton(onClick = { settings = s.copy(firstLayerOrder = (1..vm.filamentSlots.size).toList()) }) { Text("In slot order") }
                        TextButton(onClick = { settings = s.copy(firstLayerOrder = (1..vm.filamentSlots.size).reversed().toList()) }) { Text("Reversed") }
                        TextButton(onClick = { settings = s.copy(firstLayerOrder = emptyList()) }) { Text("Automatic") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.setPlateSettings(plate.id, s); onDismiss() }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun <T> Choice(label: String, options: List<Pair<T, String>>, current: T, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Box {
            TextButton(onClick = { open = true }) { Text(options.firstOrNull { it.first == current }?.second ?: "—") }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for ((v, text) in options) DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onPick(v) })
            }
        }
    }
}

/** Desktop's filament list: one preset and colour per slot, slots added or removed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilamentSlotsSheet(vm: AppViewModel, onEdit: () -> Unit, onDismiss: () -> Unit) {
    var picking by remember { mutableStateOf<Int?>(null) }
    var colouring by remember { mutableStateOf<Int?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxHeight(0.7f).padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Filaments", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { vm.setFilamentCount(vm.filamentSlots.size - 1) }, enabled = vm.filamentSlots.size > 1) {
                    Text("−", style = MaterialTheme.typography.titleLarge)
                }
                IconButton(onClick = { vm.setFilamentCount(vm.filamentSlots.size + 1) }, enabled = vm.filamentSlots.size < 16) {
                    Text("+", style = MaterialTheme.typography.titleLarge)
                }
            }
            Text(
                "Assign filaments to objects and parts from the object list. More than one filament needs a multi-material printer or tool changer.",
                style = MaterialTheme.typography.bodySmall,
            )
            LazyColumn(Modifier.weight(1f)) {
                itemsIndexed(vm.filamentSlots) { i, slot ->
                    ListItem(
                        modifier = Modifier.clickable { picking = i },
                        leadingContent = {
                            Box(
                                Modifier.size(32.dp).background(parseColor(slot.colour) ?: Color.Gray, CircleShape)
                                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape).clickable { colouring = i },
                                contentAlignment = Alignment.Center,
                            ) { Text("${i + 1}", style = MaterialTheme.typography.labelLarge, color = contrastOn(slot.colour)) }
                        },
                        headlineContent = { Text(slot.preset, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text("Tap to change the filament, the circle to change the colour") },
                    )
                    HorizontalDivider()
                }
            }
            TextButton(onClick = onEdit, modifier = Modifier.align(Alignment.End).padding(vertical = 8.dp)) { Text("Edit filament settings") }
        }
    }
    picking?.let { slot ->
        PresetPickerSheet(
            title = "Filament ${slot + 1}",
            entries = vm.filaments,
            current = vm.filamentSlots.getOrNull(slot)?.preset.orEmpty(),
            onPick = { vm.selectFilamentSlot(slot, it) },
            onDelete = { name -> vm.deleteUserPreset(PresetKind.FILAMENT, name) },
            onDismiss = { picking = null },
        )
    }
    colouring?.let { slot ->
        ColourDialog(vm.filamentSlots.getOrNull(slot)?.colour ?: "#FFFFFF", onDismiss = { colouring = null }) {
            vm.setFilamentColour(slot, it)
            colouring = null
        }
    }
}

private val PALETTE = listOf(
    "#FFFFFF", "#161616", "#8E9089", "#C12E1F", "#F4EE2A", "#FF6A13", "#00AE42", "#2850E0", "#0086D6", "#5E43B7",
    "#EC008C", "#F5547C", "#A3D8E1", "#9D2235", "#7D6556", "#C8C8C8",
)

@Composable
private fun ColourDialog(current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var hex by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Filament colour") },
        text = {
            Column {
                for (row in PALETTE.chunked(8)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 3.dp)) {
                        for (c in row) {
                            Box(
                                Modifier.size(28.dp).background(parseColor(c) ?: Color.Gray, RoundedCornerShape(6.dp))
                                    .border(if (c.equals(hex, true)) 3.dp else 1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                                    .clickable { hex = c },
                            )
                        }
                    }
                }
                androidx.compose.material3.OutlinedTextField(value = hex, onValueChange = { hex = it }, singleLine = true, label = { Text("#rrggbb") })
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(hex.trim().let { if (it.startsWith("#")) it else "#$it" }.uppercase()) }, enabled = parseColor(hex) != null) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun contrastOn(hex: String): Color {
    val c = parseColor(hex) ?: return Color.Black
    return if (0.299 * c.red + 0.587 * c.green + 0.114 * c.blue > 0.6) Color.Black else Color.White
}
