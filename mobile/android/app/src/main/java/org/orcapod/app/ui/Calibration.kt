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
import org.orcapod.app.engine.CalibrationSetup
import org.orcapod.app.engine.CalibrationTest
import org.orcapod.app.engine.PresetKind

private fun num(v: Double): String = if (v == Math.floor(v)) v.toLong().toString() else v.toString()

/** Comma-separated positive numbers, as desktop's PA pattern speed and acceleration lists; null when one is not. */
private fun numbers(text: String): List<Double>? =
    text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { it.toDoubleOrNull()?.takeIf { v -> v > 0 } ?: return null }

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
    var model by remember { mutableStateOf(0) }
    var fsx by remember { mutableStateOf("") }
    var fex by remember { mutableStateOf("") }
    var fsy by remember { mutableStateOf("") }
    var fey by remember { mutableStateOf("") }
    var shaper by remember { mutableStateOf(0) }
    var speeds by remember { mutableStateOf("") }
    var accels by remember { mutableStateOf("") }

    val shapers = CalibrationTest.shaperTypes(vm.valueOf(PresetKind.PRINTER, "gcode_flavor").trim('"'))
    // Desktop has_junction_deviation: cornering then tests junction deviation in mm, not jerk.
    val junction = vm.valueOf(PresetKind.PRINTER, "gcode_flavor").trim('"') == "marlin2" &&
        (vm.valueOf(PresetKind.PRINTER, "machine_max_junction_deviation").trim('"').substringBefore(',').toDoubleOrNull() ?: 0.0) > 0

    // Desktop dialog defaults: temperatures by filament type, PA by extruder type, cornering by firmware.
    fun defaults(t: CalibrationTest, isBowden: Boolean) {
        val range = when {
            t == CalibrationTest.TEMPERATURE ->
                CalibrationTest.temperatureRange(vm.valueOf(PresetKind.FILAMENT, "filament_type").trim('"').substringBefore(';'))
            t.isPa && isBowden -> 0.0 to 1.0
            t == CalibrationTest.PA_TOWER || t == CalibrationTest.PA_LINE -> 0.0 to 0.1
            t == CalibrationTest.SHAPING_FREQUENCY -> 0.15 to 0.0
            t == CalibrationTest.CORNERING && junction -> 0.0 to 0.25
            else -> t.start to t.end
        }
        start = num(range.first)
        end = num(range.second)
        step = num(
            when {
                t == CalibrationTest.PA_PATTERN && isBowden -> 0.05
                t.isPa && isBowden -> 0.02
                else -> t.step
            },
        )
        val (fs, fe) = if (t == CalibrationTest.SHAPING_DAMPING) 30.0 to 0.0 else 15.0 to 110.0
        fsx = num(fs); fex = num(fe); fsy = num(fs); fey = num(fe)
        model = 0
        shaper = 0
    }
    LaunchedEffect(Unit) { defaults(test, bowden) }

    val s = start.toDoubleOrNull()
    val e = end.toDoubleOrNull()
    val st = step.toDoubleOrNull()
    val freq = listOf(fsx, fex, fsy, fey).map { it.toDoubleOrNull() }
    val speedList = numbers(speeds)
    val accelList = numbers(accels)
    // The bridge's checks, which follow the desktop dialogs.
    val error = when {
        !test.hasRange -> null
        test == CalibrationTest.SHAPING_FREQUENCY -> when {
            freq.any { it == null } || s == null -> "Enter numbers for the frequencies and damping."
            freq[0]!! < 0 || freq[1]!! > 500 || freq[0]!! >= freq[1]!! || freq[2]!! < 0 || freq[3]!! > 500 || freq[2]!! >= freq[3]!! ->
                "Frequencies must rise from 0 Hz or more to at most 500 Hz."
            s < 0 || s >= 1 -> "Damping must be from 0 to below 1."
            else -> null
        }
        test == CalibrationTest.SHAPING_DAMPING -> when {
            freq[0] == null || freq[2] == null || s == null || e == null -> "Enter numbers for the frequencies and damping."
            freq[0]!! !in 0.0..500.0 || freq[2]!! !in 0.0..500.0 -> "Frequencies must be 0 to 500 Hz."
            s < 0 || e > 1 || s >= e -> "Damping must rise from 0 or more to at most 1."
            else -> null
        }
        s == null || e == null || (test.hasStep && st == null) -> "Enter numbers for the range."
        test == CalibrationTest.CORNERING ->
            if (s < 0 || s >= e || (junction && e > 0.3)) {
                if (junction) "Start at 0 or more, with the end above the start and at most 0.3 mm." else "Start at 0 or more, with the end above the start."
            } else null
        test == CalibrationTest.TEMPERATURE && (s > 500 || e < 155 || e > s - 5) ->
            "Start at most 500 °C, end at least 155 °C and 5 °C below the start."
        test != CalibrationTest.TEMPERATURE && (s < 0 || st!! <= 0 || e <= s) -> "Start at 0 or more, with the end above the start and a positive step."
        test == CalibrationTest.PA_PATTERN && (speedList == null || accelList == null) -> "Speeds and accelerations must be positive numbers, separated by commas."
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Calibration") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                for (t in CalibrationTest.entries) {
                    Choice(t.label, t == test) { test = t; defaults(t, bowden) }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                if (test.isPa) CheckRow("Bowden extruder", bowden) { bowden = it; defaults(test, it) }
                val models = test.models()
                if (models.size > 1) {
                    Text("Test model", style = MaterialTheme.typography.labelLarge)
                    models.forEachIndexed { i, (label, _) -> Choice(label, i == model) { model = i } }
                }
                if (test == CalibrationTest.SHAPING_FREQUENCY || test == CalibrationTest.SHAPING_DAMPING) {
                    if (shapers.size > 1) {
                        Text("Input shaper type", style = MaterialTheme.typography.labelLarge)
                        shapers.forEachIndexed { i, label -> Choice(label, i == shaper) { shaper = i } }
                    }
                    if (test == CalibrationTest.SHAPING_FREQUENCY) {
                        Field(fsx, { fsx = it }, "X start (Hz)")
                        Field(fex, { fex = it }, "X end (Hz)")
                        Field(fsy, { fsy = it }, "Y start (Hz)")
                        Field(fey, { fey = it }, "Y end (Hz)")
                        Field(start, { start = it }, "Damping")
                    } else {
                        Field(fsx, { fsx = it }, "X frequency (Hz)")
                        Field(fsy, { fsy = it }, "Y frequency (Hz)")
                        Field(start, { start = it }, "Damping start")
                        Field(end, { end = it }, "Damping end")
                    }
                } else if (test.hasRange) {
                    val u = if (test == CalibrationTest.CORNERING && junction) "mm" else test.unit
                    val unit = if (u.isEmpty()) "" else " ($u)"
                    Field(start, { start = it }, "Start$unit")
                    Field(end, { end = it }, "End$unit")
                    when {
                        test.hasStep -> Field(step, { step = it }, "Step$unit")
                        test == CalibrationTest.TEMPERATURE -> Text("The tower steps by 5 °C.", style = MaterialTheme.typography.bodySmall)
                        test == CalibrationTest.CORNERING -> Text(
                            if (junction) "Tests junction deviation, as the printer uses it." else "Tests jerk.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (test == CalibrationTest.PA_PATTERN) {
                    Field(speeds, { speeds = it }, "Print speeds (mm/s, comma separated)")
                    Field(accels, { accels = it }, "Accelerations (mm/s², comma separated)")
                    Text("Leave them empty to use the profile. Each speed and acceleration pair gets its own pattern.", style = MaterialTheme.typography.bodySmall)
                }
                if (test == CalibrationTest.PA_LINE || test == CalibrationTest.PA_PATTERN) CheckRow("Print numbers", printNumbers) { printNumbers = it }
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
                    val f = freq.map { it ?: 0.0 }
                    vm.startCalibration(
                        CalibrationSetup(
                            test, s ?: 0.0, e ?: 0.0, st ?: 0.0, printNumbers, resize,
                            testModel = model,
                            freqStartX = f[0], freqEndX = f[1], freqStartY = f[2], freqEndY = f[3],
                            shaperType = if (test.isMotion && test != CalibrationTest.CORNERING) shapers.getOrElse(shaper) { "" } else "",
                            speeds = if (test == CalibrationTest.PA_PATTERN) speedList.orEmpty() else emptyList(),
                            accelerations = if (test == CalibrationTest.PA_PATTERN) accelList.orEmpty() else emptyList(),
                        ),
                    )
                    onDismiss()
                },
            ) { Text("Start") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected = selected, onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
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
                // Volumetric speed keeps its range as mm/s after setup, frequency keeps the damping, and
                // cornering may be junction deviation, so those show the test alone.
                if (test == CalibrationTest.VOLUMETRIC_SPEED || test == CalibrationTest.SHAPING_FREQUENCY || test == CalibrationTest.CORNERING) "" else ": ${num(range[0])} to ${num(range[1])}$unit"
            } else ""
            Text("${test.label}$detail", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = { vm.clearCalibration() }) { Text("End test") }
        }
    }
}
