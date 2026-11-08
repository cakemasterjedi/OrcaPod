package org.orcapod.app.ui

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.orcapod.app.data.ProfileStore
import org.orcapod.app.data.VendorNames
import org.orcapod.app.engine.PresetKind

@Composable
fun OrcaPodApp(viewModel: AppViewModel, pendingImports: List<Uri>, onImportsTaken: () -> Unit) {
    OrcaPodTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (val phase = viewModel.phase) {
                is Phase.Loading -> Centered {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(phase.message)
                }
                is Phase.Failed -> Centered {
                    Text("OrcaPod could not start", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Text(phase.message, textAlign = TextAlign.Center)
                }
                is Phase.VendorSetup -> VendorSetupScreen(
                    vendors = phase.vendors,
                    initial = viewModel.installedVendors.ifEmpty { ProfileStore.SUGGESTED },
                    firstRun = phase.firstRun,
                    onCancel = viewModel::cancelVendorSetup,
                    onInstall = { viewModel.installVendors(it) },
                )
                is Phase.ModelSetup -> ModelSetupScreen(
                    catalog = phase.catalog,
                    printerChoice = viewModel.choice(PresetKind.PRINTER),
                    filamentChoice = viewModel.choice(PresetKind.FILAMENT),
                    onDone = { printers, filaments -> viewModel.finishModelSetup(phase.catalog, printers, filaments) },
                    onSkip = viewModel::skipModelSetup,
                )
                Phase.Ready -> {
                    LaunchedEffect(pendingImports.size) {
                        if (pendingImports.isNotEmpty()) {
                            viewModel.importUris(pendingImports.toList())
                            onImportsTaken()
                        }
                    }
                    PlateScreen(viewModel)
                    UpdateDialog()
                    // Drawn over the plate so the 3D view keeps its state while the library is open.
                    viewModel.library?.let { request ->
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            LibraryScreen(
                                startUrl = request.url,
                                onImport = { files ->
                                    viewModel.importFiles(files)
                                    viewModel.closeLibrary()
                                },
                                onClose = viewModel::closeLibrary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

/** Desktop's setup wizard, reduced to its first page: which printer brands to install. */
@Composable
fun VendorSetupScreen(
    vendors: List<String>,
    initial: Set<String>,
    firstRun: Boolean,
    onCancel: () -> Unit,
    onInstall: (Set<String>) -> Unit,
) {
    var chosen by remember { mutableStateOf(initial.filter { it in vendors }.toSet()) }
    var query by rememberSaveable { mutableStateOf("") }
    val shown = VendorNames.sorted(vendors).filter { VendorNames.display(it).contains(query.trim(), ignoreCase = true) }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
        Text(if (firstRun) "Welcome to OrcaPod" else "Printer brands", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "Pick the brands of the printers you own. Their printer, filament and process profiles " +
                "are installed and you switch brands in the printer picker; \"Custom\" holds generic " +
                "printers you can adapt.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            placeholder = { Text("Search brands") },
        )
        LazyColumn(Modifier.weight(1f)) {
            items(shown, key = { it }) { vendor ->
                Row(
                    Modifier.fillMaxWidth().clickable {
                        chosen = if (vendor in chosen) chosen - vendor else chosen + vendor
                    }.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = vendor in chosen, onCheckedChange = null)
                    Spacer(Modifier.width(12.dp))
                    Text(VendorNames.display(vendor))
                }
                HorizontalDivider()
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
            if (!firstRun) TextButton(onClick = onCancel) { Text("Cancel") }
            Spacer(Modifier.width(8.dp))
            Box {
                Button(onClick = { onInstall(chosen) }, enabled = chosen.isNotEmpty()) {
                    Text(if (chosen.size == 1) "Install 1 brand" else "Install ${chosen.size} brands")
                }
            }
        }
    }
}
