package org.orcapod.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppUpdatesTest {
    private fun release(tag: String, assets: String) = """
        {"tag_name": "$tag", "name": "OrcaPod build 42",
         "html_url": "https://github.com/o/r/releases/tag/$tag", "assets": [$assets]}
    """.trimIndent()

    private val apk = """{"name": "OrcaPod.apk", "browser_download_url": "https://github.com/o/r/releases/download/android-build-42/OrcaPod.apk"}"""

    @Test
    fun readsTheBuildNumberAndApk() {
        val update = AppUpdates.parseLatest(release("android-build-42", """{"name": "notes.txt", "browser_download_url": "x"}, $apk"""))!!
        assertEquals(42, update.versionCode)
        assertEquals("OrcaPod build 42", update.name)
        assertEquals("https://github.com/o/r/releases/download/android-build-42/OrcaPod.apk", update.apkUrl)
    }

    @Test
    fun ignoresOtherReleases() {
        // Desktop OrcaSlicer releases share the repository's release list.
        assertNull(AppUpdates.parseLatest(release("v2.3.1", apk)))
        assertNull(AppUpdates.parseLatest(release("android-build-x", apk)))
        assertNull(AppUpdates.parseLatest(release("android-build-42", "")))
    }
}
