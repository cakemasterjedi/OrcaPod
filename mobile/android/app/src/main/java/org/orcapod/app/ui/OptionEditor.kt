package org.orcapod.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.orcapod.app.settings.OptionDef
import org.orcapod.app.settings.Values

/**
 * One option row of a settings editor: label, the editor its type calls for,
 * and the desktop affordances (modified marker, reset, tooltip).
 *
 * [value] is the option's serialized value; [onChange] receives the new
 * serialized value. [onReset] is shown while the value is modified.
 */
@Composable
fun OptionRow(
    def: OptionDef,
    value: String,
    modified: Boolean,
    onChange: (String) -> Unit,
    onReset: (() -> Unit)?,
    location: String? = null,
) {
    var info by remember { mutableStateOf(false) }
    val elements = Values.elements(def, value)
    val wide = def.type == "string" || def.type == "point" || def.type.endsWith("groups")
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (modified) {
                Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                Spacer(Modifier.width(6.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(def.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (location != null) Text(location, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
            if (def.tooltip.isNotBlank()) {
                IconButton(onClick = { info = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Info, "About this setting", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.outline)
                }
            }
            if (modified && onReset != null) {
                IconButton(onClick = onReset, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Refresh, "Reset to the preset value", Modifier.size(18.dp))
                }
            }
            if (!wide && elements.size <= 1) {
                Spacer(Modifier.width(8.dp))
                Box(Modifier.width(170.dp)) {
                    ElementEditor(def, elements.firstOrNull().orEmpty()) { onChange(Values.join(def, listOf(it))) }
                }
            }
        }
        if (wide || elements.size > 1) {
            Spacer(Modifier.height(4.dp))
            if (elements.size > 1 && !wide) {
                for ((i, e) in elements.withIndex()) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                        Text("#${i + 1}", Modifier.width(36.dp), style = MaterialTheme.typography.labelMedium)
                        Box(Modifier.weight(1f)) { ElementEditor(def, e) { onChange(Values.withElement(def, value, i, it)) } }
                    }
                }
            } else if (def.type == "string" && def.vector && elements.size > 1) {
                for ((i, e) in elements.withIndex()) {
                    TextEditor(def, e, label = "#${i + 1}") { onChange(Values.withElement(def, value, i, it)) }
                }
            } else {
                TextEditor(def, elements.firstOrNull().orEmpty()) { onChange(Values.join(def, listOf(it))) }
            }
        }
    }
    if (info) {
        AlertDialog(
            onDismissRequest = { info = false },
            title = { Text(def.title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(def.tooltip)
                    Spacer(Modifier.height(8.dp))
                    Text(def.key, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                }
            },
            confirmButton = { TextButton(onClick = { info = false }) { Text("Close") } },
        )
    }
}

/** The editor for one scalar element (one extruder's value of a vector option). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ElementEditor(def: OptionDef, value: String, onChange: (String) -> Unit) {
    when {
        def.isBool -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Switch(
                checked = value == "1" || value == "true",
                enabled = !def.readonly,
                onCheckedChange = { onChange(if (it) "1" else "0") },
            )
        }
        def.hasChoices && !def.isOpenEnum -> {
            var open by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = open, onExpandedChange = { if (!def.readonly) open = it }) {
                OutlinedTextField(
                    value = def.choiceLabel(value),
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
                    modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                )
                ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    for (v in def.enumValues) {
                        DropdownMenuItem(text = { Text(def.choiceLabel(v)) }, onClick = { open = false; if (v != value) onChange(v) })
                    }
                }
            }
        }
        def.isColor -> ColorField(value, onChange)
        else -> ValueField(def, value, onChange)
    }
}

/** Free numeric or text input, committed on Done or focus loss; open enums offer their presets. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ValueField(def: OptionDef, value: String, onChange: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf(false) }
    fun commit() {
        val t = text.trim().let { if (def.isNumeric) it.replace(',', '.') else it }
        if (t != value && valid(def, t)) onChange(t) else text = value
    }
    val field = @Composable { modifier: Modifier ->
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = modifier.fillMaxWidth().onFocusChanged {
                if (focused && !it.isFocused) commit()
                focused = it.isFocused
            },
            singleLine = true,
            readOnly = def.readonly,
            textStyle = MaterialTheme.typography.bodyMedium,
            suffix = if (def.sidetext.isNotEmpty() && def.sidetext.length <= 8) ({ Text(def.sidetext) }) else null,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (def.isNumeric && def.type != "float_or_percent" && def.type != "percent") KeyboardType.Decimal else KeyboardType.Text,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { commit() }),
            isError = text != value && !valid(def, text.trim()),
            trailingIcon = if (def.hasChoices) ({ ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) }) else null,
        )
    }
    if (def.hasChoices) {
        ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
            field(Modifier.menuAnchor(MenuAnchorType.PrimaryEditable))
            ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for (v in def.enumValues) {
                    DropdownMenuItem(text = { Text(def.choiceLabel(v)) }, onClick = { open = false; text = v; if (v != value) onChange(v) })
                }
            }
        }
    } else {
        field(Modifier)
    }
}

private fun valid(def: OptionDef, t: String): Boolean {
    if (!def.isNumeric) return true
    if (t == "nil" && def.vector) return true
    val percent = t.endsWith("%")
    val number = t.removeSuffix("%").toDoubleOrNull() ?: return false
    return when (def.type) {
        "int" -> !percent && t.toLongOrNull() != null && inRange(def, number)
        "float" -> !percent && inRange(def, number)
        "percent" -> true
        else -> percent || inRange(def, number)
    }
}

private fun inRange(def: OptionDef, v: Double) = (def.min == null || v >= def.min) && (def.max == null || v <= def.max)

/** "#rrggbb" text with a swatch. */
@Composable
private fun ColorField(value: String, onChange: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(28.dp).background(parseColor(value) ?: Color.Transparent, CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                val t = text.trim().let { if (it.startsWith("#")) it else "#$it" }
                if (parseColor(t) != null && t != value) onChange(t.uppercase()) else text = value
            }),
        )
    }
}

