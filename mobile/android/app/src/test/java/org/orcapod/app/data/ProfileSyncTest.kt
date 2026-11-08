package org.orcapod.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProfileSyncTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var phone: File
    private lateinit var cloud: File
    private val state = HashMap<String, String>()

    @Before
    fun setUp() {
        phone = tmp.newFolder("phone")
        cloud = tmp.newFolder("cloud")
    }

    private fun sync() = ProfileSync(LocalProfileFolder(phone), LocalProfileFolder(cloud), state) { 0L }.run()

    private fun put(root: File, key: String, text: String, mtime: Long = 1_000_000L) =
        File(root, key).apply { parentFile.mkdirs(); writeText(text); setLastModified(mtime) }

    private fun text(root: File, key: String) = File(root, key).takeIf { it.isFile }?.readText()

    @Test
    fun firstSyncMergesBothSides() {
        put(phone, "process/A.json", "a")
        put(cloud, "filament/B.json", "b")
        val r = sync()
        assertEquals(1, r.pushed)
        assertEquals(1, r.pulled)
        assertTrue(r.localChanged)
        assertEquals("a", text(cloud, "process/A.json"))
        assertEquals("b", text(phone, "filament/B.json"))
        assertEquals(0, sync().changes)
    }

    @Test
    fun editsAndDeletesFlowOneWay() {
        put(phone, "process/A.json", "a")
        put(phone, "process/B.json", "b")
        sync()

        put(cloud, "process/A.json", "a2")
        File(phone, "process/B.json").delete()
        val r = sync()
        assertEquals(1, r.pulled)
        assertEquals(1, r.deletedRemote)
        assertEquals("a2", text(phone, "process/A.json"))
        assertEquals(null, text(cloud, "process/B.json"))

        File(cloud, "process/A.json").delete()
        put(phone, "process/A.info", "setting_id = 1")
        val r2 = sync()
        assertEquals(1, r2.deletedLocal)
        assertFalse(File(phone, "process/A.json").exists())
        assertFalse("the engine's sync record goes with the preset", File(phone, "process/A.info").exists())
    }

    @Test
    fun bothEditedKeepsNewerAndSavesTheOther() {
        put(phone, "machine/P.json", "p")
        sync()
        put(phone, "machine/P.json", "phone edit", mtime = 2_000_000L)
        put(cloud, "machine/P.json", "cloud edit", mtime = 3_000_000L)
        val r = sync()
        assertEquals(1, r.conflicts)
        assertEquals("cloud edit", text(phone, "machine/P.json"))
        val saved = File(cloud, ProfileSync.CONFLICTS).walk().filter { it.isFile }.toList()
        assertEquals(1, saved.size)
        assertEquals("phone edit", saved.single().readText())
        assertEquals(0, sync().changes)
    }

    @Test
    fun deletedOnOneSideButEditedOnTheOtherIsKept() {
        put(phone, "filament/F.json", "f")
        sync()
        File(phone, "filament/F.json").delete()
        put(cloud, "filament/F.json", "f2")
        sync()
        assertEquals("f2", text(phone, "filament/F.json"))
    }

    @Test
    fun ignoresFilesOutsideThePresetFolders() {
        put(cloud, "notes.json", "x")
        put(cloud, "process/readme.txt", "x")
        put(phone, "physical_machine/M.json", "m")
        assertEquals(0, sync().changes)
        assertFalse(File(phone, "notes.json").exists())
        assertFalse(File(cloud, "physical_machine").exists())
    }
}
