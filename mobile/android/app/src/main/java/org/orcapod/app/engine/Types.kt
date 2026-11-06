package org.orcapod.app.engine

// Kotlin mirrors of the bridge's C types (mobile/bridge/engine-cxx/orca_bridge.h).
// The JNI layer constructs these by their primary constructors, so keep the
// parameter lists in sync with app/src/main/cpp/orcapod_jni.cpp.

/** A failed bridge call; [code] is the bridge's ORCA_STATUS_* value. */
class OrcaException(val code: Int, message: String) : Exception(message) {
    companion object {
        const val INVALID_ARGUMENT = 1
        const val NOT_FOUND = 3
        const val CANCELLED = 5
        const val INVALID_MODEL = 7
        const val INVALID_CONFIGURATION = 8
    }
}

enum class PresetKind(val code: Int) { PRINTER(0), FILAMENT(1), PROCESS(2) }

class PresetEntry(
    val name: String,
    val vendor: String,
    val compatible: Boolean,
    /** ORCA_PRESET_ORIGIN_*: 0 system, 1 user, 2 project, 3 external. */
    val origin: Int,
) {
    val isUser: Boolean get() = origin == 1
}

/**
 * Instance placement in engine space (z-up, millimetres, radians).
 * Order matches orca_instance_transform: offset xyz, rotation xyz, scale xyz.
 */
data class InstanceTransform(
    val offsetX: Double, val offsetY: Double, val offsetZ: Double,
    val rotationX: Double, val rotationY: Double, val rotationZ: Double,
    val scaleX: Double, val scaleY: Double, val scaleZ: Double,
) {
    fun toArray() = doubleArrayOf(offsetX, offsetY, offsetZ, rotationX, rotationY, rotationZ, scaleX, scaleY, scaleZ)

    companion object {
        fun fromArray(a: DoubleArray) = InstanceTransform(a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7], a[8])
    }
}

class SceneInstance(
    val id: Long,
    transform: DoubleArray,
    /** The engine's instance matrix, column-major; the renderer draws from it. */
    val matrix: FloatArray,
    val mirrored: Boolean,
    val printable: Boolean,
    /** Index into [SceneSnapshot.plates], or -1 when off every plate. */
    val plate: Int,
) {
    val transform = InstanceTransform.fromArray(transform)
}

class SceneObject(
    val id: Long,
    val meshRevision: Long,
    val name: String,
    /** xyz triples in object space; null when the caller already holds this mesh revision. */
    val positions: FloatArray?,
    val indices: IntArray?,
    val bboxMin: FloatArray,
    val bboxMax: FloatArray,
    val instances: Array<SceneInstance>,
)

class Plate(
    val id: Long,
    val index: Int,
    val name: String,
    val origin: FloatArray,
    val size: FloatArray,
    val objectCount: Int,
)

class SceneSnapshot(
    val revision: Long,
    val objects: Array<SceneObject>,
    val plates: Array<Plate>,
    val currentPlate: Int,
    val undoCount: Int,
    val redoCount: Int,
) {
    companion object {
        val EMPTY = SceneSnapshot(0, emptyArray(), emptyArray(), 0, 0, 0)
    }
}

/** Engine print volume rectangle (engine millimetres) and its usable height. */
data class PrintVolume(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double, val height: Double) {
    val width get() = maxX - minX
    val depth get() = maxY - minY
    val centerX get() = (minX + maxX) / 2
    val centerY get() = (minY + maxY) / 2
}

class SliceWarning(val level: Int, val code: String, val message: String, val detail: String) {
    val text: String get() = listOf(message.ifEmpty { code }, detail).filter { it.isNotBlank() }.joinToString(": ")
}

class SliceReport(val timeEstimateSeconds: Double, val gcodeBytes: Long, val warnings: Array<SliceWarning>)

fun interface ProgressListener {
    fun onProgress(percent: Int, message: String)
}

/**
 * Extrusion toolpath of a G-code file for the layer preview. Segment `i` runs
 * from positions[6i..6i+2] to positions[6i+3..6i+5] (engine mm) with
 * extrusion role roles[i]; layer `l` covers segments
 * [layerFirst[l], layerFirst[l] + layerCount[l]).
 */
class Toolpath(
    val positions: FloatArray,
    val roles: ByteArray,
    val layerZ: FloatArray,
    val layerFirst: IntArray,
    val layerCount: IntArray,
    val estimatedSeconds: Float,
    /** Filament used by the first extruder, mm³. */
    val filamentVolume: Double,
    val filamentDensity: Float,
    val filamentDiameter: Float,
) {
    val segmentCount get() = roles.size
    val layerTotal get() = layerZ.size
    val filamentGrams get() = filamentVolume / 1000.0 * filamentDensity
    val filamentMetres get() =
        if (filamentDiameter > 0) filamentVolume / (Math.PI * filamentDiameter * filamentDiameter / 4) / 1000.0 else 0.0
}
