package org.orcapod.app.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PublishTest {
    private val options = """
        {"process": ["wall_loops", "outer_wall_speed#1"], "printer": ["retraction_length#0"],
         "filaments": [{"slot": 0, "name": "Generic PLA", "type": "PLA", "colour": "#FF0000", "used": true},
                       {"slot": 1, "name": "Generic PETG", "type": "PETG", "colour": "#00FF00", "used": false}],
         "previous": null}
    """.trimIndent()

    @Test
    fun startsWithTheChangedSettingsAndTheUsedFilaments() {
        val parsed = PublishOptions.parse(options)
        assertNull(parsed.previous)
        val selection = parsed.initialSelection()
        assertEquals(listOf("wall_loops", "outer_wall_speed#1", "retraction_length#0"), selection.keys)
        assertEquals(listOf(PublishMaterial(0, colour = true, type = true, full = false)), selection.materials)
    }

    @Test
    fun startsWithThePreviousSelectionWhenThereIsOne() {
        val previous = JSONObject(options).put(
            "previous",
            JSONObject("""{"keys": ["wall_loops"], "materials": [{"slot": 1, "colour": false, "type": false, "full": true}]}"""),
        )
        val selection = PublishOptions.parse(previous.toString()).initialSelection()
        assertEquals(listOf("wall_loops"), selection.keys)
        assertEquals(listOf(PublishMaterial(1, colour = false, type = false, full = true)), selection.materials)
    }

    @Test
    fun writesOnlyTheSlotsThatPublishSomething() {
        val selection = PublishSelection(
            listOf("wall_loops"),
            listOf(PublishMaterial(0, colour = true, type = false, full = false), PublishMaterial(1, colour = false, type = false, full = false)),
        )
        val json = JSONObject(selection.toJson())
        assertEquals("wall_loops", json.getJSONArray("keys").getString(0))
        val materials = json.getJSONArray("materials")
        assertEquals(1, materials.length())
        assertEquals(0, materials.getJSONObject(0).getInt("slot"))
        assertEquals(selection.copy(materials = selection.materials.take(1)), PublishSelection.parse(json))
    }

    @Test
    fun readsTheNotes() {
        val notes = PublishNotes.parse("""{"skipped": ["retraction_length#3"], "replaced": ["Slot 1: PLA -> PETG"]}""")
        assertEquals(listOf("retraction_length#3"), notes.skipped)
        assertEquals(listOf("Slot 1: PLA -> PETG"), notes.replaced)
    }
}
