package org.orcapod.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class ProfileBackupTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun preset(dir: File, kind: String, name: String, idKey: String) =
        File(dir, "$kind/$name.json").apply {
            parentFile.mkdirs()
            writeText("""{"name":"$name","from":"User","inherits":"Base","$idKey":"$name","version":"2.3.0.0"}""")
        }

    @Test
    fun exportWritesDesktopLayoutAndRoundTrips() {
        val phone = tmp.newFolder("phone")
        preset(phone, "machine", "My Printer", "printer_settings_id")
        preset(phone, "filament", "My PLA", "filament_settings_id")
        preset(phone, "process", "0.2 Strong", "print_settings_id")
        File(phone, "process/0.2 Strong.info").writeText("sync_info = \n")
        File(phone, "physical_machine").mkdirs()

        val zip = ByteArrayOutputStream()
        assertEquals(3, ProfileBackup.export(phone, zip))

        val names = ZipInputStream(ByteArrayInputStream(zip.toByteArray())).use { z ->
            generateSequence { z.nextEntry?.name }.toList()
        }
        assertEquals(listOf("printer/My Printer.json", "filament/My PLA.json", "process/0.2 Strong.json"), names)

        val other = tmp.newFolder("other")
        val result = ProfileBackup.import(ByteArrayInputStream(zip.toByteArray()), other)
        assertEquals(listOf("My Printer", "My PLA", "0.2 Strong"), result.added)
        assertEquals(mapOf("My PLA" to "Base"), result.filaments)
        assertTrue(File(other, "machine/My Printer.json").isFile)
        assertEquals(File(phone, "filament/My PLA.json").readText(), File(other, "filament/My PLA.json").readText())
    }

    @Test
    fun importReadsDesktopBundleAndSkipsWhatIsNotAPreset() {
        val bundle = ByteArrayOutputStream()
        ZipOutputStream(bundle).use { z ->
            fun put(name: String, text: String) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() }
            put("bundle_structure.json", """{"bundle_id":"x","printer_config":["printer/P.json"]}""")
            // Named after the file, but loaded under its "name": the engine uses the file name.
            put("printer/P.json", """{"name":"Voron 0.4 - Copy","printer_settings_id":"Voron 0.4 - Copy"}""")
            put("filament/F.json", """{"name":"Silk","type":"filament"}""")
            put("process/Q.json", """{"name":"Fast"}""")
            put("process/broken.json", "{not json")
            put("filament/notes.txt", "hello")
            put("printer/bed.stl", "solid")
        }
        val dir = tmp.newFolder("user")
        File(dir, "process").mkdirs()
        File(dir, "process/Fast.json").writeText("{}")
        File(dir, "process/Fast.info").writeText("setting_id = abc\n")

        val result = ProfileBackup.import(ByteArrayInputStream(bundle.toByteArray()), dir)

        assertEquals(listOf("Voron 0.4 - Copy", "Silk"), result.added)
        assertEquals(listOf("Fast"), result.replaced)
        assertEquals(listOf("broken.json"), result.skipped)
        assertTrue(File(dir, "machine/Voron 0.4 - Copy.json").isFile)
        assertTrue(File(dir, "filament/Silk.json").isFile)
        assertTrue(File(dir, "process/Fast.json").readText().contains("Fast"))
        assertFalse("a replaced preset's sync record is dropped", File(dir, "process/Fast.info").exists())
        assertFalse(File(dir, "bundle_structure.json").exists())
    }

    @Test
    fun importsASingleJsonAndRejectsNamesThatEscape() {
        val dir = tmp.newFolder("user")
        val ok = ProfileBackup.import(ByteArrayInputStream("""{"name":"Mine","filament_settings_id":"Mine"}""".toByteArray()), dir)
        assertEquals(listOf("Mine"), ok.added)
        assertTrue(File(dir, "filament/Mine.json").isFile)

        val bad = ProfileBackup.import(ByteArrayInputStream("""{"name":"../../evil","filament_settings_id":"x"}""".toByteArray()), dir)
        assertEquals(0, bad.imported)
        assertFalse(File(dir.parentFile, "evil.json").exists())
    }

    @Test
    fun folderImportTakesDesktopUserPresetsButNotSystemOnes() {
        assertTrue(ProfileBackup.isFolderPreset("OrcaSlicer/user/123/filament/Overture PETG.json"))
        assertTrue(ProfileBackup.isFolderPreset("filament/Overture PETG.json"))
        assertTrue(ProfileBackup.isFolderPreset("user/default/machine/My P1S.json"))
        assertFalse(ProfileBackup.isFolderPreset("OrcaSlicer/user/123/filament/Overture PETG.info"))
        assertFalse(ProfileBackup.isFolderPreset("OrcaSlicer/system/BBL/filament/Generic PLA.json"))
        assertFalse(ProfileBackup.isFolderPreset("OrcaSlicer/OrcaSlicer.conf.json"))
        val dir = tmp.newFolder("phone")
        val petg = """{"name":"Overture PETG","inherits":"Generic PETG @BBL X1C","filament_settings_id":"Overture PETG"}"""
        val result = ProfileBackup.importEntries(listOf("filament/Overture PETG.json" to petg.toByteArray()), dir)
        assertEquals(mapOf("Overture PETG" to "Generic PETG @BBL X1C"), result.filaments)
        assertTrue(File(dir, "filament/Overture PETG.json").isFile)
    }
}
