package org.orcapod.app.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.system.Os
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.orcapod.app.data.ProfileStore
import org.orcapod.app.engine.BedTypeState
import org.orcapod.app.engine.PaintLayer
import org.orcapod.app.engine.CustomGcode
import org.orcapod.app.engine.CustomGcodeType
import org.orcapod.app.engine.Engine
import org.orcapod.app.engine.FilamentSlot
import org.orcapod.app.engine.HeightRange
import org.orcapod.app.engine.InstanceTransform
import org.orcapod.app.engine.OrcaException
import org.orcapod.app.engine.PresetEntry
import org.orcapod.app.engine.PresetKind
import org.orcapod.app.engine.CalibrationTest
import org.orcapod.app.engine.PlateSettings
import org.orcapod.app.engine.Scene
import org.orcapod.app.engine.SceneObject
import org.orcapod.app.engine.Shape
import org.orcapod.app.engine.SliceReport
import org.orcapod.app.engine.SliceTarget
import org.orcapod.app.engine.Toolpath
import org.orcapod.app.engine.VolumeType
import org.orcapod.app.settings.OptionDef
import org.orcapod.app.settings.OptionMode
import org.orcapod.app.settings.SettingsLayout
import org.orcapod.app.settings.parseKeys
import org.orcapod.app.settings.parseValues
import org.orcapod.app.ui.viewport.PreviewOption
import org.orcapod.app.ui.viewport.PreviewRange
import org.orcapod.app.ui.viewport.PreviewStyle
import org.orcapod.app.ui.viewport.Selection
import org.orcapod.app.ui.viewport.ViewMode
import java.io.File

sealed interface Phase {
    data class Loading(val message: String) : Phase
    data class VendorSetup(val vendors: List<String>, val firstRun: Boolean) : Phase
    data object Ready : Phase
    data class Failed(val message: String) : Phase
}

sealed interface SliceState {
    data object Idle : SliceState
    data class Running(val percent: Int, val message: String) : SliceState
    /**
     * A finished slice. [plate] is the index of the sliced plate, or -1 when
     * every plate went into one file (no layer preview then).
     */
    data class Done(val report: SliceReport, val gcode: File, val toolpath: Toolpath?, val plate: Int) : SliceState
}

