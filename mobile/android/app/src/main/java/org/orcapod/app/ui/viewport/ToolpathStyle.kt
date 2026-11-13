package org.orcapod.app.ui.viewport

import org.orcapod.app.engine.Toolpath
import org.orcapod.app.engine.Toolpath.Attr
import kotlin.math.exp
import kotlin.math.ln

/** Desktop preview "Color scheme" choices (libvgcode EViewType). */
enum class ViewMode(val label: String, val attr: Attr? = null, val unit: String = "", val log: Boolean = false) {
    LINE_TYPE("Line type"),
    FILAMENT("Filament"),
    SPEED("Speed", Attr.SPEED, "mm/s"),
    ACTUAL_SPEED("Actual speed", Attr.ACTUAL_SPEED, "mm/s"),
    HEIGHT("Layer height", Attr.HEIGHT, "mm"),
    WIDTH("Line width", Attr.WIDTH, "mm"),
    FLOW("Flow", Attr.FLOW, "mm³/s"),
    ACTUAL_FLOW("Actual flow", Attr.ACTUAL_FLOW, "mm³/s"),
    FAN("Fan speed", Attr.FAN, "%"),
    TEMPERATURE("Temperature", Attr.TEMPERATURE, "°C"),
    LAYER_TIME("Layer time", Attr.LAYER_TIME, "s"),
    LAYER_TIME_LOG("Layer time (log)", Attr.LAYER_TIME, "s", log = true),
    ACCELERATION("Acceleration", Attr.ACCELERATION, "mm/s²"),
    JERK("Jerk", Attr.JERK, "mm/s"),
    PRESSURE_ADVANCE("Pressure advance", Attr.PRESSURE_ADVANCE),
}

/** Desktop preview "Options": moves and markers shown beside the extrusions. */
enum class PreviewOption(val label: String, val move: Int, val color: IntArray) {
    TRAVEL("Travel", Toolpath.MOVE_TRAVEL, intArrayOf(56, 72, 155)),
    WIPE("Wipe", Toolpath.MOVE_WIPE, intArrayOf(255, 255, 0)),
    RETRACT("Retractions", Toolpath.MOVE_RETRACT, intArrayOf(205, 34, 214)),
    UNRETRACT("Deretractions", Toolpath.MOVE_UNRETRACT, intArrayOf(73, 173, 207)),
    SEAM("Seams", Toolpath.MOVE_SEAM, intArrayOf(230, 230, 230)),
    TOOL_CHANGE("Tool changes", Toolpath.MOVE_TOOL_CHANGE, intArrayOf(193, 190, 99)),
    COLOR_CHANGE("Color changes", Toolpath.MOVE_COLOR_CHANGE, intArrayOf(218, 148, 139)),
    PAUSE("Pauses", Toolpath.MOVE_PAUSE, intArrayOf(82, 240, 131)),
    CUSTOM("Custom G-code", Toolpath.MOVE_CUSTOM, intArrayOf(226, 210, 67));

    companion object {
        fun forMove(move: Int) = entries.firstOrNull { it.move == move }
    }
}

/** Everything that decides the toolpath's colours and which moves are drawn. */
data class PreviewStyle(
    val mode: ViewMode = ViewMode.LINE_TYPE,
    val hiddenRoles: Set<Int> = emptySet(),
    val options: Set<PreviewOption> = emptySet(),
    val filamentColours: List<String> = emptyList(),
)

/** Which part of the toolpath is shown: layers [low, high], and the first [moves] moves of the top layer. */
data class PreviewRange(val low: Int = 0, val high: Int = 0, val moves: Int = Int.MAX_VALUE)

/** One legend row: a colour, a label and an optional figure (time, length). */
data class LegendItem(val color: IntArray, val label: String, val detail: String = "", val role: Int = -1)

/**
 * GPU-ready toolpath geometry. Each visible segment becomes two interleaved
 * position + colour vertices, in file order; [writeLines] fills them a run at
 * a time, so a big print is uploaded to GL without a vertex copy of it all on
 * the Java heap. [visibleBefore] counts the visible segments before each
 * segment, so a layer range maps to one draw range. [points] holds one vertex
 * per visible marker, [pointLayerStart] the first marker of each layer.
 */
