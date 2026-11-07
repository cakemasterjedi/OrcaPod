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
import org.orcapod.app.engine.PresetEntry
import org.orcapod.app.engine.PresetKind

/**
 * Searchable preset list; printers show their vendor, user presets can be deleted.
 * With more than one [brands], a row of chips above the list switches the loaded
 * brand through [onBrand]; [loading] shows while that brand loads.
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
) {
    var query by remember { mutableStateOf("") }
    val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val shown = entries.filter { e -> words.all { w -> e.name.contains(w, true) || e.vendor.contains(w, true) } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxHeight(0.9f).padding(horizontal = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            if (brands.size > 1) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (b in brands) {
                        FilterChip(selected = b == brand, onClick = { onBrand(b) }, enabled = !loading, label = { Text(b) })
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
                        modifier = Modifier.clickable { onPick(e.name); onDismiss() },
                        headlineContent = { Text(e.name) },
                        supportingContent = {
                            val tags = listOfNotNull(e.vendor.takeIf { it.isNotEmpty() }, "User preset".takeIf { e.isUser })
                            if (tags.isNotEmpty()) Text(tags.joinToString(" · "))
                        },
                        leadingContent = {
                            if (e.name == current) Icon(Icons.Filled.Check, contentDescription = "Selected")
                            else Spacer(Modifier.width(24.dp))
                        },
                        trailingContent = if (e.isUser && onDelete != null && e.name != current) {
                            { IconButton(onClick = { onDelete(e.name) }) { Icon(Icons.Filled.Delete, contentDescription = "Delete preset") } }
                        } else null,
                    )
                    HorizontalDivider()
                }
            }
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
