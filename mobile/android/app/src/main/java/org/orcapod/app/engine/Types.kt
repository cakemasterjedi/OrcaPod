package org.orcapod.app.engine

// Kotlin mirrors of the bridge's C types (mobile/bridge/engine-cxx/orca_bridge.h).
// The JNI layer constructs these by their primary constructors, so keep the
// parameter lists in sync with app/src/main/cpp/orcapod_jni.cpp.

/**
 * A failed engine call; [code] is the bridge's ORCA_STATUS_* value. [diagnostic] is the engine's text, which starts with
 * the bridge function's name (`orca_…: what went wrong`); [message] drops
 * that name so the text can be shown to people.
 */
class OrcaException(val code: Int, val diagnostic: String) : Exception(readable(diagnostic)) {
    override fun toString() = "OrcaException($code): $diagnostic"

    companion object {
        private val OPERATION = Regex("^orca_[a-z0-9_]+:\\s*")

        fun readable(diagnostic: String): String =
            diagnostic.replace(OPERATION, "").replaceFirstChar { it.uppercaseChar() }

        const val INVALID_ARGUMENT = 1
        const val NOT_FOUND = 3
        const val CANCELLED = 5
        const val INVALID_MODEL = 7
        const val INVALID_CONFIGURATION = 8
    }
}

/**
 * Desktop STEP import options (StepMeshDialog); ranges and defaults are
 * the bridge's ORCA_STEP_* values.
 */