class ToolpathGeometry(
    private val tp: Toolpath,
    private val colours: ToolpathColours,
    /** The toolpath segment of each visible line. */
    private val visibleSegments: IntArray,
    val visibleBefore: IntArray,
    val points: FloatArray,
    val pointLayerStart: IntArray,
) {
    /** Visible lines, each [FLOATS_PER_LINE] floats. */
    val lineCount: Int get() = visibleSegments.size

    /** Writes lines [first, first + count) into [out], from its start. */
    fun writeLines(first: Int, count: Int, out: FloatArray) {
        val p = tp.positions
        val c = FloatArray(3)
        var o = 0
        for (line in first until first + count) {
            val i = visibleSegments[line]
            colours.segment(i, c)
            val s = i * 6
            out[o] = p[s]; out[o + 1] = p[s + 1]; out[o + 2] = p[s + 2]
            out[o + 3] = c[0]; out[o + 4] = c[1]; out[o + 5] = c[2]
            out[o + 6] = p[s + 3]; out[o + 7] = p[s + 4]; out[o + 8] = p[s + 5]
            out[o + 9] = c[0]; out[o + 10] = c[1]; out[o + 11] = c[2]
            o += FLOATS_PER_LINE
        }
    }

    /** Every line's vertices at once; for tests and small toolpaths. */
    fun lineVertices(): FloatArray = FloatArray(lineCount * FLOATS_PER_LINE).also { writeLines(0, lineCount, it) }

    /** Visible-segment range drawn for [r], as [first, end). */
    fun lineRange(tp: Toolpath, r: PreviewRange): IntRange {
        if (tp.layerTotal == 0) return IntRange.EMPTY
        val lo = r.low.coerceIn(0, tp.layerTotal - 1)
        val hi = r.high.coerceIn(lo, tp.layerTotal - 1)
        val end = tp.layerFirst[hi] + r.moves.coerceIn(0, tp.layerCount[hi])
        return visibleBefore[tp.layerFirst[lo]] until visibleBefore[end]
    }

    fun pointRange(tp: Toolpath, r: PreviewRange): IntRange {
        if (tp.layerTotal == 0) return IntRange.EMPTY
        val lo = r.low.coerceIn(0, tp.layerTotal - 1)
        val hi = r.high.coerceIn(lo, tp.layerTotal - 1)
        return pointLayerStart[lo] until pointLayerStart[hi + 1]
    }

    companion object {
        /** Two vertices of position + colour. */
        const val FLOATS_PER_LINE = 12

        fun build(tp: Toolpath, style: PreviewStyle): ToolpathGeometry {
            val colours = ToolpathColours(tp, style)
            val n = tp.segmentCount
            val visibleBefore = IntArray(n + 1)
            var visible = 0
            for (i in 0 until n) {
                visibleBefore[i] = visible
                if (colours.visible(i)) visible++
            }
            visibleBefore[n] = visible
            val visibleSegments = IntArray(visible)
            for (i in 0 until n) if (visibleBefore[i + 1] != visibleBefore[i]) visibleSegments[visibleBefore[i]] = i

            val pointLayerStart = IntArray(tp.layerTotal + 1)
            val shown = BooleanArray(tp.pointTypes.size) {
                PreviewOption.forMove(tp.pointTypes[it].toInt()) in style.options
            }
            val points = FloatArray(shown.count { it } * 6)
            var q = 0
            var layer = 0
            for (i in tp.pointTypes.indices) {
                while (layer < tp.pointLayers[i]) pointLayerStart[++layer] = q / 6
                if (!shown[i]) continue
                val col = PreviewOption.forMove(tp.pointTypes[i].toInt())!!.color
                points[q] = tp.pointPositions[3 * i]; points[q + 1] = tp.pointPositions[3 * i + 1]
                points[q + 2] = tp.pointPositions[3 * i + 2]
                points[q + 3] = col[0] / 255f; points[q + 4] = col[1] / 255f; points[q + 5] = col[2] / 255f
                q += 6
            }
            while (layer < tp.layerTotal) pointLayerStart[++layer] = q / 6
            return ToolpathGeometry(tp, colours, visibleSegments, visibleBefore, points, pointLayerStart)
        }
    }
}

/** Colours segments for one [PreviewStyle], as desktop's GCodeViewer does. */
class ToolpathColours(private val tp: Toolpath, private val style: PreviewStyle) {
    val range: FloatArray? = style.mode.attr?.let { attrRange(tp, it, style.hiddenRoles) }
    private val slotColours = style.filamentColours.map { PlateRenderer.parseHex(it) }

    fun visible(i: Int): Boolean = when (tp.move(i)) {
        Toolpath.MOVE_EXTRUDE -> tp.role(i) !in style.hiddenRoles
        Toolpath.MOVE_TRAVEL -> PreviewOption.TRAVEL in style.options
        Toolpath.MOVE_WIPE -> PreviewOption.WIPE in style.options
        else -> false
    }