fun parseColor(hex: String): Color? {
    val h = hex.trim().removePrefix("#")
    if (h.length != 6 && h.length != 8) return null
    val v = h.toLongOrNull(16) ?: return null
    return if (h.length == 6) Color(0xFF000000 or v) else Color(((v and 0xFF) shl 24) or (v ushr 8))
}

/** Strings: single-line text, or a full-screen editor for G-code and other multi-line text. */
@Composable
private fun TextEditor(def: OptionDef, value: String, label: String? = null, onChange: (String) -> Unit) {
    if (!def.isCode && !value.contains('\n')) {
        var text by remember(value) { mutableStateOf(value) }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused && text != value) onChange(text) },
            label = label?.let { { Text(it) } },
            singleLine = true,
            readOnly = def.readonly,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (text != value) onChange(text) }),
        )
        return
    }
    var editing by remember { mutableStateOf(false) }
    val lines = value.lines()
    OutlinedButton(onClick = { editing = true }, modifier = Modifier.fillMaxWidth()) {
        Text(
            listOfNotNull(label, if (value.isEmpty()) "Empty" else "${lines.size} lines: ${lines.first().take(40)}").joinToString(" · "),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (editing) CodeEditorDialog(def.title, value, readOnly = def.readonly, onDismiss = { editing = false }) {
        editing = false
        if (it != value) onChange(it)
    }
}

@Composable
fun CodeEditorDialog(title: String, value: String, readOnly: Boolean = false, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(value) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        androidx.compose.material3.Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    readOnly = readOnly,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { onSave(text) }, enabled = !readOnly) { Text("Save") }
                }
            }
        }
    }
}
