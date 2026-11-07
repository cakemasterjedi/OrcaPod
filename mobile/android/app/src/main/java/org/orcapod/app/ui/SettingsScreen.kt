package org.orcapod.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.orcapod.app.engine.BedTypeState
import org.orcapod.app.engine.PresetKind
import org.orcapod.app.settings.OptionDef
import org.orcapod.app.settings.OptionMode
import org.orcapod.app.settings.OptionPage

/** Options visible at [mode], like desktop's Simple / Advanced / Expert switch. */
fun OptionDef.visibleAt(mode: OptionMode) = this.mode.ordinal <= mode.ordinal

/**
 * The desktop settings tabs for the selected process, filament and printer
 * presets: every option, in desktop's pages and groups, edited on top of the
 * presets and saved as user presets.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel, initial: PresetKind = PresetKind.PROCESS, onDismiss: () -> Unit) {
    var kind by rememberSaveable { mutableStateOf(initial) }
    var query by rememberSaveable { mutableStateOf("") }
    var pageIndex by rememberSaveable(kind) { mutableStateOf(0) }
    var saving by remember { mutableStateOf<PresetKind?>(null) }
    var menu by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close") }
                    Column(Modifier.weight(1f)) {
                        Text("Settings", style = MaterialTheme.typography.titleLarge)
                        val name = when (kind) {
                            PresetKind.PROCESS -> vm.process
                            PresetKind.FILAMENT -> vm.filament
                            PresetKind.PRINTER -> vm.printer
                        }
                        val modified = vm.modifiedKeys[kind].orEmpty().isNotEmpty()
                        Text(
                            if (modified) "$name (modified)" else name,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    ModeSwitch(vm.settingsMode, vm::changeSettingsMode)
                    androidx.compose.foundation.layout.Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Save as preset…") },
                                enabled = vm.modifiedKeys[kind].orEmpty().isNotEmpty(),
                                onClick = { menu = false; saving = kind },
                            )
                            DropdownMenuItem(
                                text = { Text("Discard changes") },
                                enabled = vm.modifiedKeys[kind].orEmpty().isNotEmpty(),
                                onClick = { menu = false; vm.resetKind(kind) },
                            )
                        }
                    }
                }
                PrimaryTabRow(selectedTabIndex = TABS.indexOf(kind)) {
                    for (k in TABS) {
                        val n = vm.modifiedKeys[k].orEmpty().size
                        Tab(selected = k == kind, onClick = { kind = k }, text = { Text(if (n > 0) "${k.title()} • $n" else k.title()) })
                    }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    placeholder = { Text("Search settings") },
                )
                val pages = visiblePages(vm, kind, query)
                if (query.isBlank()) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        for ((i, p) in pages.withIndex()) {
                            FilterChip(selected = i == pageIndex, onClick = { pageIndex = i }, label = { Text(p.title) })
                        }
                    }
                }
                val shown = if (query.isBlank()) listOfNotNull(pages.getOrNull(pageIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0)))) else pages
                LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    if (kind == PresetKind.PRINTER && query.isBlank() && pageIndex == 0) {
                        vm.bedType?.takeIf { it.selectable }?.let { bed ->
                            item("bed_type") { BedTypeCard(bed, vm::setBedType) }
                        }
                    }
                    for (page in shown) {
                        if (query.isNotBlank()) item("p:${page.title}") {
                            Text(page.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                        }
                        for (group in page.groups) {
                            item("g:${page.title}:${group.title}") {
                                Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                        Text(group.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                                        for (key in group.keys) {
                                            val def = vm.optionDefs[key] ?: continue
                                            OptionRow(
                                                def = def,
                                                value = vm.valueOf(kind, key),
                                                modified = key in vm.modifiedKeys[kind].orEmpty(),
                                                onChange = { vm.setValue(kind, key, it) },
                                                onReset = { vm.resetValue(kind, key) },
                                            )
                                            HorizontalDivider()
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (shown.isEmpty()) item("empty") {
                        Text(
                            if (query.isBlank()) "No settings at this level; switch to Advanced or Expert." else "No setting matches \"$query\".",
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }
    saving?.let { k -> SavePresetDialog(vm, k) { saving = null } }
}

private val TABS = listOf(PresetKind.PROCESS, PresetKind.FILAMENT, PresetKind.PRINTER)

/** The layout's pages for [kind], keeping options the engine knows, visible at the current mode and matching [query]. */
private fun visiblePages(vm: AppViewModel, kind: PresetKind, query: String): List<OptionPage> {
    val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val keys = vm.layout.pages(kind).flatMap { p -> p.groups.flatMap { it.keys } }.filter { key ->
        val def = vm.optionDefs[key] ?: return@filter false
        // Searching reaches every level, like desktop's search.
        if (words.isNotEmpty()) words.all { w -> def.title.contains(w, true) || key.contains(w, true) || def.tooltip.contains(w, true) }
        else def.visibleAt(vm.settingsMode)
    }.toSet()
    return vm.layout.filtered(kind, keys)
}

@Composable
fun ModeSwitch(mode: OptionMode, onChange: (OptionMode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        TextButton(onClick = { open = true }) { Text(mode.label) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (m in listOf(OptionMode.SIMPLE, OptionMode.ADVANCED, OptionMode.EXPERT, OptionMode.DEVELOP)) {
                DropdownMenuItem(text = { Text(m.label) }, onClick = { open = false; onChange(m) })
            }
        }
    }
}

@Composable
private fun BedTypeCard(bed: BedTypeState, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Plate type", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            androidx.compose.foundation.layout.Box {
                TextButton(onClick = { open = true }) { Text(BedTypeState.NAMES.getOrElse(bed.current) { "Plate ${bed.current}" }) }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    for (t in 1 until bed.count) if (bed.supported(t)) {
                        DropdownMenuItem(text = { Text(BedTypeState.NAMES.getOrElse(t) { "Plate $t" }) }, onClick = { open = false; onChange(t) })
                    }
                }
            }
        }
    }
}

@Composable
fun SavePresetDialog(vm: AppViewModel, kind: PresetKind, onDismiss: () -> Unit) {
    val current = when (kind) {
        PresetKind.PROCESS -> vm.process
        PresetKind.FILAMENT -> vm.filament
        PresetKind.PRINTER -> vm.printer
    }
    val isUser = when (kind) {
        PresetKind.PROCESS -> vm.processes
        PresetKind.FILAMENT -> vm.filaments
        PresetKind.PRINTER -> vm.printers
    }.any { it.name == current && it.isUser }
    var name by remember { mutableStateOf(if (isUser) current else current.substringBefore(" @") + " (mine)") }
    var detach by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save ${kind.title().lowercase()} preset") },
        text = {
            Column {
                Text("Saves the edited settings as a user preset and selects it.")
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = detach, onCheckedChange = { detach = it })
                    Spacer(Modifier.width(4.dp))
                    Text("Detach from the system preset")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.savePreset(kind, name.trim(), detach); onDismiss() }, enabled = name.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