/** The per-object or per-part settings target: [volumeId] 0 is the object itself. */
data class OverrideTarget(val objectId: Long, val volumeId: Long)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ProfileStore(app)
    private val prefs = app.getSharedPreferences("session", Context.MODE_PRIVATE)
    private var engine: Engine? = null
    private var sliceJob: Job? = null

    var phase by mutableStateOf<Phase>(Phase.Loading("Starting the engine…"))
        private set
    var scene by mutableStateOf(Scene.EMPTY)
        private set
    var selection by mutableStateOf<Selection?>(null)
    var busy by mutableStateOf<String?>(null)
        private set
    var slice by mutableStateOf<SliceState>(SliceState.Idle)
        private set
    var showPreview by mutableStateOf(false)
    /** Layers and moves of the preview shown (desktop's vertical and horizontal sliders). */
    var previewRange by mutableStateOf(PreviewRange())
    var previewMode by mutableStateOf(ViewMode.LINE_TYPE)
        private set
    /** Extrusion roles hidden from the preview (desktop legend toggles). */
    var hiddenRoles by mutableStateOf<Set<Int>>(emptySet())
    var previewOptions by mutableStateOf<Set<PreviewOption>>(emptySet())
        private set
    /** The current plate's per-layer custom G-code (colour changes, pauses, custom). */
    var customGcodes by mutableStateOf<List<CustomGcode>>(emptyList())
        private set
    /** The custom G-code changed since the shown slice; slicing again applies it. */
    var sliceStale by mutableStateOf(false)
        private set
    val previewStyle: PreviewStyle
        get() = PreviewStyle(previewMode, hiddenRoles, previewOptions, filamentSlots.map { it.colour })

    var printers by mutableStateOf<List<PresetEntry>>(emptyList())
        private set
    var filaments by mutableStateOf<List<PresetEntry>>(emptyList())
        private set
    var processes by mutableStateOf<List<PresetEntry>>(emptyList())
        private set
    var printer by mutableStateOf("")
        private set
    var filament by mutableStateOf("")
        private set
    var process by mutableStateOf("")
        private set
    /** Engine option definitions, by key. */
    var optionDefs by mutableStateOf<Map<String, OptionDef>>(emptyMap())
        private set
    /** The desktop settings tabs' pages and groups. */
    val layout: SettingsLayout by lazy {
        SettingsLayout.parse(app.assets.open("settings_layout.json").bufferedReader().use { it.readText() })
    }
    /** Edited (working) values of each selected preset, as slicing uses them. */
    var presetValues by mutableStateOf<Map<PresetKind, Map<String, String>>>(emptyMap())
        private set
    /** Keys whose edited value differs from the saved preset, per kind. */
    var modifiedKeys by mutableStateOf<Map<PresetKind, Set<String>>>(emptyMap())
        private set
    /** Keys a per-object (index 0) or per-part (index 1) override accepts. */
    var overrideKeys by mutableStateOf(listOf<Set<String>>(emptySet(), emptySet()))
        private set
    var settingsMode by mutableStateOf(OptionMode.ADVANCED)
        private set
    var filamentSlots by mutableStateOf<List<FilamentSlot>>(emptyList())
        private set
    var bedType by mutableStateOf<BedTypeState?>(null)
        private set
    /** The part highlighted in the object list, if any. */
    var selectedVolume by mutableStateOf<Long?>(null)
    var installedVendors by mutableStateOf<Set<String>>(emptySet())
        private set

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    init {
        viewModelScope.launch { start() }
    }

    private suspend fun start() {
        try {
            // libc++ on Android resolves the temporary directory from TMPDIR (else /data/local/tmp).
            Os.setenv("TMPDIR", getApplication<Application>().cacheDir.path, true)
            Engine.abiMismatch()?.let { phase = Phase.Failed(it); return }
            installedVendors = store.selectedVendors
            if (!store.isInstalled()) {
                if (installedVendors.isEmpty()) {
                    phase = Phase.VendorSetup(store.availableVendors(), firstRun = true)
                    return
                }
                phase = Phase.Loading("Updating printer profiles…")
                store.install(installedVendors)
            }
            openEngine()
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            Log.e(TAG, "startup failed", e)
            phase = Phase.Failed(e.message ?: e.toString())
        }
    }

    // --- vendors ---

    fun manageVendors() = viewModelScope.launch {
        phase = Phase.VendorSetup(store.availableVendors(), firstRun = false)
    }

    fun cancelVendorSetup() {
        if (engine != null) phase = Phase.Ready
    }

    fun installVendors(vendors: Set<String>) = viewModelScope.launch {
        try {
            phase = Phase.Loading("Installing printer profiles…")
            store.install(vendors)
            installedVendors = vendors
            val e = engine
            if (e == null) {
                openEngine()
            } else {
                phase = Phase.Loading("Loading printer profiles…")
                e.reloadPresets(store.dataDir.path)
                restoreSelection(e)
                refreshScene()
                invalidateSlice()
                phase = Phase.Ready
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            Log.e(TAG, "install failed", e)
            phase = Phase.Failed(e.message ?: e.toString())
        }
    }

    private suspend fun openEngine() {
        phase = Phase.Loading("Loading printer profiles…")
        val e = Engine.open(store.dataDir.path)
        engine = e
        settingsMode = OptionMode.of(prefs.getString("settings_mode", OptionMode.ADVANCED.code) ?: "")
        previewMode = ViewMode.entries.firstOrNull { it.name == prefs.getString("preview_mode", null) } ?: ViewMode.LINE_TYPE
        previewOptions = prefs.getStringSet("preview_options", emptySet()).orEmpty()
            .mapNotNull { n -> PreviewOption.entries.firstOrNull { it.name == n } }.toSet()
        val defs = e.optionDefs()
        val objectKeys = e.overrideKeys(false)
        val volumeKeys = e.overrideKeys(true)
        withContext(Dispatchers.Default) {
            optionDefs = OptionDef.parseAll(defs)
            overrideKeys = listOf(parseKeys(objectKeys) - "extruder", parseKeys(volumeKeys) - "extruder")
            layout.pages(PresetKind.PROCESS) // parse off the main thread
        }
        restoreSelection(e)
        refreshScene()
        phase = Phase.Ready
    }

    /** Re-selects the last session's presets, or the first printer's defaults. */
    private suspend fun restoreSelection(e: Engine) {
        val machines = e.list(PresetKind.PRINTER)
        val saved = prefs.getString("printer", null)
        if (saved != null && machines.any { it.name == saved }) {
            try {
                e.select(saved, prefs.getString("filament", "") ?: "", prefs.getString("process", "") ?: "")
            } catch (_: OrcaException) {
                e.selectPrinter(saved)
            }
        } else if (machines.isNotEmpty()) {
            e.selectPrinter(machines.first().name)
            _messages.tryEmit("Pick your printer from the bar at the top")
        }
        refreshPresets()
    }

    private suspend fun refreshPresets() {
        val e = engine ?: return
        printers = e.list(PresetKind.PRINTER)
        filaments = e.list(PresetKind.FILAMENT).filter { it.compatible }
        processes = e.list(PresetKind.PROCESS).filter { it.compatible }
        printer = e.current(PresetKind.PRINTER)
        filament = e.current(PresetKind.FILAMENT)
        process = e.current(PresetKind.PROCESS)
        prefs.edit().putString("printer", printer).putString("filament", filament).putString("process", process).apply()
        readSettings()
    }

    /** Re-reads the selected presets' values, filament slots and plate type. */
    private suspend fun readSettings() {
        val e = engine ?: return
        val dumps = PresetKind.entries.associateWith { e.presetConfig(it, edited = true) to e.presetConfig(it, edited = false) }
        val (values, modified) = withContext(Dispatchers.Default) {
            val v = HashMap<PresetKind, Map<String, String>>()
            val m = HashMap<PresetKind, Set<String>>()
            for ((kind, pair) in dumps) {
                val edited = parseValues(pair.first)
                val saved = parseValues(pair.second)
                v[kind] = edited
                m[kind] = edited.filter { (k, value) -> saved[k] != value }.keys
            }
            v to m
        }
        presetValues = values
        modifiedKeys = modified
        filamentSlots = e.filamentSlots()
        bedType = e.bedType()
    }

    /** Values of the edited preset of [kind] with defaults for keys it does not set. */
    fun valueOf(kind: PresetKind, key: String): String =
        presetValues[kind]?.get(key) ?: optionDefs[key]?.default.orEmpty()

    fun changeSettingsMode(mode: OptionMode) {
        settingsMode = mode
        prefs.edit().putString("settings_mode", mode.code).apply()
    }

    // --- preset selection and editing ---

    fun selectPrinter(name: String) = engineAction("Switching printer…") { e ->
        e.selectPrinter(name)
        refreshPresets()
        refreshScene()
        invalidateSlice()
    }

    fun selectFilament(name: String) = engineAction { e ->
        e.select(printer, name, process)
        refreshPresets()
        invalidateSlice()
    }

    fun selectProcess(name: String) = engineAction { e ->
        e.select(printer, filament, name)
        refreshPresets()
        invalidateSlice()
    }

    fun setValue(kind: PresetKind, key: String, value: String) = engineAction { e ->
        e.setValue(kind, key, value)
        readSettings()
        // Printable area and height change the bed.
        if (kind == PresetKind.PRINTER) { e.refreshBed(); refreshScene() }
        invalidateSlice()
    }

    fun resetValue(kind: PresetKind, key: String) = engineAction { e ->
        e.resetValue(kind, key)
        readSettings()
        if (kind == PresetKind.PRINTER) { e.refreshBed(); refreshScene() }
        invalidateSlice()
    }

    fun resetKind(kind: PresetKind) = engineAction { e ->
        e.resetEdits(kind)
        readSettings()
        if (kind == PresetKind.PRINTER) { e.refreshBed(); refreshScene() }
        invalidateSlice()
    }

    /** Saves the edited preset of [kind] as the user preset [name] and selects it. */
    fun savePreset(kind: PresetKind, name: String, detach: Boolean = false) = engineAction { e ->
        e.saveUserPreset(kind, name, detach)
        refreshPresets()
        _messages.tryEmit("Saved preset \"$name\"")
    }

    fun deleteUserPreset(kind: PresetKind, name: String) = engineAction { e ->
        e.deleteUserPreset(kind, name)
        refreshPresets()
    }

    // --- document ---

    /** Copies picked files into the cache (the engine reads by extension) and imports them. */
    fun importUris(uris: List<Uri>, asProject: Boolean = false) = engineAction("Importing…") { e ->
        var imported = 0
        for (uri in uris) {
            val file = copyToCache(uri)
            val ext = file.extension.lowercase()
            if (ext == "step" || ext == "stp") {
                _messages.tryEmit("STEP files are not supported yet; export the model as STL or 3MF")
                continue
            }
            if (ext !in IMPORTABLE) {
                _messages.tryEmit("${file.name}: not a model file (STL, 3MF, OBJ or AMF)")
                continue
            }
            if (asProject && ext == "3mf") {
                e.openProject(file.path)
                refreshPresets()
            } else {
                imported += e.import(file.path)
            }
        }
        if (uris.size > 1 && imported > 1) {
            val unfit = e.arrange()
            if (unfit > 0) _messages.tryEmit("$unfit object(s) do not fit on the plate")
        }
        refreshScene()
        invalidateSlice()
    }

    /** Saves the whole project (model, plates, settings) as an OrcaSlicer 3MF. */
    fun saveProject(uri: Uri) = engineAction("Saving…") { e ->
        val tmp = File(getApplication<Application>().cacheDir, "project.3mf")
        e.saveProject(tmp.path)
        copyFromFile(tmp, uri)
        _messages.tryEmit("Project saved")
    }

    /** Geometry-only 3MF that any slicer reads. */
    fun exportGeneric3mf(uri: Uri) = engineAction("Exporting…") { e ->
        val tmp = File(getApplication<Application>().cacheDir, "export.3mf")
        e.export3mf(tmp.path)
        copyFromFile(tmp, uri)
        _messages.tryEmit("Exported the plate as 3MF")
    }

    /** One object's mesh, or every object's when [objectId] is null, as binary STL. */
    fun exportStl(objectId: Long?, uri: Uri) = engineAction("Exporting…") { e ->
        val tmp = File(getApplication<Application>().cacheDir, "export.stl")
        e.exportStl(objectId, tmp.path)
        copyFromFile(tmp, uri)
        _messages.tryEmit("Exported STL")
    }

    fun arrange() = mutate("Arranging…") { e ->
        val unfit = e.arrange()
        if (unfit > 0) _messages.tryEmit("$unfit object(s) do not fit on the plate")
    }

    fun undo() = mutate { it.undo() }
    fun redo() = mutate { it.redo() }
    fun clearPlate() = mutate { it.clear(); selection = null }

    fun move(sel: Selection, dx: Double, dy: Double) = mutate { e ->
        val t = transformOf(sel) ?: return@mutate
        e.setTransform(sel.objectId, sel.instanceId, t.copy(offsetX = t.offsetX + dx, offsetY = t.offsetY + dy), "Move")
    }

    fun setTransform(sel: Selection, t: InstanceTransform, label: String) = mutate { e ->
        e.setTransform(sel.objectId, sel.instanceId, t, label)
    }

    fun rotate(sel: Selection, axis: Int, degrees: Double) = mutate { e ->
        e.rotate(sel.objectId, sel.instanceId, axis, Math.toRadians(degrees))
    }

    fun layFlat(sel: Selection, nx: Double, ny: Double, nz: Double) = mutate { e ->
        e.layFlat(sel.objectId, sel.instanceId, nx, ny, nz)
    }

    fun orient(sel: Selection) = mutate("Orienting…") { e -> e.orient(sel.objectId, sel.instanceId) }
    fun scaleToFit(sel: Selection) = mutate { e -> e.scaleToFit(sel.objectId, sel.instanceId) }
    fun duplicate(sel: Selection) = mutate { e ->
        e.addInstance(sel.objectId)
        e.arrange()
    }

    fun delete(sel: Selection) = mutate { e ->
        val obj = scene.snapshot.objects.firstOrNull { it.id == sel.objectId } ?: return@mutate
        e.deleteInstance(sel.objectId, sel.instanceId, lastInstance = obj.instances.size <= 1)
        selection = null
    }

    fun transformOf(sel: Selection): InstanceTransform? =
        scene.snapshot.objects.firstOrNull { it.id == sel.objectId }
            ?.instances?.firstOrNull { it.id == sel.instanceId }?.transform

    private fun mutate(label: String? = null, block: suspend (Engine) -> Unit) = engineAction(label) { e ->
        block(e)
        refreshScene()
        invalidateSlice()
    }

    private suspend fun refreshScene() {
        val e = engine ?: return
        scene = e.scene()
        customGcodes = e.customGcodes()
        refreshCalibration(e)
        if (paint != null) refreshPaint()
        if (measure?.let { objectOf(it.objectId) } == null) measure = null
        layerHeights?.let { l ->
            layerHeights = if (objectOf(l.objectId) == null) null else l.copy(profile = e.heightProfile(l.objectId))
        }
        val sel = selection
        if (sel != null && transformOf(sel) == null) selection = null
        val vol = selectedVolume
        if (vol != null && scene.snapshot.objects.none { o -> o.volumes.any { it.id == vol } }) selectedVolume = null
    }

    // --- object list: objects, parts and modifiers ---

    fun objectOf(id: Long): SceneObject? = scene.snapshot.objects.firstOrNull { it.id == id }

    fun renameObject(objectId: Long, name: String) = mutate { it.renameObject(objectId, name) }

    fun addShape(shape: Shape) = mutate { e ->
        val id = e.addShapeObject(shape)
        refreshScene()
        objectOf(id)?.instances?.firstOrNull()?.let { selection = Selection(id, it.id) }
    }

    /** Desktop "Add part / modifier / negative part / support blocker or enforcer" with a built-in shape. */
    fun addVolume(sel: Selection, type: VolumeType, shape: Shape) = mutate { e ->
        selectedVolume = e.addVolumeShape(sel.objectId, sel.instanceId, type, shape)
    }

    /** The same, loading the volume's mesh from a model file. */
    fun addVolumeFromFile(objectId: Long, type: VolumeType, uri: Uri) = mutate("Loading…") { e ->
        val file = copyToCache(uri)
        if (file.extension.lowercase() !in IMPORTABLE) {
            _messages.tryEmit("${file.name}: not a model file (STL, 3MF, OBJ or AMF)")
            return@mutate
        }
        selectedVolume = e.addVolumeFile(objectId, type, file.path)
    }

    fun setVolumeType(objectId: Long, volumeId: Long, type: VolumeType) = mutate { it.setVolumeType(objectId, volumeId, type) }
    fun renameVolume(objectId: Long, volumeId: Long, name: String) = mutate { it.renameVolume(objectId, volumeId, name) }
    fun deleteVolume(objectId: Long, volumeId: Long) = mutate { e ->
        e.deleteVolume(objectId, volumeId)
        if (selectedVolume == volumeId) selectedVolume = null
    }

    // --- painting (desktop painter gizmos) ---

    /** The painter in use, if any. */
    var paint by mutableStateOf<PaintState?>(null)
    /** The painted facets of the painter's layer, ready for the viewport. */
    var paintOverlay by mutableStateOf<FloatArray?>(null)
        private set

    fun startPaint(objectId: Long) {
        measure = null
        cut = null
        layerHeights = null
        paint = PaintState(objectId)
        engineAction { refreshPaint() }
    }

    fun choosePaintLayer(layer: PaintLayer) {
        val p = paint ?: return
        paint = p.copy(layer = layer, state = 1)
        engineAction { refreshPaint() }
    }

    /** A dab or fill from the viewport; brush strokes send one per finger step, fills only the first touch. */
    fun paintAt(objectId: Long, volumeId: Long, hit: FloatArray, view: FloatArray, first: Boolean) {
        val p = paint ?: return
        if (p.objectId != objectId || (p.tool != PaintTool.BRUSH && !first)) return
        engineAction { e ->
            when (p.tool) {
                PaintTool.BRUSH -> e.paintSphere(objectId, volumeId, p.layer, hit, view, p.radius.toDouble(), p.state)
                PaintTool.SMART_FILL -> e.paintFill(objectId, volumeId, p.layer, hit, view, p.angle.toDouble(), p.state)
                PaintTool.BUCKET -> e.paintFill(objectId, volumeId, p.layer, hit, view, 0.0, p.state)
            }
            refreshScene()
            invalidateSlice()
        }
    }

    fun clearPaint() {
        val p = paint ?: return
        val obj = objectOf(p.objectId) ?: return
        mutate { e -> for (part in obj.parts) e.paintReset(p.objectId, part.id, p.layer) }
    }

    private suspend fun refreshPaint() {
        val e = engine ?: return
        val p = paint
        val obj = p?.let { objectOf(it.objectId) }
        if (p == null || obj == null) {
            paintOverlay = null
            if (p != null) paint = null
            return
        }
        val meshes = obj.parts.map { e.paint(p.objectId, it.id, p.layer) }
        paintOverlay = paintOverlay(meshes, p.layer, filamentSlots.map { it.colour })
    }

    // --- cut (desktop cut gizmo) ---

    /** The cut being set up, shown as a plane in the viewport; null when not cutting. */
    var cut by mutableStateOf<CutState?>(null)

    fun startCut(sel: Selection) {
        measure = null
        paint = null
        layerHeights = null
        val obj = objectOf(sel.objectId) ?: return
        val inst = obj.instances.firstOrNull { it.id == sel.instanceId } ?: return
        val mesh = scene.meshes[obj.id] ?: return
        val b = worldBounds(mesh.positions, inst.matrix)
        val t = inst.transform
        cut = CutState(sel, b, doubleArrayOf(t.offsetX, t.offsetY, t.offsetZ), height = (b[2] + b[5]) / 2)
    }

    /** A tap while placing connectors: adds one where the ray meets the plane. */
    fun placeConnector(origin: FloatArray, direction: FloatArray) {
        val c = cut ?: return
        val uv = c.hit(origin, direction) ?: return
        if (kotlin.math.abs(uv.first) > c.extent || kotlin.math.abs(uv.second) > c.extent) return
        cut = c.copy(connectors = c.connectors + uv)
    }

    fun performCut() {
        val c = cut ?: return
        mutate("Cutting…") { e ->
            val n = e.cut(c.selection.objectId, c.selection.instanceId, c.plane, c.keep, c.engineConnectors())
            cut = null
            selection = null
            _messages.tryEmit(if (n == 1) "Cut done" else "Cut into $n objects")
        }
    }

    // --- calibration (desktop Calibration menu) ---

    /** The test the plate is set up for, with its range; null for an ordinary plate. */
    var calibration by mutableStateOf<Pair<CalibrationTest, DoubleArray>?>(null)
        private set

    /**
     * Desktop Plater::calib_*: the test's model replaces the plate's objects,
     * and the print, filament and printer settings change as desktop changes them.
     */
    fun startCalibration(test: CalibrationTest, start: Double, end: Double, step: Double, printNumbers: Boolean, resize: Boolean) =
        engineAction("Setting up ${test.label.lowercase()}…") { e ->
            val model = withContext(Dispatchers.IO) {
                val file = File(File(getApplication<Application>().cacheDir, "calib").apply { mkdirs() }, test.model)
                getApplication<Application>().assets.open("calib/${test.model}").use { input ->
                    file.outputStream().use { input.copyTo(it) }
                }
                file
            }
            cut = null
            paint = null
            layerHeights = null
            measure = null
            selection = null
            e.startCalibration(test, start, end, step, printNumbers, resize, model.path)
            readSettings()
            refreshScene()
            invalidateSlice()
            calibrationTest = test
            _messages.tryEmit("${test.label} is on the plate. Slice it to print the test.")
        }

    fun clearCalibration() = engineAction { e ->
        e.clearCalibration()
        refreshScene()
        invalidateSlice()
    }

    /** The menu entry last started, to tell flow rate passes apart (the engine keeps only the mode). */
    private var calibrationTest: CalibrationTest? = null

    private suspend fun refreshCalibration(e: Engine) {
        val c = e.calibration()
        val mode = c[0].toInt()
        calibration = if (mode == 0) null else {
            val test = calibrationTest?.takeIf { it.mode == mode } ?: CalibrationTest.entries.first { it.mode == mode }
            test to c.copyOfRange(1, 4)
        }
    }

    // --- measure (desktop measure gizmo) ---

    /** The measurement being taken, if any. */
    var measure by mutableStateOf<MeasureState?>(null)

    fun startMeasure(objectId: Long) {
        cut = null
        paint = null
        layerHeights = null
        measure = MeasureState(objectId)
    }

    /** A tap on the measured object: picks the feature there and measures once two are picked. */
    fun measureAt(sel: Selection, triangle: Int, local: FloatArray) {
        val m = measure ?: return
        val obj = objectOf(sel.objectId) ?: return
        val inst = obj.instances.firstOrNull { it.id == sel.instanceId } ?: return
        val revision = scene.meshes[obj.id]?.revision ?: return
        engineAction { e ->
            val f = e.measurePick(obj.id, revision, triangle, doubleArrayOf(local[0].toDouble(), local[1].toDouble(), local[2].toDouble()))
                ?: return@engineAction
            val next = m.pick(f.transformed(inst.matrix))
            measure = if (next.first != null && next.second != null) next.copy(measurement = e.measureBetween(next.first, next.second)) else next
        }
    }

    fun endMeasure() {
        measure = null
        engineAction { it.endMeasure() }
    }

    // --- variable layer height (desktop layers editing) ---

    /** The object whose layer heights are being edited, with its profile; null when not editing. */
    var layerHeights by mutableStateOf<LayerHeightState?>(null)

    fun startLayerHeights(sel: Selection) {
        measure = null
        cut = null
        paint = null
        val obj = objectOf(sel.objectId) ?: return
        val inst = obj.instances.firstOrNull { it.id == sel.instanceId } ?: return
        val mesh = scene.meshes[obj.id] ?: return
        val b = worldBounds(mesh.positions, inst.matrix)
        val layer = valueOf(PresetKind.PROCESS, "layer_height").toDoubleOrNull() ?: 0.2
        val state = LayerHeightState(sel.objectId, height = (b[5] - b[2]).toDouble(), layerHeight = layer)
        layerHeights = state
        engineAction { e -> layerHeights = state.copy(profile = e.heightProfile(sel.objectId), z = state.height / 2) }
    }

    private fun editHeights(block: suspend (Engine, LayerHeightState) -> Unit) {
        val l = layerHeights ?: return
        engineAction { e ->
            block(e, l)
            refreshScene()
            invalidateSlice()
        }
    }

    fun adaptiveLayerHeights() = editHeights { e, l -> e.adaptiveHeightProfile(l.objectId, l.quality.toDouble()) }
    fun smoothLayerHeights() = editHeights { e, l -> e.smoothHeightProfile(l.objectId, l.radius, l.keepMin) }
    fun resetLayerHeights() = editHeights { e, l -> e.resetHeightProfile(l.objectId) }
    fun adjustLayerHeights(action: Int) = editHeights { e, l ->
        e.adjustHeightProfile(l.objectId, l.z, LayerHeightState.STRENGTH, l.band.toDouble(), action)
    }

    fun splitToObjects(objectId: Long) = mutate("Splitting…") { e ->
        val n = e.splitToObjects(objectId)
        selection = null
        _messages.tryEmit("Split into $n objects")
    }

    fun splitToParts(objectId: Long, volumeId: Long) = mutate("Splitting…") { e ->
        val n = e.splitToParts(objectId, volumeId)
        _messages.tryEmit("Split into $n parts")
    }

    fun mirror(sel: Selection, axis: Int) = mutate { it.mirror(sel.objectId, sel.instanceId, axis) }
    fun setPrintable(objectId: Long, instanceId: Long, printable: Boolean) = mutate { it.setPrintable(objectId, instanceId, printable) }
    fun setInstanceCount(objectId: Long, count: Int) = mutate { it.setInstanceCount(objectId, count) }
    fun cloneObject(objectId: Long, count: Int) = mutate { e ->
        e.cloneObject(objectId, count)
        val unfit = e.arrange()
        if (unfit > 0) _messages.tryEmit("$unfit object(s) do not fit on the plate")
    }
    fun instanceToObject(sel: Selection) = mutate { it.instanceToObject(sel.objectId, sel.instanceId); selection = null }
    fun mergeObjects(objectIds: List<Long>) = mutate { it.mergeObjects(objectIds); selection = null }
    fun convertUnits(objectId: Long, conversion: Int) = mutate { it.convertUnits(objectId, conversion); selection = null }
    fun drop(objectId: Long) = mutate { it.drop(objectId) }
    fun fillBed(objectId: Long) = mutate("Filling the plate…") { e ->
        val n = e.fillBed(objectId)
        _messages.tryEmit(if (n == 1) "Added 1 copy" else "Added $n copies")
    }

    fun simplify(objectId: Long, volumeId: Long, targetTriangles: Int) = mutate("Simplifying…") { e ->
        val n = e.simplify(objectId, volumeId, targetTriangles)
        _messages.tryEmit("Simplified to $n triangles")
    }

    fun subdivide(objectId: Long, volumeId: Long) = mutate("Subdividing…") { e ->
        val n = e.subdivide(objectId, volumeId)
        _messages.tryEmit("Subdivided to $n triangles")
    }

    /** Assigns a filament slot (1-based) to an object, or to one of its parts when [volumeId] is not 0. */
    fun setFilament(objectId: Long, volumeId: Long, slot: Int) = mutate { e ->
        e.setExtruder(objectId, volumeId, slot)
    }

    // --- per-object / per-part settings ---

    /** The target's overrides as {key: serialized value}. */
    suspend fun overrides(target: OverrideTarget): Map<String, String> {
        val e = engine ?: return emptyMap()
        return try {
            parseValues(e.overrides(target.objectId, target.volumeId)).filterKeys { it != "extruder" }
        } catch (ex: OrcaException) {
            emptyMap()
        }
    }

    /** The value slicing would use for [key] without an override on the target. */
    fun inheritedValue(key: String): String {
        for (kind in listOf(PresetKind.PROCESS, PresetKind.FILAMENT, PresetKind.PRINTER))
            presetValues[kind]?.get(key)?.let { return it }
        return optionDefs[key]?.default.orEmpty()
    }

    fun setOverride(target: OverrideTarget, key: String, value: String) = mutate { e ->
        e.setOverride(target.objectId, target.volumeId, key, value)
    }

    fun eraseOverride(target: OverrideTarget, key: String) = mutate { e ->
        e.eraseOverride(target.objectId, target.volumeId, key)
    }

    /** Desktop "Copy settings": the target's overrides, pasted onto another with [pasteOverrides]. */
    var copiedOverrides by mutableStateOf<Map<String, String>?>(null)
        private set

    fun copyOverrides(target: OverrideTarget) = viewModelScope.launch {
        copiedOverrides = overrides(target)
        _messages.tryEmit("Copied ${copiedOverrides?.size ?: 0} setting(s)")
    }

    fun pasteOverrides(target: OverrideTarget) = mutate { e ->
        val values = copiedOverrides ?: return@mutate
        val allowed = overrideKeys[if (target.volumeId == 0L) 0 else 1]
        e.replaceOverrides(target.objectId, target.volumeId, values.filterKeys { it in allowed })
    }

    // --- height range modifiers ---

    suspend fun heightRanges(objectId: Long): List<HeightRange> {
        val e = engine ?: return emptyList()
        return try { e.heightRanges(objectId) } catch (ex: OrcaException) { emptyList() }
    }

    fun setHeightRanges(objectId: Long, ranges: List<HeightRange>) = mutate { e ->
        e.setHeightRanges(objectId, ranges.sortedBy { it.minZ })
    }

    // --- plates ---

    fun addPlate() = mutate { it.addPlate() }
    fun deletePlate(plateId: Long) = mutate { it.deletePlate(plateId) }
    fun renamePlate(plateId: Long, name: String) = mutate { it.renamePlate(plateId, name) }
    fun lockPlate(plateId: Long, lock: Boolean) = mutate { it.lockPlate(plateId, lock) }
    fun duplicatePlate(plateId: Long) = mutate { it.duplicatePlate(plateId) }
    fun movePlateToFront(plateId: Long) = mutate { it.movePlateToFront(plateId) }
    fun selectPlate(plateId: Long) = mutate { it.selectPlate(plateId) }

    suspend fun plateSettings(plateId: Long): PlateSettings =
        engine?.let { e -> try { e.plateSettings(plateId) } catch (ex: OrcaException) { null } } ?: PlateSettings()

    fun setPlateSettings(plateId: Long, settings: PlateSettings) = mutate { it.setPlateSettings(plateId, settings) }

    // --- filament slots and plate type ---

    /** Desktop "+"/"−" beside the filament list: new slots copy the last filament. */
    fun setFilamentCount(count: Int) = engineAction { e ->
        e.setFilamentCount(count.coerceIn(1, 16), if (count > filamentSlots.size) NEW_SLOT_COLOURS[(count - 1) % NEW_SLOT_COLOURS.size] else null)
        refreshPresets()
        refreshScene()
        invalidateSlice()
    }

    fun selectFilamentSlot(slot: Int, name: String) = engineAction { e ->
        e.selectFilamentSlot(slot, name)
        refreshPresets()
        invalidateSlice()
    }

    fun setFilamentColour(slot: Int, colour: String) = engineAction { e ->
        e.setFilamentColour(slot, colour)
        readSettings()
        invalidateSlice()
    }

    fun setBedType(type: Int) = engineAction { e ->
        e.setBedType(type)
        readSettings()
        invalidateSlice()
    }

    // --- preview ---

    fun choosePreviewMode(mode: ViewMode) {
        previewMode = mode
        prefs.edit().putString("preview_mode", mode.name).apply()
    }

    fun togglePreviewOption(option: PreviewOption) {
        previewOptions = if (option in previewOptions) previewOptions - option else previewOptions + option
        prefs.edit().putStringSet("preview_options", previewOptions.map { it.name }.toSet()).apply()
    }

    fun toggleRole(role: Int) {
        hiddenRoles = if (role in hiddenRoles) hiddenRoles - role else hiddenRoles + role
    }

    /**
     * Desktop layer-slider "+": adds a pause, filament change or custom G-code
     * before the layer at [printZ]. The shown slice stays until the next one,
     * which applies it.
     */
    fun addCustomGcode(printZ: Double, type: CustomGcodeType, extruder: Int = 0, colour: String = "", extra: String = "") =
        engineAction { e ->
            e.addCustomGcode(CustomGcode(printZ, type, extruder, colour, extra))
            refreshScene()
            sliceStale = true
        }

    fun deleteCustomGcode(printZ: Double) = engineAction { e ->
        e.deleteCustomGcode(printZ)
        refreshScene()
        sliceStale = true
    }

    // --- slicing ---

    /** Slices the selected plate, or every plate into one G-code file with [allPlates]. */
    fun startSlice(allPlates: Boolean = false) {
        val e = engine ?: return
        if (slice is SliceState.Running) return
        if (scene.snapshot.objects.isEmpty()) {
            _messages.tryEmit("Add a model to the plate first")
            return
        }
        sliceJob = viewModelScope.launch {
            val dir = File(getApplication<Application>().cacheDir, "gcode").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val out = File(dir, gcodeName())
            slice = SliceState.Running(0, "Preparing")
            try {
                val target = if (allPlates) SliceTarget.ALL_PLATES else SliceTarget.PLATE
                val report = e.slice(out.path, target, listener = progress())
                // One file of several plates overlaps on the bed, so only a single plate is previewed.
                val toolpath = if (allPlates) null else loadPreview(e, out)
                slice = SliceState.Done(report, out, toolpath, if (allPlates) -1 else scene.snapshot.currentPlate)
                previewRange = PreviewRange(0, (toolpath?.layerTotal ?: 1) - 1)
                sliceStale = false
                showPreview = true
            } catch (ex: OrcaException) {
                slice = SliceState.Idle
                _messages.tryEmit(if (ex.code == OrcaException.CANCELLED) "Slicing cancelled" else "Slicing failed: ${ex.message}")
            }
        }
    }

    /** Desktop "Export plate sliced file" / "Export all sliced file": G-code inside a 3MF, for Bambu-style printers. */
    fun exportSliced3mf(uri: Uri, allPlates: Boolean) {
        val e = engine ?: return
        if (slice is SliceState.Running) return
        sliceJob = viewModelScope.launch {
            val tmp = File(getApplication<Application>().cacheDir, "sliced.3mf")
            slice = SliceState.Running(0, "Preparing")
            try {
                val target = if (allPlates) SliceTarget.SLICED_3MF else SliceTarget.SLICED_3MF_PLATE
                e.slice(tmp.path, target, scene.snapshot.currentPlate, progress())
                copyFromFile(tmp, uri)
                _messages.tryEmit("Sliced 3MF saved")
            } catch (ex: OrcaException) {
                _messages.tryEmit(if (ex.code == OrcaException.CANCELLED) "Slicing cancelled" else "Slicing failed: ${ex.message}")
            } catch (ex: java.io.IOException) {
                _messages.tryEmit(ex.message ?: "File error")
            } finally {
                if (slice is SliceState.Running) slice = SliceState.Idle
            }
        }
    }

    private fun progress() = org.orcapod.app.engine.ProgressListener { percent, message ->
        viewModelScope.launch { if (slice is SliceState.Running) slice = SliceState.Running(percent, message) }
    }

    private suspend fun loadPreview(e: Engine, gcode: File): Toolpath? {
        slice = SliceState.Running(100, "Loading preview")
        return try {
            e.loadToolpath(gcode.path)
        } catch (ex: OrcaException) {
            Log.w(TAG, "preview failed", ex)
            null
        } catch (ex: OutOfMemoryError) {
            Log.w(TAG, "preview too large", ex)
            null
        }
    }

    fun cancelSlice() {
        engine?.cancelSlice()
    }

    private fun invalidateSlice() {
        if (slice is SliceState.Done) {
            slice = SliceState.Idle
            showPreview = false
        }
    }

    private fun gcodeName(): String {
        val first = scene.snapshot.objects.firstOrNull()?.name?.substringBeforeLast('.') ?: "plate"
        val safe = first.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(60).ifEmpty { "plate" }
        return "${safe}_${process.substringBefore(' ').replace(Regex("[^A-Za-z0-9.]+"), "")}.gcode"
    }

    fun saveGcode(uri: Uri) = viewModelScope.launch {
        val done = slice as? SliceState.Done ?: return@launch
        try {
            copyFromFile(done.gcode, uri)
            _messages.tryEmit("G-code saved")
        } catch (e: Exception) {
            _messages.tryEmit("Could not save: ${e.message}")
        }
    }

    // --- helpers ---

    private fun engineAction(label: String? = null, block: suspend (Engine) -> Unit) = viewModelScope.launch {
        val e = engine ?: return@launch
        if (label != null) busy = label
        try {
            block(e)
        } catch (ex: OrcaException) {
            Log.w(TAG, "engine call failed", ex)
            _messages.tryEmit(ex.message ?: "Engine error")
        } catch (ex: java.io.IOException) {
            _messages.tryEmit(ex.message ?: "File error")
        } finally {
            if (label != null) busy = null
        }
    }

    private suspend fun copyToCache(uri: Uri): File = withContext(Dispatchers.IO) {
        val ctx = getApplication<Application>()
        var name = "model.stl"
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { name = it }
        }
        val dir = File(ctx.cacheDir, "imports").apply { mkdirs() }
        val file = File(dir, name.replace('/', '_'))
        ctx.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
            ?: throw java.io.IOException("Cannot read $name")
        file
    }

    private suspend fun copyFromFile(file: File, uri: Uri) = withContext(Dispatchers.IO) {
        val ctx = getApplication<Application>()
        ctx.contentResolver.openOutputStream(uri, "wt")?.use { out -> file.inputStream().use { it.copyTo(out) } }
            ?: throw java.io.IOException("Cannot write the file")
    }

    override fun onCleared() {
        sliceJob?.cancel()
        engine?.cancelSlice()
        engine?.close()
        engine = null
    }

    companion object {
        private const val TAG = "OrcaPod"
        val IMPORTABLE = setOf("stl", "3mf", "obj", "amf")

        /** Colours for new filament slots, like desktop's default palette. */
        val NEW_SLOT_COLOURS = listOf("#00AE42", "#F4EE2A", "#2850E0", "#EC008C", "#FF6A13", "#FFFFFF", "#161616", "#A3D8E1")
    }
}

/** World bounding box (min xyz, max xyz) of object-space [positions] under a column-major [matrix]. */
internal fun worldBounds(positions: FloatArray, matrix: FloatArray): FloatArray {
    val b = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
    for (i in positions.indices step 3) {
        val x = positions[i]; val y = positions[i + 1]; val z = positions[i + 2]
        for (k in 0 until 3) {
            val w = matrix[k] * x + matrix[4 + k] * y + matrix[8 + k] * z + matrix[12 + k]
            if (w < b[k]) b[k] = w
            if (w > b[k + 3]) b[k + 3] = w
        }
    }
    return b
}
