package org.orcapod.app.ui

import android.app.Application
import android.content.Context
import android.content.Intent
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
import org.orcapod.app.data.LocalProfileFolder
import org.orcapod.app.data.ProfileBackup
import org.orcapod.app.data.ProfileStore
import org.orcapod.app.data.ProfileSync
import org.orcapod.app.data.SafProfileFolder
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
    var slice by mutableStateOf<SliceState>(SliceState.Idle)
        private set
    var showPreview by mutableStateOf(false)
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
    /** Number of the user's own printer, filament and process presets. */
    var userPresetCount by mutableStateOf(0)
        private set
    /** Display name of the profile sync folder; null when sync is off. */
    var syncFolder by mutableStateOf<String?>(null)
        private set
    /** When the profiles last synced, in epoch milliseconds; 0 when never. */
    var lastSync by mutableStateOf(0L)
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
        syncFolder = syncTree()?.let { SafProfileFolder.displayName(getApplication(), it) }
        lastSync = prefs.getLong(KEY_SYNC_LAST, 0L)
        syncProfiles(quiet = true)
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
        userPresetCount = withContext(Dispatchers.IO) { ProfileBackup.userFiles(userDir).size }
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
        syncProfiles(quiet = true)
    }

    fun deleteUserPreset(kind: PresetKind, name: String) = engineAction { e ->
        e.deleteUserPreset(kind, name)
        refreshPresets()
        syncProfiles(quiet = true)
    }

    // --- profile backup and sync ---

    private val userDir get() = File(store.dataDir, "user/default")

    /** Writes the user's presets to [uri] as an OrcaSlicer preset zip. */
    fun exportProfiles(uri: Uri) = engineAction("Exporting profiles…") {
        val ctx = getApplication<Application>()
        val count = withContext(Dispatchers.IO) {
            ctx.contentResolver.openOutputStream(uri, "wt")?.use { ProfileBackup.export(userDir, it) }
                ?: throw java.io.IOException("Cannot write the file")
        }
        _messages.tryEmit(if (count == 1) "Exported 1 profile" else "Exported $count profiles")
    }

    /** Adds the presets in [uri] (a preset zip or JSON file) to the user's presets. */
    fun importProfiles(uri: Uri) = engineAction("Importing profiles…") { e ->
        val ctx = getApplication<Application>()
        val result = withContext(Dispatchers.IO) {
            ctx.contentResolver.openInputStream(uri)?.use { ProfileBackup.import(it, userDir) }
                ?: throw java.io.IOException("Cannot read the file")
        }
        if (result.imported > 0) reloadUserPresets(e)
        _messages.tryEmit(
            when {
                result.imported == 0 -> "No printer, filament or process profiles found in that file"
                result.skipped.isEmpty() -> if (result.imported == 1) "Imported 1 profile" else "Imported ${result.imported} profiles"
                else -> "Imported ${result.imported} of ${result.imported + result.skipped.size} files; the rest are not profiles"
            },
        )
        if (result.imported > 0) syncProfiles(quiet = true)
    }

    /** Starts syncing profiles with the folder at [treeUri], picked with ACTION_OPEN_DOCUMENT_TREE. */
    fun chooseSyncFolder(treeUri: Uri) {
        val resolver = getApplication<Application>().contentResolver
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            resolver.takePersistableUriPermission(treeUri, flags)
        } catch (ex: SecurityException) {
            _messages.tryEmit("That folder cannot be kept; pick another one")
            return
        }
        syncTree()?.takeIf { it != treeUri }?.let { old -> releaseTree(old) }
        // A new folder starts from scratch: its files are merged with the phone's, nothing is deleted.
        prefs.edit().putString(KEY_SYNC_TREE, treeUri.toString()).remove(KEY_SYNC_STATE).remove(KEY_SYNC_LAST).apply()
        syncFolder = SafProfileFolder.displayName(getApplication(), treeUri)
        lastSync = 0L
        engineAction("Syncing profiles…") { syncProfiles(quiet = false) }
    }

    fun stopSync() {
        syncTree()?.let(::releaseTree)
        prefs.edit().remove(KEY_SYNC_TREE).remove(KEY_SYNC_STATE).remove(KEY_SYNC_LAST).apply()
        syncFolder = null
        lastSync = 0L
    }

    fun syncNow() = engineAction("Syncing profiles…") { syncProfiles(quiet = false) }

    /** Syncs with the chosen folder, if any. [quiet] reports only changes and failures. */
    private suspend fun syncProfiles(quiet: Boolean) {
        val tree = syncTree() ?: return
        val e = engine ?: return
        val ctx = getApplication<Application>()
        val result = try {
            withContext(Dispatchers.IO) {
                val state = HashMap<String, String>()
                prefs.getString(KEY_SYNC_STATE, null)?.let { json ->
                    val o = JSONObject(json)
                    for (k in o.keys()) state[k] = o.getString(k)
                }
                val result = ProfileSync(LocalProfileFolder(userDir), SafProfileFolder(ctx, tree), state).run()
                prefs.edit().putString(KEY_SYNC_STATE, JSONObject(state as Map<*, *>).toString())
                    .putLong(KEY_SYNC_LAST, System.currentTimeMillis()).apply()
                result
            }
        } catch (ex: Exception) {
            if (ex is CancellationException) throw ex
            Log.w(TAG, "profile sync failed", ex)
            _messages.tryEmit(
                if (ex is SecurityException) "Lost access to the sync folder; choose it again"
                else "Profile sync failed: ${ex.message}",
            )
            return
        }
        lastSync = prefs.getLong(KEY_SYNC_LAST, 0L)
        if (result.localChanged) reloadUserPresets(e)
        when {
            result.conflicts > 0 -> _messages.tryEmit(
                "Synced profiles; ${result.conflicts} edited in both places kept the newer copy (the other is in the folder's conflicts)",
            )
            // Quiet syncs follow the user's own saves, so only changes from elsewhere are news.
            result.changes > 0 && (!quiet || result.localChanged) -> _messages.tryEmit(
                if (result.changes == 1) "Synced 1 profile change" else "Synced ${result.changes} profile changes",
            )
            !quiet -> _messages.tryEmit("Profiles are up to date")
        }
    }

    /** Reloads presets after their files changed on disk; unsaved setting edits are dropped. */
    private suspend fun reloadUserPresets(e: Engine) {
        e.reloadPresets(store.dataDir.path)
        editedKeys = emptySet()
        restoreSelection(e)
        invalidateSlice()
    }

    private fun syncTree(): Uri? = prefs.getString(KEY_SYNC_TREE, null)?.let(Uri::parse)

    private fun releaseTree(uri: Uri) {
        try {
            getApplication<Application>().contentResolver.releasePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            // Already gone.
        }
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
        private const val KEY_SYNC_TREE = "sync_tree"
        private const val KEY_SYNC_STATE = "sync_state"
        private const val KEY_SYNC_LAST = "sync_last"
        val IMPORTABLE = setOf("stl", "3mf", "obj", "amf")
    }
}
