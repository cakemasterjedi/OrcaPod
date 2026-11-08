package org.orcapod.app.data

import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** The three user preset kinds, by their folder under `user/default` and in a bundle. */
enum class ProfileKind(val dir: String, val bundleDir: String, val idKey: String) {
    PRINTER("machine", "printer", "printer_settings_id"),
    FILAMENT("filament", "filament", "filament_settings_id"),
    PROCESS("process", "process", "print_settings_id"),
}

/**
 * Export and import of the user's own presets in OrcaSlicer's preset zip
 * layout: `printer/`, `filament/` and `process/` folders of preset JSON files,
 * as desktop's "Export preset bundle" writes them.
 *
 * Exports carry no `bundle_structure.json`, so desktop's "Import configs"
 * adds them as ordinary user presets rather than a read-only bundle. Imports
 * accept desktop's `.orca_printer`, `.orca_filament` and `.zip` exports, and
 * single preset `.json` files.
 */
object ProfileBackup {
    data class ImportResult(val added: List<String>, val replaced: List<String>, val skipped: List<String>) {
        val imported get() = added.size + replaced.size
    }

    /** The user's preset files under [userDir], in kind and name order. */
    fun userFiles(userDir: File): List<Pair<ProfileKind, File>> = ProfileKind.entries.flatMap { kind ->
        File(userDir, kind.dir).listFiles { f -> f.isFile && f.name.endsWith(JSON) }.orEmpty()
            .sortedBy { it.name.lowercase() }
            .map { kind to it }
    }

    /** Writes every user preset under [userDir] to [out] as a zip; returns the number written. */
    fun export(userDir: File, out: OutputStream): Int {
        val files = userFiles(userDir)
        ZipOutputStream(out).use { zip ->
            for ((kind, file) in files) {
                zip.putNextEntry(ZipEntry("${kind.bundleDir}/${file.name}"))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return files.size
    }

    /**
     * Copies the presets in [input] (a preset zip or one preset JSON) into
     * [userDir], replacing user presets of the same name. Files that are not
     * presets are skipped.
     */
    fun import(input: InputStream, userDir: File): ImportResult {
        val bytes = input.readBytes()
        val entries = if (isZip(bytes)) unzip(bytes) else listOf("preset.json" to bytes)
        val added = ArrayList<String>()
        val replaced = ArrayList<String>()
        val skipped = ArrayList<String>()
        for ((path, data) in entries) {
            val leaf = path.substringAfterLast('/')
            if (!leaf.endsWith(JSON, ignoreCase = true) || leaf == BUNDLE_STRUCTURE) continue
            val json = try {
                JSONObject(String(data, Charsets.UTF_8))
            } catch (_: JSONException) {
                skipped += leaf
                continue
            }
            val kind = classify(json, path)
            val name = presetName(json, leaf)
            if (kind == null || name == null) {
                skipped += leaf
                continue
            }
            val target = File(File(userDir, kind.dir), "$name$JSON")
            if (target.exists()) replaced += name else added += name
            writeAtomically(target, data)
            // A stale sync record would describe the preset that was replaced.
            File(target.parentFile, "$name.info").delete()
        }
        return ImportResult(added, replaced, skipped)
    }

    /** The preset kind: by its settings id key, its `type`, then the folder it came from. */
    internal fun classify(json: JSONObject, path: String): ProfileKind? {
        ProfileKind.entries.firstOrNull { json.has(it.idKey) }?.let { return it }
        when (json.optString("type")) {
            "machine", "printer" -> return ProfileKind.PRINTER
            "filament" -> return ProfileKind.FILAMENT
            "process", "print" -> return ProfileKind.PROCESS
        }
        val folders = path.split('/').dropLast(1)
        return ProfileKind.entries.firstOrNull { k -> folders.any { it == k.dir || it == k.bundleDir } }
    }

    /** The name the engine will load the preset under; null when it is not a usable file name. */
    internal fun presetName(json: JSONObject, leaf: String): String? {
        val name = json.optString("name").ifEmpty { leaf.removeSuffix(JSON).removeSuffix(".JSON") }.trim()
        if (name.isEmpty() || name == "." || name == ".." || name.any { it in "/\\" || it.code < 0x20 }) return null
        return name
    }

    internal fun writeAtomically(target: File, data: ByteArray) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, ".${target.name}.tmp")
        tmp.writeBytes(data)
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("Cannot write ${target.name}")
        }
    }

    private fun isZip(bytes: ByteArray) = bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()

    private fun unzip(bytes: ByteArray): List<Pair<String, ByteArray>> {
        val out = ArrayList<Pair<String, ByteArray>>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                // Bounded, so a zip bomb fails instead of exhausting memory.
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(16 shl 10)
                while (true) {
                    val n = zip.read(chunk)
                    if (n < 0) break
                    buffer.write(chunk, 0, n)
                    if (buffer.size() > MAX_PRESET_BYTES) throw IOException("${entry.name} is too large to be a preset")
                }
                out += entry.name.replace('\\', '/') to buffer.toByteArray()
            }
        }
        return out
    }

    private const val JSON = ".json"
    private const val BUNDLE_STRUCTURE = "bundle_structure.json"
    private const val MAX_PRESET_BYTES = 8L shl 20
}
