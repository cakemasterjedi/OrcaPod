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

    // --- STEP import ---
    external fun stepNew(path: String): Long
    /** Reads the file; returns has-original-names (0/1) and has-multi-body-compound (0/1). */
    external fun stepLoad(step: Long): LongArray
    external fun stepFree(step: Long)
    /** Stops a running [stepLoad], [stepTriangleCount] or [stepMesh]; callable from any thread. */
    external fun stepCancel(step: Long)
    /** How far the running load or mesh is, 0..100; callable from any thread. */
    external fun stepProgress(step: Long): Int
    external fun stepTriangleCount(step: Long, linear: Double, angle: Double): Long
    external fun stepMesh(
        step: Long, linear: Double, angle: Double,
        splitCompound: Boolean, keepNames: Boolean, asAssembly: Boolean, sortNames: Boolean,
    )
    /** JSON array of [original, cleaned] name pairs; a null replacement is "_", "" removes. */
    external fun stepNameChanges(step: Long, replacement: String?): String
    /** Returns the first imported object id and the object count. */
    external fun platerImportStep(plater: Long, step: Long, replacement: String?, cx: Double, cy: Double): LongArray

    // --- slicing ---
    external fun jobNew(): Long
    external fun jobCancel(job: Long)
    external fun jobFree(job: Long)
    /**
     * [target] is a [SliceTarget] code; [plateIndex] and a non-null [machine] (slice for that
     * printer preset instead of the selected one) are used by SLICED_3MF_PLATE.
     */
    external fun platerSlice(
        plater: Long, presets: Long, path: String, job: Long, listener: ProgressListener, target: Int, plateIndex: Int,
        machine: String?,
    ): SliceReport
    /** At most about [maxLines] lines: beyond that, runs of short moves are joined. */
    external fun toolpathLoad(gcodePath: String, maxLines: Int): Toolpath
    external fun customGcodes(plater: Long): Array<CustomGcode>
    external fun customGcodeAdd(plater: Long, z: Double, type: Int, extruder: Int, colour: String, extra: String)
    external fun customGcodeDelete(plater: Long, z: Double)

    // --- settings editors ---
    /** Option definitions as JSON ({key: def}); null keys = every option. */
    external fun optionDefs(keysJson: String?): String
    external fun presetsConfigDump(presets: Long, kind: Int, edited: Boolean): String
    external fun presetsResetValue(presets: Long, kind: Int, key: String)
    external fun presetsSaveUserDetached(presets: Long, kind: Int, name: String, detach: Boolean)

    // --- filament slots and plate type ---
    /** [name0, colour0, name1, colour1, ...]. */
    external fun presetsFilamentSlots(presets: Long): Array<String>
    external fun presetsSetFilamentCount(presets: Long, count: Int, colour: String?)
    external fun presetsSelectFilamentSlot(presets: Long, slot: Int, name: String)
    external fun presetsSetFilamentColour(presets: Long, slot: Int, colour: String)
    /** [current, machine default, count, selectable, supported mask]. */
    external fun presetsBedType(presets: Long): IntArray
    external fun presetsSetBedType(presets: Long, bedType: Int)

    // --- per-object / per-part settings (volume 0 = the object) ---
    external fun settingsKeys(forVolume: Boolean): String
    external fun settingsDump(plater: Long, objectId: Long, volumeId: Long): String
    external fun settingsSet(plater: Long, objectId: Long, volumeId: Long, key: String, value: String)
    external fun settingsErase(plater: Long, objectId: Long, volumeId: Long, key: String)
    /** Filament slot (1-based) of an object or part; 0 = follow the object / the first slot. */
    external fun setExtruder(plater: Long, objectId: Long, volumeId: Long, extruder: Int)
    external fun settingsApply(plater: Long, objectId: Long, volumeId: Long, keys: Array<String>, values: Array<String>)

    // --- objects and parts ---
    external fun objectRename(plater: Long, objectId: Long, name: String)
    external fun addShapeObject(plater: Long, shape: Int, size: Double, cx: Double, cy: Double): Long
    external fun volumeAddShape(plater: Long, objectId: Long, instanceId: Long, type: Int, shape: Int, size: Double): Long
    external fun volumeAddFile(plater: Long, objectId: Long, type: Int, path: String): Long
    external fun volumeSetType(plater: Long, objectId: Long, volumeId: Long, type: Int)
    external fun volumeDelete(plater: Long, objectId: Long, volumeId: Long)
    external fun volumeRename(plater: Long, objectId: Long, volumeId: Long, name: String)
    external fun splitObjects(plater: Long, objectId: Long): Int
    external fun heightProfile(plater: Long, objectId: Long): DoubleArray
    external fun setHeightProfile(plater: Long, objectId: Long, pairs: DoubleArray)
    external fun heightProfileAdaptive(plater: Long, presets: Long, objectId: Long, quality: Double): DoubleArray
    external fun heightProfileSmooth(plater: Long, presets: Long, objectId: Long, radius: Int, keepMin: Boolean): DoubleArray
    external fun heightProfileAdjust(plater: Long, presets: Long, objectId: Long, z: Double, delta: Double, band: Double, action: Int)
    external fun calibrationStart(
        plater: Long, presets: Long, ints: IntArray, doubles: DoubleArray,
        shaper: String, speeds: DoubleArray, accels: DoubleArray, model: String?,
    )
    external fun calibration(plater: Long): DoubleArray
    external fun calibrationClear(plater: Long)
    external fun measureCreate(plater: Long, objectId: Long): Long
    external fun measureFree(measure: Long)
    external fun measurePick(measure: Long, face: Int, x: Double, y: Double, z: Double): DoubleArray?
    external fun measureBetween(measure: Long, a: DoubleArray, b: DoubleArray): DoubleArray
    external fun volumePaint(plater: Long, objectId: Long, volumeId: Long, layer: Int): Array<Any>
    external fun volumePaintSphere(plater: Long, objectId: Long, volumeId: Long, layer: Int, ray: DoubleArray, radius: Double, state: Int)
    external fun volumePaintStroke(plater: Long, objectId: Long, volumeId: Long, layer: Int, hits: DoubleArray, views: DoubleArray, radius: Double, state: Int, continueStroke: Boolean): Long
    external fun volumePaintFill(plater: Long, objectId: Long, volumeId: Long, layer: Int, ray: DoubleArray, angle: Double, propagate: Boolean, state: Int)
    external fun volumePaintReset(plater: Long, objectId: Long, volumeId: Long, layer: Int)
    external fun objectCut(plater: Long, objectId: Long, instanceId: Long, plane: DoubleArray, keep: Int): Int
    external fun cutConnectorAdd(plater: Long, objectId: Long, geometry: DoubleArray, sizes: FloatArray, kinds: IntArray)
    external fun cutConnectorsClear(plater: Long, objectId: Long)
    external fun splitParts(plater: Long, objectId: Long, volumeId: Long): Int
    external fun meshErrors(plater: Long, objectId: Long, volumeId: Long): IntArray
    external fun repair(plater: Long, objectId: Long, volumeId: Long, keepPainting: Boolean): RepairReport
    external fun meshBoolean(plater: Long, objectId: Long, sourceVolumeId: Long, toolVolumeId: Long, operation: Int,
                             deleteTool: Boolean, keepPainting: Boolean): Long
    external fun instanceMirror(plater: Long, objectId: Long, instanceId: Long, axis: Int)
    external fun setPrintable(plater: Long, objectId: Long, instanceId: Long, printable: Boolean)
    external fun setInstanceCount(plater: Long, objectId: Long, count: Int)
    external fun cloneObject(plater: Long, objectId: Long, count: Int)
    external fun instanceToObject(plater: Long, objectId: Long, instanceId: Long)
    external fun mergeObjects(plater: Long, objectIds: LongArray)
    external fun convertUnits(plater: Long, objectId: Long, conversion: Int)
    external fun objectDrop(plater: Long, objectId: Long)
    external fun volumeSimplify(plater: Long, objectId: Long, volumeId: Long, useCount: Boolean, count: Int, maxError: Double): Int
    external fun volumeSubdivide(plater: Long, objectId: Long, volumeId: Long): Int
    external fun fillBed(plater: Long, presets: Long, objectId: Long): Int
    external fun objectExportStl(plater: Long, objectId: Long, path: String)
    external fun exportStlAll(plater: Long, path: String)
    /** A non-null [machine] writes the project for that printer instead of the selected one. */
    external fun projectSave(plater: Long, presets: Long, path: String, thumbnailSize: Int, thumbnail: ByteArray?, machine: String?)

    // --- height range modifiers: flat (min z, max z, layer height) triples ---
    external fun heightRanges(plater: Long, objectId: Long): DoubleArray
    external fun setHeightRanges(plater: Long, objectId: Long, flat: DoubleArray)

    // --- plates ---
    external fun plateAdd(plater: Long)
    external fun plateDelete(plater: Long, plateId: Long)
    external fun plateRename(plater: Long, plateId: Long, name: String)
    external fun plateLock(plater: Long, plateId: Long, lock: Boolean)
    external fun plateSelect(plater: Long, plateId: Long)
    external fun plateDuplicate(plater: Long, plateId: Long)
    external fun plateMoveToFront(plater: Long, plateId: Long)
    external fun plateSettings(plater: Long, plateId: Long): IntArray
    external fun plateSetSettings(plater: Long, plateId: Long, values: IntArray)
}
