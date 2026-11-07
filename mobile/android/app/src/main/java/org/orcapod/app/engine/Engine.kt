package org.orcapod.app.engine

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.concurrent.Executors

/** A triangle mesh in object space. */
class TriMesh(val positions: FloatArray, val indices: IntArray)

/**
 * An object's merged part mesh in object space, plus the meshes of its
 * modifiers, negative parts and support blockers/enforcers by volume id;
 * shared across snapshots while its revision holds.
 */
class Mesh(
    val revision: Long,
    val positions: FloatArray,
    val indices: IntArray,
    val volumes: Map<Long, TriMesh> = emptyMap(),
)

/** A document snapshot with every object's mesh resolved. */
class Scene(val snapshot: SceneSnapshot, val meshes: Map<Long, Mesh>) {
    companion object {
        val EMPTY = Scene(SceneSnapshot.EMPTY, emptyMap())
    }
}

/**
 * The engine session: one preset bundle and one document (plater).
 *
 * The bridge serializes engine work process-wide, and slicing blocks for a
 * long time, so every call runs on one dedicated engine thread. Only
 * [cancelSlice] runs on the caller's thread, which the bridge allows.
 */
class Engine private constructor(private var presets: Long, private val plater: Long) : Closeable {
    private val meshCache = HashMap<Long, Mesh>()

    @Volatile
    private var job: Long = 0

    // --- presets ---

    suspend fun list(kind: PresetKind): List<PresetEntry> = onEngine { Native.presetsList(presets, kind.code).toList() }

    suspend fun current(kind: PresetKind): String = onEngine { Native.presetsCurrent(presets, kind.code) }

    /** Selects a printer and lets the engine pick a compatible filament and process. */
    suspend fun selectPrinter(name: String) = onEngine {
        Native.presetsSelectMachine(presets, name)
        applyBed()
    }

    suspend fun select(printer: String, filament: String, process: String) = onEngine {
        Native.presetsSelect(presets, printer, filament, process)
        applyBed()
    }

    suspend fun printVolume(): PrintVolume = onEngine { volume() }

    /** Re-derives the plates from the printer's (possibly edited) printable area and height. */
    suspend fun refreshBed() = onEngine { applyBed() }

    /** The fully resolved config ({key: serialized value}) slicing will use. */
    suspend fun resolvedConfig(): String = onEngine { Native.presetsResolvedConfig(presets) }

    suspend fun setValue(kind: PresetKind, key: String, value: String) = onEngine {
        Native.presetsSetValue(presets, kind.code, key, value)
    }

    suspend fun resetEdits(kind: PresetKind) = onEngine { Native.presetsResetKind(presets, kind.code) }

    suspend fun saveUserPreset(kind: PresetKind, name: String) = onEngine {
        Native.presetsSaveUser(presets, kind.code, name)
    }

    suspend fun deleteUserPreset(kind: PresetKind, name: String) = onEngine {
        Native.presetsDeleteUser(presets, kind.code, name)
    }

    /** Reloads the preset bundle from [dataDir], e.g. after the installed vendors changed. */
    suspend fun reloadPresets(dataDir: String) = onEngine {
        val fresh = Native.presetsNew(dataDir)
        Native.presetsFree(presets)
        presets = fresh
    }

    // --- document ---

    suspend fun import(path: String): Int = onEngine {
        val v = volume()
        Native.platerImport(plater, path, v.centerX, v.centerY)[1].toInt()
    }

    /** Opens a 3MF as a project, adopting the settings it carries. */
    suspend fun openProject(path: String) = onEngine {
        Native.platerProjectOpen(plater, presets, path)
        applyBed()
    }

    suspend fun scene(): Scene = onEngine {
        val ids = meshCache.keys.toLongArray()
        val revs = LongArray(ids.size) { meshCache.getValue(ids[it]).revision }
        val snap = Native.platerSnapshot(plater, ids, revs)
        val meshes = HashMap<Long, Mesh>(snap.objects.size)
        for (o in snap.objects) {
            val positions = o.positions
            val indices = o.indices
            meshes[o.id] = if (positions != null && indices != null) {
                val volumes = HashMap<Long, TriMesh>()
                for (v in o.volumes) {
                    val vp = v.positions
                    val vi = v.indices
                    if (vp != null && vi != null) volumes[v.id] = TriMesh(vp, vi)
                }
                Mesh(o.meshRevision, positions, indices, volumes)
            } else {
                meshCache.getValue(o.id)
            }
        }
        meshCache.clear()
        meshCache.putAll(meshes)
        Scene(snap, meshes)
    }

