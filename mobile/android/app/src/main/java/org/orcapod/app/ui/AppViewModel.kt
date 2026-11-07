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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.orcapod.app.data.ProfileStore
import org.orcapod.app.engine.Engine
import org.orcapod.app.engine.InstanceTransform
import org.orcapod.app.engine.OrcaException
import org.orcapod.app.engine.PresetEntry
import org.orcapod.app.engine.PresetKind
import org.orcapod.app.engine.Scene
import org.orcapod.app.engine.SliceReport
import org.orcapod.app.engine.StepFile
import org.orcapod.app.engine.StepImportOptions
import org.orcapod.app.engine.Toolpath
import org.orcapod.app.ui.viewport.Selection
import java.io.File

sealed interface Phase {
    data class Loading(val message: String) : Phase
    data class VendorSetup(val vendors: List<String>, val firstRun: Boolean) : Phase
    data object Ready : Phase
    data class Failed(val message: String) : Phase
}

/** The STEP import dialog's question, open while [AppViewModel.stepImport] holds it. */
class StepImportRequest(
    val fileName: String,
    val step: StepFile,
    val initial: StepImportOptions,
    internal val reply: CompletableDeferred<StepImportAnswer?>,
)

/** The dialog's answer: the options, and whether to keep them as defaults and stop asking. */
data class StepImportAnswer(val options: StepImportOptions, val remember: Boolean, val dontAsk: Boolean)

/** Desktop "Unsupported characters in names", open while [AppViewModel.nameCleanup] holds it. */
class NameCleanupRequest(
    val fileName: String,
    val step: StepFile,
    val initialRemove: Boolean,
    val initialReplacement: String,
    internal val reply: CompletableDeferred<NameCleanupAnswer?>,
)

/** Remove the characters, or replace each with [replacement]; and whether to keep the rule. */
data class NameCleanupAnswer(val remove: Boolean, val replacement: String, val remember: Boolean, val dontAsk: Boolean)

