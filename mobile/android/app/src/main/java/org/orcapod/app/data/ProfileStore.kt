package org.orcapod.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * OrcaSlicer's system profiles, shipped as assets/profiles.zip, installed per
 * vendor (printer brand):
 *
 *     <filesDir>/orca/system/<Vendor>.json + <Vendor>/   (the active brand)
 *     <filesDir>/orca-brands/<Vendor>.json + <Vendor>/   (other installed brands)
 *     <filesDir>/orca/user/default/                       (user presets)
 *
 * The engine loads every vendor under system/, and parsing a vendor's preset
 * tree is the bulk of startup, so only the brand of the printer in use sits
 * there. Switching brand moves the bundles between the two directories (a
 * rename on the same filesystem) and reloads the engine's presets. Installing
 * only the brands the user owns keeps the brand list short, like desktop's
 * setup wizard. The filament library and the vendor-independent files are
 * always in system/.
 */
class ProfileStore(private val context: Context) {
    val dataDir = File(context.filesDir, "orca")
    private val systemDir = File(dataDir, "system")
    private val brandsDir = File(context.filesDir, "orca-brands")
    private val prefs = context.getSharedPreferences("profiles", Context.MODE_PRIVATE)

    /**
     * Vendors the user picked; empty before setup. After an app update they
     * may not be installed yet (see [isInstalled]); reinstall them then.
     */
    val selectedVendors: Set<String>
        get() = prefs.getStringSet(KEY_VENDORS, emptySet()).orEmpty()

    /** The installed vendor whose profiles the engine loads; null before setup. */
    val activeVendor: String?
        get() = prefs.getString(KEY_ACTIVE, null)?.takeIf { it in selectedVendors } ?: defaultVendor(selectedVendors)

    /** Every vendor in the bundled profiles, sorted case-insensitively. */
    suspend fun availableVendors(): List<String> = withContext(Dispatchers.IO) {
        ZipFile(bundle()).use { zip -> vendorsIn(zip).filter { it !in ALWAYS }.sortedWith(String.CASE_INSENSITIVE_ORDER) }
    }

    /**
     * Replaces the installed system profiles with [vendors] and activates the
     * current brand when it is still among them, else the first one. User
     * presets are kept.
     */
    suspend fun install(vendors: Set<String>) = withContext(Dispatchers.IO) {
        prefs.edit().remove(KEY_STAMP).apply()
        systemDir.deleteRecursively()
        brandsDir.deleteRecursively()
        systemDir.mkdirs()
        brandsDir.mkdirs()
        File(dataDir, "user/default").mkdirs()
        val active = prefs.getString(KEY_ACTIVE, null)?.takeIf { it in vendors } ?: defaultVendor(vendors)
        ZipFile(bundle()).use { zip ->
            val all = vendorsIn(zip)
            for (entry in zip.entries()) {
                if (entry.isDirectory) continue
                val owner = if ('/' in entry.name) entry.name.substringBefore('/') else entry.name.removeSuffix(".json")
                // Vendor files follow the selection; shared files (blacklist.json, ...) always go in system/.
                val target = when {
                    owner !in all || owner in ALWAYS || owner == active -> systemDir
                    owner in vendors -> brandsDir
                    else -> continue
                }
                val out = File(target, entry.name)
                if (!out.canonicalPath.startsWith(target.canonicalPath + File.separator)) continue // zip slip
                out.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
            }
        }
        prefs.edit()
            .putStringSet(KEY_VENDORS, vendors)
            .putString(KEY_ACTIVE, active)
            .putLong(KEY_STAMP, appStamp())
            .apply()
    }

    /**
     * Makes [vendor] the brand the engine loads on its next preset load;
     * every other installed brand is parked outside system/. Safe to repeat
     * after an interrupted switch; missing files are extracted again.
     */
    suspend fun activate(vendor: String) = withContext(Dispatchers.IO) {
        require(vendor in selectedVendors) { "$vendor is not installed" }
        prefs.edit().putString(KEY_ACTIVE, vendor).apply()
        try {
            switchBrand(systemDir, brandsDir, selectedVendors, vendor)
        } catch (_: IOException) {
            install(selectedVendors) // files went missing: extract the brands again, this one active
        }
    }

    /** The printer models and filaments that [vendors] (plus the shared filament library) bring. */
    suspend fun catalog(vendors: Set<String>): ProfileCatalog = withContext(Dispatchers.IO) {
        ZipFile(bundle()).use { zip ->
            val owners = vendors + ALWAYS
            val files = zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.endsWith(".json") && it.name.substringBefore('/') in owners && '/' in it.name }
                .map { e -> e.name to zip.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) } }
            ProfileCatalog.parse(files)
        }
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
        private const val KEY_ACTIVE = "active_vendor"
        private const val KEY_STAMP = "installed_stamp"
        private const val KEY_BUNDLE_STAMP = "bundle_stamp"

        /** Installed whatever the selection: shared filament definitions. */
        val ALWAYS = setOf("OrcaFilamentLibrary")

        /** Preselected on first launch. */
        val SUGGESTED = setOf("Custom")

        /** The brand activated when none was picked yet: the first one, preferring a real brand to Custom. */
        fun defaultVendor(vendors: Set<String>): String? =
            vendors.sortedWith(compareBy<String> { it in SUGGESTED }.thenBy(String.CASE_INSENSITIVE_ORDER) { it }).firstOrNull()

        /**
         * Moves every vendor of [installed] other than [active] (its <Vendor>.json
         * and <Vendor>/) from [systemDir] to [brandsDir], and [active] the other
         * way. Each piece moves on its own, so a run cut short by the process
         * dying is finished by the next run.
         */
        fun switchBrand(systemDir: File, brandsDir: File, installed: Set<String>, active: String) {
            brandsDir.mkdirs()
            for (vendor in installed) {
                val (from, to) = if (vendor == active) brandsDir to systemDir else systemDir to brandsDir
                for (name in listOf("$vendor.json", vendor)) {
                    val src = File(from, name)
                    if (!src.exists()) continue
                    val dst = File(to, name)
                    dst.deleteRecursively()
                    if (!src.renameTo(dst)) throw IOException("Could not move the $vendor profiles")
                }
            }
            if (!File(systemDir, "$active.json").isFile || !File(systemDir, active).isDirectory)
                throw IOException("The $active profiles are missing; reinstall the brand")
        }
    }
}