    suspend fun setTransform(objectId: Long, instanceId: Long, t: InstanceTransform, label: String) = onEngine {
        Native.platerInstanceSet(plater, objectId, instanceId, t.toArray(), label)
    }

    /** Rotates about a world axis (0 = X, 1 = Y, 2 = Z) through the instance origin. */
    suspend fun rotate(objectId: Long, instanceId: Long, axis: Int, radians: Double) = onEngine {
        Native.platerInstanceRotate(plater, objectId, instanceId, axis, radians)
    }

    suspend fun addInstance(objectId: Long) = onEngine { Native.platerInstanceAdd(plater, objectId) }

    suspend fun deleteInstance(objectId: Long, instanceId: Long, lastInstance: Boolean) = onEngine {
        if (lastInstance) Native.platerObjectDelete(plater, objectId)
        else Native.platerInstanceDelete(plater, objectId, instanceId)
    }

    suspend fun clear() = onEngine { Native.platerClear(plater) }
    suspend fun undo() = onEngine { Native.platerUndo(plater) }
    suspend fun redo() = onEngine { Native.platerRedo(plater) }

    /** Arranges every instance; returns how many did not fit the bed. */
    suspend fun arrange(): Int = onEngine { Native.platerArrange(plater, presets) }

    suspend fun orient(objectId: Long, instanceId: Long) = onEngine {
        Native.platerOrient(plater, presets, objectId, instanceId)
    }

    suspend fun scaleToFit(objectId: Long, instanceId: Long) = onEngine {
        Native.platerScaleToFit(plater, presets, objectId, instanceId)
    }

    /** Rotates so the world-space face normal points down onto the bed. */
    suspend fun layFlat(objectId: Long, instanceId: Long, nx: Double, ny: Double, nz: Double) = onEngine {
        Native.platerLayFlat(plater, objectId, instanceId, nx, ny, nz)
    }

    suspend fun export3mf(path: String) = onEngine { Native.platerExport3mf(plater, path) }

    /** Saves an OrcaSlicer project 3MF: model, plates and the current settings. */
    suspend fun saveProject(path: String) = onEngine { Native.projectSave(plater, presets, path) }

    suspend fun exportStl(objectId: Long?, path: String) = onEngine {
        if (objectId == null) Native.exportStlAll(plater, path) else Native.objectExportStl(plater, objectId, path)
    }

    // --- settings editors ---

    /** Every option definition, as JSON; parse with [org.orcapod.app.settings.OptionDefs]. */
    suspend fun optionDefs(): String = onEngine { Native.optionDefs(null) }

    /** The edited (or, with [edited] false, the selected saved) preset of [kind] as {key: value} JSON. */
    suspend fun presetConfig(kind: PresetKind, edited: Boolean): String = onEngine {
        Native.presetsConfigDump(presets, kind.code, edited)
    }

    suspend fun resetValue(kind: PresetKind, key: String) = onEngine { Native.presetsResetValue(presets, kind.code, key) }

    suspend fun saveUserPreset(kind: PresetKind, name: String, detach: Boolean) = onEngine {
        Native.presetsSaveUserDetached(presets, kind.code, name, detach)
    }

    // --- filament slots and plate type ---

    suspend fun filamentSlots(): List<FilamentSlot> = onEngine {
        Native.presetsFilamentSlots(presets).toList().chunked(2).map { FilamentSlot(it[0], it[1]) }
    }

    suspend fun setFilamentCount(count: Int, colour: String?) = onEngine {
        Native.presetsSetFilamentCount(presets, count, colour)
    }

    suspend fun selectFilamentSlot(slot: Int, name: String) = onEngine { Native.presetsSelectFilamentSlot(presets, slot, name) }
    suspend fun setFilamentColour(slot: Int, colour: String) = onEngine { Native.presetsSetFilamentColour(presets, slot, colour) }

    suspend fun bedType(): BedTypeState = onEngine {
        val v = Native.presetsBedType(presets)
        BedTypeState(v[0], v[1], v[2], v[3] != 0, v[4])
    }

    suspend fun setBedType(type: Int) = onEngine { Native.presetsSetBedType(presets, type) }

    // --- per-object / per-part settings ([volumeId] 0 = the object itself) ---

