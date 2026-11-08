package org.orcapod.app.data

import java.io.File
import java.security.MessageDigest

/**
 * A folder of preset files keyed by `<kind dir>/<name>.json` (for example
 * `process/0.20mm Fine.json`), the layout of OrcaSlicer's `user/default`.
 */
interface ProfileFolder {
    /** Every preset file, with its last-modified time in milliseconds. */
    fun list(): Map<String, Long>
    fun read(key: String): ByteArray
    fun write(key: String, data: ByteArray)
    fun delete(key: String)
}

/** A preset folder on the local file system: the engine's `user/default`. */
class LocalProfileFolder(private val root: File) : ProfileFolder {
    override fun list(): Map<String, Long> = ProfileKind.entries.flatMap { kind ->
        File(root, kind.dir).listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty()
            .map { "${kind.dir}/${it.name}" to it.lastModified() }
    }.toMap()

    override fun read(key: String) = file(key).readBytes()

    override fun write(key: String, data: ByteArray) = ProfileBackup.writeAtomically(file(key), data)

    override fun delete(key: String) {
        val f = file(key)
        f.delete()
        // The engine's sync record for the preset.
        File(f.parentFile, f.name.removeSuffix(".json") + ".info").delete()
    }

    private fun file(key: String): File {
        val f = File(root, key)
        require(f.canonicalPath.startsWith(root.canonicalPath + File.separator)) { "Bad preset path $key" }
        return f
    }
}

/**
 * Two-way sync of user presets between the phone and a folder that a cloud
 * app keeps in sync, as a three-way merge against the content hashes recorded
 * at the last sync ([state]):
 *
 * - changed on one side only: copied to the other side, deletions included;
 * - changed on both sides: the newer file wins, and the older one is kept in
 *   the folder under `conflicts/` so nothing is lost;
 * - deleted on one side and changed on the other: the changed file is kept.
 *
 * On the first sync [state] is empty, so files on either side are copied
 * across and differing files are conflicts.
 */
class ProfileSync(
    private val local: ProfileFolder,
    private val remote: ProfileFolder,
    private val state: MutableMap<String, String>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Result(
        val pulled: Int = 0,
        val pushed: Int = 0,
        val deletedLocal: Int = 0,
        val deletedRemote: Int = 0,
        val conflicts: Int = 0,
    ) {
        /** The phone's presets changed, so the engine must reload them. */
        val localChanged get() = pulled + deletedLocal > 0
        val changes get() = pulled + pushed + deletedLocal + deletedRemote
    }

    fun run(): Result {
        val localFiles = local.list()
        val remoteFiles = remote.list()
        var pulled = 0
        var pushed = 0
        var deletedLocal = 0
        var deletedRemote = 0
        var conflicts = 0
        val keys = (localFiles.keys + remoteFiles.keys + state.keys).toSortedSet()
        for (key in keys) {
            val localData = if (key in localFiles) local.read(key) else null
            val remoteData = if (key in remoteFiles) remote.read(key) else null
            val l = localData?.let(::hash)
            val r = remoteData?.let(::hash)
            val base = state[key]
            when {
                l == r -> Unit
                l == base -> if (remoteData == null) { local.delete(key); deletedLocal++ } else { local.write(key, remoteData); pulled++ }
                r == base -> if (localData == null) { remote.delete(key); deletedRemote++ } else { remote.write(key, localData); pushed++ }
                localData == null -> { local.write(key, remoteData!!); pulled++ }
                remoteData == null -> { remote.write(key, localData); pushed++ }
                else -> {
                    conflicts++
                    val localNewer = localFiles.getValue(key) >= remoteFiles.getValue(key)
                    val (kept, lost) = if (localNewer) localData to remoteData else remoteData to localData
                    remote.write(conflictKey(key, if (localNewer) "folder" else "phone"), lost)
                    if (localNewer) { remote.write(key, kept); pushed++ } else { local.write(key, kept); pulled++ }
                }
            }
        }
        // Record what both sides now hold.
        state.clear()
        for ((key, _) in local.list()) state[key] = hash(local.read(key))
        return Result(pulled, pushed, deletedLocal, deletedRemote, conflicts)
    }

    private fun conflictKey(key: String, from: String): String {
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HHmmss", java.util.Locale.ROOT).format(java.util.Date(clock()))
        return "$CONFLICTS/${key.removeSuffix(".json")} ($from copy $stamp).json"
    }

    companion object {
        const val CONFLICTS = "conflicts"

        fun hash(data: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
    }
}
