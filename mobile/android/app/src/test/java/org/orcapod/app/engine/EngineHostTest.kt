package org.orcapod.app.engine

import kotlinx.coroutines.runBlocking
import org.orcapod.app.settings.OptionDef
import org.orcapod.app.settings.SettingsLayout
import org.orcapod.app.settings.parseKeys
import org.orcapod.app.settings.parseValues
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.io.File
import java.nio.file.Files
import kotlin.math.abs

/**
 * The app's engine layer (Engine + JNI) against a host build of the engine.
 * Run through tools/host-jni-test.sh; skipped in a plain unit-test run.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class EngineHostTest {
    companion object {
        private const val PRINTER = "Prusa MK4 0.4 nozzle"
        private const val PROCESS = "0.20mm Standard @MK4"
        private lateinit var work: File
        private var engine: Engine? = null

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val profiles = System.getProperty("orcapod.profilesDir")
            assumeTrue("host JNI library not configured", System.getProperty("orcapod.hostJniDir") != null && profiles != null)
            work = Files.createTempDirectory("orcapod-host").toFile()
            // The layout ProfileStore installs: the chosen vendors, the filament library and shared files.
            val system = File(work, "data/system").apply { mkdirs() }
            File(work, "data/user/default").mkdirs()
            for (name in listOf("Prusa", "Prusa.json", "OrcaFilamentLibrary", "OrcaFilamentLibrary.json", "blacklist.json")) {
                Files.createSymbolicLink(File(system, name).toPath(), File(profiles!!, name).toPath())
            }
            File(work, "cube.stl").writeText(cubeStl(20.0))
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            engine?.close()
            // File.deleteRecursively follows the symlinks into the profile tree; Files.walk does not.
            if (::work.isInitialized) {
                Files.walk(work.toPath()).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
            }
        }

        private fun cubeStl(s: Double): String {
            val v = listOf(
                "0,0,0 1,1,0 1,0,0", "0,0,0 0,1,0 1,1,0", "0,0,1 1,0,1 1,1,1", "0,0,1 1,1,1 0,1,1",
                "0,0,0 1,0,0 1,0,1", "0,0,0 1,0,1 0,0,1", "0,1,0 0,1,1 1,1,1", "0,1,0 1,1,1 1,1,0",
                "0,0,0 0,0,1 0,1,1", "0,0,0 0,1,1 0,1,0", "1,0,0 1,1,0 1,1,1", "1,0,0 1,1,1 1,0,1",
            )
            return buildString {
                append("solid cube\n")
                for (f in v) {
                    append(" facet normal 0 0 0\n  outer loop\n")
                    for (p in f.split(' ')) {
                        val (x, y, z) = p.split(',').map { it.toDouble() * s }
                        append("   vertex $x $y $z\n")
                    }
                    append("  endloop\n endfacet\n")
                }
                append("endsolid cube\n")
            }
        }
    }

    private val e: Engine get() = engine!!

    @Test
    fun a_opensPresets() = runBlocking {
        assertNull(Engine.abiMismatch())
        engine = Engine.open(File(work, "data").path)
        val printers = e.list(PresetKind.PRINTER)
        assertTrue("MK4 listed", printers.any { it.name == PRINTER && it.vendor.isNotEmpty() })
        e.selectPrinter(PRINTER)
        assertEquals(PRINTER, e.current(PresetKind.PRINTER))
        e.select(PRINTER, e.current(PresetKind.FILAMENT), PROCESS)
        assertEquals(PROCESS, e.current(PresetKind.PROCESS))
        assertTrue(e.list(PresetKind.PROCESS).any { it.name == PROCESS && it.compatible })
        val v = e.printVolume()
        assertTrue("bed ${v.width} x ${v.depth}", v.width > 100 && v.depth > 100 && v.height > 100)
    }

    @Test
    fun b_importsAndReusesMeshes() = runBlocking {
        assertEquals(1, e.import(File(work, "cube.stl").path))
        val scene = e.scene()
        val obj = scene.snapshot.objects.single()
        val mesh = scene.meshes.getValue(obj.id)
        assertEquals(36, mesh.indices.size)
        assertTrue(mesh.positions.size >= 24)
        assertEquals(0, obj.instances.single().plate)
        assertEquals(16, obj.instances.single().matrix.size)
        // An unchanged mesh is not copied again.
        val again = e.scene()
        assertSame(mesh, again.meshes.getValue(obj.id))
        assertNull(again.snapshot.objects.single().positions)
    }

    @Test
    fun c_transformsWithUndo() = runBlocking {
        val obj = e.scene().snapshot.objects.single()
        val inst = obj.instances.single()
        val t = inst.transform
        e.setTransform(obj.id, inst.id, t.copy(offsetX = t.offsetX + 10), "Move")
        assertEquals(t.offsetX + 10, transform(obj.id).offsetX, 1e-6)
        e.undo()
        assertEquals(t.offsetX, transform(obj.id).offsetX, 1e-6)
        e.redo()
        assertEquals(t.offsetX + 10, transform(obj.id).offsetX, 1e-6)
        e.rotate(obj.id, inst.id, 2, Math.PI / 2)
        assertEquals(Math.PI / 2, abs(transform(obj.id).rotationZ), 1e-6)
        e.layFlat(obj.id, inst.id, 1.0, 0.0, 0.0)
        assertTrue(e.scene().snapshot.undoCount >= 3)
    }

    @Test
    fun d_duplicatesAndArranges() = runBlocking {
        val obj = e.scene().snapshot.objects.single()
        e.addInstance(obj.id)
        assertEquals(2, e.scene().snapshot.objects.single().instances.size)
        assertEquals(0, e.arrange())
        val insts = e.scene().snapshot.objects.single().instances
        assertTrue("instances apart", abs(insts[0].transform.offsetX - insts[1].transform.offsetX) > 15 ||
            abs(insts[0].transform.offsetY - insts[1].transform.offsetY) > 15)
        assertTrue(insts.all { it.plate == 0 })
    }

    @Test
    fun e_editsSettings() = runBlocking {
        e.setValue(PresetKind.PROCESS, "layer_height", "0.3")
        assertEquals("0.3", layerHeight())
        e.resetEdits(PresetKind.PROCESS)
        assertEquals("0.2", layerHeight())
    }

    @Test
    fun f_slicesAndPreviews() = runBlocking {
        val gcode = File(work, "plate.gcode")
        val progress = ArrayList<Int>()
        val report = e.slice(gcode.path) { p, _ -> progress += p }
        assertTrue(gcode.length() > 10_000)
        assertEquals(gcode.length(), report.gcodeBytes)
        assertTrue(report.timeEstimateSeconds > 60)
        assertTrue("progress reported", progress.isNotEmpty())

        val tp = e.loadToolpath(gcode.path)
        assertEquals(tp.segmentCount * 6, tp.positions.size)
        assertTrue("layers: ${tp.layerTotal}", tp.layerTotal in 90..110)
        assertEquals(tp.segmentCount, tp.layerFirst.last() + tp.layerCount.last())
        assertTrue(tp.filamentGrams > 1)
    }

    @Test
    fun g_cancelsSlice() = runBlocking {
        try {
            e.slice(File(work, "cancelled.gcode").path) { _, _ -> e.cancelSlice() }
            fail("slice was not cancelled")
        } catch (ex: OrcaException) {
            assertEquals(OrcaException.CANCELLED, ex.code)
        }
    }

    @Test
    fun h_reportsErrors() = runBlocking {
        try {
            e.import(File(work, "missing.stl").path)
            fail("missing file imported")
        } catch (ex: OrcaException) {
            assertTrue(ex.code != 0)
            assertNotNull(ex.message)
        }
        try {
            e.selectPrinter("No Such Printer")
            fail("unknown printer accepted")
        } catch (ex: OrcaException) {
            assertTrue(ex.code != 0)
        }
    }

    @Test
    fun i_optionDefsCoverTheSettingsLayout() = runBlocking {
        val defs = OptionDef.parseAll(e.optionDefs())
        assertTrue("defs: ${defs.size}", defs.size > 500)
        val layer = defs.getValue("layer_height")
        assertEquals("float", layer.type)
        assertTrue(layer.label.isNotEmpty() && layer.sidetext.isNotEmpty())
        assertTrue(defs.getValue("sparse_infill_pattern").enumValues.contains("gyroid"))
        assertTrue(defs.getValue("nozzle_temperature").vector)
        // Every option of the desktop tab layout the app ships is known to the engine.
        val layout = SettingsLayout.parse(File("src/main/assets/settings_layout.json").readText())
        for (kind in PresetKind.entries) {
            val keys = layout.pages(kind).flatMap { p -> p.groups.flatMap { it.keys } }
            assertTrue("$kind has options", keys.size > 50)
            val unknown = keys.filter { it !in defs }
            assertTrue("$kind layout keys unknown to the engine: $unknown", unknown.isEmpty())
        }
    }

    @Test
    fun j_dumpsPresetsAndTracksEdits() = runBlocking {
        val saved = parseValues(e.presetConfig(PresetKind.PROCESS, edited = false))
        assertEquals("0.2", saved["layer_height"])
        e.setValue(PresetKind.PROCESS, "wall_loops", "5")
        assertEquals("5", parseValues(e.presetConfig(PresetKind.PROCESS, edited = true))["wall_loops"])
        e.resetValue(PresetKind.PROCESS, "wall_loops")
        assertEquals(saved["wall_loops"], parseValues(e.presetConfig(PresetKind.PROCESS, edited = true))["wall_loops"])
    }

    @Test
    fun k_editsPartsAndPerObjectSettings() = runBlocking {
        val obj = e.scene().snapshot.objects.single()
        val objectKeys = parseKeys(e.overrideKeys(false))
        val volumeKeys = parseKeys(e.overrideKeys(true))
        assertTrue("layer_height" in objectKeys && "layer_height" !in volumeKeys && "sparse_infill_density" in volumeKeys)

        // Object override.
        e.setOverride(obj.id, 0, "sparse_infill_density", "40%")
        assertEquals("40%", parseValues(e.overrides(obj.id, 0))["sparse_infill_density"])
        assertEquals(1, e.scene().snapshot.objects.single().overrideCount)

        // A modifier with its own setting, drawn from its own mesh.
        val modifier = e.addVolumeShape(obj.id, obj.instances.first().id, VolumeType.MODIFIER, Shape.CUBE)
        e.setOverride(obj.id, modifier, "sparse_infill_density", "80%")
        val scene = e.scene()
        val o = scene.snapshot.objects.single()
        assertEquals(listOf(VolumeType.PART, VolumeType.MODIFIER), o.volumes.map { it.type })
        assertEquals(1, o.volumes[1].overrideCount)
        assertNotNull("modifier mesh", scene.meshes.getValue(o.id).volumes[modifier])
        assertEquals(12, o.volumes[0].mergedTriangleCount)

        e.setVolumeType(obj.id, modifier, VolumeType.SUPPORT_BLOCKER)
        assertEquals(VolumeType.SUPPORT_BLOCKER, e.scene().snapshot.objects.single().volumes[1].type)
        e.renameVolume(obj.id, modifier, "Blocker")
        assertEquals("Blocker", e.scene().snapshot.objects.single().volumes[1].name)
        e.deleteVolume(obj.id, modifier)
        assertEquals(1, e.scene().snapshot.objects.single().volumes.size)

        e.replaceOverrides(obj.id, 0, mapOf("wall_loops" to "4", "layer_height" to "0.16"))
        assertEquals(mapOf("wall_loops" to "4", "layer_height" to "0.16"), parseValues(e.overrides(obj.id, 0)))
        e.eraseOverride(obj.id, 0, "wall_loops")
        e.eraseOverride(obj.id, 0, "layer_height")
        assertEquals(0, e.scene().snapshot.objects.single().overrideCount)

        e.renameObject(obj.id, "Cube")
        assertEquals("Cube", e.scene().snapshot.objects.single().name)
    }

    @Test
    fun l_setsHeightRanges() = runBlocking {
        val obj = e.scene().snapshot.objects.single()
        e.setHeightRanges(obj.id, listOf(HeightRange(0.0, 5.0, 0.1), HeightRange(5.0, 10.0, 0.3)))
        assertEquals(listOf(HeightRange(0.0, 5.0, 0.1), HeightRange(5.0, 10.0, 0.3)), e.heightRanges(obj.id))
        try {
            e.setHeightRanges(obj.id, listOf(HeightRange(0.0, 5.0, 0.1), HeightRange(4.0, 10.0, 0.3)))
            fail("overlapping ranges accepted")
        } catch (ex: OrcaException) {
            assertEquals(OrcaException.INVALID_ARGUMENT, ex.code)
        }
        e.setHeightRanges(obj.id, emptyList())
        assertTrue(e.heightRanges(obj.id).isEmpty())
    }

    @Test
    fun m_managesPlates() = runBlocking {
        e.addPlate()
        var snap = e.scene().snapshot
        assertEquals(2, snap.plates.size)
        val second = snap.plates[1]
        e.renamePlate(second.id, "Spare")
        e.lockPlate(second.id, true)
        snap = e.scene().snapshot
        assertEquals("Spare", snap.plates[1].name)
        assertTrue(snap.plates[1].locked)
        e.setPlateSettings(second.id, PlateSettings(printByObject = true, spiralMode = false))
        assertEquals(PlateSettings(printByObject = true, spiralMode = false), e.plateSettings(second.id))
        e.selectPlate(second.id)
        assertEquals(1, e.scene().snapshot.currentPlate)
        e.selectPlate(snap.plates[0].id)
        e.deletePlate(second.id)
        assertEquals(1, e.scene().snapshot.plates.size)
    }

    @Test
    fun n_assignsFilaments() = runBlocking {
        e.setFilamentCount(2, "#2850E0")
        val slots = e.filamentSlots()
        assertEquals(2, slots.size)
        assertEquals("#2850E0", slots[1].colour.uppercase())
        e.setFilamentColour(1, "#FF6A13")
        assertEquals("#FF6A13", e.filamentSlots()[1].colour.uppercase())
        val obj = e.scene().snapshot.objects.single()
        e.setExtruder(obj.id, 0, 2)
        assertEquals(2, e.scene().snapshot.objects.single().extruder)
        e.setExtruder(obj.id, obj.volumes[0].id, 1)
        assertEquals(1, e.scene().snapshot.objects.single().volumes[0].extruder)
        e.setExtruder(obj.id, obj.volumes[0].id, 0)
        e.setExtruder(obj.id, 0, 1)
        // The first slot is the filament the settings tab edits.
        val other = e.list(PresetKind.FILAMENT).first { it.compatible && it.name != slots[0].preset }.name
        e.selectFilamentSlot(0, other)
        assertEquals(other, e.filamentSlots()[0].preset)
        assertEquals(other, e.current(PresetKind.FILAMENT))
        e.setFilamentCount(1, null)
        assertEquals(1, e.filamentSlots().size)
        assertTrue(e.bedType().count > 1)
    }

    @Test
    fun o_slicesAllPlatesAndSavesProjects() = runBlocking {
        val all = File(work, "all.gcode")
        val report = e.slice(all.path, SliceTarget.ALL_PLATES) { _, _ -> }
        assertTrue(all.length() > 10_000)
        assertEquals(all.length(), report.gcodeBytes)
        val sliced = File(work, "plate.gcode.3mf")
        e.slice(sliced.path, SliceTarget.SLICED_3MF_PLATE, 0) { _, _ -> }
        assertTrue(sliced.length() > 1_000)
        val project = File(work, "project.3mf")
        e.saveProject(project.path)
        assertTrue(project.length() > 1_000)
        val stl = File(work, "all.stl")
        e.exportStl(null, stl.path)
        assertTrue(stl.length() > 84)
    }

    private suspend fun layerHeight() =
        Regex("\"layer_height\"\\s*:\\s*\"([^\"]*)\"").find(e.resolvedConfig())?.groupValues?.get(1)

    private suspend fun transform(objectId: Long) =
        e.scene().snapshot.objects.first { it.id == objectId }.instances.first().transform
}
