package org.orcapod.app.library

import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.util.zip.ZipInputStream

/** Naming and unpacking of files downloaded from model sites. */
object ModelFiles {
    /** Extensions the plate can import (STEP is reported by the importer when unsupported). */
    val MODEL_EXTENSIONS = setOf("stl", "3mf", "obj", "amf", "step", "stp")

    /** Model sites pack multi-part models as zip archives; larger ones are refused. */
    private const val MAX_UNPACKED_BYTES = 1L shl 30

    fun extension(name: String) = name.substringAfterLast('.', "").lowercase()

    fun isModel(name: String) = extension(name) in MODEL_EXTENSIONS

    /** Strips directories and characters that are not safe in a file name. */
    fun safeName(name: String): String {
        val base = name.replace('\\', '/').substringAfterLast('/').trim()
        val clean = base.replace(Regex("[\\u0000-\\u001f<>:\"|?*]"), "_").trimStart('.')
        return clean.take(120).ifEmpty { "model" }
    }

    /**
     * The file name a download should be saved under: the Content-Disposition
     * name when the server sends one, else the last segment of the URL path.
     */
    fun nameFor(contentDisposition: String?, url: String, fallback: String = "download"): String {
        contentDisposition?.let { cd ->
            Regex("filename\\*\\s*=\\s*(?:UTF-8|utf-8)''([^;]+)").find(cd)?.let {
                return safeName(URLDecoder.decode(it.groupValues[1].trim().trim('"'), "UTF-8"))
            }
            Regex("filename\\s*=\\s*\"([^\"]+)\"").find(cd)?.let { return safeName(it.groupValues[1]) }
            Regex("filename\\s*=\\s*([^;]+)").find(cd)?.let { return safeName(it.groupValues[1].trim()) }
        }
        val path = url.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
        val decoded = runCatching { URLDecoder.decode(path, "UTF-8") }.getOrDefault(path)
        return safeName(decoded.ifEmpty { fallback })
    }

    /** A zip archive that is not itself a model (3MF files are zip archives too). */
    fun isArchive(file: File): Boolean {
        val ext = extension(file.name)
        if (ext == "zip") return true
        if (ext in MODEL_EXTENSIONS) return false
        return file.inputStream().use { s ->
            val magic = ByteArray(4)
            s.read(magic) == 4 && magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte() &&
                magic[2] == 3.toByte() && magic[3] == 4.toByte()
        }
    }

    /**
     * The model files in [file]: the file itself when it is a model, the models
     * inside it when it is a zip archive (unpacked flat into [dir]), else none.
     */
    fun unpack(file: File, dir: File): List<File> {
        if (!isArchive(file)) return if (isModel(file.name)) listOf(file) else emptyList()
        dir.mkdirs()
        val out = ArrayList<File>()
        val used = HashSet<String>()
        var total = 0L
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val path = entry.name.replace('\\', '/')
                if (entry.isDirectory || path.startsWith("__MACOSX/") || !isModel(path)) continue
                // Entries are flattened to their base name, so no path can escape dir.
                var name = safeName(path)
                var n = 1
                while (!used.add(name.lowercase())) name = "${name.substringBeforeLast('.')} (${n++}).${extension(name)}"
                val target = File(dir, name)
                target.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val read = zip.read(buf)
                        if (read < 0) break
                        total += read
                        if (total > MAX_UNPACKED_BYTES) throw IOException("${file.name} unpacks to more than 1 GB")
                        o.write(buf, 0, read)
                    }
                }
                out += target
            }
        }
        return out
    }
}
