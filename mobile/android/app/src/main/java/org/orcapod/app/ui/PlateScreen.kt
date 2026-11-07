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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.ui.graphics.Color
import org.orcapod.app.engine.Shape
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
    val snackbar = remember { SnackbarHostState() }
    var picker by remember { mutableStateOf<PresetKind?>(null) }
    var settingsFor by remember { mutableStateOf<PresetKind?>(null) }
    var objectList by remember { mutableStateOf(false) }
    var objectSheet by remember { mutableStateOf<Selection?>(null) }
    var filamentSlots by remember { mutableStateOf(false) }
    var shapeMenu by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var layFlat by remember { mutableStateOf(false) }
    var viewRef by remember { mutableStateOf<PlateView?>(null) }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val importModels = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.importUris(uris)
    }
    val openProject = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importUris(listOf(uri), asProject = true)
    }
    val saveProject = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("model/3mf")) { uri ->
        if (uri != null) vm.saveProject(uri)
    }
    val export3mf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("model/3mf")) { uri ->
        if (uri != null) vm.exportGeneric3mf(uri)
    }
    val exportStl = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("model/stl")) { uri ->
        if (uri != null) vm.exportStl(null, uri)
    }
    var slicedAll by remember { mutableStateOf(false) }
    val exportSliced = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("model/3mf")) { uri ->
        if (uri != null) vm.exportSliced3mf(uri, slicedAll)
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
            override fun onRay(origin: FloatArray, direction: FloatArray) { vm.placeConnector(origin, direction) }
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
                            val hasObjects = vm.scene.snapshot.objects.isNotEmpty()
                            DropdownMenuItem(text = { Text("Import models") }, onClick = { menuOpen = false; importModels.launch(MODEL_TYPES) })
                            DropdownMenuItem(text = { Text("Add shape…") }, onClick = { menuOpen = false; shapeMenu = true })
                            DropdownMenuItem(text = { Text("Open 3MF project") }, onClick = { menuOpen = false; openProject.launch(MODEL_TYPES) })
                            DropdownMenuItem(
                                text = { Text("Save project") },
                                enabled = hasObjects,
                                onClick = { menuOpen = false; saveProject.launch("project.3mf") },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Export plate sliced file") },
                                enabled = hasObjects && vm.slice !is SliceState.Running,
                                onClick = { menuOpen = false; slicedAll = false; exportSliced.launch("plate.gcode.3mf") },
                            )
                            if (vm.scene.snapshot.plates.size > 1) DropdownMenuItem(
                                text = { Text("Export all plates sliced file") },
                                enabled = hasObjects && vm.slice !is SliceState.Running,
                                onClick = { menuOpen = false; slicedAll = true; exportSliced.launch("plates.gcode.3mf") },
                            )
                            DropdownMenuItem(
                                text = { Text("Export generic 3MF") },
                                enabled = hasObjects,
                                onClick = { menuOpen = false; export3mf.launch("plate.3mf") },
                            )
                            DropdownMenuItem(
                                text = { Text("Export all objects as STL") },
                                enabled = hasObjects,
                                onClick = { menuOpen = false; exportStl.launch("plate.stl") },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Clear plate") },
                                enabled = hasObjects,
                                onClick = { menuOpen = false; vm.clearPlate() },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("Reset view") }, onClick = { menuOpen = false; viewRef?.resetView() })
                            DropdownMenuItem(text = { Text("Printer brands") }, onClick = { menuOpen = false; vm.manageVendors() })
                        }
                        DropdownMenu(expanded = shapeMenu, onDismissRequest = { shapeMenu = false }) {
                            for (shape in Shape.entries) if (shape != Shape.SLAB) {
                                DropdownMenuItem(text = { Text(shape.label) }, onClick = { shapeMenu = false; vm.addShape(shape) })
                            }
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
                    view.rayTaps = vm.cut?.placing == true
                    view.lockDrag = vm.cut != null
                    view.state = ViewportState(
                        scene = vm.scene,
                        selection = vm.selection,
                        toolpath = done?.toolpath,
                        range = vm.previewRange,
                        previewStyle = vm.previewStyle,
                        showToolpath = previewing && done?.toolpath != null,
                        toolpathPlate = done?.plate ?: 0,
                        selectedVolume = vm.selectedVolume,
                        filamentColours = vm.filamentSlots.map { it.colour },
                        cutQuad = vm.cut?.quad(),
                        cutPoints = vm.cut?.connectorPoints(),
                    )
                },
            )

            Column(Modifier.fillMaxWidth().padding(8.dp)) {
                PresetBar(vm, onFilaments = { filamentSlots = true }) { picker = it }
                if (!previewing) PlateStrip(vm)
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
                    )
                    vm.slice is SliceState.Running -> SlicingPanel(vm.slice as SliceState.Running, vm::cancelSlice)
                    vm.cut != null -> CutPanel(vm, vm.cut!!)
                    else -> {
                        if (sel != null && vm.transformOf(sel) != null) {
                            ObjectPanel(vm, sel, layFlat = layFlat, onLayFlat = { layFlat = !layFlat }, onEdit = { objectSheet = sel })
                            Spacer(Modifier.height(8.dp))
                        }
                        ActionBar(
                            hasObjects = vm.scene.snapshot.objects.isNotEmpty(),
                            hasResult = done != null,
                            plates = vm.scene.snapshot.plates.size,
                            onImport = { importModels.launch(MODEL_TYPES) },
                            onObjects = { objectList = true },
                            onArrange = vm::arrange,
                            onSettings = { settingsFor = PresetKind.PROCESS },
                            onSlice = { vm.startSlice(allPlates = it) },
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
        )
    }
    settingsFor?.let { kind -> SettingsScreen(vm, kind) { settingsFor = null } }
    if (objectList) ObjectListSheet(vm, onOpen = { objectList = false; vm.selection = it; objectSheet = it }) { objectList = false }
    objectSheet?.let { sel -> ObjectSheet(vm, sel) { objectSheet = null } }
    if (filamentSlots) {
        FilamentSlotsSheet(vm, onEdit = { filamentSlots = false; settingsFor = PresetKind.FILAMENT }) { filamentSlots = false }
    }
}

