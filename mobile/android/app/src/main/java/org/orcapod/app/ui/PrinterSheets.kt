package org.orcapod.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.orcapod.app.printhost.DiscoveredPrinter
import org.orcapod.app.printhost.HostType
import org.orcapod.app.printhost.PrintOptions
import org.orcapod.app.printhost.PrintState
import org.orcapod.app.printhost.PrinterConnection
import org.orcapod.app.printhost.PrinterStatus
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/** Desktop's "Send to printer" / "Upload to print host" dialog for the sliced plate. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendToPrinterSheet(vm: PrinterViewModel, gcode: File, printerPreset: String, onDismiss: () -> Unit) {
    var target by remember { mutableStateOf(vm.preferredFor(printerPreset)) }
    var name by remember { mutableStateOf(gcode.name) }
    var options by remember { mutableStateOf(PrintOptions()) }
    var editing by remember { mutableStateOf<PrinterConnection?>(null) }
    var watching by remember { mutableStateOf(false) }

    // Watching stops with the sheet, so a forgotten monitor doesn't poll the printer all day.
    fun close() { vm.clearUpload(); vm.stopMonitor(); onDismiss() }
    ModalBottomSheet(onDismissRequest = ::close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            Text("Send to printer", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            if (watching && vm.monitored != null) {
                PrinterMonitor(vm)
                return@Column
            }
            val c = target?.let { t -> vm.connections.firstOrNull { it.id == t.id } }
            if (vm.connections.isEmpty()) {
                Text("No printers yet. Add one with its network address to upload and start prints from here.")
                Spacer(Modifier.height(12.dp))
                Button(onClick = { editing = blankConnection(vm.newId(), printerPreset) }) {
                    Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("Add printer")
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ConnectionPicker(vm.connections, c, Modifier.weight(1f)) { target = it }
                    IconButton(onClick = { editing = blankConnection(vm.newId(), printerPreset) }) { Icon(Icons.Filled.Add, "Add printer") }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text("File name") }, modifier = Modifier.fillMaxWidth(),
                )
                if (c != null) PrintOptionsEditor(c.type, options) { options = it }
                Spacer(Modifier.height(12.dp))
                when (val u = vm.upload) {
                    is UploadState.Running -> {
                        Text("Uploading to ${u.printer}… ${(u.fraction * 100).roundToInt()}%", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(progress = { u.fraction }, modifier = Modifier.fillMaxWidth())
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = vm::cancelUpload) { Text("Cancel") }
                        }
                    }
                    is UploadState.Done -> {
                        Text(
                            if (u.started) "Sent ${u.fileName} to ${u.printer} and started the print."
                            else "Uploaded ${u.fileName} to ${u.printer}.",
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = ::close) { Text("Close") }
                            if (c != null) {
                                Spacer(Modifier.width(8.dp))
                                Button(onClick = { vm.watch(c.id); watching = true }) { Text("Watch printer") }
                            }
                        }
                    }
                    else -> {
                        if (u is UploadState.Failed) {
                            Text(u.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(8.dp))
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            OutlinedButton(
                                onClick = { if (c != null) vm.send(c, gcode, name, start = false, options, printerPreset) },
                                enabled = c != null && name.isNotBlank(),
                            ) { Text("Upload") }
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = { if (c != null) vm.send(c, gcode, name, start = true, options, printerPreset) },
                                enabled = c != null && name.isNotBlank(),
                            ) {
                                Icon(AppIcons.Print, null); Spacer(Modifier.width(6.dp)); Text("Upload and print")
                            }
                        }
                    }
                }
            }
        }
    }
    editing?.let { initial ->
        ConnectionEditor(vm, initial, onDismiss = { editing = null }) { saved -> target = saved; editing = null }
    }
}

/** Saved printers, what they are doing, and printers found on the network. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrintersSheet(vm: PrinterViewModel, printerPreset: String, onDismiss: () -> Unit) {
    var editing by remember { mutableStateOf<PrinterConnection?>(null) }
    var deleting by remember { mutableStateOf<PrinterConnection?>(null) }
    DisposableEffect(Unit) {
        vm.startDiscovery()
        onDispose { vm.stopDiscovery() }
    }
    ModalBottomSheet(onDismissRequest = { vm.stopMonitor(); onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxHeight(0.9f).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Printers", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { editing = blankConnection(vm.newId(), printerPreset) }) {
                    Icon(Icons.Filled.Add, null); Spacer(Modifier.width(4.dp)); Text("Add")
                }
            }
            if (vm.connections.isEmpty()) {
                Text("Add a printer to send sliced plates to it and watch its prints.", style = MaterialTheme.typography.bodyMedium)
            }
            for (c in vm.connections.sortedBy { it.name.lowercase() }) {
                val watched = vm.monitored == c.id
                ListItem(
                    modifier = Modifier.clickable { if (watched) vm.stopMonitor() else vm.watch(c.id) },
                    headlineContent = { Text(c.name) },
                    supportingContent = { Text("${c.type.label} · ${c.host}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { editing = c }) { Icon(Icons.Filled.Edit, "Edit") }
                            IconButton(onClick = { deleting = c }) { Icon(Icons.Filled.Delete, "Delete") }
                        }
                    },
                )
                if (watched) PrinterMonitor(vm)
                HorizontalDivider()
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("On this network", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.width(8.dp))
                CircularProgressIndicator(Modifier.width(14.dp).height(14.dp), strokeWidth = 2.dp)
            }
            val fresh = vm.discovered.filter { d -> vm.connections.none { it.host.contains(d.host.substringBefore(':')) } }
            if (fresh.isEmpty()) {
                Text(
                    "Looking for Bambu Lab, OctoPrint and Klipper printers. Others, and printers on another network, can be added by address.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            for (d in fresh) {
                ListItem(
                    modifier = Modifier.clickable { editing = fromDiscovered(vm.newId(), d, printerPreset) },
                    headlineContent = { Text(d.name) },
                    supportingContent = { Text(listOf(d.type.label, d.model, d.host).filter { it.isNotEmpty() }.joinToString(" · ")) },
                    trailingContent = { Icon(Icons.Filled.Add, "Add") },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    editing?.let { initial -> ConnectionEditor(vm, initial, onDismiss = { editing = null }) { editing = null } }
    deleting?.let { c ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${c.name}?") },
            text = { Text("Removes the saved connection and its login from this device.") },
            confirmButton = { TextButton(onClick = { vm.delete(c.id); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

/** Live state of the watched printer with pause, resume and stop, like desktop's device page. */
@Composable
fun PrinterMonitor(vm: PrinterViewModel) {
    val s = vm.status
    var confirmStop by remember { mutableStateOf(false) }
    var cameraOpen by remember { mutableStateOf(false) }
    var historyOpen by remember { mutableStateOf(false) }
    val id = vm.monitored
    val name = vm.connections.firstOrNull { it.id == id }?.name.orEmpty()
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            when {
                s == null && vm.statusError == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Connecting…")
                }
                s == null -> Text(vm.statusError.orEmpty(), color = MaterialTheme.colorScheme.error)
                else -> StatusDetails(s, vm.statusError)
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                TextButton(onClick = { cameraOpen = true }) { Icon(AppIcons.Camera, null); Spacer(Modifier.width(4.dp)); Text("Camera") }
                TextButton(onClick = { historyOpen = true }) { Icon(AppIcons.History, null); Spacer(Modifier.width(4.dp)); Text("History") }
            }
            if (s != null && (s.state == PrintState.PRINTING || s.state == PrintState.PAUSED || s.state == PrintState.PREPARING)) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    if (s.state == PrintState.PAUSED) {
                        FilledTonalButton(onClick = vm::resume) { Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text("Resume") }
                    } else {
                        FilledTonalButton(onClick = vm::pause) { Icon(AppIcons.Pause, null); Spacer(Modifier.width(4.dp)); Text("Pause") }
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { confirmStop = true }) { Icon(AppIcons.Stop, null); Spacer(Modifier.width(4.dp)); Text("Stop") }
                }
            }
        }
    }
    if (cameraOpen && id != null) CameraDialog(vm, id, name) { cameraOpen = false }
    if (historyOpen && id != null) HistorySheet(vm, id, name) { historyOpen = false }
    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("Stop the print?") },
            text = { Text("The print is cancelled and cannot be resumed.") },
            confirmButton = { TextButton(onClick = { vm.cancelPrint(); confirmStop = false }) { Text("Stop print") } },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("Keep printing") } },
        )
    }
}

