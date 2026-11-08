package org.orcapod.app.printhost

import java.io.File

/** A point on the bed, in G-code coordinates (mm). */
typealias BedPoint = Pair<Double, Double>

/**
 * One object of the running print: [id] is what the printer skips it by
 * (Klipper's object name, the M486 number, Bambu's label id), [outline] where
 * it stands on the bed (empty when unknown).
 */
data class PrintObject(
    val id: String,
    val name: String,
    val outline: List<BedPoint> = emptyList(),
    val skipped: Boolean = false,
    /** The object the printer is printing right now, when it says. */
    val current: Boolean = false,
)

/**
 * The objects the slicer labeled in a G-code file, which the engine writes
 * when "Exclude objects" is on (always for Bambu printers): Klipper's
 * EXCLUDE_OBJECT_DEFINE, Marlin and RepRapFirmware's M486, or Bambu's
 * label ids. Printers that cannot list the objects of their print themselves
 * (OctoPrint, Bambu) are given these, read from the file the app sent.
 */
class GcodeLabels(val style: Style, val objects: List<PrintObject>) {
    enum class Style {
        /** Skipped with EXCLUDE_OBJECT NAME=. */
        KLIPPER,
        /** Skipped with M486 P<number>. */
        M486,
        /** Skipped with the printer's skip_objects command. */
        BAMBU,
    }

    /** The G-code that skips [o] on a printer streamed line by line (OctoPrint). */
    fun skipCommand(o: PrintObject): String = when (style) {
        Style.KLIPPER -> "EXCLUDE_OBJECT NAME=${o.id}"
        Style.M486, Style.BAMBU -> "M486 P${o.id}"
    }

    companion object {
        /** Reads [file]'s object labels; null when it has none. */
        fun read(file: File): GcodeLabels? = file.bufferedReader().useLines { parse(it) }

        fun parse(lines: Sequence<String>): GcodeLabels? {
            var style: Style? = null
            val order = ArrayList<String>()
            val names = HashMap<String, String>()
            val polygons = HashMap<String, List<BedPoint>>()
            val boxes = HashMap<String, DoubleArray>()
            fun add(id: String) { if (id !in names) { order += id; names[id] = "" } }
            var current: String? = null
            // The last "; printing object <name>" comment, naming the Bambu label that follows it.
            var pendingName: String? = null
            var m486: String? = null
            var x = 0.0
            var y = 0.0
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                if (line[0] == ';') {
                    when {
                        line.startsWith("; printing object ") ->
                            pendingName = line.removePrefix("; printing object ").substringBeforeLast(" id:").trim()
                        line.startsWith("; model label id:") -> {
                            style = Style.BAMBU
                            line.substringAfter(':').split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach(::add)
                        }
                        line.startsWith("; start printing object, unique label id:") -> {
                            val id = line.substringAfterLast(':').trim()
                            style = Style.BAMBU
                            add(id)
                            if (names[id].isNullOrEmpty() && pendingName != null) names[id] = pendingName
                            current = id
                        }
                        line.startsWith("; stop printing object, unique label id:") -> current = null
                    }
                    continue
                }
                val code = line.substringBefore(';').trim()
                when {
                    code.startsWith("G1 ") || code.startsWith("G0 ") || code.startsWith("G2 ") || code.startsWith("G3 ") -> {
                        var extrudes = false
                        val fromX = x
                        val fromY = y
                        for (word in code.split(' ')) {
                            if (word.length < 2) continue
                            val v = word.substring(1).toDoubleOrNull() ?: continue
                            when (word[0]) {
                                'X' -> x = v
                                'Y' -> y = v
                                'E' -> extrudes = v > 0
                            }
                        }
                        val id = current
                        // An extrusion covers the line from where the head was to where it goes.
                        if (extrudes && id != null) {
                            val b = boxes.getOrPut(id) { doubleArrayOf(fromX, fromY, fromX, fromY) }
                            b[0] = minOf(b[0], fromX, x)
                            b[1] = minOf(b[1], fromY, y)
                            b[2] = maxOf(b[2], fromX, x)
                            b[3] = maxOf(b[3], fromY, y)
                        }
                    }
                    code.startsWith("EXCLUDE_OBJECT_DEFINE ") -> {
                        style = Style.KLIPPER
                        val id = param(code, "NAME") ?: continue
                        add(id)
                        names[id] = id
                        param(code, "POLYGON")?.let { polygons[id] = parsePolygon(it) }
                    }
                    code.startsWith("EXCLUDE_OBJECT_START ") -> current = param(code, "NAME")
                    code.startsWith("EXCLUDE_OBJECT_END") -> current = null
                    code.startsWith("M486 ") -> {
                        if (style == null) style = Style.M486
                        // RepRapFirmware writes "M486 S1 A"name"" on one line, Marlin "M486 S1" then "M486 A name".
                        val s = Regex("""\bS(-?\d+)""").find(code)?.groupValues?.get(1)
                        val a = Regex("""\bA"([^"]*)"""").find(code)?.groupValues?.get(1)
                            ?: code.substringAfter(" A", "").takeIf { code.contains(" A") }?.trim()
                        if (s != null) {
                            m486 = s.takeIf { it != "-1" }
                            current = m486
                            m486?.let(::add)
                        }
                        if (a != null && a.isNotEmpty()) m486?.let { if (names[it].isNullOrEmpty()) names[it] = a }
                    }
                }
            }
            if (style == null || order.isEmpty()) return null
            val objects = order.map { id ->
                val outline = polygons[id] ?: boxes[id]?.let { b -> listOf(b[0] to b[1], b[2] to b[1], b[2] to b[3], b[0] to b[3]) }
                PrintObject(id, names[id].orEmpty().ifEmpty { "Object $id" }, outline.orEmpty())
            }
            return GcodeLabels(style, objects)
        }

        /** NAME=value from a Klipper command; values have no spaces except inside POLYGON's brackets. */
        private fun param(code: String, key: String): String? {
            val i = code.indexOf(" $key=")
            if (i < 0) return null
            val rest = code.substring(i + key.length + 2)
            return if (rest.startsWith("[")) rest.substring(0, matchingBracket(rest) + 1) else rest.substringBefore(' ')
        }

        private fun matchingBracket(s: String): Int {
            var depth = 0
            for ((i, ch) in s.withIndex()) {
                if (ch == '[') depth++
                if (ch == ']' && --depth == 0) return i
            }
            return s.length - 1
        }

        fun parsePolygon(s: String): List<BedPoint> =
            Regex("""\[\s*(-?[\d.]+)\s*,\s*(-?[\d.]+)\s*]""").findAll(s).map { it.groupValues[1].toDouble() to it.groupValues[2].toDouble() }.toList()
    }
}
