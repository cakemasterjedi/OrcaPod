package org.orcapod.app.data

import org.junit.Assert.assertEquals
import org.junit.Test
import org.orcapod.app.engine.PresetEntry

class PresetChoiceTest {
    private fun entries(vararg names: String) = names.map { PresetEntry(it, "BBL", true, 0) }
    private fun names(list: List<PresetEntry>) = list.map { it.name }

    @Test
    fun listsEverythingUntilSomethingIsPicked() {
        val all = entries("A1", "P1S", "X1C")
        assertEquals(listOf("A1", "P1S", "X1C"), names(PresetChoice().filter(all, "A1")))
    }

    @Test
    fun listsOnlyPicksAndTheCurrentPreset() {
        val all = entries("A1", "P1S", "X1C")
        val choice = PresetChoice().pick(all, setOf("P1S"))
        assertEquals(listOf("P1S"), names(choice.filter(all, "P1S")))
        assertEquals(listOf("A1", "P1S"), names(choice.filter(all, "A1")))
    }

    @Test
    fun presetsAddedAfterPickingAreListedUntilDecidedOn() {
        val choice = PresetChoice().pick(entries("A1", "P1S"), setOf("P1S"))
        val later = entries("A1", "P1S", "My PLA")
        assertEquals(listOf("P1S", "My PLA"), names(choice.filter(later, "P1S")))
        val decided = choice.pick(later, setOf("P1S"))
        assertEquals(listOf("P1S"), names(decided.filter(later, "P1S")))
    }

    @Test
    fun picksForOtherBrandsAreKeptAndDoNotHideThisOne() {
        val bambu = entries("A1", "P1S")
        val prusa = entries("MK4", "MINI")
        val choice = PresetChoice().pick(bambu, setOf("A1")).pick(prusa, setOf("MK4"))
        assertEquals(setOf("A1", "MK4"), choice.shown)
        val cleared = choice.pick(prusa, emptySet())
        assertEquals(listOf("MK4", "MINI"), names(cleared.filter(prusa, "MK4")))
        assertEquals(listOf("A1"), names(cleared.filter(bambu, "A1")))
    }

    @Test
    fun vendorIdsAndProfileNamesShowTheFullBrandName() {
        assertEquals("Bambu Lab", VendorNames.display("BBL"))
        assertEquals("Bambu Lab", VendorNames.display("Bambulab"))
        assertEquals("Prusa", VendorNames.display("Prusa"))
        assertEquals(listOf("Anker", "BBL", "Creality"), VendorNames.sorted(listOf("Creality", "BBL", "Anker")))
    }
}
