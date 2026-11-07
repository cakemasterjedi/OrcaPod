package org.orcapod.app.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import org.orcapod.app.engine.PresetKind
import org.orcapod.app.ui.viewport.PlateView
import org.orcapod.app.ui.viewport.Selection
import org.orcapod.app.ui.viewport.ViewportListener
import org.orcapod.app.ui.viewport.ViewportState

private val MODEL_TYPES = arrayOf("*/*")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlateScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val printerVm: PrinterViewModel = viewModel()
    var sendOpen by remember { mutableStateOf(false) }
    var printersOpen by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    var picker by remember { mutableStateOf<PresetKind?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var layFlat by remember { mutableStateOf(false) }
    var viewRef by remember { mutableStateOf<PlateView?>(null) }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) { printerVm.messages.collect { snackbar.showSnackbar(it) } }

    val importModels = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.importUris(uris)
    }
    val openProject = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importUris(listOf(uri), asProject = true)
    }
    val export3mf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("model/3mf")) { uri ->
        if (uri != null) vm.exportProject(uri)
    }
    val saveGcode = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/x-gcode")) { uri ->
        if (uri != null) vm.saveGcode(uri)
    }

    val listener = remember {
        object : ViewportListener {
            override fun onSelect(selection: Selection?) { vm.selection = selection }
            override fun onMoved(selection: Selection, dx: Double, dy: Double) { vm.move(selection, dx, dy) }
            override fun onFacePicked(selection: Selection, nx: Double, ny: Double, nz: Double) {
                vm.selection = selection
                vm.layFlat(selection, nx, ny, nz)
                layFlat = false
            }
        }
    }

    val done = vm.slice as? SliceState.Done
    val previewing = vm.showPreview && done != null

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (previewing) "Preview" else "OrcaPod") },
                actions = {
                    if (!previewing) {
                        IconButton(onClick = vm::undo, enabled = vm.scene.snapshot.undoCount > 0) { Icon(AppIcons.Undo, "Undo") }
                        IconButton(onClick = vm::redo, enabled = vm.scene.snapshot.redoCount > 0) { Icon(AppIcons.Redo, "Redo") }
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, "More") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("Import models") }, onClick = { menuOpen = false; importModels.launch(MODEL_TYPES) })
                            DropdownMenuItem(text = { Text("Online model library") }, onClick = { menuOpen = false; vm.openLibrary() })
                            DropdownMenuItem(text = { Text("Open 3MF project") }, onClick = { menuOpen = false; openProject.launch(MODEL_TYPES) })
                            DropdownMenuItem(
                                text = { Text("Export plate as 3MF") },
                                enabled = vm.scene.snapshot.objects.isNotEmpty(),
                                onClick = { menuOpen = false; export3mf.launch("plate.3mf") },
                            )
                            DropdownMenuItem(
                                text = { Text("Clear plate") },
                                enabled = vm.scene.snapshot.objects.isNotEmpty(),
                                onClick = { menuOpen = false; vm.clearPlate() },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("Printers") }, onClick = { menuOpen = false; printersOpen = true })
                            DropdownMenuItem(text = { Text("Reset view") }, onClick = { menuOpen = false; viewRef?.resetView() })
                            DropdownMenuItem(text = { Text("Printer brands") }, onClick = { menuOpen = false; vm.manageVendors() })
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx -> PlateView(ctx, listener).also { viewRef = it } },
                update = { view ->
                    view.pickFaces = layFlat
                    view.state = ViewportState(
                        scene = vm.scene,
                        selection = vm.selection,
                        toolpath = done?.toolpath,
                        layer = vm.previewLayer,
                        showToolpath = previewing && done?.toolpath != null,
                    )
                },
            )

            Column(Modifier.fillMaxWidth().padding(8.dp)) {
                PresetBar(vm) { picker = it }
                vm.busy?.let { label ->
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(label, style = MaterialTheme.typography.labelMedium)
                }
                if (layFlat) {
                    Spacer(Modifier.height(6.dp))
                    Hint("Tap the face the object should rest on")
                }
            }

            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(8.dp)) {
                val sel = vm.selection
                when {
                    previewing -> PreviewPanel(
                        vm = vm,
                        done = done!!,
                        onShare = {
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", done.gcode)
                            val send = Intent(Intent.ACTION_SEND)
                                .setType("text/x-gcode")
                                .putExtra(Intent.EXTRA_STREAM, uri)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            context.startActivity(Intent.createChooser(send, "Send G-code"))
                        },
                        onSave = { saveGcode.launch(done.gcode.name) },
                        onPrint = { sendOpen = true },
                    )
                    vm.slice is SliceState.Running -> SlicingPanel(vm.slice as SliceState.Running, vm::cancelSlice)
                    else -> {
                        if (sel != null && vm.transformOf(sel) != null) {
                            ObjectPanel(vm, sel, layFlat = layFlat, onLayFlat = { layFlat = !layFlat })
                            Spacer(Modifier.height(8.dp))
                        }
                        ActionBar(
                            hasObjects = vm.scene.snapshot.objects.isNotEmpty(),
                            hasResult = done != null,
                            onImport = { importModels.launch(MODEL_TYPES) },
                            onLibrary = { vm.openLibrary() },
                            onArrange = vm::arrange,
                            onSettings = { settingsOpen = true },
                            onSlice = vm::startSlice,
                            onShowResult = { vm.showPreview = true },
                        )
                    }
                }
            }
        }
    }

    picker?.let { kind ->
        val (entries, current) = when (kind) {
            PresetKind.PRINTER -> vm.printers to vm.printer
            PresetKind.FILAMENT -> vm.filaments to vm.filament
            PresetKind.PROCESS -> vm.processes to vm.process
        }
        PresetPickerSheet(
            title = kind.title(),
            entries = entries,
            current = current,
            onPick = {
                when (kind) {
                    PresetKind.PRINTER -> vm.selectPrinter(it)
                    PresetKind.FILAMENT -> vm.selectFilament(it)
                    PresetKind.PROCESS -> vm.selectProcess(it)
                }
            },
            onDelete = if (kind == PresetKind.PRINTER) null else { name -> vm.deleteUserPreset(kind, name) },
            onDismiss = { picker = null },
            brands = if (kind == PresetKind.PRINTER) vm.installedVendors.sortedWith(String.CASE_INSENSITIVE_ORDER) else emptyList(),
            brand = vm.activeVendor,
            loading = vm.busy != null,
            onBrand = vm::switchBrand,
        )
    }
    if (settingsOpen) SettingsSheet(vm) { settingsOpen = false }
    if (sendOpen && done != null) SendToPrinterSheet(printerVm, done.gcode, vm.printer) { sendOpen = false }
    if (printersOpen) PrintersSheet(printerVm, vm.printer) { printersOpen = false }
}

