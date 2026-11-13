package org.orcapod.app.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.system.Os
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.orcapod.app.data.LocalProfileFolder
import org.orcapod.app.data.PresetChoice
import org.orcapod.app.data.CrashReport
import org.orcapod.app.data.CrashReports
import org.orcapod.app.data.ProfileCatalog
import org.orcapod.app.data.ProfileBackup
import org.orcapod.app.data.ProfileStore
import org.orcapod.app.data.ProfileSync
import org.orcapod.app.data.ProjectStore
import org.orcapod.app.data.RecentProject
import org.orcapod.app.data.Recovery
import org.orcapod.app.data.SafProfileFolder
import org.orcapod.app.data.VendorNames
import org.orcapod.app.engine.BedTypeState
import org.orcapod.app.engine.BooleanOperation
import org.orcapod.app.engine.MeshErrors
import org.orcapod.app.engine.RepairReport
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
import org.orcapod.app.engine.CalibrationSetup
import org.orcapod.app.engine.CalibrationTest
import org.orcapod.app.engine.PlateSettings
import org.orcapod.app.engine.Scene
import org.orcapod.app.engine.SceneObject
import org.orcapod.app.engine.Shape
import org.orcapod.app.engine.SliceReport
import org.orcapod.app.engine.StepFile
import org.orcapod.app.engine.StepImportOptions
import org.orcapod.app.engine.SliceTarget
import org.orcapod.app.engine.SlicedPlate
import org.orcapod.app.engine.splitPlates
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
    /** Which printer models and filaments of the installed brands the pickers list. */
    data class ModelSetup(val catalog: ProfileCatalog) : Phase
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
    /**
     * A finished slice. [plate] is the index of the plate [toolpath] shows. A
     * slice of every plate keeps all of them in [gcode] and each one in
     * [plates], whose previews the user switches between.
     */
    data class Done(
        val report: SliceReport,
        val gcode: File,
        val toolpath: Toolpath?,
        val plate: Int,
        val plates: List<SlicedPlate> = emptyList(),
    ) : SliceState {
        /** Whether the G-code may be printed or exported: as on desktop, not when toolpaths collide. */
        val printable get() = !report.hasPathConflict
    }
}

/** The per-object or per-part settings target: [volumeId] 0 is the object itself. */
data class OverrideTarget(val objectId: Long, val volumeId: Long)

