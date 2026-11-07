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
import org.json.JSONObject
import org.orcapod.app.data.PresetChoice
import org.orcapod.app.data.ProfileStore
import org.orcapod.app.data.VendorNames
import org.orcapod.app.engine.Engine
import org.orcapod.app.engine.InstanceTransform
import org.orcapod.app.engine.OrcaException
import org.orcapod.app.engine.PresetEntry
import org.orcapod.app.engine.PresetKind
import org.orcapod.app.engine.Scene
import org.orcapod.app.engine.SliceReport
import org.orcapod.app.engine.Toolpath
import org.orcapod.app.ui.viewport.Selection
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
    data class Done(val report: SliceReport, val gcode: File, val toolpath: Toolpath?) : SliceState
}

/** An open model library, optionally starting at a web page. */
data class LibraryRequest(val url: String? = null)

/** A process or filament setting exposed for quick editing. */
data class QuickSetting(
    val kind: PresetKind,
    val key: String,
    val label: String,
    val unit: String = "",
    /** Allowed serialized values with labels; null for free numeric input. */
    val choices: List<Pair<String, String>>? = null,
    val isBool: Boolean = false,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ProfileStore(app)
    private val prefs = app.getSharedPreferences("session", Context.MODE_PRIVATE)
    private val choicePrefs = app.getSharedPreferences("preset_choices", Context.MODE_PRIVATE)
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
    /** The model library is open; a URL opens it on that page (a link shared to the app). */
    var library by mutableStateOf<LibraryRequest?>(null)
        private set
    var previewLayer by mutableStateOf(0)

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
    /** Current values of [quickSettings], keyed by config key. */
    var settings by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    /** Keys edited away from the selected presets in this session. */
    var editedKeys by mutableStateOf<Set<String>>(emptySet())
        private set
    var installedVendors by mutableStateOf<Set<String>>(emptySet())
        private set

    /** Printers and filaments the user picked to show in the pickers, by kind. */
    var choices by mutableStateOf(loadChoices())
        private set

    /** The installed brand whose profiles are loaded; the others load when picked. */
    var activeVendor by mutableStateOf<String?>(null)
        private set

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    val quickSettings = listOf(
        QuickSetting(PresetKind.PROCESS, "layer_height", "Layer height", "mm"),
        QuickSetting(PresetKind.PROCESS, "wall_loops", "Wall loops"),
        QuickSetting(PresetKind.PROCESS, "top_shell_layers", "Top shell layers"),
        QuickSetting(PresetKind.PROCESS, "bottom_shell_layers", "Bottom shell layers"),
        QuickSetting(PresetKind.PROCESS, "sparse_infill_density", "Infill density", "%"),
        QuickSetting(
            PresetKind.PROCESS, "sparse_infill_pattern", "Infill pattern",
            choices = listOf(
                "grid" to "Grid", "gyroid" to "Gyroid", "cubic" to "Cubic", "rectilinear" to "Rectilinear",
                "line" to "Line", "triangles" to "Triangles", "honeycomb" to "Honeycomb",
                "adaptivecubic" to "Adaptive cubic", "lightning" to "Lightning", "crosshatch" to "Cross hatch",
            ),
        ),
        QuickSetting(PresetKind.PROCESS, "enable_support", "Supports", isBool = true),
        QuickSetting(
            PresetKind.PROCESS, "support_type", "Support type",
            choices = listOf(
                "normal(auto)" to "Normal (auto)", "tree(auto)" to "Tree (auto)",
                "normal(manual)" to "Normal (manual)", "tree(manual)" to "Tree (manual)",
            ),
        ),
        QuickSetting(
            PresetKind.PROCESS, "brim_type", "Brim",
            choices = listOf(
                "auto_brim" to "Auto", "no_brim" to "None", "outer_only" to "Outer only",
                "inner_only" to "Inner only", "outer_and_inner" to "Outer and inner", "brim_ears" to "Mouse ears",
            ),
        ),
        QuickSetting(PresetKind.FILAMENT, "nozzle_temperature", "Nozzle temperature", "°C"),
    )

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
            activateBrand(store.activeVendor)
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
            activateBrand(store.activeVendor)
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

    /** Switches the loaded profiles to [vendor]'s, restoring the presets last used with it. */
    fun switchBrand(vendor: String) {
        if (vendor == activeVendor) return
        engineAction("Loading ${VendorNames.display(vendor)} profiles…") { e ->
            val previous = activeVendor
            try {
                activateBrand(vendor)
                e.reloadPresets(store.dataDir.path)
            } catch (ex: Exception) {
                activateBrand(previous) // the engine still holds the previous brand's presets
                throw ex
            }
            editedKeys = emptySet()
            restoreSelection(e)
            refreshScene()
            invalidateSlice()
        }
    }

    private suspend fun activateBrand(vendor: String?) {
        if (vendor != null) store.activate(vendor)
        activeVendor = vendor
    }

    private fun sessionKey(key: String) = activeVendor?.let { "$key/$it" } ?: key

    private suspend fun openEngine() {
        phase = Phase.Loading("Loading printer profiles…")
        val e = Engine.open(store.dataDir.path)
        engine = e
        restoreSelection(e)
        refreshScene()
        phase = Phase.Ready
    }

    /** Re-selects the last session's presets, or the first printer's defaults. */
    private suspend fun restoreSelection(e: Engine) {
        val machines = e.list(PresetKind.PRINTER)
        // Selections are kept per brand; the unscoped keys hold the one from before brands loaded on their own.
        val key: (String) -> String = if (prefs.contains(sessionKey("printer"))) ::sessionKey else { k: String -> k }
        val saved = prefs.getString(key("printer"), null)
        if (saved != null && machines.any { it.name == saved }) {
            try {
                e.select(saved, prefs.getString(key("filament"), "") ?: "", prefs.getString(key("process"), "") ?: "")
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
        prefs.edit()
            .putString(sessionKey("printer"), printer)
            .putString(sessionKey("filament"), filament)
            .putString(sessionKey("process"), process)
            .apply()
        readSettings()
    }

    private suspend fun readSettings() {
        val e = engine ?: return
        val json = withContext(Dispatchers.Default) { JSONObject(e.resolvedConfig()) }
        settings = quickSettings.associate { s ->
            // Filament options are per-extruder lists; show the first slot.
            s.key to json.optString(s.key).substringBefore(',')
        }
    }

    // --- preset selection and editing ---

    fun selectPrinter(name: String) = engineAction("Switching printer…") { e ->
        e.selectPrinter(name)
        editedKeys = emptySet()
        refreshPresets()
        invalidateSlice()
    }

    fun selectFilament(name: String) = engineAction { e ->
        e.select(printer, name, process)
        editedKeys = editedKeys.filterTo(HashSet()) { k -> quickSettings.first { it.key == k }.kind != PresetKind.FILAMENT }
        refreshPresets()
        invalidateSlice()
    }

    fun selectProcess(name: String) = engineAction { e ->
        e.select(printer, filament, name)
        editedKeys = editedKeys.filterTo(HashSet()) { k -> quickSettings.first { it.key == k }.kind != PresetKind.PROCESS }
        refreshPresets()
        invalidateSlice()
    }

    fun setSetting(setting: QuickSetting, value: String) = engineAction { e ->
        e.setValue(setting.kind, setting.key, value)
        editedKeys = editedKeys + setting.key
        readSettings()
        invalidateSlice()
    }

    fun resetSettings() = engineAction { e ->
        e.resetEdits(PresetKind.PROCESS)
        e.resetEdits(PresetKind.FILAMENT)
        editedKeys = emptySet()
        readSettings()
        invalidateSlice()
    }

    /** Saves the edited process (and filament, when edited) as user presets named [name]. */
    fun saveEdits(name: String) = engineAction { e ->
        val kinds = editedKeys.map { k -> quickSettings.first { it.key == k }.kind }.toSet()
        for (kind in kinds) e.saveUserPreset(kind, name)
        editedKeys = emptySet()
        refreshPresets()
        _messages.tryEmit("Saved preset \"$name\"")
    }

    fun deleteUserPreset(kind: PresetKind, name: String) = engineAction { e ->
        e.deleteUserPreset(kind, name)
        refreshPresets()
    }

    /** Shows only [picked] of [entries] in the [kind] picker; nothing picked shows them all. */
    fun choosePresets(kind: PresetKind, entries: List<PresetEntry>, picked: Set<String>) {
        val choice = (choices[kind] ?: PresetChoice()).pick(entries, picked)
        choices = choices + (kind to choice)
        choicePrefs.edit()
            .putStringSet("shown/${kind.name}", choice.shown)
            .putStringSet("seen/${kind.name}", choice.seen)
            .apply()
    }

    private fun loadChoices(): Map<PresetKind, PresetChoice> = CHOOSABLE.associateWith { kind ->
        PresetChoice(
            shown = choicePrefs.getStringSet("shown/${kind.name}", null).orEmpty().toSet(),
            seen = choicePrefs.getStringSet("seen/${kind.name}", null).orEmpty().toSet(),
        )
    }

    // --- document ---

    fun openLibrary(url: String? = null) { library = LibraryRequest(url) }
    fun closeLibrary() { library = null }

    /** Copies picked files into the cache (the engine reads by extension) and imports them. */
    fun importUris(uris: List<Uri>, asProject: Boolean = false) = engineAction("Importing…") { e ->
        importFiles(e, uris.map { copyToCache(it) }, asProject)
    }

    /** Imports model files already on disk, such as downloads from the model library. */
    fun importFiles(files: List<File>) = engineAction("Importing…") { e -> importFiles(e, files, asProject = false) }

    private suspend fun importFiles(e: Engine, files: List<File>, asProject: Boolean) {
        var imported = 0
        for (file in files) {
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
        if (files.size > 1 && imported > 1) {
            val unfit = e.arrange()
            if (unfit > 0) _messages.tryEmit("$unfit object(s) do not fit on the plate")
        }
        refreshScene()
        invalidateSlice()
    }

    fun exportProject(uri: Uri) = engineAction("Exporting…") { e ->
        val tmp = File(getApplication<Application>().cacheDir, "export.3mf")
        e.export3mf(tmp.path)
        copyFromFile(tmp, uri)
        _messages.tryEmit("Exported the plate as 3MF")
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
        val sel = selection
        if (sel != null && transformOf(sel) == null) selection = null
    }

    // --- slicing ---

    fun startSlice() {
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
                val report = e.slice(out.path) { percent, message ->
                    viewModelScope.launch { if (slice is SliceState.Running) slice = SliceState.Running(percent, message) }
                }
                slice = SliceState.Running(100, "Loading preview")
                val toolpath = try {
                    e.loadToolpath(out.path)
                } catch (ex: OrcaException) {
                    Log.w(TAG, "preview failed", ex)
                    null
                } catch (ex: OutOfMemoryError) {
                    Log.w(TAG, "preview too large", ex)
                    null
                }
                slice = SliceState.Done(report, out, toolpath)
                previewLayer = (toolpath?.layerTotal ?: 1) - 1
                showPreview = true
            } catch (ex: OrcaException) {
                slice = SliceState.Idle
                _messages.tryEmit(if (ex.code == OrcaException.CANCELLED) "Slicing cancelled" else "Slicing failed: ${ex.message}")
            }
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

        /** Preset kinds whose picker lists only the presets the user picked. */
        val CHOOSABLE = listOf(PresetKind.PRINTER, PresetKind.FILAMENT)
    }
}