sealed interface SliceState {
    data object Idle : SliceState
    data class Running(val percent: Int, val message: String) : SliceState
    data class Done(val report: SliceReport, val gcode: File, val toolpath: Toolpath?) : SliceState
}

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
    private var engine: Engine? = null
    private var sliceJob: Job? = null

    var phase by mutableStateOf<Phase>(Phase.Loading("Starting the engine…"))
        private set
    var scene by mutableStateOf(Scene.EMPTY)
        private set
    var selection by mutableStateOf<Selection?>(null)
    var busy by mutableStateOf<String?>(null)
        private set
    /** Percent done of the busy work, when it reports one. */
    var busyProgress by mutableStateOf<Int?>(null)
        private set
    /** Stops the busy work, when it can be stopped. */
    var busyCancel by mutableStateOf<(() -> Unit)?>(null)
        private set
    var slice by mutableStateOf<SliceState>(SliceState.Idle)
        private set
    var showPreview by mutableStateOf(false)
    /** The open STEP import dialog, if any. */
    var stepImport by mutableStateOf<StepImportRequest?>(null)
        private set
    /** The open name-cleanup dialog, if any. */
    var nameCleanup by mutableStateOf<NameCleanupRequest?>(null)
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

    // --- document ---

    /** Copies picked files into the cache (the engine reads by extension) and imports them. */
    fun importUris(uris: List<Uri>, asProject: Boolean = false) = engineAction("Importing…") { e ->
        var imported = 0
        // Desktop asks once per import for all its STEP files.
        var stepOptions: StepImportOptions? = null
        // and once for the names of all of them ("" removes the characters)
        var nameRule: String? = null
        for (uri in uris) {
            val file = copyToCache(uri)
            val ext = file.extension.lowercase()
            if (ext !in IMPORTABLE) {
                _messages.tryEmit("${file.name}: not a model file (STL, 3MF, OBJ, AMF or STEP)")
                continue
            }
            if (ext in STEP_EXTENSIONS) {
                val step = e.newStep(file.path)
                try {
                    stepStage(step, "Reading ${file.name}") { e.loadStep(step) }
                    val options = stepOptions ?: askStepOptions(file.name, step) ?: break
                    stepOptions = options
                    stepStage(step, "Meshing ${file.name}") { e.meshStep(step, options) }
                    val replacement = nameRule ?: when (val rule = cleanNames(e, file.name, step)) {
                        NameRule.Cancelled -> break
                        NameRule.Unneeded -> null
                        is NameRule.Chosen -> rule.replacement.also { nameRule = it }
                        // desktop refuses the file and goes on with the others
                        NameRule.InvalidUnicode -> {
                            _messages.tryEmit("${file.name}: a name in the file is not valid Unicode. The geometry was not imported.")
                            continue
                        }
                    }
                    imported += e.importStep(step, replacement)
                } catch (ex: OrcaException) {
                    if (ex.code != OrcaException.CANCELLED) throw ex
                    _messages.tryEmit("Import of ${file.name} cancelled")
                    break
                } finally {
                    e.closeStep(step)
                    busy = "Importing…"
                }
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

    // --- STEP import dialog ---

    /** The saved STEP choices (desktop's linear_deflection, angle_deflection, is_split_compound, ...). */
    private fun savedStepOptions() = StepImportOptions(
        linearDeflection = prefs.getFloat(KEY_STEP_LINEAR, StepImportOptions.LINEAR_DEFAULT.toFloat()).toDouble()
            .takeIf { it in StepImportOptions.LINEAR_MIN..StepImportOptions.LINEAR_MAX } ?: StepImportOptions.LINEAR_DEFAULT,
        angleDeflection = prefs.getFloat(KEY_STEP_ANGLE, StepImportOptions.ANGLE_DEFAULT.toFloat()).toDouble()
            .takeIf { it in StepImportOptions.ANGLE_MIN..StepImportOptions.ANGLE_MAX } ?: StepImportOptions.ANGLE_DEFAULT,
        splitCompound = prefs.getBoolean(KEY_STEP_SPLIT, true),
        keepNames = prefs.getBoolean(KEY_STEP_KEEP_NAMES, true),
        asAssembly = prefs.getBoolean(KEY_STEP_ASSEMBLY, true),
        sortNames = prefs.getBoolean(KEY_STEP_SORT_NAMES, false),
    )

    /** Runs a slow STEP stage with its progress and a Cancel button in the busy row. */
    private suspend fun <T> stepStage(step: StepFile, label: String, block: suspend () -> T): T = coroutineScope {
        busy = "$label…"
        busyProgress = 0
        busyCancel = { step.cancel() }
        val poll = launch {
            while (true) {
                busyProgress = step.progress()
                delay(200)
            }
        }
        try {
            block()
        } finally {
            poll.cancel()
            busyProgress = null
            busyCancel = null
        }
    }

    /** Desktop StepMeshDialog: null when the user cancelled the import. */
    private suspend fun askStepOptions(fileName: String, step: StepFile): StepImportOptions? {
        val saved = savedStepOptions()
        if (!askStepOptions) return saved
        val reply = CompletableDeferred<StepImportAnswer?>()
        val label = busy
        busy = null
        stepImport = StepImportRequest(fileName, step, saved, reply)
        val answer = try {
            reply.await()
        } finally {
            stepImport = null
            busy = label
            // the mesh must not start while the dialog's count runs, nor be stopped by it
            countJob?.let {
                stopTriangleCount(step, it)
                it.join()
            }
            countJob = null
        }
        if (answer == null) return null
        if (answer.remember || answer.dontAsk) {
            val o = answer.options
            val edit = prefs.edit()
                .putFloat(KEY_STEP_LINEAR, o.linearDeflection.toFloat())
                .putFloat(KEY_STEP_ANGLE, o.angleDeflection.toFloat())
                .putBoolean(KEY_STEP_SPLIT, o.splitCompound)
                .putBoolean(KEY_STEP_KEEP_NAMES, o.keepNames)
                .putBoolean(KEY_STEP_ASSEMBLY, o.asAssembly)
            // a disabled sorting choice keeps the remembered one, as on desktop
            if (o.keepNames) edit.putBoolean(KEY_STEP_SORT_NAMES, o.sortNames)
            edit.apply()
        }
        if (answer.dontAsk) askStepOptions = false
        return answer.options
    }

    /** Answers the open STEP dialog; null cancels the import. */
    fun answerStepImport(answer: StepImportAnswer?) {
        stepImport?.reply?.complete(answer)
    }

    private var countJob: Deferred<Long?>? = null

    /**
     * Counts the triangles the import would produce, for the dialog. Runs
     * outside the caller's scope so the caller can stop waiting at once and
     * stop the count with [stopTriangleCount]. Null when cancelled or failed.
     */
    fun stepTriangleCountAsync(step: StepFile, linear: Double, angle: Double): Deferred<Long?> = viewModelScope.async {
        try {
            engine?.stepTriangleCount(step, linear, angle)
        } catch (ex: OrcaException) {
            null
        }
    }.also { countJob = it }

    /** Stops a count from [stepTriangleCountAsync] that has not finished; a finished one is left alone. */
    fun stopTriangleCount(step: StepFile, count: Deferred<Long?>) {
        if (count.isCompleted) return
        count.cancel()
        step.cancel()
    }

    private sealed interface NameRule {
        data object Unneeded : NameRule
        data object Cancelled : NameRule
        data object InvalidUnicode : NameRule
        /** "" removes the characters. */
        data class Chosen(val replacement: String) : NameRule
    }

    /** Desktop prepare_geometry_import_names: picks how unsupported characters in names change. */
    private suspend fun cleanNames(e: Engine, fileName: String, step: StepFile): NameRule {
        var remove = prefs.getBoolean(KEY_NAMES_REMOVE, false)
        var replacement = prefs.getString(KEY_NAMES_REPLACEMENT, null) ?: NAME_REPLACEMENT_DEFAULT
        var ask = askNameCleanup
        val changes = try {
            e.stepNameChanges(step, if (remove) "" else replacement)
        } catch (ex: OrcaException) {
            when (ex.code) {
                OrcaException.INVALID_MODEL -> return NameRule.InvalidUnicode
                // a damaged saved replacement must not silently create more invalid names
                OrcaException.INVALID_ARGUMENT -> {
                    replacement = NAME_REPLACEMENT_DEFAULT
                    ask = true
                    e.stepNameChanges(step, replacement)
                }
                else -> throw ex
            }
        }
        if (changes.isEmpty()) return NameRule.Unneeded
        if (!ask) return NameRule.Chosen(if (remove) "" else replacement)
        val reply = CompletableDeferred<NameCleanupAnswer?>()
        val label = busy
        busy = null
        nameCleanup = NameCleanupRequest(fileName, step, remove, replacement, reply)
        val answer = try {
            reply.await()
        } finally {
            nameCleanup = null
            busy = label
        } ?: return NameRule.Cancelled
        remove = answer.remove
        replacement = answer.replacement
        if (answer.remember || answer.dontAsk)
            prefs.edit().putBoolean(KEY_NAMES_REMOVE, remove).putString(KEY_NAMES_REPLACEMENT, replacement).apply()
        if (answer.dontAsk) askNameCleanup = false
        return NameRule.Chosen(if (remove) "" else replacement)
    }

    /** The name changes [replacement] makes, for the dialog's preview; null when it is not usable. */
    suspend fun previewNameChanges(step: StepFile, replacement: String): List<Pair<String, String>>? = try {
        engine?.stepNameChanges(step, replacement)
    } catch (ex: OrcaException) {
        null
    }

    /** Answers the open name dialog; null cancels the import. */
    fun answerNameCleanup(answer: NameCleanupAnswer?) {
        nameCleanup?.reply?.complete(answer)
    }

    /** Desktop Preferences "Show the dialog for unsupported characters in names". */
    var askNameCleanup: Boolean
        get() = askNameCleanupState
        set(ask) {
            askNameCleanupState = ask
            prefs.edit().putBoolean(KEY_NAMES_SHOW_DIALOG, ask).apply()
        }
    private var askNameCleanupState by mutableStateOf(prefs.getBoolean(KEY_NAMES_SHOW_DIALOG, true))

    /** Desktop Preferences "Show the STEP mesh parameter setting dialog". */
    var askStepOptions: Boolean
        get() = askStepOptionsState
        set(ask) {
            askStepOptionsState = ask
            prefs.edit().putBoolean(KEY_STEP_SHOW_DIALOG, ask).apply()
        }
    private var askStepOptionsState by mutableStateOf(prefs.getBoolean(KEY_STEP_SHOW_DIALOG, true))

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
        val STEP_EXTENSIONS = setOf("step", "stp")
        val IMPORTABLE = setOf("stl", "3mf", "obj", "amf") + STEP_EXTENSIONS
        private const val KEY_STEP_LINEAR = "step_linear_deflection"
        private const val KEY_STEP_ANGLE = "step_angle_deflection"
        private const val KEY_STEP_SPLIT = "step_split_compound"
        private const val KEY_STEP_KEEP_NAMES = "step_keep_names"
        private const val KEY_STEP_ASSEMBLY = "step_load_as_assembly"
        private const val KEY_STEP_SHOW_DIALOG = "step_show_dialog"
        private const val KEY_STEP_SORT_NAMES = "step_sort_names"
        private const val KEY_NAMES_REMOVE = "import_names_cleanup_remove"
        private const val KEY_NAMES_REPLACEMENT = "import_names_cleanup_replacement"
        private const val KEY_NAMES_SHOW_DIALOG = "import_names_cleanup_show_dialog"
        const val NAME_REPLACEMENT_DEFAULT = "_"
    }
}
