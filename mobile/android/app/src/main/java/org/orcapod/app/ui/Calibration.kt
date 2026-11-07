package org.orcapod.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.orcapod.app.engine.CalibrationTest
import org.orcapod.app.engine.PresetKind

private fun num(v: Double): String = if (v == Math.floor(v)) v.toLong().toString() else v.toString()

/** Desktop's Calibration menu and its dialogs, in one: pick a test, check its range, start it. */
@Composable
fun CalibrationDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    var test by remember { mutableStateOf(CalibrationTest.TEMPERATURE) }
    var bowden by remember { mutableStateOf(false) }
    var start by remember { mutableStateOf("") }
    var end by remember { mutableStateOf("") }
    var step by remember { mutableStateOf("") }
    var printNumbers by remember { mutableStateOf(true) }
    var resize by remember { mutableStateOf(true) }

    // Desktop dialog defaults: temperatures by filament type, PA by extruder type.
    fun defaults(t: CalibrationTest, isBowden: Boolean) {
        val range = when {
            t == CalibrationTest.TEMPERATURE ->
                CalibrationTest.temperatureRange(vm.valueOf(PresetKind.FILAMENT, "filament_type").trim('"').substringBefore(';'))
            t == CalibrationTest.PA_TOWER || t == CalibrationTest.PA_LINE -> 0.0 to if (isBowden) 1.0 else 0.1
            else -> t.start to t.end
        }
        start = num(range.first)
        end = num(range.second)
        step = num(if ((t == CalibrationTest.PA_TOWER || t == CalibrationTest.PA_LINE) && isBowden) 0.02 else t.step)
    }
    LaunchedEffect(Unit) { defaults(test, bowden) }

    val s = start.toDoubleOrNull()
    val e = end.toDoubleOrNull()
    val st = step.toDoubleOrNull()
    val error = when {
        !test.hasRange -> null
        s == null || e == null || st == null -> "Enter numbers for the range."
        test == CalibrationTest.TEMPERATURE && (s > 500 || e < 155 || e > s - 5) ->
            "Start at most 500 °C, end at least 155 °C and 5 °C below the start."
        test != CalibrationTest.TEMPERATURE && (s < 0 || st <= 0 || e <= s) -> "Start at 0 or more, with the end above the start and a positive step."
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Calibration") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                for (t in CalibrationTest.entries) {
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = t == test, onClick = { test = t; defaults(t, bowden) }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = t == test, onClick = { test = t; defaults(t, bowden) })
                        Text(t.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                if (test == CalibrationTest.PA_TOWER || test == CalibrationTest.PA_LINE) {
                    CheckRow("Bowden extruder", bowden) { bowden = it; defaults(test, it) }
                }
                if (test.hasRange) {
                    val unit = if (test.unit.isEmpty()) "" else " (${test.unit})"
                    OutlinedTextField(start, { start = it }, label = { Text("Start$unit") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(end, { end = it }, label = { Text("End$unit") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (test != CalibrationTest.TEMPERATURE) {
                        OutlinedTextField(step, { step = it }, label = { Text("Step$unit") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    } else {
                        Text("The tower steps by 5 °C.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (test == CalibrationTest.PA_LINE) CheckRow("Print numbers", printNumbers) { printNumbers = it }
                if (test == CalibrationTest.TEMPERATURE || test == CalibrationTest.VFA) {
                    CheckRow("Scale to the nozzle diameter", resize) { resize = it }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Text(
                    "The test replaces the objects on the plate and changes the print, filament and printer settings, as on desktop.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = error == null,
                onClick = {
                    vm.startCalibration(test, s ?: 0.0, e ?: 0.0, st ?: 0.0, printNumbers, resize)
                    onDismiss()
                },
            ) { Text("Start") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** The test the plate is set up for, with a way back to ordinary slicing. */
@Composable
fun CalibrationBanner(vm: AppViewModel) {
    val (test, range) = vm.calibration ?: return
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val detail = if (test.hasRange) {
                val unit = if (test.unit.isEmpty()) "" else " ${test.unit}"
                // The volumetric speed test keeps its range as mm/s after setup, so show the test alone.
                if (test == CalibrationTest.VOLUMETRIC_SPEED) "" else ": ${num(range[0])} to ${num(range[1])}$unit"
            } else ""
            Text("${test.label}$detail", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = { vm.clearCalibration() }) { Text("End test") }
        }
    }
}
