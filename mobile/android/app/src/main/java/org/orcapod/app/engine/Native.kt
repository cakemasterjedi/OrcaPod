package org.orcapod.app.engine

/**
 * Raw JNI entry points over the bridge's C API. Handles are native pointers
 * passed as Long. Every call that can fail throws [OrcaException]. Callers go
 * through [Engine], which keeps engine work off the main thread.
 */
internal object Native {
    init {
        System.loadLibrary("orcapod_jni")
    }

    external fun abiVersion(): Int
    external fun headerAbiVersion(): Int

    // --- presets ---
    external fun presetsNew(dataDir: String): Long
    external fun presetsFree(presets: Long)
    external fun presetsList(presets: Long, kind: Int): Array<PresetEntry>
    external fun presetsSelectMachine(presets: Long, machine: String)
    external fun presetsSelect(presets: Long, machine: String, filament: String, process: String)
    external fun presetsCurrent(presets: Long, kind: Int): String
    /** minX, minY, maxX, maxY. */
    external fun presetsPrintVolume(presets: Long): DoubleArray
    external fun presetsResolvedConfig(presets: Long): String
    external fun presetsSetValue(presets: Long, kind: Int, key: String, value: String)
    external fun presetsResetKind(presets: Long, kind: Int)
    external fun presetsSaveUser(presets: Long, kind: Int, name: String)
    external fun presetsDeleteUser(presets: Long, kind: Int, name: String)

    // --- plater (the document) ---
    external fun platerNew(): Long
    external fun platerFree(plater: Long)
    external fun platerSetBed(plater: Long, x: Double, y: Double, w: Double, d: Double, h: Double)
    /** Returns the first imported object id and the object count. */
    external fun platerImport(plater: Long, path: String, cx: Double, cy: Double): LongArray
    external fun platerProjectOpen(plater: Long, presets: Long, path: String)
    /**
     * Snapshot of the document. Objects whose (id, mesh revision) pair is in
     * [knownIds]/[knownRevisions] come back without positions and indices.
     */
    external fun platerSnapshot(plater: Long, knownIds: LongArray, knownRevisions: LongArray): SceneSnapshot
    external fun platerInstanceSet(plater: Long, objectId: Long, instanceId: Long, transform: DoubleArray, label: String)
    external fun platerInstanceRotate(plater: Long, objectId: Long, instanceId: Long, axis: Int, radians: Double)
    external fun platerInstanceAdd(plater: Long, objectId: Long)
    external fun platerInstanceDelete(plater: Long, objectId: Long, instanceId: Long)
    external fun platerObjectDelete(plater: Long, objectId: Long)
    external fun platerClear(plater: Long)
    external fun platerUndo(plater: Long)
    external fun platerRedo(plater: Long)
    /** Returns the number of instances that did not fit. */
    external fun platerArrange(plater: Long, presets: Long): Int
    external fun platerOrient(plater: Long, presets: Long, objectId: Long, instanceId: Long)
    external fun platerScaleToFit(plater: Long, presets: Long, objectId: Long, instanceId: Long)
    external fun platerLayFlat(plater: Long, objectId: Long, instanceId: Long, nx: Double, ny: Double, nz: Double)
    external fun platerExport3mf(plater: Long, path: String)

    // --- slicing ---
    external fun jobNew(): Long
    external fun jobCancel(job: Long)
    external fun jobFree(job: Long)
    external fun platerSlice(plater: Long, presets: Long, path: String, job: Long, listener: ProgressListener): SliceReport
    external fun toolpathLoad(gcodePath: String): Toolpath
}