data class StepImportOptions(
    /** Chordal deflection, mm. */
    val linearDeflection: Double = LINEAR_DEFAULT,
    /** Angular deflection, radians. */
    val angleDeflection: Double = ANGLE_DEFAULT,
    /** Each body of a multi-body part becomes its own part. */
    val splitCompound: Boolean = true,
    /** Parts take the body names stored in the file. */
    val keepNames: Boolean = true,
    /** Load as an assembly; false loads a single object whose parts are hidden. */
    val asAssembly: Boolean = true,
    /** Orders the parts in the object list by name (natural order); needs [keepNames]. */
    val sortNames: Boolean = false,
) {
    companion object {
        const val LINEAR_DEFAULT = 0.003
        const val LINEAR_MIN = 0.001
        const val LINEAR_MAX = 0.1
        const val ANGLE_DEFAULT = 0.5
        const val ANGLE_MIN = 0.01
        const val ANGLE_MAX = 1.0
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
 * Parsed G-code for the preview: every extrusion, travel and wipe as a line
 * segment in file order, grouped by layer, plus point markers (retractions,
 * seams, tool and colour changes, pauses, custom G-code). Layer `l` covers
 * segments [layerFirst[l], layerFirst[l] + layerCount[l]).
 */
class Toolpath(
    /** Six floats per segment: start and end, engine millimetres. */
    val positions: FloatArray,
    /** Engine EMoveType per segment: [MOVE_TRAVEL], [MOVE_WIPE] or [MOVE_EXTRUDE]. */
    val moveTypes: ByteArray,
    /** Engine ExtrusionRole per segment. */
    val roles: ByteArray,
    /** 0-based extruder per segment. */
    val extruders: ByteArray,
    /** [Attr.COUNT] floats per segment, in [Attr] order. */
    val attrs: FloatArray,
    /** Estimated time of each segment, seconds. */
    val times: FloatArray,
    val layerZ: FloatArray,
    val layerFirst: IntArray,
    val layerCount: IntArray,
    val layerTime: FloatArray,
    /** Three floats per marker. */
    val pointPositions: FloatArray,
    /** Engine EMoveType per marker (1..7). */
    val pointTypes: ByteArray,
    /** Layer index of each marker. */
    val pointLayers: IntArray,
    /** [normal mode, silent mode] total time, seconds; silent is 0 when the same. */
    val totals: FloatArray,
    /** Filament used per extruder, mm³. */
    val volumes: DoubleArray,
    val densities: FloatArray,
    val diameters: FloatArray,
    /** Filament cost per kg, per extruder. */
    val costs: FloatArray,
) {
    enum class Attr { SPEED, ACTUAL_SPEED, HEIGHT, WIDTH, FAN, TEMPERATURE, FLOW, ACTUAL_FLOW, LAYER_TIME, ACCELERATION, JERK, PRESSURE_ADVANCE;
        companion object { val COUNT = entries.size }
    }

    val segmentCount get() = moveTypes.size
    val layerTotal get() = layerZ.size
    val estimatedSeconds get() = totals[0]
    val silentSeconds get() = totals[1]

    fun attr(segment: Int, a: Attr) = attrs[segment * Attr.COUNT + a.ordinal]
    fun move(segment: Int) = moveTypes[segment].toInt() and 0xFF
    fun role(segment: Int) = roles[segment].toInt() and 0xFF

    fun grams(extruder: Int) = volumes[extruder] / 1000.0 * densities.getOrElse(extruder) { 0f }
    fun metres(extruder: Int): Double {
        val d = diameters.getOrElse(extruder) { 0f }
        return if (d > 0) volumes[extruder] / (Math.PI * d * d / 4) / 1000.0 else 0.0
    }
    fun cost(extruder: Int) = grams(extruder) / 1000.0 * costs.getOrElse(extruder) { 0f }

    val filamentGrams get() = volumes.indices.sumOf { grams(it) }
    val filamentMetres get() = volumes.indices.sumOf { metres(it) }
    val filamentCost get() = volumes.indices.sumOf { cost(it) }

    companion object {
        const val MOVE_RETRACT = 1
        const val MOVE_UNRETRACT = 2
        const val MOVE_SEAM = 3
        const val MOVE_TOOL_CHANGE = 4
        const val MOVE_COLOR_CHANGE = 5
        const val MOVE_PAUSE = 6
        const val MOVE_CUSTOM = 7
        const val MOVE_TRAVEL = 8
        const val MOVE_WIPE = 9
        const val MOVE_EXTRUDE = 10
    }
}

/** Desktop layer-slider tick types (CustomGCode::Type through the bridge). */
enum class CustomGcodeType(val code: Int) {
    COLOR_CHANGE(0), PAUSE(1), TOOL_CHANGE(2), TEMPLATE(3), CUSTOM(4);
    companion object { fun of(code: Int) = entries.firstOrNull { it.code == code } ?: CUSTOM }
}

/** One per-layer custom G-code: applied from the first layer printed at [printZ]. */
data class CustomGcode(val printZ: Double, val type: CustomGcodeType, val extruder: Int, val colour: String, val extra: String) {
    /** JNI constructor. */
    constructor(printZ: Double, type: Int, extruder: Int, colour: String, extra: String) :
        this(printZ, CustomGcodeType.of(type), extruder, colour, extra)
}

/** A cut plane through [x], [y], [z] (engine world mm) with normal ([nx], [ny], [nz]). */
data class CutPlane(val x: Double, val y: Double, val z: Double, val nx: Double, val ny: Double, val nz: Double)

/** What the cut keeps (orca_plater_object_cut). */
enum class CutKeep(val code: Int, val label: String) {
    UPPER(0, "Upper part"), LOWER(1, "Lower part"), PARTS(2, "Both, as parts"), OBJECTS(3, "Both, as objects")
}

/** Desktop CutConnectorType / Style / Shape, in engine order. */
enum class ConnectorType(val label: String) { PLUG("Plug"), DOWEL("Dowel"), SNAP("Snap") }
enum class ConnectorStyle(val label: String) { PRISM("Prism"), FRUSTUM("Frustum") }
enum class ConnectorShape(val label: String) { TRIANGLE("Triangle"), SQUARE("Square"), HEXAGON("Hexagon"), CIRCLE("Circle") }

/**
 * One cut connector. Like desktop's, its position is the world point on the
 * cut plane minus the instance offset; sizes are millimetres.
 */
data class CutConnector(
    val x: Double, val y: Double, val z: Double,
    val radius: Float, val depth: Float,
    val radiusTolerance: Float, val depthTolerance: Float,
    val type: ConnectorType, val style: ConnectorStyle, val shape: ConnectorShape,
)

/** Desktop painter gizmos (ORCA_PAINT_*). */
enum class PaintLayer(val code: Int, val label: String) {
    SUPPORT(0, "Supports"), SEAM(1, "Seams"), MMU(2, "Colour"), FUZZY(3, "Fuzzy skin")
}

/** Painted triangles of one layer: nine world floats and one state per triangle. */
class PaintMesh(val positions: FloatArray, val states: ByteArray) {
    val count get() = states.size
}

/** Desktop measure feature kinds (ORCA_MEASURE_*). */
enum class FeatureType(val label: String) { POINT("Point"), EDGE("Edge"), CIRCLE("Circle"), PLANE("Plane") }

/**
 * A surface feature picked by the measure tool: a point, an edge (a to b), a
 * circle (centre a, normal, radius) or a plane (anchor a, normal, area, index).
 */
class SurfaceFeature(
    val type: FeatureType,
    val a: DoubleArray,
    val b: DoubleArray,
    val normal: DoubleArray,
    val radius: Double,
    val area: Double,
    val planeIndex: Int,
) {
    /** The feature through a column-major instance [matrix], as desktop measures in world space. */
    fun transformed(matrix: FloatArray): SurfaceFeature {
        fun linear(v: DoubleArray) = DoubleArray(3) { r -> matrix[r] * v[0] + matrix[4 + r] * v[1] + matrix[8 + r] * v[2] }
        fun point(p: DoubleArray) = linear(p).also { for (r in 0 until 3) it[r] += matrix[12 + r] }
        fun norm(v: DoubleArray) = kotlin.math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        // Normals go through the cofactor matrix (the inverse transpose times the
        // determinant), whose length on a unit normal is also the area scale.
        val col = Array(3) { c -> DoubleArray(3) { r -> matrix[c * 4 + r].toDouble() } }
        fun cross(x: DoubleArray, y: DoubleArray) = doubleArrayOf(x[1] * y[2] - x[2] * y[1], x[2] * y[0] - x[0] * y[2], x[0] * y[1] - x[1] * y[0])
        val cof = arrayOf(cross(col[1], col[2]), cross(col[2], col[0]), cross(col[0], col[1]))
        val n = DoubleArray(3) { r -> cof[0][r] * normal[0] + cof[1][r] * normal[1] + cof[2][r] * normal[2] }
        val areaScale = norm(n)
        if (areaScale > 0) for (k in 0 until 3) n[k] /= areaScale
        // A circle's radius scales with the instance in the circle's plane.
        val radiusScale = if (type == FeatureType.CIRCLE) {
            val ref = if (kotlin.math.abs(normal[0]) < 0.9) doubleArrayOf(1.0, 0.0, 0.0) else doubleArrayOf(0.0, 1.0, 0.0)
            val inPlane = cross(normal, ref)
            norm(linear(inPlane)) / norm(inPlane)
        } else 1.0
        return SurfaceFeature(
            type, point(a), if (type == FeatureType.EDGE) point(b) else b.copyOf(), n,
            radius * radiusScale, if (type == FeatureType.PLANE) area * areaScale else area, planeIndex,
        )
    }

    internal fun values(): DoubleArray = doubleArrayOf(
        type.ordinal.toDouble(), a[0], a[1], a[2], b[0], b[1], b[2], normal[0], normal[1], normal[2],
        radius, area, planeIndex.toDouble(), 0.0, 0.0,
    )

    companion object {
        internal fun of(v: DoubleArray) = SurfaceFeature(
            FeatureType.entries[v[0].toInt()], v.copyOfRange(1, 4), v.copyOfRange(4, 7), v.copyOfRange(7, 10),
            v[10], v[11], v[12].toInt(),
        )
    }
}

/** Desktop's measurement between two features; each part is null when the engine has none for the pair. */
class Measurement(
    val from: DoubleArray?,
    val to: DoubleArray?,
    val distance: Double?,
    val xyz: DoubleArray?,
    val angleDegrees: Double?,
) {
    companion object {
        internal fun of(v: DoubleArray) = Measurement(
            from = if (v[11] != 0.0) v.copyOfRange(0, 3) else null,
            to = if (v[11] != 0.0) v.copyOfRange(3, 6) else null,
            distance = if (v[11] != 0.0) v[6] else null,
            xyz = if (v[12] != 0.0) v.copyOfRange(7, 10) else null,
            angleDegrees = if (v[13] != 0.0) Math.toDegrees(v[10]) else null,
        )
    }
}

/**
 * Desktop's Calibration menu tests the engine sets up: the engine mode
 * (CalibMode), the model shipped in assets/calib, the dialog's default range
 * and its unit. Flow rate passes are separate entries, as in the menu.
 */
enum class CalibrationTest(
    val label: String,
    val mode: Int,
    val model: String,
    val start: Double,
    val end: Double,
    val step: Double,
    val unit: String,
    val flowPass: Int = 0,
    val flowLinear: Boolean = false,
    val flowPattern: Int = 0,
) {
    TEMPERATURE("Temperature tower", 6, "temperature_tower.3mf", 230.0, 190.0, 5.0, "°C"),
    FLOW_PASS_1("Flow rate: pass 1", 5, "flowrate_pass1.3mf", 0.0, 0.0, 0.0, "", flowPass = 1),
    FLOW_PASS_2("Flow rate: pass 2", 5, "flowrate_pass2.3mf", 0.0, 0.0, 0.0, "", flowPass = 2),
    FLOW_YOLO("Flow rate: YOLO", 5, "flowrate_yolo.3mf", 0.0, 0.0, 0.0, "", flowPass = 1, flowLinear = true),
    FLOW_YOLO_FINE("Flow rate: YOLO (perfectionist)", 5, "flowrate_yolo_fine.3mf", 0.0, 0.0, 0.0, "", flowPass = 2, flowLinear = true),
    PA_TOWER("Pressure advance: tower", 3, "pa_tower.3mf", 0.0, 0.1, 0.002, ""),
    PA_LINE("Pressure advance: line", 1, "pa_line.3mf", 0.0, 0.1, 0.002, ""),
    VOLUMETRIC_SPEED("Max volumetric speed", 7, "volumetric_speed.3mf", 5.0, 20.0, 0.5, "mm³/s"),
    VFA("VFA", 8, "vfa.3mf", 40.0, 200.0, 10.0, "mm/s"),
    RETRACTION("Retraction test", 9, "retraction_tower.3mf", 0.0, 2.0, 0.1, "mm"),
    PA_PATTERN("Pressure advance: pattern", 2, "", 0.0, 0.08, 0.005, ""),
    SHAPING_FREQUENCY("Input shaping: frequency", 10, "ringing_tower.3mf", 15.0, 110.0, 0.0, "Hz"),
    SHAPING_DAMPING("Input shaping: damping", 11, "ringing_tower.3mf", 0.0, 0.4, 0.0, ""),
    CORNERING("Cornering", 12, "ringing_tower.3mf", 1.0, 15.0, 0.0, "mm/s");

    val hasRange get() = mode != 5
    val isFlow get() = mode == 5
    val isPa get() = this == PA_TOWER || this == PA_LINE || this == PA_PATTERN
    val isMotion get() = this == SHAPING_FREQUENCY || this == SHAPING_DAMPING || this == CORNERING
    /** Desktop asks for a step only for the stepped towers and patterns. */
    val hasStep get() = hasRange && !isMotion && this != TEMPERATURE

    /** Desktop's test model choices: ringing and fast towers, and SCV-V2 for cornering. */
    fun models(): List<Pair<String, String>> = when (this) {
        SHAPING_FREQUENCY, SHAPING_DAMPING -> listOf("Ringing tower" to "ringing_tower.3mf", "Fast tower" to "fast_tower.3mf")
        CORNERING -> listOf("Ringing tower" to "ringing_tower.3mf", "Fast tower" to "fast_tower.3mf", "SCV-V2" to "scv_v2.3mf")
        else -> emptyList()
    }

    companion object {
        /** Desktop input_shaper_types_for_flavor, without Disable, by gcode_flavor value. */
        fun shaperTypes(flavor: String): List<String> = when (flavor) {
            "klipper" -> listOf("Default", "ZV", "MZV", "ZVD", "EI", "2HUMP_EI", "3HUMP_EI")
            "reprapfirmware" -> listOf("Default", "MZV", "ZVD", "ZVDD", "ZVDDD", "EI2", "EI3", "DAA")
            "marlin2" -> listOf("ZV")
            else -> listOf("Default")
        }

        /** Desktop Temp_Calibration_Dlg ranges by filament type. */
        fun temperatureRange(filamentType: String): Pair<Double, Double> = when (filamentType.uppercase()) {
            "ABS", "ASA" -> 270.0 to 230.0
            "PETG" -> 250.0 to 230.0
            "PCTG" -> 280.0 to 240.0
            "TPU" -> 240.0 to 210.0
            "PA-CF", "PET-CF", "PA", "PAHT-CF" -> 320.0 to 280.0
            else -> 230.0 to 190.0
        }
    }
}

/** One test as the calibration dialog fills it in (desktop Calib_Params). */
data class CalibrationSetup(
    val test: CalibrationTest,
    val start: Double = test.start,
    val end: Double = test.end,
    val step: Double = test.step,
    val printNumbers: Boolean = true,
    val resize: Boolean = true,
    /** Input shaping and cornering: index into [CalibrationTest.models]. */
    val testModel: Int = 0,
    val freqStartX: Double = 0.0,
    val freqEndX: Double = 0.0,
    val freqStartY: Double = 0.0,
    val freqEndY: Double = 0.0,
    val shaperType: String = "",
    /** PA pattern batch: empty uses the profile's acceleration and the optimal speed. */
    val speeds: List<Double> = emptyList(),
    val accelerations: List<Double> = emptyList(),
) {
    val model: String? get() = test.models().getOrNull(testModel)?.second ?: test.model.ifEmpty { null }
}

/** orca_mesh_errors: what desktop's object list warning icon reports. */
class MeshErrors(values: IntArray) {
    /** Remaining non-manifold edges. */
    val openEdges = values[0]
    /** Errors fixed automatically when the mesh was loaded. */
    val repairedErrors = values[1]
    val shells = values[2]
    val facets = values[3]
    val manifold = values[4] != 0
    val repaired = values[5] != 0
    val hasWarning: Boolean get() = !manifold || repaired
}

/** orca_mesh_repair_report, built by the JNI layer from its counts and failure list. */
class RepairReport(counts: IntArray, val failures: String) {
    val weldedVertices = counts[0]
    val removedTriangles = counts[1]
    val reversedShells = counts[2]
    val filledHoles = counts[3]
    val removedShells = counts[4]
    val selfIntersections = counts[5]
    val repairedVolumes = counts[6]
    val failedVolumes = counts[7]
    val modified = counts[8] != 0
    val paintingRemoved = counts[9] != 0
}

/** ORCA_BOOLEAN_*: desktop Mesh Boolean modes. */
enum class BooleanOperation(val code: Int, val label: String) {
    UNION(0, "Union"),
    DIFFERENCE(1, "Difference"),
    INTERSECTION(2, "Intersection"),
}
