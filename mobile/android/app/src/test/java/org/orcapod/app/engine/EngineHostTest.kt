package org.orcapod.app.engine

import kotlinx.coroutines.runBlocking
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

    private suspend fun layerHeight() =
        Regex("\"layer_height\"\\s*:\\s*\"([^\"]*)\"").find(e.resolvedConfig())?.groupValues?.get(1)

    private suspend fun transform(objectId: Long) =
        e.scene().snapshot.objects.first { it.id == objectId }.instances.first().transform
}