@Composable
private fun PresetBar(vm: AppViewModel, onFilaments: () -> Unit, onPick: (PresetKind) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))) {
        Column(Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
            PresetLine("Printer", vm.printer) { onPick(PresetKind.PRINTER) }
            Row {
                Box(Modifier.weight(1f)) {
                    val slots = vm.filamentSlots
                    if (slots.size > 1) {
                        PresetLine("Filaments", "${slots.size} slots", swatches = slots.map { it.colour }, onClick = onFilaments)
                    } else {
                        PresetLine("Filament", vm.filament, onLongClick = onFilaments) { onPick(PresetKind.FILAMENT) }
                    }
                }
                Box(Modifier.weight(1f)) { PresetLine("Process", vm.process) { onPick(PresetKind.PROCESS) } }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresetLine(
    label: String,
    value: String,
    swatches: List<String> = emptyList(),
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            for (c in swatches.take(8)) {
                Box(Modifier.size(14.dp).background(parseColor(c) ?: Color.Gray, CircleShape))
                Spacer(Modifier.width(3.dp))
            }
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
    plates: Int,
    onImport: () -> Unit,
    onObjects: () -> Unit,
    onArrange: () -> Unit,
    onSettings: () -> Unit,
    onSlice: (allPlates: Boolean) -> Unit,
    onShowResult: () -> Unit,
) {
    var sliceMenu by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(onClick = onImport) { Icon(Icons.Filled.Add, "Import models") }
            IconButton(onClick = onObjects, enabled = hasObjects) { Icon(Icons.AutoMirrored.Filled.List, "Objects") }
            IconButton(onClick = onArrange, enabled = hasObjects) { Icon(AppIcons.GridView, "Arrange") }
            IconButton(onClick = onSettings) { Icon(AppIcons.Tune, "Print settings") }
            if (hasResult) IconButton(onClick = onShowResult) { Icon(AppIcons.Layers, "Show preview") }
            Spacer(Modifier.weight(1f))
            Box {
                Button(onClick = { if (plates > 1) sliceMenu = true else onSlice(false) }, enabled = hasObjects) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Slice")
                }
                DropdownMenu(expanded = sliceMenu, onDismissRequest = { sliceMenu = false }) {
                    DropdownMenuItem(text = { Text("Slice this plate") }, onClick = { sliceMenu = false; onSlice(false) })
                    DropdownMenuItem(text = { Text("Slice all plates") }, onClick = { sliceMenu = false; onSlice(true) })
                }
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
