package org.orcapod.app.ui

import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Export, import and folder sync of the user's own printer, filament and process presets. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) vm.exportProfiles(uri)
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.importProfiles(uris)
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.chooseSyncFolder(uri)
    }
    val busy = vm.busy != null

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
        ) {
            Text("Back up and sync profiles", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                when (vm.userPresetCount) {
                    0 -> "You have no custom profiles yet. Profiles you save from the print settings appear here."
                    1 -> "You have 1 custom profile."
                    else -> "You have ${vm.userPresetCount} custom printer, filament and process profiles."
                },
                style = MaterialTheme.typography.bodyMedium,
            )

            Spacer(Modifier.height(16.dp))
            Text("Backup file", style = MaterialTheme.typography.titleMedium)
            Text(
                "A zip in OrcaSlicer's preset format. Desktop OrcaSlicer can import it with File > Import > Import Configs, " +
                    "and its exported .orca_printer, .orca_filament and .zip files, or the .json files in its user preset folder, can be imported here.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = { export.launch("OrcaPod profiles ${SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())}.zip") },
                    enabled = !busy && vm.userPresetCount > 0,
                ) { Text("Export") }
                OutlinedButton(onClick = { import.launch(arrayOf("*/*")) }, enabled = !busy) { Text("Import") }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            Text("Sync folder", style = MaterialTheme.typography.titleMedium)
            val folder = vm.syncFolder
            if (folder == null) {
                Text(
                    "Pick a folder that a cloud or sync app keeps in sync. Profiles you save here are copied to it, " +
                        "and profiles added or changed there by your other devices are copied back.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { pickFolder.launch(null) }, enabled = !busy) { Text("Choose folder") }
            } else {
                Text("Syncing with \"$folder\"", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (vm.lastSync == 0L) "Not synced yet"
                    else "Last synced " + DateUtils.getRelativeTimeSpanString(vm.lastSync, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Syncs when the app starts and whenever you save or delete a profile.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = vm::syncNow, enabled = !busy) { Text("Sync now") }
                    OutlinedButton(onClick = { pickFolder.launch(null) }, enabled = !busy) { Text("Change") }
                    TextButton(onClick = vm::stopSync, enabled = !busy) { Text("Stop") }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