    suspend fun overrideKeys(forVolume: Boolean): String = onEngine { Native.settingsKeys(forVolume) }
    suspend fun overrides(objectId: Long, volumeId: Long): String = onEngine { Native.settingsDump(plater, objectId, volumeId) }
    suspend fun setOverride(objectId: Long, volumeId: Long, key: String, value: String) = onEngine {
        Native.settingsSet(plater, objectId, volumeId, key, value)
    }
    suspend fun eraseOverride(objectId: Long, volumeId: Long, key: String) = onEngine {
        Native.settingsErase(plater, objectId, volumeId, key)
    }
    /** Assigns filament slot [extruder] (1-based) to the object, or a part when [volumeId] is not 0 (0 = follow the object). */
    suspend fun setExtruder(objectId: Long, volumeId: Long, extruder: Int) = onEngine {
        Native.setExtruder(plater, objectId, volumeId, extruder)
    }

    /** Replaces every override of the target in one undo step (desktop "Paste settings"). */
    suspend fun replaceOverrides(objectId: Long, volumeId: Long, values: Map<String, String>) = onEngine {
        Native.settingsApply(plater, objectId, volumeId, values.keys.toTypedArray(), values.values.toTypedArray())
    }

    // --- objects and parts ---

    suspend fun renameObject(objectId: Long, name: String) = onEngine { Native.objectRename(plater, objectId, name) }

    suspend fun addShapeObject(shape: Shape): Long = onEngine {
        val v = volume()
        Native.addShapeObject(plater, shape.code, maxOf(v.width, v.depth) * 0.1, v.centerX, v.centerY)
    }

    suspend fun addVolumeShape(objectId: Long, instanceId: Long, type: VolumeType, shape: Shape): Long = onEngine {
        val v = volume()
        Native.volumeAddShape(plater, objectId, instanceId, type.code, shape.code, maxOf(v.width, v.depth) * 0.1)
    }

    suspend fun addVolumeFile(objectId: Long, type: VolumeType, path: String): Long = onEngine {
        Native.volumeAddFile(plater, objectId, type.code, path)
    }

    suspend fun setVolumeType(objectId: Long, volumeId: Long, type: VolumeType) = onEngine {
        Native.volumeSetType(plater, objectId, volumeId, type.code)
    }

    suspend fun deleteVolume(objectId: Long, volumeId: Long) = onEngine { Native.volumeDelete(plater, objectId, volumeId) }
    suspend fun renameVolume(objectId: Long, volumeId: Long, name: String) = onEngine {
        Native.volumeRename(plater, objectId, volumeId, name)
    }

    suspend fun splitToObjects(objectId: Long): Int = onEngine { Native.splitObjects(plater, objectId) }

    /** Variable layer height: flat (z, layer height) pairs; empty follows the process layer height. */
    suspend fun heightProfile(objectId: Long): DoubleArray = onEngine { Native.heightProfile(plater, objectId) }

    /** Desktop "Reset": the object follows the process layer height again. */
    suspend fun resetHeightProfile(objectId: Long) = onEngine { Native.setHeightProfile(plater, objectId, DoubleArray(0)) }

    /** Desktop "Adaptive", at [quality] -0.1 (fast) to 1 (fine detail). */
    suspend fun adaptiveHeightProfile(objectId: Long, quality: Double): DoubleArray =
        onEngine { Native.heightProfileAdaptive(plater, presets, objectId, quality) }

    suspend fun smoothHeightProfile(objectId: Long, radius: Int, keepMin: Boolean): DoubleArray =
        onEngine { Native.heightProfileSmooth(plater, presets, objectId, radius, keepMin) }

    /** Desktop manual editing at [z]: [action] 0 increase, 1 decrease, 2 reduce, 3 smooth, over [band] mm. */
    suspend fun adjustHeightProfile(objectId: Long, z: Double, delta: Double, band: Double, action: Int) =
        onEngine { Native.heightProfileAdjust(plater, presets, objectId, z, delta, band, action) }

    /** One paint layer of a volume, as world triangles with their states. */
    suspend fun paint(objectId: Long, volumeId: Long, layer: PaintLayer): PaintMesh = onEngine {
        val r = Native.volumePaint(plater, objectId, volumeId, layer.code)
        PaintMesh(r[0] as FloatArray, r[1] as ByteArray)
    }

