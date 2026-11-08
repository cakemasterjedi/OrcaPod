package org.orcapod.app.printhost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Object labels as GCode.cpp writes them for each firmware. */
class GcodeLabelsTest {
    private fun parse(text: String) = GcodeLabels.parse(text.trimIndent().lineSequence())

    @Test
    fun klipperDefinesNamesAndOutlines() {
        val l = parse(
            """
            ; HEADER_BLOCK_END
            EXCLUDE_OBJECT_DEFINE NAME=Cube_id_0_copy_0 CENTER=100,100 POLYGON=[[90,90],[110,90],[110,110],[90,110]]
            EXCLUDE_OBJECT_DEFINE NAME=Cylinder_id_1_copy_0 CENTER=150,100 POLYGON=[[140.5,90],[160,90],[160,110]]
            G28
            EXCLUDE_OBJECT_START NAME=Cube_id_0_copy_0
            G1 X95 Y95 E1.2
            EXCLUDE_OBJECT_END NAME=Cube_id_0_copy_0
            """,
        )!!
        assertEquals(GcodeLabels.Style.KLIPPER, l.style)
        assertEquals(listOf("Cube_id_0_copy_0", "Cylinder_id_1_copy_0"), l.objects.map { it.id })
        assertEquals(listOf(140.5 to 90.0, 160.0 to 90.0, 160.0 to 110.0), l.objects[1].outline)
        assertEquals("EXCLUDE_OBJECT NAME=Cube_id_0_copy_0", l.skipCommand(l.objects[0]))
    }

    @Test
    fun marlinNumbersObjectsAndBoxesTheirMoves() {
        val l = parse(
            """
            M486 S0
            M486 A Cube_id_0_copy_0
            M486 S-1
            M486 S1
            M486 A Cube_id_0_copy_1
            M486 S-1
            G1 X5 Y5 E2 ; outside any object: the skirt
            M486 S0
            G1 X10 Y20 F3000
            G1 X30 Y20 E1
            G1 X30 Y40 E1
            M486 S-1
            G1 X200 Y200 F9000
            M486 S1
            G1 X60 Y20
            G1 X80 Y45 E0.5
            M486 S-1
            """,
        )!!
        assertEquals(GcodeLabels.Style.M486, l.style)
        assertEquals(listOf("0", "1"), l.objects.map { it.id })
        assertEquals(listOf("Cube_id_0_copy_0", "Cube_id_0_copy_1"), l.objects.map { it.name })
        assertEquals(listOf(10.0 to 20.0, 30.0 to 20.0, 30.0 to 40.0, 10.0 to 40.0), l.objects[0].outline)
        assertEquals(listOf(60.0 to 20.0, 80.0 to 20.0, 80.0 to 45.0, 60.0 to 45.0), l.objects[1].outline)
        assertEquals("M486 P1", l.skipCommand(l.objects[1]))
    }

    @Test
    fun repRapFirmwareNamesOnTheSameLine() {
        val l = parse("M486 S3 A\"Gear_id_2_copy_0\"\nM486 S-1\n")!!
        assertEquals("3", l.objects.single().id)
        assertEquals("Gear_id_2_copy_0", l.objects.single().name)
    }

    @Test
    fun bambuLabelIdsTakeTheirNamesFromTheObjectComments() {
        val l = parse(
            """
            ; model label id: 74,91
            ; printing object Benchy id:1 copy 0
            ; start printing object, unique label id: 74
            G1 X100 Y100
            G1 X100 Y110 E1
            G1 X120 Y130 E1
            ; stop printing object, unique label id: 74
            ; printing object Cube id:2 copy 0
            ; start printing object, unique label id: 91
            G1 X10 Y10 E1
            ; stop printing object, unique label id: 91
            """,
        )!!
        assertEquals(GcodeLabels.Style.BAMBU, l.style)
        assertEquals(listOf("74", "91"), l.objects.map { it.id })
        assertEquals(listOf("Benchy", "Cube"), l.objects.map { it.name })
        assertEquals(listOf(100.0 to 100.0, 120.0 to 100.0, 120.0 to 130.0, 100.0 to 130.0), l.objects[0].outline)
    }

    @Test
    fun unlabeledGcodeHasNoObjects() {
        assertNull(parse("; printing object Cube id:0 copy 0\nG28\nG1 X10 Y10 E1\n"))
    }
}
