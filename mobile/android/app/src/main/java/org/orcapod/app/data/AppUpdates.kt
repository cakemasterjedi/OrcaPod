package org.orcapod.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import org.orcapod.app.library.Http
import java.io.File

/** A newer build published on the GitHub releases page. */
data class AppUpdate(val versionCode: Int, val name: String, val apkUrl: String, val pageUrl: String)

/**
 * The in-app update check. CI publishes every main build as a GitHub release
 * tagged `android-build-<versionCode>` with the APK attached; all builds are
 * signed with the same key, so a newer one installs over the app in place.
 */
object AppUpdates {
    private const val TAG_PREFIX = "android-build-"
    private const val PREFS = "app_updates"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_SKIPPED = "skipped"
    /** How often the automatic check on launch runs. */
    private const val CHECK_INTERVAL_MS = 12L * 60 * 60 * 1000

    fun releasesPage(repo: String) = "https://github.com/$repo/releases/latest"

    /** Reads the GitHub "latest release" JSON; null when it is not an app build or has no APK. */
    fun parseLatest(json: String): AppUpdate? {
        val release = JSONObject(json)
        val tag = release.optString("tag_name")
        if (!tag.startsWith(TAG_PREFIX)) return null
        val code = tag.removePrefix(TAG_PREFIX).toIntOrNull() ?: return null
        val assets = release.optJSONArray("assets") ?: return null
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) } ?: return null
        return AppUpdate(
            versionCode = code,
            name = release.optString("name").ifEmpty { tag },
            apkUrl = apk.getString("browser_download_url"),
            pageUrl = release.optString("html_url"),
        )
    }

    /** Blocking: the latest release when it is newer than [currentCode], else null. */
    fun check(repo: String, currentCode: Int): AppUpdate? {
        val json = Http.getText(
            "https://api.github.com/repos/$repo/releases/latest",
            mapOf("Accept" to "application/vnd.github+json"),
        )
        return parseLatest(json)?.takeIf { it.versionCode > currentCode }
    }

    /** Whether the automatic check is due, and records that it ran. */
    fun autoCheckDue(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (now - prefs.getLong(KEY_LAST_CHECK, 0) < CHECK_INTERVAL_MS) return false
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
        return true
    }

    /** The build the user said "Later" to, so the automatic check does not ask again for it. */
    fun skipped(context: Context): Int = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_SKIPPED, 0)

    fun skip(context: Context, versionCode: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_SKIPPED, versionCode).apply()
    }

    /** Blocking: downloads the APK into the cache, replacing an earlier download. */
    fun download(context: Context, update: AppUpdate, onProgress: (Long, Long) -> Unit): File {
        val dir = File(context.cacheDir, "updates")
        dir.deleteRecursively()
        return Http.download(update.apkUrl, dir, nameHint = "OrcaPod-build-${update.versionCode}.apk", onProgress = onProgress)
    }

    /** Whether Android lets this app hand an APK to the installer (the per-app "Install unknown apps" switch). */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    /** Opens the "Install unknown apps" setting for this app. */
    fun allowInstallsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /** Hands [apk] to the system installer, which installs it over this app. */
    fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