    /** One brush dab at a world [hit], seen along [view] (one undo step). */
    suspend fun paintSphere(objectId: Long, volumeId: Long, layer: PaintLayer, hit: FloatArray, view: FloatArray, radius: Double, state: Int) =
        onEngine { Native.volumePaintSphere(plater, objectId, volumeId, layer.code, ray(hit, view), radius, state) }

    /** Smart fill with [angle] > 0, else bucket fill of the hit facet's region. */
    suspend fun paintFill(objectId: Long, volumeId: Long, layer: PaintLayer, hit: FloatArray, view: FloatArray, angle: Double, state: Int) =
        onEngine { Native.volumePaintFill(plater, objectId, volumeId, layer.code, ray(hit, view), angle, angle <= 0.0, state) }

    suspend fun paintReset(objectId: Long, volumeId: Long, layer: PaintLayer) =
        onEngine { Native.volumePaintReset(plater, objectId, volumeId, layer.code) }

    private fun ray(hit: FloatArray, view: FloatArray) =
        doubleArrayOf(hit[0].toDouble(), hit[1].toDouble(), hit[2].toDouble(), view[0].toDouble(), view[1].toDouble(), view[2].toDouble())

    /**
     * Desktop cut: replaces the object by the [keep] result of cutting it
     * through [plane]. Its connectors are drilled into the cut faces; the
     * object's earlier connectors are replaced. Returns the objects produced.
     */
    suspend fun cut(objectId: Long, instanceId: Long, plane: CutPlane, keep: CutKeep, connectors: List<CutConnector>): Int =
        onEngine {
            Native.cutConnectorsClear(plater, objectId)
            for (c in connectors) {
                Native.cutConnectorAdd(
                    plater, objectId, doubleArrayOf(c.x, c.y, c.z, plane.nx, plane.ny, plane.nz),
                    floatArrayOf(c.radius, c.depth, c.radiusTolerance, c.depthTolerance),
                    intArrayOf(c.type.ordinal, c.style.ordinal, c.shape.ordinal),
                )
            }
            Native.objectCut(plater, objectId, instanceId, doubleArrayOf(plane.x, plane.y, plane.z, plane.nx, plane.ny, plane.nz), keep.code)
        }
    suspend fun splitToParts(objectId: Long, volumeId: Long): Int = onEngine { Native.splitParts(plater, objectId, volumeId) }
    suspend fun mirror(objectId: Long, instanceId: Long, axis: Int) = onEngine { Native.instanceMirror(plater, objectId, instanceId, axis) }
    /** [instanceId] 0 applies to every instance of the object. */
    suspend fun setPrintable(objectId: Long, instanceId: Long, printable: Boolean) = onEngine {
        Native.setPrintable(plater, objectId, instanceId, printable)
    }
    suspend fun setInstanceCount(objectId: Long, count: Int) = onEngine { Native.setInstanceCount(plater, objectId, count) }
    suspend fun cloneObject(objectId: Long, count: Int) = onEngine { Native.cloneObject(plater, objectId, count) }
    suspend fun instanceToObject(objectId: Long, instanceId: Long) = onEngine { Native.instanceToObject(plater, objectId, instanceId) }
    suspend fun mergeObjects(objectIds: List<Long>) = onEngine { Native.mergeObjects(plater, objectIds.toLongArray()) }
    /** ORCA_UNITS_*: 0 from inch, 1 to inch, 2 from metre, 3 to metre. */
    suspend fun convertUnits(objectId: Long, conversion: Int) = onEngine { Native.convertUnits(plater, objectId, conversion) }
    suspend fun drop(objectId: Long) = onEngine { Native.objectDrop(plater, objectId) }
    /** Simplifies toward [targetTriangles] triangles; returns the resulting count. */
    suspend fun simplify(objectId: Long, volumeId: Long, targetTriangles: Int): Int = onEngine {
        Native.volumeSimplify(plater, objectId, volumeId, true, targetTriangles, 0.0)
    }
    suspend fun subdivide(objectId: Long, volumeId: Long): Int = onEngine { Native.volumeSubdivide(plater, objectId, volumeId) }
    suspend fun fillBed(objectId: Long): Int = onEngine { Native.fillBed(plater, presets, objectId) }

    // --- height range modifiers ---

    suspend fun heightRanges(objectId: Long): List<HeightRange> = onEngine {
        Native.heightRanges(plater, objectId).toList().chunked(3).map { HeightRange(it[0], it[1], it[2]) }
    }

