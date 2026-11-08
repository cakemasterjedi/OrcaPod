package org.orcapod.app.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import org.orcapod.app.engine.PresetKind
import org.orcapod.app.engine.Shape
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
    var queueOpen by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    var picker by remember { mutableStateOf<PresetKind?>(null) }
    var profilesOpen by remember { mutableStateOf(false) }
    var settingsFor by remember { mutableStateOf<PresetKind?>(null) }
    var objectList by remember { mutableStateOf(false) }
    var objectSheet by remember { mutableStateOf<Selection?>(null) }
    var filamentSlots by remember { mutableStateOf(false) }
    var shapeMenu by remember { mutableStateOf(false) }
    var calibrating by remember { mutableStateOf(false) }
    var layFlat by remember { mutableStateOf(false) }
    var viewRef by remember { mutableStateOf<PlateView?>(null) }
    val config = LocalConfiguration.current
    val twoPane = usesTwoPane(config.screenWidthDp, config.screenHeightDp)
    var paneTab by rememberSaveable { mutableStateOf(PaneTab.OBJECTS) }
    // Picking an object in the viewport brings its controls into view.
    LaunchedEffect(vm.selection) { if (vm.selection != null) paneTab = PaneTab.OBJECTS }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) { printerVm.messages.collect { snackbar.showSnackbar(it) } }
    // A queue notification was tapped.
    LaunchedEffect(Unit) {
        printerVm.fleet.dashboardRequested.collect { if (it) { queueOpen = true; printerVm.fleet.dashboardRequested.value = false } }
    }

    val importModels = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.importUris(uris)
    }
    var recentSheet by remember { mutableStateOf(false) }
    // A step that replaces the plate, held while the user decides about unsaved changes.
    var unsavedGuard by remember { mutableStateOf<(() -> Unit)?>(null) }
    // Runs after a "Save as" that the unsaved-changes prompt started.
    var afterSaveAs by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun guarded(action: () -> Unit) { if (vm.projectDirty) unsavedGuard = action else action() }

    val openProject = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.openProject(uri)
    }
    val saveProjectAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("model/3mf")) { uri ->
        val then = afterSaveAs
        afterSaveAs = null
        if (uri != null) vm.saveProject(uri, then)
    }
    val projectFileName = (vm.projectName ?: vm.scene.snapshot.objects.firstOrNull()?.name?.substringBeforeLast('.') ?: "project") + ".3mf"
    fun save(then: (() -> Unit)? = null) {
        if (vm.projectUri != null) vm.saveProject(null, then)
        else { afterSaveAs = then; saveProjectAs.launch(projectFileName) }
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
            override fun onMeasure(selection: Selection, triangle: Int, local: FloatArray) { vm.measureAt(selection, triangle, local) }
            override fun onPaint(objectId: Long, volumeId: Long, hit: FloatArray, view: FloatArray, first: Boolean) {
                vm.paintAt(objectId, volumeId, hit, view, first)
            }
            override fun onPaintEnd() { vm.paintStrokeEnd() }
        }
    }

    val done = vm.slice as? SliceState.Done
    val previewing = vm.showPreview && done != null

    val hasObjects = vm.scene.snapshot.objects.isNotEmpty()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val canExport = hasObjects && vm.slice !is SliceState.Running
    val menuGroups = listOf(
        MenuGroup(
            "Project",
            listOf(
                MenuEntry("New project", Icons.Filled.Add) { guarded { vm.newProject() } },
                MenuEntry("Open project", AppIcons.FolderOpen) { guarded { openProject.launch(MODEL_TYPES) } },
                MenuEntry("Recent projects", AppIcons.History) { recentSheet = true },
                MenuEntry("Save project", AppIcons.Save, enabled = hasObjects) { save() },
                MenuEntry("Save project as", AppIcons.Save, enabled = hasObjects) { saveProjectAs.launch(projectFileName) },
                MenuEntry("Clear plate", Icons.Filled.Delete, enabled = hasObjects) { vm.clearPlate() },
            ),
        ),
        MenuGroup(
            "Add to the plate",
            listOf(
                MenuEntry("Import models", AppIcons.FileOpen) { importModels.launch(MODEL_TYPES) },
                MenuEntry("Online model library", AppIcons.CloudDownload) { vm.openLibrary() },
                MenuEntry("Add shape", AppIcons.Category) { shapeMenu = true },
            ),
        ),
        MenuGroup(
            "Export",
            listOfNotNull(
                MenuEntry("Export plate sliced file", AppIcons.Export, enabled = canExport) {
                    slicedAll = false; exportSliced.launch("plate.gcode.3mf")
                },
                if (vm.scene.snapshot.plates.size > 1) MenuEntry("Export all plates sliced file", AppIcons.Export, enabled = canExport) {
                    slicedAll = true; exportSliced.launch("plates.gcode.3mf")
                } else null,
                MenuEntry("Export generic 3MF", AppIcons.Export, enabled = hasObjects) { export3mf.launch("plate.3mf") },
                MenuEntry("Export all objects as STL", AppIcons.Export, enabled = hasObjects) { exportStl.launch("plate.stl") },
            ),
        ),
        MenuGroup(
            "Printers",
            listOf(
                MenuEntry("My printers", AppIcons.Print) { printersOpen = true },
                MenuEntry("Print queue", AppIcons.Queue) { queueOpen = true },
                MenuEntry("Printer brands", Icons.Filled.Star) { vm.manageVendors() },
                MenuEntry("Back up and sync profiles", Icons.Filled.Refresh) { profilesOpen = true },
            ),
        ),
        MenuGroup(
            "Preferences",
            listOf(
                MenuEntry("Ask for STEP import options", checked = vm.askStepOptions) { vm.askStepOptions = !vm.askStepOptions },
                MenuEntry("Ask about unsupported characters in names", checked = vm.askNameCleanup) {
                    vm.askNameCleanup = !vm.askNameCleanup
                },
            ),
        ),
        MenuGroup(
            "Tools",
            listOf(
                MenuEntry("Print settings", AppIcons.Tune) { settingsFor = PresetKind.PROCESS },
                MenuEntry("Calibration", Icons.Filled.Build) { calibrating = true },
                MenuEntry("Reset view", AppIcons.ViewInAr) { viewRef?.resetView() },
            ),
        ),
    )
    val projectTitle = vm.projectName?.let { if (vm.projectDirty) "$it*" else it }

    ModalNavigationDrawer(
        drawerState = drawer,
        // Swipes belong to the 3D view; the drawer only opens from the menu button.
        gesturesEnabled = drawer.isOpen,
        drawerContent = { AppDrawerSheet(menuGroups, projectTitle) { scope.launch { drawer.close() } } },
    ) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            CenterAlignedTopAppBar(
                navigationIcon = {
                    IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Filled.Menu, "Menu") }
                },
                title = {
                    StageTabs(
                        previewing = previewing,
                        canPreview = done != null,
                        onPrepare = { vm.showPreview = false },
                        onPreview = { vm.showPreview = true },
                    )
                },
                actions = {
                    if (!previewing) {
                        IconButton(onClick = vm::undo, enabled = vm.scene.snapshot.undoCount > 0) { Icon(AppIcons.Undo, "Undo") }
                        IconButton(onClick = vm::redo, enabled = vm.scene.snapshot.redoCount > 0) { Icon(AppIcons.Redo, "Redo") }
                    }
                    Box {
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
        val emptyCard: @Composable BoxScope.() -> Unit = {
            if (!hasObjects && !previewing && vm.busy == null) {
                EmptyPlateCard(
                    onImport = { importModels.launch(MODEL_TYPES) },
                    onLibrary = { vm.openLibrary() },
                    onShape = { shapeMenu = true },
                    onOpenProject = { guarded { openProject.launch(MODEL_TYPES) } },
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
            }
        }
        val viewport: @Composable () -> Unit = {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx -> PlateView(ctx, listener).also { viewRef = it } },
            update = { view ->
                view.pickFaces = layFlat
                view.rayTaps = vm.cut?.placing == true
                view.lockDrag = vm.cut != null || vm.paint != null || vm.measure != null || vm.layerHeights != null
                view.paintObject = vm.paint?.objectId
                view.measureObject = vm.measure?.objectId
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
                    paintOverlay = if (vm.paint != null) vm.paintOverlay else null,
                    measureLines = vm.measure?.lines(),
                    measurePoints = vm.measure?.points(),
                )
            },
        )
        }
        val status: @Composable () -> Unit = {
            if (!previewing) PlateStrip(vm)
            vm.busy?.let { label ->
                Spacer(Modifier.height(6.dp))
                val percent = vm.busyProgress
                if (percent != null) {
                    LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (percent != null) "$label $percent%" else label,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    vm.busyCancel?.let { cancel -> TextButton(onClick = cancel) { Text("Cancel") } }
                }
            }
            if (layFlat) {
                Spacer(Modifier.height(6.dp))
                Hint("Tap the face the object should rest on")
            }
        }
        // The preview, slicing progress, tool panels or the plate actions; the phone also shows
        // the selected object's controls here, a wide screen shows them in its side pane.
        val controls: @Composable (withObject: Boolean, onObjects: () -> Unit, onSettings: () -> Unit) -> Unit =
            { withObject, onObjects, onSettings ->
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
                    vm.cut != null -> CutPanel(vm, vm.cut!!)
                    vm.paint != null -> PaintPanel(vm, vm.paint!!)
                    vm.layerHeights != null -> LayerHeightPanel(vm, vm.layerHeights!!)
                    vm.measure != null -> MeasurePanel(vm, vm.measure!!)
                    else -> Column {
                        if (withObject && sel != null && vm.transformOf(sel) != null) {
                            ObjectPanel(vm, sel, layFlat = layFlat, onLayFlat = { layFlat = !layFlat }, onEdit = { objectSheet = sel })
                            Spacer(Modifier.height(8.dp))
                        }
                        if (vm.calibration != null) {
                            CalibrationBanner(vm)
                            Spacer(Modifier.height(8.dp))
                        }
                        ActionBar(
                            hasObjects = hasObjects,
                            hasResult = done != null,
                            plates = vm.scene.snapshot.plates.size,
                            onImport = { importModels.launch(MODEL_TYPES) },
                            onObjects = onObjects,
                            onLibrary = { vm.openLibrary() },
                            onShape = { shapeMenu = true },
                            onArrange = vm::arrange,
                            onSettings = onSettings,
                            onSlice = { vm.startSlice(allPlates = it) },
                            onShowResult = { vm.showPreview = true },
                        )
                    }
                }
            }

        if (twoPane) {
            Row(Modifier.fillMaxSize().padding(padding)) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    viewport()
                    emptyCard()
                    Column(Modifier.fillMaxWidth().padding(8.dp)) { status() }
                }
                VerticalDivider()
                SidePane(
                    tab = paneTab,
                    onTab = { paneTab = it },
                    modifier = Modifier.width(sidePaneWidth(config.screenWidthDp)).fillMaxHeight().navigationBarsPadding(),
                    header = { PresetBar(vm, onFilaments = { filamentSlots = true }) { picker = it } },
                    objects = { ObjectsTab(vm, layFlat = layFlat, onLayFlat = { layFlat = !layFlat }, onEdit = { objectSheet = it }) },
                    settings = { SettingsContent(vm, PresetKind.PROCESS, Modifier.fillMaxSize()) },
                    footer = { controls(false, { paneTab = PaneTab.OBJECTS }, { paneTab = PaneTab.SETTINGS }) },
                )
            }
        } else {
            Box(Modifier.fillMaxSize().padding(padding)) {
                viewport()
                emptyCard()
                Column(Modifier.fillMaxWidth().padding(8.dp)) {
                    PresetBar(vm, onFilaments = { filamentSlots = true }) { picker = it }
                    status()
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(8.dp)) {
                    controls(true, { objectList = true }, { settingsFor = PresetKind.PROCESS })
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
    if (profilesOpen) ProfilesSheet(vm) { profilesOpen = false }
    settingsFor?.let { kind -> SettingsScreen(vm, kind) { settingsFor = null } }
    // The side pane already shows the object list.
    if (objectList && !twoPane) ObjectListSheet(vm, onOpen = { objectList = false; vm.selection = it; objectSheet = it }) { objectList = false }
    objectSheet?.let { sel -> ObjectSheet(vm, sel) { objectSheet = null } }
    if (calibrating) CalibrationDialog(vm) { calibrating = false }
    if (recentSheet) {
        RecentProjectsSheet(vm, onOpen = { p -> recentSheet = false; guarded { vm.openRecent(p) } }) { recentSheet = false }
    }
    unsavedGuard?.let { action ->
        UnsavedChangesDialog(
            name = vm.projectName,
            onSave = { unsavedGuard = null; save(action) },
            onDiscard = { unsavedGuard = null; action() },
            onCancel = { unsavedGuard = null },
        )
    }
    vm.projectChoice?.let { uri ->
        ProjectChoiceDialog(
            onOpen = { vm.projectChoice = null; guarded { vm.openProject(uri) } },
            onGeometry = { vm.projectChoice = null; vm.importUris(listOf(uri), askProject = false) },
            onCancel = { vm.projectChoice = null },
        )
    }
    RecoveryDialog(vm)
    if (filamentSlots) {
        FilamentSlotsSheet(vm, onEdit = { filamentSlots = false; settingsFor = PresetKind.FILAMENT }) { filamentSlots = false }
    }
    if (sendOpen && done != null) {
        SendToPrinterSheet(printerVm, done.gcode, vm.printer, onOpenQueue = { sendOpen = false; queueOpen = true }) { sendOpen = false }
    }
    if (queueOpen) PrintQueueSheet(printerVm) { queueOpen = false }
    if (printersOpen) PrintersSheet(printerVm, vm.printer) { printersOpen = false }
    vm.stepImport?.let { StepImportDialog(it, vm::stepTriangleCountAsync, vm::stopTriangleCount, vm::answerStepImport) }
    vm.nameCleanup?.let { NameCleanupDialog(it, vm::previewNameChanges, vm::answerNameCleanup) }
}

@Composable
private fun PresetBar(vm: AppViewModel, onFilaments: () -> Unit, onPick: (PresetKind) -> Unit) =
    PresetBar(vm.printer, vm.filament, vm.process, vm.filamentSlots.map { it.colour }, onFilaments, onPick)

/** The printer, filament and process in use; tapping one opens its picker. */
@Composable
internal fun PresetBar(
    printer: String,
    filament: String,
    process: String,
    slotColours: List<String>,
    onFilaments: () -> Unit,
    onPick: (PresetKind) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.94f))) {
        Column(Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
            PresetLine("Printer", printer, icon = AppIcons.Print) { onPick(PresetKind.PRINTER) }
            Row {
                Box(Modifier.weight(1f)) {
                    if (slotColours.size > 1) {
                        PresetLine("Filaments", "${slotColours.size} slots", swatches = slotColours, onClick = onFilaments)
                    } else {
                        PresetLine("Filament", filament, swatches = slotColours.take(1), onLongClick = onFilaments) { onPick(PresetKind.FILAMENT) }
                    }
                }
                Box(Modifier.weight(1f)) { PresetLine("Process", process, icon = AppIcons.Tune) { onPick(PresetKind.PROCESS) } }
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
    icon: ImageVector? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
            }
            for (c in swatches.take(8)) {
                Box(Modifier.size(14.dp).background(parseColor(c) ?: Color.Gray, CircleShape))
                Spacer(Modifier.width(3.dp))
            }
            Text(
                value.ifEmpty { "—" },
                Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Hint(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Text(text, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

/** The add → arrange → slice flow, with every button labelled. */
@Composable
internal fun ActionBar(
    hasObjects: Boolean,
    hasResult: Boolean,
    plates: Int,
    onImport: () -> Unit,
    onObjects: () -> Unit,
    onLibrary: () -> Unit,
    onShape: () -> Unit,
    onArrange: () -> Unit,
    onSettings: () -> Unit,
    onSlice: (allPlates: Boolean) -> Unit,
    onShowResult: () -> Unit,
) {
    var sliceMenu by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.96f))) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                ToolButton("Add", Icons.Filled.Add) { addMenu = true }
                DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Import models") },
                        leadingIcon = { Icon(AppIcons.FileOpen, null) },
                        onClick = { addMenu = false; onImport() },
                    )
                    DropdownMenuItem(
                        text = { Text("Online model library") },
                        leadingIcon = { Icon(AppIcons.CloudDownload, null) },
                        onClick = { addMenu = false; onLibrary() },
                    )
                    DropdownMenuItem(
                        text = { Text("Add shape") },
                        leadingIcon = { Icon(AppIcons.Category, null) },
                        onClick = { addMenu = false; onShape() },
                    )
                }
            }
            ToolButton("Objects", Icons.AutoMirrored.Filled.List, enabled = hasObjects, onClick = onObjects)
            ToolButton("Arrange", AppIcons.GridView, enabled = hasObjects, onClick = onArrange)
            ToolButton("Settings", AppIcons.Tune, onClick = onSettings)
            Spacer(Modifier.weight(1f))
            if (hasResult) {
                FilledTonalIconButton(onClick = onShowResult) { Icon(AppIcons.Layers, "Show preview") }
                Spacer(Modifier.width(4.dp))
            }
            Box {
                Button(
                    onClick = { if (plates > 1) sliceMenu = true else onSlice(false) },
                    enabled = hasObjects,
                    modifier = Modifier.height(48.dp),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Slice", style = MaterialTheme.typography.titleSmall)
                }
                DropdownMenu(expanded = sliceMenu, onDismissRequest = { sliceMenu = false }) {
                    DropdownMenuItem(text = { Text("Slice this plate") }, onClick = { sliceMenu = false; onSlice(false) })
                    DropdownMenuItem(text = { Text("Slice all plates") }, onClick = { sliceMenu = false; onSlice(true) })
                }
            }
            Spacer(Modifier.width(4.dp))
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
