package org.orcapod.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.orcapod.app.data.PresetChoice
import org.orcapod.app.data.VendorNames
import org.orcapod.app.engine.PresetEntry
import org.orcapod.app.engine.PresetKind

/**
 * Searchable preset list; printers show their vendor, user presets can be deleted.
 * With more than one [brands], a row of chips above the list switches the loaded
 * brand through [onBrand]; [loading] shows while that brand loads. With a
 * [choice], the list holds only the presets the user picked to show, and
 * "Choose" lets them tick which ones through [onChoose].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetPickerSheet(
    title: String,
    entries: List<PresetEntry>,
    current: String,
    onPick: (String) -> Unit,
    onDelete: ((String) -> Unit)?,
    onDismiss: () -> Unit,
    brands: List<String> = emptyList(),
    brand: String? = null,
    loading: Boolean = false,
    onBrand: (String) -> Unit = {},
    choice: PresetChoice? = null,
    onChoose: (Set<String>) -> Unit = {},
) {
    var query by remember { mutableStateOf("") }
    // Ticked presets while choosing which to show; null when picking a preset.
    var picking by remember { mutableStateOf<Set<String>?>(null) }
    val ticked = picking
    val listed = if (choice == null || ticked != null) entries else choice.filter(entries, current)
    val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val shown = listed.filter { e ->
        val vendor = VendorNames.display(e.vendor)
        words.all { w -> e.name.contains(w, true) || vendor.contains(w, true) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxHeight(0.9f).padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                if (choice != null) {
                    if (ticked == null) {
                        TextButton(onClick = {
                            val hiding = choice.hidesAny(entries, current)
                            picking = if (hiding) listed.mapTo(HashSet()) { it.name } else emptySet()
                        }) { Text("Choose") }
                    } else {
                        TextButton(onClick = { picking = emptySet() }, enabled = ticked.isNotEmpty()) { Text("Clear") }
                        TextButton(onClick = { onChoose(ticked); picking = null }) { Text("Done") }
                    }
                }
            }
            when {
                ticked != null -> Text(
                    if (ticked.isEmpty()) "Tick the ones you use; only those will be listed. With none ticked, all are listed."
                    else "${ticked.size} ticked; only those will be listed.",
                    style = MaterialTheme.typography.bodySmall,
                )
                listed.size < entries.size -> Text(
                    "Showing ${listed.size} of ${entries.size}. Tap Choose to change which are listed.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (brands.size > 1 && ticked == null) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (b in brands) {
                        FilterChip(selected = b == brand, onClick = { onBrand(b) }, enabled = !loading, label = { Text(VendorNames.display(b)) })
                    }
                }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text("Search") },
            )
            LazyColumn(Modifier.weight(1f)) {
                items(shown, key = { it.name }) { e ->
                    ListItem(
                        modifier = Modifier.clickable {
                            if (ticked != null) {
                                picking = if (e.name in ticked) ticked - e.name else ticked + e.name
                            } else {
                                onPick(e.name)
                                onDismiss()
                            }
                        },
                        headlineContent = { Text(e.name) },
                        supportingContent = {
                            val tags = listOfNotNull(
                                e.vendor.takeIf { it.isNotEmpty() }?.let(VendorNames::display),
                                "User preset".takeIf { e.isUser },
                            )
                            if (tags.isNotEmpty()) Text(tags.joinToString(" · "))
                        },
                        leadingContent = {
                            when {
                                ticked != null -> Checkbox(checked = e.name in ticked, onCheckedChange = null)
                                e.name == current -> Icon(Icons.Filled.Check, contentDescription = "Selected")
                                else -> Spacer(Modifier.width(24.dp))
                            }
                        },
                        trailingContent = if (ticked == null && e.isUser && onDelete != null && e.name != current) {
                            { IconButton(onClick = { onDelete(e.name) }) { Icon(Icons.Filled.Delete, contentDescription = "Delete preset") } }
                        } else null,
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

/** The most used process and filament settings, edited on top of the selected presets. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(viewModel: AppViewModel, onDismiss: () -> Unit) {
    var saving by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxHeight(0.9f).padding(horizontal = 16.dp)) {
            Text("Print settings", style = MaterialTheme.typography.titleLarge)
            Text(
                "Changes apply on top of \"${viewModel.process}\" and \"${viewModel.filament}\".",
                style = MaterialTheme.typography.bodySmall,
            )
            LazyColumn(Modifier.weight(1f).padding(top = 8.dp)) {
                items(viewModel.quickSettings, key = { it.key }) { s ->
                    SettingRow(
                        setting = s,
                        value = viewModel.settings[s.key].orEmpty(),
                        edited = s.key in viewModel.editedKeys,
                        onChange = { viewModel.setSetting(s, it) },
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = viewModel::resetSettings, enabled = viewModel.editedKeys.isNotEmpty()) { Text("Reset") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { saving = true }, enabled = viewModel.editedKeys.isNotEmpty()) { Text("Save as preset") }
            }
        }
    }
    if (saving) {
        var name by remember { mutableStateOf(viewModel.process.substringBefore(" @") + " (mine)") }
        AlertDialog(
            onDismissRequest = { saving = false },
            title = { Text("Save preset") },
            text = {
                Column {
                    Text("Saves the edited settings as a user preset and selects it.")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") })
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.saveEdits(name.trim()); saving = false }, enabled = name.isNotBlank()) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { saving = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingRow(setting: QuickSetting, value: String, edited: Boolean, onChange: (String) -> Unit) {
    val label = if (edited) "${setting.label} •" else setting.label
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        when {
            setting.isBool -> Switch(checked = value == "1" || value == "true", onCheckedChange = { onChange(if (it) "1" else "0") })
            setting.choices != null -> {
                var open by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }, modifier = Modifier.width(190.dp)) {
                    OutlinedTextField(
                        value = setting.choices.firstOrNull { it.first == value }?.second ?: value,
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        for ((v, text) in setting.choices) {
                            DropdownMenuItem(text = { Text(text) }, onClick = { open = false; if (v != value) onChange(v) })
                        }
                    }
                }
            }
            else -> NumberField(
                value = value.removeSuffix("%"),
                suffix = setting.unit,
                modifier = Modifier.width(130.dp),
                onCommit = { text ->
                    val v = if (setting.unit == "%") "$text%" else text
                    if (v != value) onChange(v)
                },
            )
        }
    }
}

/** A numeric field that commits on Done or when it loses focus. */
@Composable
fun NumberField(
    value: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    suffix: String = "",
    onCommit: (String) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    fun commit() {
        val t = text.trim().replace(',', '.')
        if (t.toDoubleOrNull() != null && t != value) onCommit(t) else text = value
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        modifier = modifier.onFocusChanged {
            if (focused && !it.isFocused) commit()
            focused = it.isFocused
        },
        singleLine = true,
        label = label?.let { { Text(it) } },
        suffix = if (suffix.isNotEmpty()) ({ Text(suffix) }) else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit() }),
    )
}

fun PresetKind.title() = when (this) {
    PresetKind.PRINTER -> "Printer"
    PresetKind.FILAMENT -> "Filament"
    PresetKind.PROCESS -> "Process"
}