/** An open model library, optionally starting at a web page. */
data class LibraryRequest(val url: String? = null)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ProfileStore(app)
    private val projects = ProjectStore(app)
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
    /** The model library is open; a URL opens it on that page (a link shared to the app). */
    var library by mutableStateOf<LibraryRequest?>(null)
        private set

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
    /** Mesh errors per object id, for desktop's object list warning icons; refreshed with the scene. */
    var meshErrors by mutableStateOf<Map<Long, MeshErrors>>(emptyMap())
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

    /** Printers and filaments the user picked to show in the pickers, by kind. */
    var choices by mutableStateOf(loadChoices())
        private set

    /** The installed brand whose profiles are loaded; the others load when picked. */
    var activeVendor by mutableStateOf<String?>(null)
    // --- the open project (desktop's project title, dirty mark and recent list) ---

    /** Name of the open project, or null for a plate never saved or opened. */
    var projectName by mutableStateOf<String?>(null)
        private set
    /** Where the open project lives, when "Save" may write there again. */
    var projectUri by mutableStateOf<Uri?>(null)
        private set
    private var savedRevision by mutableStateOf(0L)
    private var savedSettings by mutableStateOf<Any?>(null)
    /** The plate or settings changed since the project was last opened or saved. */
    val projectDirty: Boolean
        get() = scene.snapshot.objects.isNotEmpty() &&
            (scene.snapshot.revision != savedRevision || settingsFingerprint() != savedSettings)
    var recentProjects by mutableStateOf<List<RecentProject>>(emptyList())
        private set
    /** A project 3MF the user imported or opened from another app: open it, or import its geometry? */
    var projectChoice by mutableStateOf<Uri?>(null)
    /** An unsaved plate left by the last session, offered back once at startup. */
    var recovery by mutableStateOf<Recovery?>(null)
        private set

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    /** The report of the app's last unexpected close, offered for sharing once. */
    var crashReport by mutableStateOf<CrashReport?>(null)

    init {
        viewModelScope.launch {
            crashReport = withContext(Dispatchers.IO) {
                try {
                    CrashReports.takePending(getApplication())
                } catch (ex: Exception) {
                    Log.w(TAG, "could not read the crash report", ex)
                    null
                }
            }
        }
        viewModelScope.launch { start() }
        viewModelScope.launch { autoSaveRecovery() }
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
            showModelSetup()
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            Log.e(TAG, "install failed", e)
            phase = Phase.Failed(e.message ?: e.toString())
        }
    }

    /** Opens the step that picks which printer models and filaments the pickers list. */
    fun manageModels() = viewModelScope.launch { showModelSetup() }

    private suspend fun showModelSetup() {
        phase = Phase.Loading("Reading printer models…")
        phase = try {
            Phase.ModelSetup(store.catalog(installedVendors))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "catalog failed", e)
            _messages.tryEmit("Could not read the printer models; every printer and filament stays listed")
            Phase.Ready
        }
    }

    /**
     * Lists only the [printers] and [filaments] picked from [catalog] in the
     * pickers; picking no printer lists them all, picking no filament lists none.
     */
    fun finishModelSetup(catalog: ProfileCatalog, printers: Set<ProfileCatalog.Group>, filaments: Set<ProfileCatalog.Group>) {
        saveChoice(PresetKind.PRINTER, choice(PresetKind.PRINTER).pick(catalog.printerPresets, printers.flatMapTo(HashSet()) { it.presets }))
        saveChoice(PresetKind.FILAMENT, choice(PresetKind.FILAMENT).pick(catalog.filamentPresets, filaments.flatMapTo(HashSet()) { it.presets }))
        phase = Phase.Ready
    }

    fun skipModelSetup() {
        phase = Phase.Ready
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
        markSaved()
        recentProjects = projects.recent()
        recovery = projects.recovery()
        phase = Phase.Ready
        syncFolder = syncTree()?.let { SafProfileFolder.displayName(getApplication(), it) }
        lastSync = prefs.getLong(KEY_SYNC_LAST, 0L)
        syncProfiles(quiet = true)
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
            // Start on a printer the user ticked, not the first one alphabetically.
            val start = choice(PresetKind.PRINTER).filter(machines, "").first()
            e.selectPrinter(start.name)
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
        userPresetCount = withContext(Dispatchers.IO) { ProfileBackup.userFiles(userDir).size }
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

    /**
     * Adds the presets in [uris] (preset zips, desktop's .orca_filament and
     * .orca_printer exports, or the JSON files from its user preset folder)
     * to the user's presets, and says which filaments did not come through.
     */
    fun importProfiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val ctx = getApplication<Application>()
        importAndReport {
            uris.map { uri ->
                ctx.contentResolver.openInputStream(uri)?.use { ProfileBackup.import(it, userDir) }
                    ?: throw java.io.IOException("Cannot read the file")
            }.reduce(ProfileBackup.ImportResult::plus)
        }
    }

    /**
     * Adds every user preset in the folder at [treeUri], picked with
     * ACTION_OPEN_DOCUMENT_TREE: desktop OrcaSlicer's own `user` folder (or the
     * whole OrcaSlicer folder) copied to the phone or reached through a cloud app.
     */
    fun importProfileFolder(treeUri: Uri) {
        val ctx = getApplication<Application>()
        importAndReport {
            val root = DocumentFile.fromTreeUri(ctx, treeUri)?.takeIf { it.isDirectory }
                ?: throw java.io.IOException("Cannot open that folder")
            val files = ArrayList<Pair<String, ByteArray>>()
            fun walk(dir: DocumentFile, path: String, depth: Int) {
                for (f in dir.listFiles()) {
                    val name = f.name ?: continue
                    val p = if (path.isEmpty()) name else "$path/$name"
                    if (f.isDirectory && depth < 5) walk(f, p, depth + 1)
                    else if (f.isFile && f.length() < (8L shl 20) && ProfileBackup.isFolderPreset("${root.name}/$p")) {
                        ctx.contentResolver.openInputStream(f.uri)?.use { files += p to it.readBytes() }
                    }
                }
            }
            walk(root, "", 0)
            ProfileBackup.importEntries(files, userDir)
        }
    }

    private fun importAndReport(read: () -> ProfileBackup.ImportResult) = engineAction("Importing profiles…") { e ->
        val result = withContext(Dispatchers.IO) { read() }
        if (result.imported > 0) reloadUserPresets(e)
        val loaded = if (result.filaments.isEmpty()) emptyList() else e.list(PresetKind.FILAMENT)
        _messages.tryEmit(
            when {
                result.imported == 0 -> "No printer, filament or process profiles found there"
                result.skipped.isEmpty() -> if (result.imported == 1) "Imported 1 profile" else "Imported ${result.imported} profiles"
                else -> "Imported ${result.imported} of ${result.imported + result.skipped.size} files; the rest are not profiles"
            } + importProblems(result.filaments, loaded),
        )
        if (result.imported > 0) syncProfiles(quiet = true)
    }

    // The engine drops a preset whose parent is not installed, and lists one made for another printer as incompatible.
    private fun importProblems(filaments: Map<String, String>, loaded: List<PresetEntry>): String {
        val byName = loaded.associateBy { it.name }
        val missing = filaments.filterKeys { it !in byName }
        val other = filaments.keys.filter { byName[it]?.compatible == false }
        val notes = ArrayList<String>()
        missing.entries.firstOrNull()?.let { (name, parent) ->
            val more = if (missing.size > 1) " (and ${missing.size - 1} more)" else ""
            notes += if (parent.isEmpty()) "\"$name\"$more could not be loaded"
            else "\"$name\"$more is based on \"$parent\", which is not installed; add that printer brand"
        }
        if (other.isNotEmpty()) {
            val more = if (other.size > 1) " (and ${other.size - 1} more)" else ""
            notes += "\"${other.first()}\"$more is for another printer, so it is listed when you switch to it"
        }
        return notes.joinToString("") { ". $it" }
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
        restoreSelection(e)
        refreshScene()
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

    /** Shows only [picked] of [entries] in the [kind] picker; nothing picked shows every printer, but no filaments. */
    fun choosePresets(kind: PresetKind, entries: List<PresetEntry>, picked: Set<String>) =
        saveChoice(kind, choice(kind).pick(entries, picked))

    fun choice(kind: PresetKind): PresetChoice = choices[kind] ?: emptyChoice(kind)

    private fun saveChoice(kind: PresetKind, choice: PresetChoice) {
        choices = choices + (kind to choice)
        choicePrefs.edit()
            .putStringSet("shown/${kind.name}", choice.shown)
            .putStringSet("seen/${kind.name}", choice.seen)
            .apply()
    }

    private fun loadChoices(): Map<PresetKind, PresetChoice> = CHOOSABLE.associateWith { kind ->
        emptyChoice(kind).copy(
            shown = choicePrefs.getStringSet("shown/${kind.name}", null).orEmpty().toSet(),
            seen = choicePrefs.getStringSet("seen/${kind.name}", null).orEmpty().toSet(),
        )
    }

    // The filament library runs to thousands of presets, so only the ones the user ticked are listed.
    private fun emptyChoice(kind: PresetKind) = PresetChoice(emptyShowsAll = kind != PresetKind.FILAMENT)

    // --- document ---

    fun openLibrary(url: String? = null) { library = LibraryRequest(url) }
    fun closeLibrary() { library = null }

    /**
     * Copies picked files into the cache (the engine reads by extension) and imports them.
     * A single 3MF that carries project settings is not imported straight away: like
     * desktop, the user picks between opening it as a project and taking its geometry.
     */
    fun importUris(uris: List<Uri>, askProject: Boolean = true) = engineAction("Importing…") { e ->
        val files = uris.map { copyToCache(it) }
        val single = files.singleOrNull()
        if (askProject && single != null && single.extension.lowercase() == "3mf" &&
            withContext(Dispatchers.IO) { ProjectStore.isProject(single) }
        ) {
            projectChoice = uris.single()
            return@engineAction
        }
        importFiles(e, files)
    }

    /** Imports model files already on disk, such as downloads from the model library. */
    fun importFiles(files: List<File>) = engineAction("Importing…") { e -> importFiles(e, files) }

    private suspend fun importFiles(e: Engine, files: List<File>) {
        var imported = 0
        // Desktop asks once per import for all its STEP files.
        var stepOptions: StepImportOptions? = null
        // and once for the names of all of them ("" removes the characters)
        var nameRule: String? = null
        for (file in files) {
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
            imported += e.import(file.path)
        }
        if (files.size > 1 && imported > 1) {
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

    // --- project files ---

    /** Desktop "Open project": the 3MF's objects, plates and settings replace the current plate. */
    fun openProject(uri: Uri) = engineAction("Opening…") { e ->
        val file = copyToCache(uri)
        if (file.extension.lowercase() != "3mf") {
            _messages.tryEmit("${file.name}: a project is a 3MF file")
            return@engineAction
        }
        openProjectFile(e, file, uri)
        refreshScene()
        invalidateSlice()
        markSaved()
    }

    /** Re-opens a project from the recent list; drops it when the file is gone or no longer readable. */
    fun openRecent(project: RecentProject) = engineAction("Opening…") { e ->
        val uri = Uri.parse(project.uri)
        val file = try {
            copyToCache(uri)
        } catch (ex: Exception) {
            if (ex is CancellationException) throw ex
            projects.forget(project.uri)
            recentProjects = projects.recent()
            _messages.tryEmit("${project.name} can no longer be opened; removed it from the list")
            return@engineAction
        }
        openProjectFile(e, file, uri)
        refreshScene()
        invalidateSlice()
        markSaved()
    }

    fun recentThumbnail(project: RecentProject): Bitmap? = projects.thumbnail(project)

    fun forgetRecent(project: RecentProject) {
        runCatching {
            getApplication<Application>().contentResolver.releasePersistableUriPermission(
                Uri.parse(project.uri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        projects.forget(project.uri)
        recentProjects = projects.recent()
    }

    private suspend fun openProjectFile(e: Engine, file: File, uri: Uri) {
        e.openProject(file.path)
        refreshPresets()
        selection = null
        selectedVolume = null
        val name = ProjectStore.projectName(file.name)
        projectName = name
        projectUri = uri.takeIf { keepAccess(it) }
        val thumbnail = withContext(Dispatchers.IO) { ProjectStore.thumbnailOf(file) }
        if (projectUri != null) remember(uri, name, thumbnail)
        projects.clearRecovery()
        recovery = null
    }

    /** Starts an empty, unnamed project (desktop "New project"); keeps the selected presets. */
    fun newProject() = engineAction { e ->
        e.clear()
        selection = null
        selectedVolume = null
        projectName = null
        projectUri = null
        refreshScene()
        invalidateSlice()
        markSaved()
        projects.clearRecovery()
        recovery = null
    }

    /**
     * Desktop "Save project": the whole plate (objects, parts, transforms, per-object and
     * per-plate settings, painting, plates) and the selected settings as an OrcaSlicer 3MF.
     * [uri] null writes back to the open project ("Save"); otherwise it is "Save as".
     */
    fun saveProject(uri: Uri? = null, then: (() -> Unit)? = null) = engineAction("Saving…") { e ->
        val target = uri ?: projectUri ?: return@engineAction
        val name = if (uri == null) projectName ?: "Project" else ProjectStore.projectName(displayName(target) ?: "Project")
        val thumbnail = renderThumbnail()
        val tmp = File(getApplication<Application>().cacheDir, "project.3mf")
        e.saveProject(tmp.path, thumbnail?.size ?: 0, thumbnail?.rgba())
        copyFromFile(tmp, target)
        projectName = name
        if (keepAccess(target)) {
            projectUri = target
            remember(target, name, thumbnail?.bitmap())
        }
        markSaved()
        projects.clearRecovery()
        _messages.tryEmit("Saved $name")
        then?.invoke()
    }

    /**
     * Refreshes the recovery copy of an unsaved plate shortly after each change, so a crash
     * or a killed process loses at most the last few seconds of work. Changes that come in
     * a burst (a drag, a run of setting edits) are written once, when they settle.
     */
    @OptIn(FlowPreview::class)
    private suspend fun autoSaveRecovery() {
        snapshotFlow {
            listOf(
                phase == Phase.Ready, slice is SliceState.Running, recovery != null,
                scene.snapshot.revision, settingsFingerprint(), projectName, projectUri,
                savedRevision, savedSettings,
            )
        }
            .distinctUntilChanged()
            .debounce(AUTOSAVE_DELAY_MS)
            .collect { writeRecovery() }
    }

    /**
     * Keeps a copy of an unsaved plate when the app goes to the background, so a
     * process the system kills can be restored on the next start.
     */
    fun saveRecovery() {
        viewModelScope.launch { writeRecovery() }
    }

    private val recoveryLock = Mutex()

    private suspend fun writeRecovery() = recoveryLock.withLock<Unit> {
        val e = engine ?: return@withLock
        if (phase != Phase.Ready || slice is SliceState.Running) return@withLock
        // The last session's copy is still waiting for the user's answer: keep it.
        if (recovery != null) return@withLock
        if (!projectDirty) {
            projects.clearRecovery()
            recoveryState = null
            return@withLock
        }
        // Opening a file picker stops the activity too: skip a copy that would not change.
        val state = listOf(scene.snapshot.revision, settingsFingerprint(), projectName, projectUri)
        if (state == recoveryState && projects.recoveryFile.isFile) return@withLock
        try {
            val thumbnail = renderThumbnail()
            val tmp = File(projects.recoveryFile.path + ".tmp.3mf")
            e.saveProject(tmp.path, thumbnail?.size ?: 0, thumbnail?.rgba())
            val name = projectName
            val uri = projectUri?.toString()
            withContext(Dispatchers.IO) {
                if (!tmp.renameTo(projects.recoveryFile)) throw java.io.IOException("Cannot keep the recovery copy")
                projects.recoveryWritten(name, uri)
            }
            recoveryState = state
        } catch (ex: Exception) {
            if (ex is CancellationException) throw ex
            Log.w(TAG, "recovery save failed", ex)
        }
    }

    /** Opens the plate the last session left unsaved; it stays marked as unsaved. */
    fun restoreRecovery() = engineAction("Restoring…") { e ->
        val r = recovery ?: return@engineAction
        recovery = null
        e.openProject(r.file.path)
        refreshPresets()
        refreshScene()
        invalidateSlice()
        projectName = r.name
        projectUri = r.uri?.let(Uri::parse)?.takeIf { stillGranted(it) }
        savedRevision = -1 // unsaved until the user saves it
        savedSettings = settingsFingerprint()
    }

    fun discardRecovery() {
        recovery = null
        projects.clearRecovery()
    }

    private suspend fun renderThumbnail(): PlateThumbnail? = withContext(Dispatchers.Default) {
        try {
            PlateThumbnail.render(scene, filamentSlots.map { it.colour })
        } catch (ex: OutOfMemoryError) {
            null
        }
    }

    private suspend fun remember(uri: Uri, name: String, thumbnail: Bitmap?) {
        withContext(Dispatchers.IO) { projects.remember(RecentProject(uri.toString(), name, System.currentTimeMillis()), thumbnail) }
        recentProjects = projects.recent()
    }

    private var recoveryState: Any? = null

    private fun markSaved() {
        savedRevision = scene.snapshot.revision
        savedSettings = settingsFingerprint()
    }

    /** Everything about the selected settings that a saved project carries. */
    private fun settingsFingerprint(): Any = listOf(printer, filament, process, presetValues, filamentSlots)

    /**
     * Keeps read (and, when granted, write) access to a picked document across restarts.
     * True when the app can write to it again later, so "Save" may overwrite it.
     */
    private fun keepAccess(uri: Uri): Boolean {
        if (uri.scheme != "content") return false
        val resolver = getApplication<Application>().contentResolver
        val rw = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        return try {
            resolver.takePersistableUriPermission(uri, rw)
            true
        } catch (_: SecurityException) {
            runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            false
        }
    }

    private fun stillGranted(uri: Uri): Boolean =
        getApplication<Application>().contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }

    private fun displayName(uri: Uri): String? = runCatching {
        getApplication<Application>().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    /**
     * Desktop project export for another printer: the plate as an OrcaSlicer 3MF set up for
     * the printer preset [machine], so it opens ready for that printer in OrcaSlicer or Bambu
     * Studio. The plate here keeps its own printer and settings.
     */
    fun exportProjectFor(machine: String, uri: Uri) = engineAction("Exporting…") { e ->
        val thumbnail = renderThumbnail()
        val tmp = File(getApplication<Application>().cacheDir, "export-project.3mf")
        e.exportProjectFor(machine, tmp.path, thumbnail?.size ?: 0, thumbnail?.rgba())
        copyFromFile(tmp, uri)
        _messages.tryEmit("Exported the plate as 3MF for $machine")
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
        meshErrors = scene.snapshot.objects.associate { it.id to e.meshErrors(it.id) }
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

    /** One brush dab from the viewport, waiting for the engine. */
    private class Dab(val objectId: Long, val volumeId: Long, val hit: FloatArray, val view: FloatArray, val first: Boolean)

    /** Dabs the finger has made that the engine has not painted yet (main thread only). */
    private val pendingDabs = ArrayList<Dab>()
    private var dabWorker: Job? = null
    /** A finger is down painting: the scene refresh waits for it to lift. */
    private var stroking = false

    /** Painted support enforcers on an object whose supports are off: desktop's "enable support" warning. */
    var paintSupportsOff by mutableStateOf(false)
        private set

    /**
     * A dab or fill from the viewport; brush strokes send one per finger step,
     * fills only the first touch. Brush dabs queue up while the engine is
     * busy and go to it together, and a whole stroke is one undo step.
     */
    fun paintAt(objectId: Long, volumeId: Long, hit: FloatArray, view: FloatArray, first: Boolean) {
        val p = paint ?: return
        if (p.objectId != objectId || (p.tool != PaintTool.BRUSH && !first)) return
        if (p.tool == PaintTool.BRUSH) {
            if (first) stroking = true
            pendingDabs += Dab(objectId, volumeId, hit, view, first)
            if (dabWorker?.isActive != true) dabWorker = viewModelScope.launch { paintDabs() }
            return
        }
        engineAction { e ->
            try {
                e.paintFill(objectId, volumeId, p.layer, hit, view, if (p.tool == PaintTool.SMART_FILL) p.angle.toDouble() else 0.0, p.state)
            } catch (ex: OrcaException) {
                // A tap on the very edge of the part can miss its surface in
                // the engine's own hit test: nothing to fill, like desktop.
                if (ex.code != OrcaException.NOT_FOUND) throw ex
                return@engineAction
            }
            refreshScene()
            invalidateSlice()
        }
    }

    /** The finger lifted: the stroke is over. */
    fun paintStrokeEnd() {
        stroking = false
        if (dabWorker?.isActive != true) engineAction { refreshScene() }
    }

    private suspend fun paintDabs() {
        val e = engine ?: return
        var painted = false
        try {
            while (pendingDabs.isNotEmpty()) {
                val p = paint ?: break
                val batch = fillGaps(pendingDabs.toList(), p.radius)
                pendingDabs.clear()
                // One engine call per run of dabs on the same part; only a
                // stroke's first dab starts a new undo step.
                var i = 0
                while (i < batch.size) {
                    val first = batch[i]
                    var j = i + 1
                    while (j < batch.size && batch[j].volumeId == first.volumeId && !batch[j].first) j++
                    val run = batch.subList(i, j)
                    val hits = DoubleArray(run.size * 3)
                    val views = DoubleArray(run.size * 3)
                    run.forEachIndexed { k, d -> for (c in 0 until 3) { hits[k * 3 + c] = d.hit[c].toDouble(); views[k * 3 + c] = d.view[c].toDouble() } }
                    e.paintStroke(first.objectId, first.volumeId, p.layer, hits, views, p.radius.toDouble(), p.state, continueStroke = !first.first)
                    i = j
                }
                painted = true
                refreshPaint()
                invalidateSlice()
            }
        } catch (ex: OrcaException) {
            pendingDabs.clear()
            Log.w(TAG, "paint stroke failed", ex)
            _messages.tryEmit(ex.message ?: "Engine error")
        }
        if (painted && !stroking) refreshScene()
    }

    /** The last dab of the stroke being painted, for [fillGaps]. */
    private var lastDab: Dab? = null

    /**
     * Adds dabs between ones further apart than half the brush radius, so a
     * fast finger draws a solid line like desktop's brush instead of a row of
     * dots. The engine casts each dab along its view ray onto the surface.
     */
    private fun fillGaps(dabs: List<Dab>, radius: Float): List<Dab> {
        val out = ArrayList<Dab>(dabs.size)
        val step = maxOf(radius * 0.5f, 0.05f)
        for (d in dabs) {
            val prev = lastDab
            if (!d.first && prev != null && prev.volumeId == d.volumeId) {
                val dist = kotlin.math.sqrt((0 until 3).sumOf { ((d.hit[it] - prev.hit[it]) * (d.hit[it] - prev.hit[it])).toDouble() }).toFloat()
                val n = minOf((dist / step).toInt(), MAX_GAP_DABS)
                for (k in 1 until n) {
                    val t = k.toFloat() / n
                    val hit = FloatArray(3) { prev.hit[it] + (d.hit[it] - prev.hit[it]) * t }
                    val view = FloatArray(3) { prev.view[it] + (d.view[it] - prev.view[it]) * t }
                    out += Dab(d.objectId, d.volumeId, hit, view, false)
                }
            }
            out += d
            lastDab = d
        }
        return out
    }

    /** Desktop's fix for its warning: turn supports on for the painted object. */
    fun enableSupportsForPaint() {
        val p = paint ?: return
        setOverride(OverrideTarget(p.objectId, 0), "enable_support", "1")
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
        paintSupportsOff = p.layer == PaintLayer.SUPPORT &&
            meshes.any { m -> m.states.any { it.toInt() == 1 } } &&
            (overrides(OverrideTarget(p.objectId, 0))["enable_support"] ?: valueOf(PresetKind.PROCESS, "enable_support")) != "1"
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
    fun startCalibration(setup: CalibrationSetup) =
        engineAction("Setting up ${setup.test.label.lowercase()}…") { e ->
            val model = setup.model?.let { name ->
                withContext(Dispatchers.IO) {
                    val file = File(File(getApplication<Application>().cacheDir, "calib").apply { mkdirs() }, name)
                    getApplication<Application>().assets.open("calib/$name").use { input ->
                        file.outputStream().use { input.copyTo(it) }
                    }
                    file
                }
            }
            val test = setup.test
            cut = null
            paint = null
            layerHeights = null
            measure = null
            selection = null
            e.startCalibration(setup, model?.path)
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

    /** Desktop "Fix model" on objects (every volume) or, with [volumeId], on one part. */
    fun repair(objectIds: List<Long>, volumeId: Long = 0) = mutate("Repairing…") { e ->
        val reports = objectIds.map { id -> (objectOf(id)?.name ?: "Object") to e.repair(id, volumeId) }
        _messages.tryEmit(repairSummary(reports))
    }

    /** Desktop Mesh Boolean between two volumes of an object; selects the result. */
    fun meshBoolean(objectId: Long, sourceVolumeId: Long, toolVolumeId: Long, operation: BooleanOperation, deleteTool: Boolean) =
        mutate("Running ${operation.label.lowercase()}…") { e ->
            selectedVolume = e.meshBoolean(objectId, sourceVolumeId, toolVolumeId, operation, deleteTool)
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
            CrashReports.breadcrumb(getApplication(), "Slicing ${if (allPlates) "every plate" else "a plate"}")
            try {
                val target = if (allPlates) SliceTarget.ALL_PLATES else SliceTarget.PLATE
                val report = e.slice(out.path, target, listener = progress())
                // The plates of one file overlap on the bed, so each is previewed on its own.
                val plates = if (allPlates) withContext(Dispatchers.IO) { splitPlates(out, dir) } else emptyList()
                val plate = if (allPlates) {
                    plates.firstOrNull { it.plate == scene.snapshot.currentPlate }?.plate ?: plates.firstOrNull()?.plate ?: -1
                } else scene.snapshot.currentPlate
                val previewFile = if (allPlates) plates.firstOrNull { it.plate == plate }?.gcode else out
                val toolpath = previewFile?.let { loadPreview(e, it) }
                slice = SliceState.Done(report, out, toolpath, plate, plates)
                previewRange = PreviewRange(0, (toolpath?.layerTotal ?: 1) - 1)
                sliceStale = false
                showPreview = true
            } catch (ex: OrcaException) {
                slice = SliceState.Idle
                _messages.tryEmit(if (ex.code == OrcaException.CANCELLED) "Slicing cancelled" else "Slicing failed: ${ex.message}")
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                // Anything else must not close the app: report it like an engine error.
                Log.e(TAG, "slicing failed", ex)
                slice = SliceState.Idle
                _messages.tryEmit("Slicing failed: ${ex.message ?: ex.javaClass.simpleName}")
            } catch (ex: OutOfMemoryError) {
                Log.e(TAG, "slicing ran out of memory", ex)
                slice = SliceState.Idle
                _messages.tryEmit("Slicing ran out of memory. Try fewer or smaller objects, or a larger layer height.")
            } finally {
                CrashReports.breadcrumb(getApplication(), null)
            }
        }
    }

    /** Whether [previewSlicedPlate] is loading a plate. */
    var loadingPlatePreview by mutableStateOf(false)
        private set

    /** Shows another plate of a slice of every plate. */
    fun previewSlicedPlate(plate: Int) {
        val done = slice as? SliceState.Done ?: return
        val e = engine ?: return
        if (plate == done.plate || loadingPlatePreview) return
        val file = done.plates.firstOrNull { it.plate == plate }?.gcode ?: return
        loadingPlatePreview = true
        viewModelScope.launch {
            try {
                val toolpath = loadPreview(e, file, showProgress = false)
                // Unless the model changed meanwhile, which drops the slice.
                if (slice === done) {
                    slice = done.copy(toolpath = toolpath, plate = plate)
                    previewRange = PreviewRange(0, (toolpath?.layerTotal ?: 1) - 1)
                }
            } finally {
                loadingPlatePreview = false
                CrashReports.breadcrumb(getApplication(), null)
            }
        }
    }

    /**
     * Desktop "Export plate sliced file" / "Export all sliced file": G-code inside a 3MF, for
     * Bambu-style printers. With [machine], the selected plate is sliced for that printer preset
     * instead, keeping this session's process changes; the plate's own printer stays selected.
     */
    fun exportSliced3mf(uri: Uri, allPlates: Boolean, machine: String? = null) {
        val e = engine ?: return
        if (slice is SliceState.Running) return
        sliceJob = viewModelScope.launch {
            val tmp = File(getApplication<Application>().cacheDir, "sliced.3mf")
            slice = SliceState.Running(0, "Preparing")
            try {
                val target = if (allPlates && machine == null) SliceTarget.SLICED_3MF else SliceTarget.SLICED_3MF_PLATE
                val report = e.slice(tmp.path, target, scene.snapshot.currentPlate, machine, progress())
                if (report.hasPathConflict) {
                    _messages.tryEmit(COLLISION_BLOCKS_OUTPUT)
                } else {
                    copyFromFile(tmp, uri)
                    _messages.tryEmit(if (machine != null) "Sliced 3MF for $machine saved" else "Sliced 3MF saved")
                }
            } catch (ex: OrcaException) {
                _messages.tryEmit(if (ex.code == OrcaException.CANCELLED) "Slicing cancelled" else "Slicing failed: ${ex.message}")
            } catch (ex: java.io.IOException) {
                _messages.tryEmit(ex.message ?: "File error")
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                Log.e(TAG, "sliced 3MF export failed", ex)
                _messages.tryEmit("Slicing failed: ${ex.message ?: ex.javaClass.simpleName}")
            } catch (ex: OutOfMemoryError) {
                Log.e(TAG, "sliced 3MF export ran out of memory", ex)
                _messages.tryEmit("Slicing ran out of memory. Try fewer or smaller objects, or a larger layer height.")
            } finally {
                CrashReports.breadcrumb(getApplication(), null)
                if (slice is SliceState.Running) slice = SliceState.Idle
            }
        }
    }

    private fun progress() = org.orcapod.app.engine.ProgressListener { percent, message ->
        // Kept on disk, so a crash report names the step the engine was at.
        CrashReports.breadcrumb(getApplication(), "Slicing, $percent%: $message")
        viewModelScope.launch { if (slice is SliceState.Running) slice = SliceState.Running(percent, message) }
    }

    private suspend fun loadPreview(e: Engine, gcode: File, showProgress: Boolean = true): Toolpath? {
        if (showProgress) slice = SliceState.Running(100, "Loading preview")
        CrashReports.breadcrumb(getApplication(), "Loading the sliced preview")
        // A big print's lines may not all fit in the app's memory: the engine
        // then joins runs of short moves, and joins more if it still runs out.
        var maxLines = previewLineBudget()
        repeat(3) {
            try {
                return e.loadToolpath(gcode.path, maxLines)
            } catch (ex: OrcaException) {
                Log.w(TAG, "preview failed", ex)
                return null
            } catch (ex: OutOfMemoryError) {
                Log.w(TAG, "preview of $maxLines lines did not fit", ex)
                maxLines /= 3
            }
        }
        _messages.tryEmit("This G-code is too large to preview on this device. It can still be printed.")
        return null
    }

    /**
     * Lines the preview can have in the Java heap left now: each takes about
     * 80 bytes in the toolpath and 8 in the renderer's draw ranges (it uploads
     * the vertices a run at a time); the rest of the 180 is headroom for
     * rebuilding them when the view mode changes. Half the free heap is left
     * for the rest of the app.
     */
    private fun previewLineBudget(): Int {
        val rt = Runtime.getRuntime()
        val free = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())
        return (free / 2 / 180).coerceIn(100_000L, Int.MAX_VALUE.toLong()).toInt()
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
        val safe = exportName().replace(Regex("[^A-Za-z0-9._-]+"), "_").take(60).ifEmpty { "plate" }
        return "${safe}_${process.substringBefore(' ').replace(Regex("[^A-Za-z0-9.]+"), "")}.gcode"
    }

    /** Base name for exported files: the project's name, else the first object's, else "plate". */
    fun exportName(): String {
        val name = projectName ?: scene.snapshot.objects.firstOrNull()?.name?.substringBeforeLast('.')
        return name?.let { exportFileName(it) } ?: "plate"
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
        /** Quiet time after an edit before the recovery copy is refreshed. */
        private const val AUTOSAVE_DELAY_MS = 2_000L
        /** Most dabs [fillGaps] adds between two finger samples. */
        private const val MAX_GAP_DABS = 64
        const val COLLISION_BLOCKS_OUTPUT = "Some objects' toolpaths collide, so this slice can't be printed or exported. Move them apart and slice again."
        private const val KEY_SYNC_TREE = "sync_tree"
        private const val KEY_SYNC_STATE = "sync_state"
        private const val KEY_SYNC_LAST = "sync_last"
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

        /** Preset kinds whose picker lists only the presets the user picked. */
        val CHOOSABLE = listOf(PresetKind.PRINTER, PresetKind.FILAMENT)
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

/** The notice desktop shows after "Fix model": what was repaired, what failed, what needed nothing. */
internal fun repairSummary(reports: List<Pair<String, RepairReport>>): String {
    val repaired = reports.filter { it.second.modified }.map { it.first }
    val failed = reports.filter { it.second.failedVolumes > 0 }
    val unchanged = reports.count { !it.second.modified && it.second.failedVolumes == 0 }
    val lines = mutableListOf<String>()
    if (repaired.isNotEmpty()) lines += "Repaired: " + repaired.joinToString()
    for ((name, r) in failed) lines += "Could not repair $name: ${r.failures}"
    if (unchanged > 0) lines += if (reports.size == 1) "The model needed no repair" else "$unchanged of ${reports.size} needed no repair"
    if (reports.any { it.second.paintingRemoved }) lines += "Painting on the repaired parts was removed"
    return lines.joinToString("\n")
}

/** [name] without the characters file pickers refuse, or null when nothing is left. */
internal fun exportFileName(name: String): String? =
    name.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]+"""), "_").trim(' ', '.', '_').take(80).ifEmpty { null }
