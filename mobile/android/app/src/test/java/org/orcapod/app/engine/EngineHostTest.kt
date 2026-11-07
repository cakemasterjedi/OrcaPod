package org.orcapod.app.engine

import kotlinx.coroutines.runBlocking
import org.orcapod.app.settings.OptionDef
import org.orcapod.app.settings.SettingsLayout
import org.orcapod.app.settings.parseKeys
import org.orcapod.app.settings.parseValues
import org.orcapod.app.ui.CutState
import org.orcapod.app.ui.paintOverlay
import org.orcapod.app.ui.worldBounds
import org.orcapod.app.ui.viewport.PreviewOption
import org.orcapod.app.ui.viewport.Selection
import org.orcapod.app.ui.viewport.PreviewRange
import org.orcapod.app.ui.viewport.PreviewStyle
import org.orcapod.app.ui.viewport.ToolpathColours
import org.orcapod.app.ui.viewport.ToolpathGeometry
import org.orcapod.app.ui.viewport.ViewMode
import org.orcapod.app.ui.viewport.roleTimes
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
        assertEquals(tp.segmentCount * Toolpath.Attr.COUNT, tp.attrs.size)
        val extrusions = (0 until tp.segmentCount).filter { tp.move(it) == Toolpath.MOVE_EXTRUDE }
        assertTrue("travels kept", (0 until tp.segmentCount).any { tp.move(it) == Toolpath.MOVE_TRAVEL })
        assertTrue(extrusions.all { tp.attr(it, Toolpath.Attr.SPEED) > 0 && tp.attr(it, Toolpath.Attr.WIDTH) > 0 })
        assertTrue(extrusions.any { tp.attr(it, Toolpath.Attr.TEMPERATURE) > 150 })
        assertTrue("seams and retractions", tp.pointTypes.any { it.toInt() == Toolpath.MOVE_SEAM })
        assertEquals(tp.pointTypes.size, tp.pointLayers.size)
        assertTrue(abs(tp.times.sum() - tp.estimatedSeconds) < tp.estimatedSeconds * 0.1)
        assertTrue(roleTimes(tp).isNotEmpty())

        // Hiding a role and showing travels change what the preview draws.
        val plain = ToolpathGeometry.build(tp, PreviewStyle())
        assertEquals(extrusions.size * 12, plain.lines.size)
        val withTravel = ToolpathGeometry.build(tp, PreviewStyle(options = setOf(PreviewOption.TRAVEL, PreviewOption.SEAM)))
        assertTrue(withTravel.lines.size > plain.lines.size)
        assertTrue(withTravel.points.isNotEmpty())
        val outer = 2
        val noOuter = ToolpathGeometry.build(tp, PreviewStyle(hiddenRoles = setOf(outer)))
        assertEquals(extrusions.count { tp.role(it) != outer } * 12, noOuter.lines.size)
        val all = plain.lineRange(tp, PreviewRange(0, tp.layerTotal - 1))
        assertEquals(extrusions.size, all.last - all.first + 1)
        val first = plain.lineRange(tp, PreviewRange(0, 0, 1))
        assertTrue(first.last - first.first + 1 <= 1)
        val speed = ToolpathGeometry.build(tp, PreviewStyle(mode = ViewMode.SPEED))
        assertEquals(plain.lines.size, speed.lines.size)
        val range = ToolpathColours.attrRange(tp, Toolpath.Attr.SPEED, emptySet())
        assertTrue(range[1] > range[0])
        assertEquals(ToolpathColours.RANGE_COLORS.first().toList(), ToolpathColours.rangeColor(range[0], range, false).toList())
        assertEquals(ToolpathColours.RANGE_COLORS.last().toList(), ToolpathColours.rangeColor(range[1], range, false).toList())
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

    @Test
    fun p_addsLayerGcode() = runBlocking {
        val before = File(work, "ticks0.gcode")
        e.slice(before.path) { _, _ -> }
        val tp0 = e.loadToolpath(before.path)
        assertTrue("layer heights", tp0.layerZ.all { it > 0f })
        val z = tp0.layerZ[tp0.layerTotal / 2].toDouble()
        val pauseZ = tp0.layerZ[tp0.layerTotal / 3].toDouble()
        // Desktop "Change filament" needs a second filament.
        e.setFilamentCount(2, "#2850E0")
        // The default tower position does not fit this bed; the change itself is what is tested.
        e.setValue(PresetKind.PROCESS, "enable_prime_tower", "0")
        e.addCustomGcode(CustomGcode(z, CustomGcodeType.TOOL_CHANGE, 2, "", ""))
        e.addCustomGcode(CustomGcode(pauseZ, CustomGcodeType.PAUSE, 0, "", "Insert magnets"))
        val items = e.customGcodes()
        assertEquals(listOf(CustomGcodeType.PAUSE, CustomGcodeType.TOOL_CHANGE), items.map { it.type })
        assertEquals("Insert magnets", items[0].extra)
        val after = File(work, "ticks1.gcode")
        e.slice(after.path) { _, _ -> }
        val tp = e.loadToolpath(after.path)
        assertTrue(tp.pointTypes.any { it.toInt() == Toolpath.MOVE_PAUSE })
        assertTrue(tp.pointTypes.any { it.toInt() == Toolpath.MOVE_TOOL_CHANGE })
        assertEquals(2, tp.volumes.size)
        assertTrue(tp.grams(1) > 0)
        // The filament view colours the layers above the change in the second filament's colour.
        val style = PreviewStyle(ViewMode.FILAMENT, filamentColours = listOf("#00FF00", "#FF0000"))
        val g = ToolpathGeometry.build(tp, style)
        assertEquals(1f, g.lines[4])
        assertEquals(1f, g.lines[g.lines.size - 3])
        assertEquals(0f, g.lines[g.lines.size - 2])
        e.deleteCustomGcode(z)
        e.deleteCustomGcode(pauseZ)
        assertTrue(e.customGcodes().isEmpty())
        e.setFilamentCount(1, null)
        e.resetEdits(PresetKind.PROCESS)
    }

    @Test
    fun q_cutsWithConnectors() = runBlocking {
        val obj = e.scene().snapshot.objects.single()
        val inst = obj.instances.first()
        val scene = e.scene()
        val b = worldBounds(scene.meshes.getValue(obj.id).positions, inst.matrix)
        val t = inst.transform
        var cut = CutState(Selection(obj.id, inst.id), b, doubleArrayOf(t.offsetX, t.offsetY, t.offsetZ), height = (b[2] + b[5]) / 2)
        // A ray straight down through the centre meets the plane at its origin.
        val uv = cut.hit(floatArrayOf(cut.centerX.toFloat(), cut.centerY.toFloat(), 500f), floatArrayOf(0f, 0f, -1f))!!
        assertEquals(0.0, uv.first, 1e-6)
        assertEquals(0.0, uv.second, 1e-6)
        cut = cut.copy(connectors = listOf(uv), size = 5f, depth = 4f)
        val n = e.cut(obj.id, inst.id, cut.plane, CutKeep.OBJECTS, cut.engineConnectors())
        assertEquals(2, n)
        val parts = e.scene().snapshot.objects
        assertEquals(2, parts.size)
        e.undo()
        assertEquals(1, e.scene().snapshot.objects.size)
        // A tilted plane above the middle: the upper part is shorter than the cube, the lower one is not.
        val tilted = cut.copy(height = 15f, tiltX = 30f, connectors = emptyList())
        assertEquals(0.866, tilted.normal[2], 1e-3)
        assertEquals(2, e.cut(obj.id, inst.id, tilted.plane, CutKeep.OBJECTS, emptyList()))
        val heights = e.scene().let { sc ->
            sc.snapshot.objects.map { o ->
                val wb = worldBounds(sc.meshes.getValue(o.id).positions, o.instances.first().matrix)
                wb[5] - wb[2]
            }.sorted()
        }
        // 20 mm cube cut at z = 15 with a 30° tilt: 5 + 10·tan 30° above, all 20 mm below.
        assertEquals(10.77f, heights[0], 0.05f)
        assertEquals(20f, heights[1], 0.05f)
        e.undo()
        assertEquals(1, e.scene().snapshot.objects.size)
    }

    @Test
    fun r_paintsFacets() = runBlocking {
        val obj = e.scene().snapshot.objects.single()
        val part = obj.parts.single()
        val sc = e.scene()
        val b = worldBounds(sc.meshes.getValue(obj.id).positions, obj.instances.first().matrix)
        val top = floatArrayOf((b[0] + b[3]) / 2, (b[1] + b[4]) / 2, b[5])
        val down = floatArrayOf(0f, 0f, -1f)
        e.paintSphere(obj.id, part.id, PaintLayer.SUPPORT, top, down, 3.0, 1)
        val brushed = e.paint(obj.id, part.id, PaintLayer.SUPPORT)
        assertTrue(brushed.count > 0)
        assertTrue(brushed.states.all { it.toInt() == 1 })
        assertEquals(brushed.count * 9, brushed.positions.size)
        assertTrue("painted on the top face", (0 until brushed.count).all { abs(brushed.positions[it * 9 + 2] - b[5]) < 0.01f })
        // Smart fill takes the whole flat top face, into another layer.
        e.paintFill(obj.id, part.id, PaintLayer.SEAM, top, down, 30.0, 2)
        val seams = e.paint(obj.id, part.id, PaintLayer.SEAM)
        assertTrue(seams.count >= 2)
        val overlay = paintOverlay(listOf(seams), PaintLayer.SEAM, emptyList())!!
        assertEquals(seams.count * 18, overlay.size)
        assertEquals(0.9f, overlay[3], 1e-6f) // blocker red
        e.paintReset(obj.id, part.id, PaintLayer.SUPPORT)
        e.paintReset(obj.id, part.id, PaintLayer.SEAM)
        assertEquals(0, e.paint(obj.id, part.id, PaintLayer.SUPPORT).count)
        assertNull(paintOverlay(listOf(e.paint(obj.id, part.id, PaintLayer.SEAM)), PaintLayer.SEAM, emptyList()))
    }

    @Test
    fun s_editsLayerHeights() = runBlocking {
        val obj = e.scene().snapshot.objects.single()
        assertEquals(0, e.heightProfile(obj.id).size)
        val b = worldBounds(e.scene().meshes.getValue(obj.id).positions, obj.instances.first().matrix)
        val top = (b[5] - b[2]).toDouble()
        // Manual edits start from the uniform profile, as on desktop.
        e.adjustHeightProfile(obj.id, top / 2, 0.02, 2.0, 1)
        val thinner = e.heightProfile(obj.id)
        assertTrue(thinner.size >= 4 && thinner.size % 2 == 0)
        assertEquals(top, thinner[thinner.size - 2], 1e-3)
        val adaptive = e.adaptiveHeightProfile(obj.id, 0.5)
        assertTrue(adaptive.size >= 4 && adaptive.size % 2 == 0)
        assertEquals(top, adaptive[adaptive.size - 2], 1e-3)
        assertTrue((1 until adaptive.size step 2).all { adaptive[it] > 0 })
        val smoothed = e.smoothHeightProfile(obj.id, 5, false)
        assertTrue(smoothed.size >= 4)
        assertTrue(e.heightProfile(obj.id).contentEquals(smoothed))
        e.resetHeightProfile(obj.id)
        assertEquals(0, e.heightProfile(obj.id).size)
    }

    @Test
    fun t_measuresFeatures() = runBlocking {
        val sc = e.scene()
        val obj = sc.snapshot.objects.single()
        val inst = obj.instances.first()
        val mesh = sc.meshes.getValue(obj.id)
        val p = mesh.positions
        val zMin = (2 until p.size step 3).minOf { p[it] }
        val zMax = (2 until p.size step 3).maxOf { p[it] }
        fun vertex(t: Int, k: Int) = DoubleArray(3) { p[mesh.indices[t * 3 + k] * 3 + it].toDouble() }
        val tris = mesh.indices.size / 3
        val top = (0 until tris).first { t -> (0 until 3).all { vertex(t, it)[2] == zMax.toDouble() } }
        val bottom = (0 until tris).first { t -> (0 until 3).all { vertex(t, it)[2] == zMin.toDouble() } }
        // A corner of a triangle snaps to a point, the middle of a face picks the plane.
        val corner = e.measurePick(obj.id, mesh.revision, bottom, vertex(bottom, 0))!!
        assertEquals(FeatureType.POINT, corner.type)
        val mid = DoubleArray(3) { k -> (0 until 3).sumOf { vertex(top, it)[k] } / 3 }
        val plane = e.measurePick(obj.id, mesh.revision, top, mid)!!
        assertEquals(FeatureType.PLANE, plane.type)
        assertEquals(400.0, plane.area, 1.0)
        val m = e.measureBetween(plane.transformed(inst.matrix), corner.transformed(inst.matrix))!!
        assertEquals(20.0, m.distance!!, 0.05)
        // World features follow the instance: twice the scale doubles a circle and a plane's area.
        val scale2 = FloatArray(16).also { it[0] = 2f; it[5] = 2f; it[10] = 2f; it[15] = 1f; it[12] = 5f }
        val circle = SurfaceFeature(FeatureType.CIRCLE, doubleArrayOf(1.0, 0.0, 0.0), DoubleArray(3), doubleArrayOf(0.0, 0.0, 1.0), 3.0, 0.0, -1)
        val c2 = circle.transformed(scale2)
        assertEquals(6.0, c2.radius, 1e-9)
        assertEquals(7.0, c2.a[0], 1e-9)
        assertEquals(1.0, c2.normal[2], 1e-9)
        assertEquals(4 * plane.area, plane.transformed(scale2).area, 1e-6)
        e.endMeasure()
    }

    private suspend fun layerHeight() =
        Regex("\"layer_height\"\\s*:\\s*\"([^\"]*)\"").find(e.resolvedConfig())?.groupValues?.get(1)

    private suspend fun transform(objectId: Long) =
        e.scene().snapshot.objects.first { it.id == objectId }.instances.first().transform
}
