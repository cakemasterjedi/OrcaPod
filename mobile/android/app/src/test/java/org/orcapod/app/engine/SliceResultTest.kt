package org.orcapod.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SliceResultTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun splitsEveryPlateIntoItsOwnFile() {
        val combined = tmp.newFile("model.gcode")
        combined.writeText(
            """
            ; ORCA_PLATE index=1 total=2 name="First" begin
            G1 X1
            ; ORCA_PLATE index=1 end
            ; ORCA_PLATE index=3 total=2 name="" begin
            G1 X3
            G1 Y3
            ; ORCA_PLATE index=3 end
            """.trimIndent() + "\n",
        )
        val plates = splitPlates(combined, tmp.root)
        assertEquals(listOf(0, 2), plates.map { it.plate })
        assertEquals("G1 X1\n", plates[0].gcode.readText())
        assertEquals("G1 X3\nG1 Y3\n", plates[1].gcode.readText())
    }

    @Test
    fun wordsCodedGcodeWarnings() {
        val coded = SliceWarning(2, "not_support_traditional_timelapse", "10018003", "")
        assertTrue(coded.text.startsWith("Traditional timelapse"))
        val unknown = SliceWarning(1, "some_new_check", "1000C009", "1\n2")
        assertEquals("Some new check: 1 2", unknown.text)
        val plain = SliceWarning(1, "", "Object floats above the bed", "Cube")
        assertEquals("Object floats above the bed: Cube", plain.text)
        val conflict = SliceWarning(2, "gcode_path_conflict", "", "Cube\nCylinder\n1.2\n6")
        assertEquals("The toolpaths of Cube and Cylinder collide at 1.20 mm. Move them apart.", conflict.text)

        // A collision blocks printing; other warnings don't.
        assertTrue(SliceReport(60.0, 1, arrayOf(coded, conflict)).hasPathConflict)
        assertFalse(SliceReport(60.0, 1, arrayOf(coded, plain)).hasPathConflict)
    }
}
