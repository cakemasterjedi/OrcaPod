package org.orcapod.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ProfileStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun vendor(dir: File, name: String) {
        File(dir, "$name.json").apply { parentFile.mkdirs() }.writeText("{}")
        File(dir, "$name/machine/a.json").apply { parentFile.mkdirs() }.writeText("{}")
    }

    private fun names(dir: File) = dir.list().orEmpty().toSortedSet()

    @Test
    fun switchingBrandLeavesOnlyThatBrandAndSharedFilesInSystem() {
        val system = tmp.newFolder("system")
        val brands = tmp.newFolder("brands")
        vendor(system, "OrcaFilamentLibrary")
        File(system, "blacklist.json").writeText("[]")
        vendor(system, "BBL")
        vendor(brands, "Prusa")
        vendor(brands, "Custom")
        val installed = setOf("BBL", "Prusa", "Custom")

        ProfileStore.switchBrand(system, brands, installed, "Prusa")
        assertEquals(sortedSetOf("OrcaFilamentLibrary", "OrcaFilamentLibrary.json", "Prusa", "Prusa.json", "blacklist.json"), names(system))
        assertEquals(sortedSetOf("BBL", "BBL.json", "Custom", "Custom.json"), names(brands))
        assertEquals("{}", File(system, "Prusa/machine/a.json").readText())

        // Repeating the switch changes nothing.
        ProfileStore.switchBrand(system, brands, installed, "Prusa")
        assertEquals(sortedSetOf("BBL", "BBL.json", "Custom", "Custom.json"), names(brands))
    }

    @Test
    fun aSwitchCutShortIsFinishedByTheNextOne() {
        val system = tmp.newFolder("system")
        val brands = tmp.newFolder("brands")
        // BBL's json already moved out, its directory not; Prusa's json moved in, its directory not.
        File(brands, "BBL.json").writeText("{}")
        File(system, "BBL/machine/a.json").apply { parentFile.mkdirs() }.writeText("{}")
        File(system, "Prusa.json").writeText("{}")
        File(brands, "Prusa/machine/a.json").apply { parentFile.mkdirs() }.writeText("{}")

        ProfileStore.switchBrand(system, brands, setOf("BBL", "Prusa"), "Prusa")
        assertEquals(sortedSetOf("Prusa", "Prusa.json"), names(system))
        assertEquals(sortedSetOf("BBL", "BBL.json"), names(brands))
    }

    @Test
    fun aMissingBrandIsReported() {
        val system = tmp.newFolder("system")
        val brands = tmp.newFolder("brands")
        vendor(system, "BBL")
        assertThrows(IOException::class.java) { ProfileStore.switchBrand(system, brands, setOf("BBL", "Prusa"), "Prusa") }
    }

    @Test
    fun theDefaultBrandPrefersARealBrandToCustom() {
        assertEquals("BBL", ProfileStore.defaultVendor(setOf("Custom", "Prusa", "BBL")))
        assertEquals("Custom", ProfileStore.defaultVendor(setOf("Custom")))
        assertEquals(null, ProfileStore.defaultVendor(emptySet()))
    }
}
