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

/** Volume roles (ORCA_VOLUME_*): desktop object list parts and modifiers. */
enum class VolumeType(val code: Int, val label: String) {
    PART(0, "Part"),
    NEGATIVE(1, "Negative part"),
    MODIFIER(2, "Modifier"),
    SUPPORT_BLOCKER(3, "Support blocker"),
    SUPPORT_ENFORCER(4, "Support enforcer");

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code } ?: PART
    }
}

/** Built-in shapes (ORCA_SHAPE_*); SLAB is only valid for volumes. */
enum class Shape(val code: Int, val label: String) {
    CUBE(0, "Cube"), CYLINDER(1, "Cylinder"), SPHERE(2, "Sphere"), CONE(3, "Cone"),
    DISC(4, "Disc"), TORUS(5, "Torus"), SLAB(6, "Slab"),
}

class SceneVolume(
    val id: Long,
    val name: String,
    typeCode: Int,
    /** The volume's "extruder" option, 1-based; 0 follows the object. */
    val extruder: Int,
    val bboxMin: FloatArray,
    val bboxMax: FloatArray,
    /** Object-space mesh of a non-part volume; null for parts or when the caller holds this revision. */
    val positions: FloatArray?,
    val indices: IntArray?,
    /** Parts only: the triangle range inside the object's merged mesh. */
    val mergedTriangleStart: Int,
    val mergedTriangleCount: Int,
    val overrideCount: Int,
) {
    val type = VolumeType.of(typeCode)
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
    /** Document order; at least one model part. */
    val volumes: Array<SceneVolume>,
    /** Per-object setting overrides. */
    val overrideCount: Int,
    /** Object filament, 1-based; 0 means the first. */
    val extruder: Int,
) {
    val parts get() = volumes.filter { it.type == VolumeType.PART }
}

class Plate(
    val id: Long,
    val index: Int,
    val name: String,
    val origin: FloatArray,
    val size: FloatArray,
    val objectCount: Int,
    val locked: Boolean,
)

class SceneSnapshot(
    val revision: Long,
    val objects: Array<SceneObject>,
    val plates: Array<Plate>,
    val currentPlate: Int,
    val undoCount: Int,
    val redoCount: Int,
    val undoLabel: String,
    val redoLabel: String,
) {
    companion object {
        val EMPTY = SceneSnapshot(0, emptyArray(), emptyArray(), 0, 0, 0, "", "")
    }
}

/** A filament slot of the project: its preset and colour (#rrggbb). */
data class FilamentSlot(val preset: String, val colour: String)

/** Plate type state of the selected printer (values are engine BedType ordinals). */
class BedTypeState(val current: Int, val machineDefault: Int, val count: Int, val selectable: Boolean, val supportedMask: Int) {
    fun supported(type: Int) = type in 1 until count && (supportedMask and (1 shl type)) != 0

    companion object {
        /** Engine BedType names, by ordinal (btDefault = 0 is "follow the printer"). */
        val NAMES = listOf(
            "Printer default", "Smooth Cool Plate", "Engineering Plate", "Smooth High Temp Plate",
            "Textured PEI Plate", "Textured Cool Plate", "Cool Plate (SuperTack)",
        )
    }
}

/** One layer-height range of an object (desktop height range modifier). */
data class HeightRange(val minZ: Double, val maxZ: Double, val layerHeight: Double)

/**
 * A plate's own print settings (desktop plate settings dialog); null fields
 * follow the project. Sequences list 1-based filament slots.
 */
data class PlateSettings(
    val bedType: Int? = null,
    val printByObject: Boolean? = null,
    val spiralMode: Boolean? = null,
    val firstLayerOrder: List<Int> = emptyList(),
    val otherLayerOrder: List<Int> = emptyList(),
) {
    fun toArray(): IntArray {
        val v = ArrayList<Int>()
        v += bedType ?: -1
        v += printByObject?.let { if (it) 1 else 0 } ?: -1
        v += spiralMode?.let { if (it) 1 else 0 } ?: -1
        v += firstLayerOrder.size; v += firstLayerOrder
        v += otherLayerOrder.size; v += otherLayerOrder
        return v.toIntArray()
    }

    companion object {
        fun fromArray(a: IntArray): PlateSettings {
            var i = 0
            fun next() = if (i < a.size) a[i++] else 0
            val bed = next()
            val seq = next()
            val spiral = next()
            val first = List(next().coerceIn(0, 16)) { next() }
            val other = List(next().coerceIn(0, 16)) { next() }
            return PlateSettings(
                bedType = bed.takeIf { it >= 0 },
                printByObject = seq.takeIf { it >= 0 }?.let { it == 1 },
                spiralMode = spiral.takeIf { it >= 0 }?.let { it == 1 },
                firstLayerOrder = first,
                otherLayerOrder = other,
            )
        }
    }
}

/** What a slice produces (see orcapod_jni.cpp platerSlice). */
enum class SliceTarget(val code: Int) { PLATE(0), ALL_PLATES(1), SLICED_3MF(2), SLICED_3MF_PLATE(3) }

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