@Composable
private fun PresetBar(vm: AppViewModel, onPick: (PresetKind) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))) {
        Column(Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
            PresetLine("Printer", vm.printer) { onPick(PresetKind.PRINTER) }
            Row {
                Box(Modifier.weight(1f)) { PresetLine("Filament", vm.filament) { onPick(PresetKind.FILAMENT) } }
                Box(Modifier.weight(1f)) { PresetLine("Process", vm.process) { onPick(PresetKind.PROCESS) } }
            }
        }
    }
}

@Composable
private fun PresetLine(label: String, value: String, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text(
                value.ifEmpty { "—" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Hint(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Text(text, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ActionBar(
    hasObjects: Boolean,
    hasResult: Boolean,
    onImport: () -> Unit,
    onLibrary: () -> Unit,
    onArrange: () -> Unit,
    onSettings: () -> Unit,
    onSlice: () -> Unit,
    onShowResult: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(onClick = onImport) { Icon(Icons.Filled.Add, "Import models") }
            IconButton(onClick = onLibrary) { Icon(AppIcons.CloudDownload, "Online model library") }
            IconButton(onClick = onArrange, enabled = hasObjects) { Icon(AppIcons.GridView, "Arrange") }
            IconButton(onClick = onSettings) { Icon(AppIcons.Tune, "Print settings") }
            if (hasResult) IconButton(onClick = onShowResult) { Icon(AppIcons.Layers, "Show preview") }
            Spacer(Modifier.weight(1f))
            Button(onClick = onSlice, enabled = hasObjects) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Slice")
            }
        }
    }
}

@Composable
private fun SlicingPanel(state: SliceState.Running, onCancel: () -> Unit) {
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Slicing… ${state.percent}%", style = MaterialTheme.typography.titleMedium)
            Text(state.message, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = { state.percent / 100f }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = onCancel, modifier = Modifier.align(Alignment.End)) { Text("Cancel") }
        }
    }
}
