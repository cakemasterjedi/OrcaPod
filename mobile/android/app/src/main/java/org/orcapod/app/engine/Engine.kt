package org.orcapod.app.engine

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.concurrent.Executors

/** An object's mesh in object space, shared across snapshots while its revision holds. */
class Mesh(val revision: Long, val positions: FloatArray, val indices: IntArray)

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
                Mesh(o.meshRevision, positions, indices)
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

    // --- slicing ---

    suspend fun slice(gcodePath: String, listener: ProgressListener): SliceReport {
        val handle = Native.jobNew()
        job = handle
        try {
            return onEngine { Native.platerSlice(plater, presets, gcodePath, handle, listener) }
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