    fun segment(i: Int, out: FloatArray) {
        when (tp.move(i)) {
            Toolpath.MOVE_TRAVEL -> return rgb(PreviewOption.TRAVEL.color, out)
            Toolpath.MOVE_WIPE -> return rgb(PreviewOption.WIPE.color, out)
        }
        val extruder = tp.extruders[i].toInt() and 0xFF
        when (style.mode) {
            ViewMode.LINE_TYPE -> rgb(PlateRenderer.roleColor(tp.role(i)), out)
            ViewMode.FILAMENT -> slot(extruder).copyInto(out, 0, 0, 3)
            else -> rgb(rangeColor(tp.attr(i, style.mode.attr!!), range!!, style.mode.log), out)
        }
    }

    private fun slot(extruder: Int) = slotColours.getOrElse(extruder) { DUMMY }

    companion object {
        private val DUMMY = floatArrayOf(64 / 255f, 64 / 255f, 64 / 255f, 1f)

        /** libvgcode DEFAULT_RANGES_COLORS, bluish to reddish. */
        val RANGE_COLORS = arrayOf(
            intArrayOf(11, 44, 122), intArrayOf(19, 89, 133), intArrayOf(28, 136, 145), intArrayOf(4, 214, 15),
            intArrayOf(170, 242, 0), intArrayOf(252, 249, 3), intArrayOf(245, 206, 10), intArrayOf(227, 136, 32),
            intArrayOf(209, 104, 48), intArrayOf(194, 82, 60), intArrayOf(148, 38, 22),
        )

        private fun rgb(c: IntArray, out: FloatArray) {
            out[0] = c[0] / 255f; out[1] = c[1] / 255f; out[2] = c[2] / 255f
        }

        /** [min, max] of [attr] over the shown extrusions; desktop excludes travels from the ranges. */
        fun attrRange(tp: Toolpath, attr: Attr, hiddenRoles: Set<Int>): FloatArray {
            var lo = Float.POSITIVE_INFINITY
            var hi = Float.NEGATIVE_INFINITY
            for (i in 0 until tp.segmentCount) {
                if (tp.move(i) != Toolpath.MOVE_EXTRUDE || tp.role(i) in hiddenRoles) continue
                val v = tp.attr(i, attr)
                if (v < lo) lo = v
                if (v > hi) hi = v
            }
            return if (lo > hi) floatArrayOf(0f, 0f) else floatArrayOf(lo, hi)
        }

        private fun step(range: FloatArray, log: Boolean): Float {
            val n = RANGE_COLORS.size - 1
            return if (log) {
                if (range[0] > 0f && range[1] > 0f) (ln(range[1]) - ln(range[0])) / n else 0f
            } else (range[1] - range[0]) / n
        }

        /** libvgcode ColorRange::get_color_at. */
        fun rangeColor(value: Float, range: FloatArray, log: Boolean): IntArray {
            val v = value.coerceIn(range[0], range[1])
            val step = step(range, log)
            val t = when {
                step <= 0f -> 0f
                log -> if (range[0] > 0f) ln(v / range[0]) / step else 0f
                else -> (v - range[0]) / step
            }
            val max = RANGE_COLORS.size - 1
            val low = t.toInt().coerceIn(0, max)
            val high = (low + 1).coerceAtMost(max)
            val f = (t - low).coerceIn(0f, 1f)
            val a = RANGE_COLORS[low]
            val b = RANGE_COLORS[high]
            return IntArray(3) { (a[it] + (b[it] - a[it]) * f).toInt() }
        }

        /** The legend's values, one per palette colour (libvgcode ColorRange::get_values). */
        fun rangeValues(range: FloatArray, log: Boolean): List<Float> {
            if (range[0] == range[1]) return listOf(range[0])
            val step = step(range, log)
            return RANGE_COLORS.indices.map { i ->
                if (log) exp(ln(range[0]) + i * step) else range[0] + i * step
            }
        }
    }
}

/** Desktop extrusion role names (ExtrusionRole order). */
val ROLE_NAMES = listOf(
    "Undefined", "Inner wall", "Outer wall", "Overhang wall", "Sparse infill", "Internal solid infill",
    "Top surface", "Bottom surface", "Ironing", "Bridge", "Internal bridge", "Gap infill", "Skirt", "Brim",
    "Support", "Support interface", "Support transition", "Prime tower", "Custom", "Multiple",
)

/** Estimated time per extrusion role, seconds, for the roles that occur. */
fun roleTimes(tp: Toolpath): Map<Int, Float> {
    val out = sortedMapOf<Int, Float>()
    for (i in 0 until tp.segmentCount) {
        if (tp.move(i) != Toolpath.MOVE_EXTRUDE) continue
        val r = tp.role(i)
        out[r] = (out[r] ?: 0f) + tp.times[i]
    }
    return out
}

/** Estimated time spent travelling, seconds. */
fun travelTime(tp: Toolpath): Float {
    var t = 0f
    for (i in 0 until tp.segmentCount) if (tp.move(i) == Toolpath.MOVE_TRAVEL) t += tp.times[i]
    return t
}
