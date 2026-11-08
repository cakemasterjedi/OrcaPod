package org.orcapod.app.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.IOException

/**
 * A preset folder inside a folder the user picked through the Storage Access
 * Framework, typically one a cloud or sync app keeps in sync.
 *
 * Presets go in an `OrcaPod profiles` subfolder, unless the picked folder
 * already holds `machine/`, `filament/` or `process/` (a copy of desktop
 * OrcaSlicer's `user/default`), in which case it is used as is.
 */
class SafProfileFolder(private val context: Context, treeUri: Uri) : ProfileFolder {
    private val base: DocumentFile
    private val files = HashMap<String, DocumentFile>()

    init {
        val tree = DocumentFile.fromTreeUri(context, treeUri)
        if (tree == null || !tree.isDirectory || !tree.canWrite()) throw IOException("The sync folder is not available")
        base = if (ProfileKind.entries.any { tree.findFile(it.dir)?.isDirectory == true }) {
            tree
        } else {
            tree.findFile(SUBFOLDER)?.takeIf { it.isDirectory } ?: tree.createDirectory(SUBFOLDER)
                ?: throw IOException("Cannot create \"$SUBFOLDER\" in the sync folder")
        }
    }

    override fun list(): Map<String, Long> {
        files.clear()
        for (kind in ProfileKind.entries) {
            val dir = base.findFile(kind.dir)?.takeIf { it.isDirectory } ?: continue
            for (f in dir.listFiles()) {
                val name = f.name ?: continue
                if (f.isFile && name.endsWith(".json")) files["${kind.dir}/$name"] = f
            }
        }
        return files.mapValues { it.value.lastModified() }
    }

    override fun read(key: String): ByteArray {
        val f = files[key] ?: throw IOException("$key is missing from the sync folder")
        return context.contentResolver.openInputStream(f.uri)?.use { it.readBytes() }
            ?: throw IOException("Cannot read $key from the sync folder")
    }

    override fun write(key: String, data: ByteArray) {
        val f = files[key] ?: create(key).also { files[key] = it }
        context.contentResolver.openOutputStream(f.uri, "wt")?.use { it.write(data) }
            ?: throw IOException("Cannot write $key to the sync folder")
    }

    override fun delete(key: String) {
        files.remove(key)?.delete()
    }

    private fun create(key: String): DocumentFile {
        var dir = base
        val parts = key.split('/')
        for (part in parts.dropLast(1)) {
            dir = dir.findFile(part)?.takeIf { it.isDirectory } ?: dir.createDirectory(part)
                ?: throw IOException("Cannot create $part in the sync folder")
        }
        val name = parts.last()
        // Some providers append an extension to the display name; octet-stream keeps it as given.
        val file = dir.createFile("application/octet-stream", name) ?: throw IOException("Cannot create $key in the sync folder")
        if (file.name != name) file.renameTo(name)
        return file
    }

    companion object {
        const val SUBFOLDER = "OrcaPod profiles"

        /** A readable name for the picked folder, for the UI. */
        fun displayName(context: Context, treeUri: Uri): String =
            DocumentFile.fromTreeUri(context, treeUri)?.name ?: treeUri.lastPathSegment.orEmpty()
    }
}
