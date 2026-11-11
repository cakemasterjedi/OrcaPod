package org.orcapod.app.data

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.File
import java.io.InputStream

/** What the app was doing when it last closed unexpectedly, and the report to share. */
class CrashReport(val doing: String?, val outOfMemory: Boolean, val text: String)

/**
 * Keeps enough of a crash to find its cause later: a Kotlin crash's stack
 * trace, what the app was doing (a breadcrumb the slicer updates as it goes),
 * and, from Android 11, the system's own record of why the process ended,
 * which also covers engine (native) crashes and low-memory kills. The next
 * launch offers the report for sharing; nothing leaves the device otherwise.
 */
object CrashReports {
    private const val STACK = "crash-stack.txt"
    private const val BREADCRUMB = "crash-breadcrumb.txt"
    private const val PREFS = "crash_reports"
    private const val KEY_SEEN = "seen_exit"
    @Volatile private var installed = false
    @Volatile private var crumb: String? = null

    fun install(context: Context) {
        if (installed) return
        installed = true
        val dir = context.applicationContext.filesDir
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                File(dir, STACK).writeText("Thread ${thread.name}\n${Log.getStackTraceString(error)}")
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Records what the app is doing now ([what] null: nothing that could crash it). */
    fun breadcrumb(context: Context, what: String?) {
        if (what == crumb) return
        crumb = what
        try {
            val file = File(context.filesDir, BREADCRUMB)
            if (what == null) file.delete() else file.writeText(what)
        } catch (_: Exception) {
        }
    }

    /** The report of a crash since the last call, or null; it is handed out once. */
    fun takePending(context: Context): CrashReport? {
        val stackFile = File(context.filesDir, STACK)
        val crumbFile = File(context.filesDir, BREADCRUMB)
        val stack = stackFile.takeIf { it.exists() }?.readText()
        val doing = crumbFile.takeIf { it.exists() }?.readText()
        stackFile.delete()
        crumbFile.delete()
        crumb = null

        var exit: String? = null
        var outOfMemory = false
        if (Build.VERSION.SDK_INT >= 30) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val am = context.getSystemService(ActivityManager::class.java)
            val last = try {
                am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull()
            } catch (_: Exception) {
                null
            }
            if (last != null && last.timestamp > prefs.getLong(KEY_SEEN, 0)) {
                prefs.edit().putLong(KEY_SEEN, last.timestamp).apply()
                val crashed = when (last.reason) {
                    ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE,
                    ApplicationExitInfo.REASON_ANR -> true
                    // The system ends background apps for memory all the time:
                    // only one that was slicing counts.
                    ApplicationExitInfo.REASON_LOW_MEMORY -> doing != null
                    else -> false
                }
                if (crashed) {
                    outOfMemory = last.reason == ApplicationExitInfo.REASON_LOW_MEMORY
                    exit = buildString {
                        append("Exit: ").append(reasonName(last.reason))
                        last.description?.let { append(" (").append(it).append(')') }
                        append(", status ").append(last.status)
                        append(", memory used ").append(last.pss / 1024).append(" MB")
                        if (last.reason == ApplicationExitInfo.REASON_CRASH_NATIVE && Build.VERSION.SDK_INT >= 31) {
                            val trace = try {
                                last.traceInputStream?.use { tombstoneText(it) }
                            } catch (_: Exception) {
                                null
                            }
                            if (!trace.isNullOrEmpty()) append("\n").append(trace)
                        }
                    }
                }
            }
        }
        if (stack == null && exit == null) return null

        val memory = ActivityManager.MemoryInfo().also {
            context.getSystemService(ActivityManager::class.java).getMemoryInfo(it)
        }
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (_: Exception) {
            "?"
        }
        val text = buildString {
            append("OrcaPod ").append(version).append(" crash report\n")
            append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            append(", Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            append("Memory: ").append(memory.totalMem / (1024 * 1024)).append(" MB device, ")
            append(Runtime.getRuntime().maxMemory() / (1024 * 1024)).append(" MB app heap\n")
            append("Doing: ").append(doing ?: "nothing recorded").append('\n')
            exit?.let { append(it).append('\n') }
            stack?.let { append(it.take(8000)) }
        }
        return CrashReport(doing, outOfMemory, text)
    }

    @RequiresApi(30)
    private fun reasonName(reason: Int) = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "app crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "engine crash"
        ApplicationExitInfo.REASON_ANR -> "not responding"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "out of memory"
        else -> "reason $reason"
    }

    /**
     * The readable parts of a native crash's tombstone, which Android hands
     * over as protobuf: its text fields (signal, abort message, and the
     * function and library names of the backtraces) in file order.
     */
    private fun tombstoneText(input: InputStream): String {
        val bytes = ByteArray(1 shl 20)
        var size = 0
        while (size < bytes.size) {
            val n = input.read(bytes, size, bytes.size - size)
            if (n < 0) break
            size += n
        }
        val runs = mutableListOf<String>()
        val run = StringBuilder()
        fun flush() {
            if (run.length >= 6) runs += run.toString()
            run.setLength(0)
        }
        for (i in 0 until size) {
            val c = bytes[i].toInt() and 0xFF
            if (c in 0x20..0x7E) run.append(c.toChar()) else flush()
        }
        flush()
        // The head holds the signal and abort message; the frames name functions and libraries.
        val head = runs.take(12)
        val frames = runs.drop(12).filter { it.startsWith("_Z") || it.contains(".so") }.distinct().take(80)
        return (head + frames).joinToString("\n").take(12000)
    }
}
