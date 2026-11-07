package org.orcapod.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/** A project in the recent list: where it lives, its name and when it was last opened or saved. */
data class RecentProject(val uri: String, val name: String, val time: Long) {
    internal val key: String get() = sha1(uri)
}

/** The plate left unsaved when the app last went to the background. */
data class Recovery(val file: File, val name: String?, val uri: String?, val time: Long)

/**
 * Project bookkeeping outside the engine: the recent projects list (with a
 * thumbnail of each), and the recovery copy of an unsaved plate, written when
 * the app goes to the background so a killed process loses nothing (desktop's
 * project backup).
 */
class ProjectStore(context: Context) {
    private val prefs = context.getSharedPreferences("projects", Context.MODE_PRIVATE)
    private val thumbDir = File(context.filesDir, "recent").apply { mkdirs() }
    private val recoveryDir = File(context.filesDir, "recovery").apply { mkdirs() }

    val recoveryFile: File get() = File(recoveryDir, "recovery.3mf")
    private val recoveryMeta: File get() = File(recoveryDir, "recovery.json")

    fun recent(): List<RecentProject> = parseRecent(prefs.getString(KEY_RECENT, null))

    /** Moves [project] to the top of the list, replacing its thumbnail when one is given. */
    fun remember(project: RecentProject, thumbnail: Bitmap?) {
        val list = listOf(project) + recent().filter { it.uri != project.uri }
        val kept = list.take(MAX_RECENT)
        for (dropped in list.drop(MAX_RECENT)) thumbFile(dropped).delete()
        prefs.edit().putString(KEY_RECENT, writeRecent(kept)).apply()
        if (thumbnail != null) {
            runCatching { thumbFile(project).outputStream().use { thumbnail.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        }
    }

    fun forget(uri: String) {
        recent().firstOrNull { it.uri == uri }?.let { thumbFile(it).delete() }
        prefs.edit().putString(KEY_RECENT, writeRecent(recent().filter { it.uri != uri })).apply()
    }

    fun thumbnail(project: RecentProject): Bitmap? =
        thumbFile(project).takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path) }

    private fun thumbFile(project: RecentProject) = File(thumbDir, project.key + ".png")

    fun recovery(): Recovery? {
        val file = recoveryFile
        if (!file.isFile) return null
        val meta = runCatching { JSONObject(recoveryMeta.readText()) }.getOrNull()
        return Recovery(
            file,
            meta?.optString("name")?.ifEmpty { null },
            meta?.optString("uri")?.ifEmpty { null },
            meta?.optLong("time") ?: file.lastModified(),
        )
    }

    /** Records which project [recoveryFile] (already written) belongs to. */
    fun recoveryWritten(name: String?, uri: String?) {
        recoveryMeta.writeText(
            JSONObject().put("name", name.orEmpty()).put("uri", uri.orEmpty()).put("time", System.currentTimeMillis()).toString(),
        )
    }

    fun clearRecovery() {
        recoveryFile.delete()
        recoveryMeta.delete()
    }

    companion object {
        private const val KEY_RECENT = "recent"
        const val MAX_RECENT = 12

        internal fun parseRecent(json: String?): List<RecentProject> {
            val array = runCatching { JSONArray(json ?: return emptyList()) }.getOrNull() ?: return emptyList()
            return (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val uri = o.optString("uri")
                if (uri.isEmpty()) null else RecentProject(uri, o.optString("name", "Project"), o.optLong("time"))
            }
        }

        internal fun writeRecent(list: List<RecentProject>): String = JSONArray().apply {
            for (p in list) put(JSONObject().put("uri", p.uri).put("name", p.name).put("time", p.time))
        }.toString()

        /** The first plate's picture stored in a project 3MF (desktop writes Metadata/plate_1.png). */
        fun thumbnailOf(project: File): Bitmap? = runCatching {
            ZipFile(project).use { zip ->
                val entry = zip.getEntry("Metadata/plate_1.png") ?: zip.getEntry("Metadata/thumbnail.png")
                    ?: return@use null
                zip.getInputStream(entry).use { BitmapFactory.decodeStream(it) }
            }
        }.getOrNull()

        /** True for an OrcaSlicer, Bambu Studio or PrusaSlicer project, not a bare geometry 3MF. */
        fun isProject(file: File): Boolean = runCatching {
            ZipFile(file).use { zip ->
                zip.getEntry("Metadata/project_settings.config") != null ||
                    zip.getEntry("Metadata/Slic3r_PE.config") != null
            }
        }.getOrDefault(false)

        /** Display name without the .3mf extension. */
        fun projectName(fileName: String): String =
            fileName.removeSuffix(".3mf").removeSuffix(".3MF").ifBlank { "Project" }
    }
}

private fun sha1(text: String): String =
    MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
