package org.orcapod.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ProjectStoreTest {
    @Test
    fun recentListRoundTrips() {
        val list = listOf(RecentProject("content://a/1", "Bracket", 10), RecentProject("content://a/2", "Vase", 20))
        assertEquals(list, ProjectStore.parseRecent(ProjectStore.writeRecent(list)))
        assertEquals(emptyList<RecentProject>(), ProjectStore.parseRecent(null))
        assertEquals(emptyList<RecentProject>(), ProjectStore.parseRecent("not json"))
    }

    @Test
    fun tellsProjectsFromGeometry() {
        val dir = Files.createTempDirectory("projects").toFile()
        try {
            val project = zip(File(dir, "p.3mf"), "3D/3dmodel.model", "Metadata/project_settings.config")
            val prusa = zip(File(dir, "q.3mf"), "3D/3dmodel.model", "Metadata/Slic3r_PE.config")
            val geometry = zip(File(dir, "g.3mf"), "3D/3dmodel.model")
            assertTrue(ProjectStore.isProject(project))
            assertTrue(ProjectStore.isProject(prusa))
            assertFalse(ProjectStore.isProject(geometry))
            assertFalse(ProjectStore.isProject(File(dir, "missing.3mf")))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun namesProjectsWithoutTheExtension() {
        assertEquals("Bracket", ProjectStore.projectName("Bracket.3mf"))
        assertEquals("Project", ProjectStore.projectName(".3mf"))
    }

    private fun zip(file: File, vararg entries: String): File {
        ZipOutputStream(file.outputStream()).use { z ->
            for (e in entries) { z.putNextEntry(ZipEntry(e)); z.write(1); z.closeEntry() }
        }
        return file
    }
}
