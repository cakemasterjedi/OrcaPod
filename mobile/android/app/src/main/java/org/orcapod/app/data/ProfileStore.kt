package org.orcapod.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

/**
 * OrcaSlicer's system profiles, shipped as assets/profiles.zip, installed per
 * vendor into the engine's data directory:
 *
 *     <filesDir>/orca/system/<Vendor>.json + <Vendor>/   (installed vendors)
 *     <filesDir>/orca/user/default/                       (user presets)
 *
 * Installing only the vendors the user owns keeps the printer list short and
 * preset loading fast, like desktop's setup wizard. The filament library and
 * the vendor-independent files are always installed.
 */
class ProfileStore(private val context: Context) {
    val dataDir = File(context.filesDir, "orca")
    private val systemDir = File(dataDir, "system")
    private val prefs = context.getSharedPreferences("profiles", Context.MODE_PRIVATE)

    /**
     * Vendors the user picked; empty before setup. After an app update they
     * may not be installed yet (see [isInstalled]); reinstall them then.
     */
    val selectedVendors: Set<String>
        get() = prefs.getStringSet(KEY_VENDORS, emptySet()).orEmpty()

    /** Every vendor in the bundled profiles, sorted case-insensitively. */
    suspend fun availableVendors(): List<String> = withContext(Dispatchers.IO) {
        ZipFile(bundle()).use { zip -> vendorsIn(zip).filter { it !in ALWAYS }.sortedWith(String.CASE_INSENSITIVE_ORDER) }
    }

    /** Replaces the installed system profiles with [vendors]; user presets are kept. */
    suspend fun install(vendors: Set<String>) = withContext(Dispatchers.IO) {
        prefs.edit().remove(KEY_STAMP).apply()
        systemDir.deleteRecursively()
        systemDir.mkdirs()
        File(dataDir, "user/default").mkdirs()
        val keep = vendors + ALWAYS
        val root = systemDir.canonicalPath + File.separator
        ZipFile(bundle()).use { zip ->
            val all = vendorsIn(zip)
            for (entry in zip.entries()) {
                if (entry.isDirectory) continue
                val owner = if ('/' in entry.name) entry.name.substringBefore('/') else entry.name.removeSuffix(".json")
                // Vendor files follow the selection; shared files (blacklist.json, ...) always go in.
                if (owner in all && owner !in keep) continue
                val out = File(systemDir, entry.name)
                if (!out.canonicalPath.startsWith(root)) continue // zip slip
                out.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
            }
        }
        prefs.edit().putStringSet(KEY_VENDORS, vendors).putLong(KEY_STAMP, appStamp()).apply()
    }

    /** True when profiles are installed and match this app build's bundle. */
    fun isInstalled(): Boolean = prefs.getLong(KEY_STAMP, -1L) == appStamp() && systemDir.isDirectory

    // A top-level <name>.json is a vendor bundle when a <name>/ directory sits beside it.
    private fun vendorsIn(zip: ZipFile): Set<String> {
        val names = zip.entries().asSequence().map { it.name }.toList()
        val dirs = names.filter { '/' in it }.map { it.substringBefore('/') }.toSet()
        return names.filter { '/' !in it && it.endsWith(".json") }.map { it.removeSuffix(".json") }.filter { it in dirs }.toSet()
    }

    // ZipFile needs a real file, so the stored asset is copied out once per app update.
    private fun bundle(): File {
        val file = File(context.noBackupFilesDir, "profiles.zip")
        val stamp = appStamp()
        if (!file.isFile || prefs.getLong(KEY_BUNDLE_STAMP, -1L) != stamp) {
            context.assets.open("profiles.zip").use { input -> file.outputStream().use { input.copyTo(it) } }
            prefs.edit().putLong(KEY_BUNDLE_STAMP, stamp).apply()
        }
        return file
    }

    @Suppress("DEPRECATION")
    private fun appStamp(): Long = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime

    companion object {
        private const val KEY_VENDORS = "vendors"
        private const val KEY_STAMP = "installed_stamp"
        private const val KEY_BUNDLE_STAMP = "bundle_stamp"

        /** Installed whatever the selection: shared filament definitions. */
        val ALWAYS = setOf("OrcaFilamentLibrary")

        /** Preselected on first launch. */
        val SUGGESTED = setOf("Custom")
    }
}
