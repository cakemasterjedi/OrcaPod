package org.orcapod.app.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LibraryTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun downloadNamesComeFromContentDispositionThenTheUrl() {
        assertEquals("Benchy v2.stl", ModelFiles.nameFor("attachment; filename*=UTF-8''Benchy%20v2.stl", "https://x/y"))
        assertEquals("cube.3mf", ModelFiles.nameFor("attachment; filename=\"cube.3mf\"", "https://x/y"))
        assertEquals("part.stl", ModelFiles.nameFor(null, "https://cdn.example.com/files/part.stl?sig=abc"))
        // A hostile name cannot leave the download directory.
        assertEquals("passwd.stl", ModelFiles.nameFor("attachment; filename=\"../../etc/passwd.stl\"", "https://x/y"))
    }

    @Test
    fun zipArchivesUnpackToTheirModelsFlat() {
        val zip = tmp.newFile("model.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            for ((name, body) in listOf(
                "files/a.stl" to "solid a",
                "../evil/b.STL" to "solid b",
                "other/a.stl" to "solid a2",
                "README.txt" to "hi",
                "__MACOSX/files/._a.stl" to "junk",
            )) {
                z.putNextEntry(ZipEntry(name))
                z.write(body.toByteArray())
                z.closeEntry()
            }
        }
        val out = tmp.newFolder("out")
        val models = ModelFiles.unpack(zip, out)
        assertEquals(listOf("a.stl", "b.STL", "a (1).stl"), models.map { it.name })
        assertTrue(models.all { it.parentFile == out })
        assertEquals("solid a2", models[2].readText())
    }

    @Test
    fun a3mfIsAModelNotAnArchive() {
        val f = tmp.newFile("plate.3mf")
        ZipOutputStream(f.outputStream()).use { z -> z.putNextEntry(ZipEntry("3D/3dmodel.model")); z.closeEntry() }
        assertFalse(ModelFiles.isArchive(f))
        assertEquals(listOf(f), ModelFiles.unpack(f, File(tmp.root, "x")))
        assertEquals(emptyList<File>(), ModelFiles.unpack(tmp.newFile("notes.txt"), File(tmp.root, "y")))
    }

    @Test
    fun thingiverseSearchAndFilesParse() {
        val things = Thingiverse.parseThings(
            """{"total":2,"hits":[
              {"id":763622,"name":"#3DBenchy","public_url":"https://www.thingiverse.com/thing:763622",
               "thumbnail":"https://cdn.thingiverse.com/t.jpg","preview_image":"https://cdn.thingiverse.com/p.jpg",
               "creator":{"name":"CreativeTools"},"like_count":12},
              {"name":"no id"}]}""",
        )
        assertEquals(1, things.size)
        assertEquals(Thing(763622, "#3DBenchy", "CreativeTools", "https://cdn.thingiverse.com/p.jpg", "https://www.thingiverse.com/thing:763622", 12), things[0])
        assertEquals(1, Thingiverse.parseThings("""[{"id":5,"name":"old"}]""").size)

        val files = Thingiverse.parseFiles(
            """[{"id":1,"name":"benchy.stl","size":11000,"direct_url":"https://cdn.thingiverse.com/benchy.stl"},
                {"id":2,"name":"photo.jpg","size":5,"download_url":"https://api.thingiverse.com/files/2/download"},
                {"id":3,"name":"part.3mf"}]""",
        )
        assertEquals(listOf(true, false, true), files.map { it.isModel })
        assertEquals("https://cdn.thingiverse.com/benchy.stl", files[0].downloadUrl)
        assertEquals("https://api.thingiverse.com/files/3/download", files[2].downloadUrl)
    }
}
