package org.orcapod.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.orcapod.app.engine.PublishFilament
import org.orcapod.app.engine.PublishMaterial
import org.orcapod.app.engine.PublishOptions
import org.orcapod.app.engine.PublishSelection

/**
 * Desktop OrcaSlicer's "Publish 3MF" dialog for a phone: pick the settings that travel with the
 * plate. Changed process and printer settings start checked, like desktop pre-checks the
 * modified ones; each used filament starts by requiring its colour and material type.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PublishSheet(vm: AppViewModel, options: PublishOptions, onDismiss: () -> Unit) {
    val initial = remember(options) { options.initialSelection() }
    // Settings of an earlier publish stay listed even when they no longer differ from the presets.
    val printerRows = options.printer
    val processRows = remember(options) { (options.process + initial.keys).distinct().filter { it !in printerRows } }
    var keys by remember(options) { mutableStateOf(initial.keys.toSet()) }
    val materials = remember(options) {
        mutableStateMapOf<Int, PublishMaterial>().apply {
            for (filament in options.filaments) put(filament.slot, PublishMaterial(filament.slot, colour = false, type = false, full = false))
            for (material in initial.materials) put(material.slot, material)
        }
    }
    val selection = { PublishSelection(keys.filter { it in processRows || it in printerRows }, materials.values.sortedBy { it.slot }) }
    val publish = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("model/3mf")) { uri ->
        if (uri != null) vm.publishProject(selection(), uri)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding()) {
            Text("Publish 3MF", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                "Shares the plate with only the settings you pick. Whoever opens it in OrcaSlicer 2.5 or newer, or in OrcaPod, " +
                    "keeps their own printer and filaments and gets these settings on top. Other slicers open the models only.",
                style = MaterialTheme.typography.bodySmall,
            )

            val settingRows = processRows + printerRows
            if (settingRows.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { keys = settingRows.toSet() }) { Text("All") }
                    TextButton(onClick = { keys = emptySet() }) { Text("None") }
                }
            } else {
                Spacer(Modifier.height(12.dp))
                Text(
                    "None of your process or printer settings differ from their profiles, so only filament requirements can be published.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (processRows.isNotEmpty()) {
                Section("Process")
                for (key in processRows) {
                    CheckRow(vm.publishKeyTitle(key), key in keys) { on -> keys = if (on) keys + key else keys - key }
                }
            }
            if (printerRows.isNotEmpty()) {
                Section("Printer retraction and Z-hop")
                for (key in printerRows) {
                    CheckRow(vm.publishKeyTitle(key), key in keys) { on -> keys = if (on) keys + key else keys - key }
                }
            }

            if (options.filaments.isNotEmpty()) Section("Filaments")
            for (filament in options.filaments) {
                val material = materials.getValue(filament.slot)
                FilamentHeader(filament)
                // The whole profile includes its colour and type.
                CheckRow("Embed the whole filament profile", material.full) { on -> materials[filament.slot] = material.copy(full = on) }
                if (!material.full) {
                    CheckRow("Require this colour", material.colour) { on -> materials[filament.slot] = material.copy(colour = on) }
                    CheckRow("Require ${filament.type.ifEmpty { "this material type" }}", material.type) { on ->
                        materials[filament.slot] = material.copy(type = on)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { publish.launch("${vm.exportName()}.published.3mf") }, enabled = vm.busy == null) { Text("Publish") }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(12.dp))
    Text(title, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun FilamentHeader(filament: PublishFilament) {
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(14.dp).clip(CircleShape).background(Color(0xff000000.toInt() or PlateThumbnail.parseColour(filament.colour))))
        Spacer(Modifier.width(8.dp))
        val unused = if (filament.used) "" else " (not used by the plate)"
        Text("${filament.slot + 1}: ${filament.name}$unused", style = MaterialTheme.typography.labelLarge)
    }
}