@Composable
private fun StatusDetails(s: PrinterStatus, error: String?) {
    Text(
        listOfNotNull(s.stateText.ifEmpty { s.state.name.lowercase() }, s.fileName).joinToString(" · "),
        style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
    )
    s.progress?.takeIf { s.isActive }?.let { p ->
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(progress = { p.toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        Text(
            listOfNotNull(
                "${(p * 100).roundToInt()}%",
                s.layer?.let { l -> s.totalLayers?.let { "layer $l of $it" } ?: "layer $l" },
                s.remainingSeconds?.let { "${hm(it)} left" },
                s.elapsedSeconds?.let { "${hm(it)} elapsed" }.takeIf { s.remainingSeconds == null },
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    val temps = listOfNotNull(
        s.nozzleTemp?.let { "Nozzle ${temp(it, s.nozzleTarget)}" },
        s.bedTemp?.let { "Bed ${temp(it, s.bedTarget)}" },
    )
    if (temps.isNotEmpty()) Text(temps.joinToString("   "), style = MaterialTheme.typography.bodySmall)
    s.message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    error?.let { Text("Last update failed: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
}

private fun temp(actual: Double, target: Double?) =
    if (target != null && target > 0) String.format(Locale.US, "%.0f / %.0f °C", actual, target)
    else String.format(Locale.US, "%.0f °C", actual)

private fun hm(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return if (h > 0) "${h} h ${m} min" else "$m min"
}

@Composable
private fun PrintOptionsEditor(type: HostType, o: PrintOptions, onChange: (PrintOptions) -> Unit) {
    when (type) {
        HostType.BAMBU_LAN -> Column(Modifier.padding(top = 8.dp)) {
            OptionSwitch("Bed leveling", o.bedLeveling) { onChange(o.copy(bedLeveling = it)) }
            OptionSwitch("Flow dynamics calibration", o.flowCalibration) { onChange(o.copy(flowCalibration = it)) }
            OptionSwitch("Vibration calibration", o.vibrationCalibration) { onChange(o.copy(vibrationCalibration = it)) }
            OptionSwitch("Timelapse", o.timelapse) { onChange(o.copy(timelapse = it)) }
            OptionSwitch("Use AMS", o.useAms) { onChange(o.copy(useAms = it)) }
            if (o.useAms) {
                NumberField(
                    value = (o.amsTray + 1).toString(), label = "AMS slot for the filament", modifier = Modifier.fillMaxWidth(),
                ) { v -> v.toDoubleOrNull()?.toInt()?.takeIf { it in 1..16 }?.let { onChange(o.copy(amsTray = it - 1)) } }
            }
        }
        HostType.DUET -> OptionSwitch("Simulate only (M37)", o.simulate) { onChange(o.copy(simulate = it)) }
        else -> {}
    }
}

@Composable
private fun OptionSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionPicker(all: List<PrinterConnection>, current: PrinterConnection?, modifier: Modifier, onPick: (PrinterConnection) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }, modifier = modifier) {
        OutlinedTextField(
            value = current?.name ?: "Choose a printer",
            onValueChange = {}, readOnly = true, singleLine = true,
            label = { Text("Printer") },
            supportingText = current?.let { { Text(it.type.label) } },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (c in all) DropdownMenuItem(text = { Text("${c.name} (${c.type.label})") }, onClick = { open = false; onPick(c) })
        }
    }
}

/** Desktop's physical printer dialog: type, address and the login that type needs, with a connection test. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionEditor(vm: PrinterViewModel, initial: PrinterConnection, onDismiss: () -> Unit, onSaved: (PrinterConnection) -> Unit) {
    var c by remember { mutableStateOf(initial) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    val scope = rememberCoroutineScope()
    fun update(n: PrinterConnection) { c = n; result = null }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (vm.connections.any { it.id == c.id }) "Edit printer" else "Add printer") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                var typeOpen by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = typeOpen, onExpandedChange = { typeOpen = it }) {
                    OutlinedTextField(
                        value = c.type.label, onValueChange = {}, readOnly = true, singleLine = true,
                        label = { Text("Type") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeOpen) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(expanded = typeOpen, onDismissRequest = { typeOpen = false }) {
                        for (t in HostType.entries) DropdownMenuItem(text = { Text(t.label) }, onClick = { typeOpen = false; update(c.copy(type = t)) })
                    }
                }
                Field("Name", c.name) { update(c.copy(name = it)) }
                Field(
                    if (c.type == HostType.BAMBU_LAN) "IP address" else "Address",
                    c.host,
                    hint = when (c.type) {
                        HostType.BAMBU_LAN -> "Shown on the printer under Network"
                        HostType.MOONRAKER -> "e.g. 192.168.1.20:7125 or http://voron.local"
                        else -> "e.g. 192.168.1.20 or https://printer.local"
                    },
                    keyboard = KeyboardType.Uri,
                ) { update(c.copy(host = it)) }
                if (c.type.usesApiKey) {
                    Field(
                        if (c.type == HostType.MOONRAKER) "API key (if required)" else "API key",
                        c.apiKey, secret = true,
                        hint = if (c.type == HostType.PRUSALINK) "Or a username and password below for digest login" else null,
                    ) { update(c.copy(apiKey = it)) }
                }
                if (c.type.usesLogin) {
                    Field("Username", c.username) { update(c.copy(username = it)) }
                    Field("Password", c.password, secret = true) { update(c.copy(password = it)) }
                }
                if (c.type.usesPassword) Field("Password", c.password, secret = true, hint = "Leave empty if none is set") { update(c.copy(password = it)) }
                if (c.type.usesSerial) {
                    Field("Access code", c.accessCode, secret = true, hint = "Settings › Network › LAN only mode on the printer") { update(c.copy(accessCode = it.trim())) }
                    Field("Serial number", c.serial, hint = "Filled in by Test when left empty") { update(c.copy(serial = it.trim())) }
                    Text(
                        "No Bambu account needed: turn on LAN only mode, and Developer mode so the printer takes jobs and " +
                            "commands from this app. On X1 and H2 printers, also turn on LAN mode liveview for the camera.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Accept self-signed certificates", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = c.allowSelfSigned, onCheckedChange = { update(c.copy(allowSelfSigned = it)) })
                    }
                }
                Field("Camera URL (optional)", c.cameraUrl, hint = "Overrides the camera the printer reports", keyboard = KeyboardType.Uri) {
                    update(c.copy(cameraUrl = it.trim()))
                }
                when {
                    testing -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp)); Text("Testing…")
                    }
                    result != null -> Text(
                        if (result!!.first) "Connected: ${result!!.second}" else result!!.second,
                        color = if (result!!.first) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(
                    onClick = {
                        testing = true
                        scope.launch {
                            result = try {
                                val (what, filled) = vm.test(c)
                                c = filled
                                true to what
                            } catch (e: java.io.IOException) {
                                false to (e.message ?: "Connection failed")
                            }
                            testing = false
                        }
                    },
                    enabled = c.host.isNotBlank() && !testing,
                ) { Text("Test") }
                TextButton(
                    onClick = {
                        val saved = c.copy(name = c.name.trim().ifEmpty { c.host.trim() }, host = c.host.trim())
                        vm.save(saved)
                        onSaved(saved)
                    },
                    enabled = c.host.isNotBlank(),
                ) { Text("Save") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Field(
    label: String,
    value: String,
    hint: String? = null,
    secret: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true,
        label = { Text(label) },
        supportingText = hint?.let { { Text(it) } },
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard),
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun blankConnection(id: String, printerPreset: String) =
    PrinterConnection(id = id, name = "", type = guessType(printerPreset), host = "", printerPreset = printerPreset)

/** The protocol a printer preset's machine most likely speaks, as a starting point. */
private fun guessType(preset: String): HostType = when {
    preset.contains("Bambu", true) -> HostType.BAMBU_LAN
    preset.contains("Prusa", true) -> HostType.PRUSALINK
    KLIPPER_HINTS.any { preset.contains(it, true) } -> HostType.MOONRAKER
    else -> HostType.OCTOPRINT
}

private val KLIPPER_HINTS = listOf("Voron", "RatRig", "VzBot", "Sovol", "Qidi", "Creality K1", "Creality K2", "Neptune 4", "Klipper")

private fun fromDiscovered(id: String, d: DiscoveredPrinter, printerPreset: String) =
    PrinterConnection(id = id, name = d.name, type = d.type, host = d.host, serial = d.serial, printerPreset = printerPreset)