    suspend fun setHeightRanges(objectId: Long, ranges: List<HeightRange>) = onEngine {
        Native.setHeightRanges(plater, objectId, ranges.flatMap { listOf(it.minZ, it.maxZ, it.layerHeight) }.toDoubleArray())
    }

    // --- plates ---

    suspend fun addPlate() = onEngine { Native.plateAdd(plater) }
    suspend fun deletePlate(plateId: Long) = onEngine { Native.plateDelete(plater, plateId) }
    suspend fun renamePlate(plateId: Long, name: String) = onEngine { Native.plateRename(plater, plateId, name) }
    suspend fun lockPlate(plateId: Long, lock: Boolean) = onEngine { Native.plateLock(plater, plateId, lock) }
    suspend fun selectPlate(plateId: Long) = onEngine { Native.plateSelect(plater, plateId) }
    suspend fun duplicatePlate(plateId: Long) = onEngine { Native.plateDuplicate(plater, plateId) }
    suspend fun movePlateToFront(plateId: Long) = onEngine { Native.plateMoveToFront(plater, plateId) }
    suspend fun plateSettings(plateId: Long): PlateSettings = onEngine { PlateSettings.fromArray(Native.plateSettings(plater, plateId)) }
    suspend fun setPlateSettings(plateId: Long, settings: PlateSettings) = onEngine {
        Native.plateSetSettings(plater, plateId, settings.toArray())
    }

    // --- slicing ---

    suspend fun slice(
        gcodePath: String,
        target: SliceTarget = SliceTarget.PLATE,
        plateIndex: Int = 0,
        listener: ProgressListener,
    ): SliceReport {
        val handle = Native.jobNew()
        job = handle
        try {
            return onEngine { Native.platerSlice(plater, presets, gcodePath, handle, listener, target.code, plateIndex) }
        } finally {
            job = 0
            Native.jobFree(handle)
        }
    }

    /** Cancels a running [slice]; it then throws OrcaException with code CANCELLED. */
    fun cancelSlice() {
        val handle = job
        if (handle != 0L) Native.jobCancel(handle)
    }

    suspend fun loadToolpath(gcodePath: String): Toolpath = onEngine { Native.toolpathLoad(gcodePath) }

    /** The current plate's per-layer custom G-code (desktop layer-slider ticks), by height. */
    suspend fun customGcodes(): List<CustomGcode> = onEngine { Native.customGcodes(plater).toList() }

    suspend fun addCustomGcode(item: CustomGcode) = onEngine {
        Native.customGcodeAdd(plater, item.printZ, item.type.code, item.extruder, item.colour, item.extra)
    }

    suspend fun deleteCustomGcode(printZ: Double) = onEngine { Native.customGcodeDelete(plater, printZ) }

    override fun close() {
        thread.execute {
            Native.platerFree(plater)
            Native.presetsFree(presets)
        }
    }

    private fun volume(): PrintVolume {
        val v = Native.presetsPrintVolume(presets)
        return PrintVolume(v[0], v[1], v[2], v[3], printableHeight())
    }

    private fun printableHeight(): Double =
        Regex("\"printable_height\"\\s*:\\s*\"([0-9.]+)\"").find(Native.presetsResolvedConfig(presets))
            ?.groupValues?.get(1)?.toDoubleOrNull() ?: 250.0

    private fun applyBed() {
        val v = volume()
        Native.platerSetBed(plater, v.minX, v.minY, v.width, v.depth, v.height)
    }

    companion object {
        private val thread = Executors.newSingleThreadExecutor { r -> Thread(null, r, "orca-engine", 64L shl 20) }
        private val dispatcher = thread.asCoroutineDispatcher()

        private suspend fun <T> onEngine(block: () -> T): T = withContext(dispatcher) { block() }

        /** Checks the native library matches the header this app was built against. */
        fun abiMismatch(): String? {
            val lib = Native.abiVersion()
            val header = Native.headerAbiVersion()
            return if (lib == header) null else "Engine ABI $lib does not match the app's ABI $header"
        }

        /** Loads presets from [dataDir] (holding system/ and user/) and opens an empty document. */
        suspend fun open(dataDir: String): Engine = withContext(dispatcher) {
            val presets = Native.presetsNew(dataDir)
            val plater = try {
                Native.platerNew()
            } catch (e: Throwable) {
                Native.presetsFree(presets)
                throw e
            }
            Engine(presets, plater)
        }
    }
}
