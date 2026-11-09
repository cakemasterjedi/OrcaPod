package org.orcapod.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileCatalogTest {
    private fun machine(name: String, model: String, inst: String = "true") =
        """{"type":"machine","name":"$name","printer_model":"$model","instantiation":"$inst"}"""
    private fun filament(name: String, inst: String = "true") =
        """{"type":"filament","name":"$name","instantiation":"$inst"}"""

    private val catalog = ProfileCatalog.parse(
        sequenceOf(
            "BBL/machine/a.json" to machine("Bambu Lab P1S 0.4 nozzle", "Bambu Lab P1S"),
            "BBL/machine/b.json" to machine("Bambu Lab P1S 0.6 nozzle", "Bambu Lab P1S"),
            "BBL/machine/c.json" to machine("Bambu Lab A1 0.4 nozzle", "Bambu Lab A1"),
            "BBL/machine/common.json" to machine("fdm_bbl_3dp_001_common", "", inst = "false"),
            "BBL/machine/model.json" to """{"type":"machine_model","name":"Bambu Lab A1"}""",
            "BBL/filament/x.json" to filament("Bambu PLA Basic @BBL P1S"),
            "BBL/filament/y.json" to filament("Bambu PLA Basic @BBL A1"),
            "BBL/filament/base.json" to filament("Bambu PLA Basic @base", inst = "false"),
            "OrcaFilamentLibrary/filament/Polymaker/p.json" to filament("Polymaker PolyLite PLA @System"),
            "OrcaFilamentLibrary/filament/g.json" to filament("Generic PLA @System"),
            "BBL/filament/broken.json" to "{",
        ),
    )

    @Test
    fun printersAreGroupedByModelUnderTheFullBrandName() {
        assertEquals(
            listOf("Bambu Lab" to "Bambu Lab A1", "Bambu Lab" to "Bambu Lab P1S"),
            catalog.printers.map { it.brand to it.name },
        )
        assertEquals(
            setOf("Bambu Lab P1S 0.4 nozzle", "Bambu Lab P1S 0.6 nozzle"),
            catalog.printers.first { it.name == "Bambu Lab P1S" }.presets,
        )
    }

    @Test
    fun filamentsAreGroupedByProductAndMaker() {
        assertEquals(
            listOf("Bambu" to "Bambu PLA Basic", "Generic" to "Generic PLA", "Polymaker" to "Polymaker PolyLite PLA"),
            catalog.filaments.map { it.brand to it.name },
        )
        assertEquals(2, catalog.filaments.first().presets.size)
    }

    @Test
    fun pickingModelsListsTheirNozzlesAndHidesTheRest() {
        val p1s = catalog.printers.filter { it.name == "Bambu Lab P1S" }
        val choice = PresetChoice().pick(catalog.printerPresets, p1s.flatMapTo(HashSet()) { it.presets })
        val loaded = catalog.printerPresets.map { org.orcapod.app.engine.PresetEntry(it, "Bambulab", true, 0) }
        assertEquals(
            setOf("Bambu Lab P1S 0.4 nozzle", "Bambu Lab P1S 0.6 nozzle"),
            choice.filter(loaded, "Bambu Lab P1S 0.4 nozzle").map { it.name }.toSet(),
        )
    }

    @Test
    fun filamentTypesAreReadFromTheProductName() {
        mapOf(
            "Bambu PLA Basic" to "PLA",
            "Overture PETG-CF" to "PETG",
            "Bambu PA6-CF" to "PA",
            "eSUN ePLA-LW" to "PLA",
            "Generic TPU 95A" to "TPU",
            "Prusament PVB" to "Other",
        ).forEach { (product, type) -> assertEquals(product, type, ProfileCatalog.typeOf(product)) }
        assertEquals("PLA", catalog.filaments.first { it.name == "Generic PLA" }.type)
    }
}
