package org.orcapod.app.library

import android.app.Application
import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/** A model site opened in the library's browser. */
data class ModelSite(val name: String, val url: String)

/** A download in progress; [progress] is 0..1, or null when the size is unknown. */
data class DownloadProgress(val label: String, val progress: Float?)

/**
 * State of the model library: Thingiverse search over its official API, and
 * downloads caught from the in-app browser for sites without a public API.
 * Finished downloads are unpacked to model files and handed out on [ready].
 */
class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("library", Context.MODE_PRIVATE)
    private val downloadDir get() = File(getApplication<Application>().cacheDir, "library")
    private var searchJob: Job? = null
    private var downloadJob: Job? = null

    var token by mutableStateOf(prefs.getString(KEY_TOKEN, "") ?: "")
        private set
    var query by mutableStateOf("")
    var things by mutableStateOf<List<Thing>>(emptyList())
        private set
    var searching by mutableStateOf(false)
        private set
    var searchError by mutableStateOf<String?>(null)
        private set
    private var page = 0
    private var endReached = false

    /** The thing whose files are listed, with its files once loaded. */
    var openThing by mutableStateOf<Thing?>(null)
        private set
    var thingFiles by mutableStateOf<List<ThingFile>?>(null)
        private set

    var download by mutableStateOf<DownloadProgress?>(null)
        private set
    /** Model files from one download, waiting for the user to pick which to add. */
    var choice by mutableStateOf<List<File>?>(null)
        private set

    private val _ready = MutableSharedFlow<List<File>>(extraBufferCapacity = 4)
    /** Model files to add to the plate. */
    val ready: SharedFlow<List<File>> = _ready
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    /** Blob downloads requested from the browser, by nonce, with their file names. */
    private val pendingBlobs = HashMap<String, String>()

    // --- Thingiverse ---

    fun saveToken(value: String) {
        token = value.trim()
        prefs.edit().putString(KEY_TOKEN, token).apply()
        things = emptyList()
        searchError = null
        if (token.isNotEmpty()) search()
    }

    fun search() {
        page = 0
        endReached = false
        things = emptyList()
        loadMore()
    }

    fun loadMore() {
        if (token.isEmpty() || searching || endReached) return
        val client = Thingiverse(token)
        val q = query
        searchJob = viewModelScope.launch {
            searching = true
            searchError = null
            try {
                val next = withContext(Dispatchers.IO) { client.search(q, page + 1) }
                page++
                if (next.isEmpty()) endReached = true
                val seen = things.mapTo(HashSet()) { it.id }
                things = things + next.filter { it.id !in seen }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                searchError = describe(e)
            } finally {
                searching = false
            }
        }
    }

    fun showThing(thing: Thing) {
        openThing = thing
        thingFiles = null
        val client = Thingiverse(token)
        viewModelScope.launch {
            try {
                thingFiles = withContext(Dispatchers.IO) { client.files(thing.id) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                openThing = null
                _messages.tryEmit(describe(e))
            }
        }
    }

    fun closeThing() {
        openThing = null
        thingFiles = null
    }

    fun downloadThingFiles(files: List<ThingFile>) {
        val thing = openThing ?: return
        val client = Thingiverse(token)
        closeThing()
        runDownload(thing.name) { report ->
            val dir = freshDir()
            files.flatMapIndexed { i, f ->
                val file = Http.download(f.downloadUrl, dir, client.downloadHeaders(), nameHint = f.name) { read, total ->
                    report("${f.name} (${i + 1}/${files.size})", read, total)
                }
                ModelFiles.unpack(file, File(dir, "unpacked-$i"))
            }
        }
    }

    // --- browser downloads ---

    /** A download started by a page in the browser, fetched again with the page's cookies. */
    fun downloadFromBrowser(url: String, userAgent: String?, contentDisposition: String?, cookies: String?, referer: String?) {
        val name = ModelFiles.nameFor(contentDisposition, url)
        val headers = buildMap {
            userAgent?.let { put("User-Agent", it) }
            cookies?.let { put("Cookie", it) }
            referer?.let { put("Referer", it) }
        }
        runDownload(name) { report ->
            val dir = freshDir()
            val file = Http.download(url, dir, headers, nameHint = name.takeIf { contentDisposition != null }) { read, total ->
                report(name, read, total)
            }
            ModelFiles.unpack(file, File(dir, "unpacked"))
        }
    }

    /** Registers a blob: download the page script will deliver, and returns its nonce. */
    fun expectBlob(name: String): String = UUID.randomUUID().toString().also {
        synchronized(pendingBlobs) { pendingBlobs[it] = name }
    }

    /** A blob delivered by the page as a data: URL. Ignored unless [nonce] was handed out. */
    fun receiveBlob(nonce: String, dataUrl: String) {
        val name = synchronized(pendingBlobs) { pendingBlobs.remove(nonce) } ?: return
        runDownload(name) { _ ->
            val dir = freshDir()
            val file = File(dir, ModelFiles.safeName(name))
            file.writeBytes(Base64.decode(dataUrl.substringAfter("base64,", ""), Base64.DEFAULT))
            ModelFiles.unpack(file, File(dir, "unpacked"))
        }
    }

    fun blobFailed(nonce: String) {
        synchronized(pendingBlobs) { pendingBlobs.remove(nonce) } ?: return
        _messages.tryEmit("The site's download could not be read; try its other download button")
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        download = null
    }

    fun pick(files: List<File>) {
        choice = null
        if (files.isNotEmpty()) _ready.tryEmit(files)
    }

    private fun runDownload(label: String, block: (report: (String, Long, Long) -> Unit) -> List<File>) {
        if (download != null) {
            _messages.tryEmit("Wait for the current download to finish")
            return
        }
        download = DownloadProgress(label, null)
        downloadJob = viewModelScope.launch {
            try {
                val models = withContext(Dispatchers.IO) {
                    var last = DownloadProgress(label, null)
                    block { l, read, total ->
                        ensureActive() // "Cancel" stops the blocking copy at its next chunk.
                        // Whole percents only, so a fast download does not flood recomposition.
                        val p = if (total > 0) (read * 100 / total).toInt() / 100f else null
                        if (l != last.label || p != last.progress) {
                            last = DownloadProgress(l, p)
                            download = last
                        }
                    }
                }
                when {
                    models.isEmpty() -> _messages.tryEmit("$label has no model files (STL, 3MF, OBJ, AMF or STEP)")
                    models.size == 1 -> _ready.tryEmit(models)
                    else -> choice = models
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "download failed", e)
                _messages.tryEmit("Download failed: ${describe(e)}")
            } finally {
                download = null
            }
        }
    }

    /** A new directory per download, so names from different models never clash. */
    private fun freshDir(): File {
        val root = downloadDir
        // Keep the last few downloads only; the plate has its own copy once imported.
        root.listFiles()?.sortedByDescending { it.lastModified() }?.drop(4)?.forEach { it.deleteRecursively() }
        return File(root, System.currentTimeMillis().toString()).apply { mkdirs() }
    }

    private fun describe(e: Exception): String = when {
        e is HttpException && e.status == 401 -> "Thingiverse refused the app token; check it under \"Change token\""
        e is HttpException && e.status == 429 -> "Thingiverse is limiting requests; try again in a minute"
        e is IOException -> e.message ?: "Network error"
        else -> e.message ?: e.toString()
    }

    companion object {
        private const val TAG = "OrcaPod"
        private const val KEY_TOKEN = "thingiverse_token"

        val SITES = listOf(
            ModelSite("Printables", "https://www.printables.com/"),
            ModelSite("MakerWorld", "https://makerworld.com/"),
            ModelSite("Thingiverse", "https://www.thingiverse.com/"),
            ModelSite("MyMiniFactory", "https://www.myminifactory.com/"),
            ModelSite("Cults", "https://cults3d.com/"),
        )
    }
}
