package org.orcapod.app.engine

import java.io.File

/** One plate's G-code out of a slice of every plate: [plate] is its 0-based plate index. */
data class SlicedPlate(val plate: Int, val gcode: File)

private val PLATE_BEGIN = Regex("""^; ORCA_PLATE index=(\d+) .*begin\s*$""")

/**
 * Splits the G-code of every plate, which the engine writes into one file with
 * each plate between `; ORCA_PLATE index=N ... begin` and `; ORCA_PLATE index=N end`
 * lines (N counts from 1), into one file per plate in [dir], so each plate can
 * be previewed on its own. Plates in the file overlap on the bed, so the whole
 * file cannot be.
 */
fun splitPlates(combined: File, dir: File): List<SlicedPlate> {
    val out = mutableListOf<SlicedPlate>()
    var writer: java.io.Writer? = null
    try {
        combined.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.startsWith("; ORCA_PLATE ")) {
                    writer?.close()
                    writer = null
                    PLATE_BEGIN.matchEntire(line)?.let { m ->
                        val plate = m.groupValues[1].toInt() - 1
                        val file = File(dir, "${combined.nameWithoutExtension}.plate${plate + 1}.gcode")
                        writer = file.bufferedWriter()
                        out += SlicedPlate(plate, file)
                    }
                    continue
                }
                writer?.let { it.write(line); it.write("\n") }
            }
        }
    } finally {
        writer?.close()
    }
    return out
}
