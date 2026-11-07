package org.orcapod.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay
import org.orcapod.app.engine.StepFile
import org.orcapod.app.engine.StepImportOptions
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

/**
 * Desktop StepMeshDialog with its import choices: meshing quality (linear
 * and angular deflection, with the resulting triangle count), Split
 * compound, assembly or single object, Keep original names, and
 * Remember my choice / Don't ask again.
 */
@Composable
fun StepImportDialog(
    request: StepImportRequest,
    countTriangles: (StepFile, Double, Double) -> Deferred<Long?>,
    onAnswer: (StepImportAnswer?) -> Unit,
) {
    val step = request.step
    var linearText by remember { mutableStateOf(format(request.initial.linearDeflection, 3)) }
    var angleText by remember { mutableStateOf(format(request.initial.angleDeflection, 2)) }
    var split by remember { mutableStateOf(request.initial.splitCompound) }
    var keepNames by remember { mutableStateOf(request.initial.keepNames) }
    var assembly by remember { mutableStateOf(request.initial.asAssembly) }
    var remember by remember { mutableStateOf(false) }
    var dontAsk by remember { mutableStateOf(false) }
    var triangles by remember { mutableStateOf<Long?>(null) }

    val linear = linearText.toDoubleOrNull()?.takeIf { it in StepImportOptions.LINEAR_MIN..StepImportOptions.LINEAR_MAX }
    val angle = angleText.toDoubleOrNull()?.takeIf { it in StepImportOptions.ANGLE_MIN..StepImportOptions.ANGLE_MAX }

    // Desktop recounts whenever the quality changes; a newer value stops the running count.
    LaunchedEffect(linear, angle) {
        triangles = null
        if (linear == null || angle == null) return@LaunchedEffect
        delay(300)
        val count = countTriangles(step, linear, angle)
        try {
            triangles = count.await()
        } catch (c: CancellationException) {
            step.cancel()
            throw c
        }
    }

    AlertDialog(
        onDismissRequest = { onAnswer(null) },
        title = { Text("Import ${request.fileName}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Smaller linear and angular deflections result in higher-quality transformations " +
                        "but increase the processing time.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))
                DeflectionRow(
                    label = "Linear deflection",
                    text = linearText,
                    value = linear,
                    range = StepImportOptions.LINEAR_MIN..StepImportOptions.LINEAR_MAX,
                    decimals = 3,
                    // 0.001 to 0.1 spans two decades; a linear slider would crowd the useful end.
                    logScale = true,
                    error = "Enter a value from 0.001 to 0.1",
                    onText = { linearText = it },
                )
                DeflectionRow(
                    label = "Angle deflection",
                    text = angleText,
                    value = angle,
                    range = StepImportOptions.ANGLE_MIN..StepImportOptions.ANGLE_MAX,
                    decimals = 2,
                    error = "Enter a value from 0.01 to 1.0",
                    onText = { angleText = it },
                )
                Text(
                    "Number of triangular facets: " + when {
                        linear == null || angle == null -> "–"
                        triangles == null -> "Calculating, please wait..."
                        else -> String.format(Locale.getDefault(), "%,d", triangles)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                CheckRow("Split compound and compsolid into multiple objects", split) {
                    split = it
                    // Body names of a multi-body part exist only after splitting it.
                    if (!it && step.hasMultiBodyCompound) keepNames = false
                }
                RadioRow("Load as an assembly", assembly) { assembly = true }
                RadioRow("Load as a single object (can be split later)", !assembly) { assembly = false }
                CheckRow("Keep original names", keepNames, enabled = step.hasOriginalNames) {
                    keepNames = it
                    if (it && step.hasMultiBodyCompound) split = true
                }
                CheckRow("Remember my choice", remember) { remember = it }
                CheckRow("Don't ask again", dontAsk) { dontAsk = it }
            }
        },
        confirmButton = {
            TextButton(
                enabled = linear != null && angle != null,
                onClick = {
                    onAnswer(
                        StepImportAnswer(
                            StepImportOptions(linear!!, angle!!, split, keepNames && step.hasOriginalNames, assembly),
                            remember = remember,
                            dontAsk = dontAsk,
                        ),
                    )
                },
            ) { Text("Import") }
        },
        dismissButton = { TextButton(onClick = { onAnswer(null) }) { Text("Cancel") } },
    )
}

@Composable
private fun DeflectionRow(
    label: String,
    text: String,
    value: Double?,
    range: ClosedFloatingPointRange<Double>,
    decimals: Int,
    error: String,
    onText: (String) -> Unit,
    logScale: Boolean = false,
) {
    val toSlider = { v: Double -> (if (logScale) log10(v) else v).toFloat() }
    val fromSlider = { f: Float -> if (logScale) 10.0.pow(f.toDouble()) else f.toDouble() }
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = text,
                onValueChange = onText,
                modifier = Modifier.width(110.dp),
                singleLine = true,
                isError = value == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
        }
        Slider(
            value = toSlider(value ?: range.start),
            onValueChange = { onText(format(fromSlider(it).coerceIn(range), decimals)) },
            valueRange = toSlider(range.start)..toSlider(range.endInclusive),
        )
        if (value == null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked && enabled, onCheckedChange = null, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

// Engine values use a decimal point whatever the locale.
private fun format(value: Double, decimals: Int): String = String.format(Locale.ROOT, "%.${decimals}f", value)
