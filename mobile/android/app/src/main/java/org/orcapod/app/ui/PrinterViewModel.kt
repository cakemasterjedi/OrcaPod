package org.orcapod.app.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.orcapod.app.data.PrintLog
import org.orcapod.app.data.PrinterDiscovery
import org.orcapod.app.data.PrinterStore
import org.orcapod.app.printhost.BambuLanHost
import org.orcapod.app.printhost.CameraSource
import org.orcapod.app.printhost.DiscoveredPrinter
import org.orcapod.app.printhost.JobOutcome
import org.orcapod.app.printhost.JobRecord
import org.orcapod.app.printhost.PrintState
import org.orcapod.app.printhost.PrintHost
import org.orcapod.app.printhost.PrintOptions
import org.orcapod.app.printhost.PrinterConnection
import org.orcapod.app.printhost.PrinterStatus
import org.orcapod.app.printhost.UploadCancelled
import java.io.File
import java.io.IOException
import java.util.UUID

sealed interface UploadState {
    data object Idle : UploadState
    data class Running(val printer: String, val fileName: String, val fraction: Float) : UploadState
    data class Done(val printer: String, val fileName: String, val started: Boolean) : UploadState
    data class Failed(val message: String) : UploadState
}

/** Printer connections, sending G-code and watching a print: desktop's physical printers and device tab. */
class PrinterViewModel(app: Application) : AndroidViewModel(app) {
    private val store = PrinterStore(app)
    private val log = PrintLog(File(app.filesDir, "print-log.json"))
    private val hosts = HashMap<String, PrintHost>()
    private var uploadJob: Job? = null
    @Volatile private var uploadCancelled = false
    private var monitorJob: Job? = null
    private var discovery: PrinterDiscovery? = null

    var connections by mutableStateOf(store.load())
        private set
    var upload by mutableStateOf<UploadState>(UploadState.Idle)
        private set
    /** The connection being watched, its last status, and the error if the last poll failed. */
    var monitored by mutableStateOf<String?>(null)
        private set
    var status by mutableStateOf<PrinterStatus?>(null)
        private set
    var statusError by mutableStateOf<String?>(null)
        private set
    var discovered by mutableStateOf<List<DiscoveredPrinter>>(emptyList())
        private set

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    /** The connection to preselect for [printerPreset]: the one used with it last, else the last used. */
    fun preferredFor(printerPreset: String): PrinterConnection? =
        connections.firstOrNull { it.printerPreset == printerPreset && printerPreset.isNotEmpty() }
            ?: connections.firstOrNull { it.id == store.lastUsed }
            ?: connections.firstOrNull()

    fun newId(): String = UUID.randomUUID().toString()

    fun save(c: PrinterConnection) {
        synchronized(this) { hosts.remove(c.id) }?.let(::closeQuietly)
        connections = connections.filterNot { it.id == c.id } + c
        store.save(connections)
    }

    fun delete(id: String) {
        synchronized(this) { hosts.remove(id) }?.let(::closeQuietly)
        if (monitored == id) stopMonitor()
        connections = connections.filterNot { it.id == id }
        store.save(connections)
    }

    /**
     * Checks [c] without saving it; returns what answered and the connection
     * with anything the printer told us filled in (a Bambu printer's serial),
     * or throws with the reason.
     */
    suspend fun test(c: PrinterConnection): Pair<String, PrinterConnection> = withContext(Dispatchers.IO) {
        PrintHost.create(c).use { h -> h.test() to if (h is BambuLanHost) c.copy(serial = h.serial) else c }
    }

    /** The camera to show for [id]; throws with the reason when there is none. */
    suspend fun camera(id: String): CameraSource = withContext(Dispatchers.IO) {
        val c = connections.firstOrNull { it.id == id } ?: throw IOException("Unknown printer")
        PrintHost.cameraFor(c, host(c)) ?: throw IOException("The printer reports no camera. Set its camera URL in the printer's settings here.")
    }

    /** Jobs the printer itself records (Moonraker only), newest first; null when it keeps none. */
    suspend fun printerHistory(id: String): List<JobRecord>? = withContext(Dispatchers.IO) {
        val c = connections.firstOrNull { it.id == id } ?: return@withContext null
        host(c).history()
    }

    /** Jobs this app sent to [id], newest first. */
    suspend fun sentHistory(id: String): List<JobRecord> = withContext(Dispatchers.IO) { log.forConnection(id).map { it.job } }

    fun clearSentHistory(id: String) = viewModelScope.launch(Dispatchers.IO) { log.clear(id) }

    fun send(c: PrinterConnection, gcode: File, remoteName: String, start: Boolean, options: PrintOptions, printerPreset: String) {
        if (upload is UploadState.Running) return
        val name = PrintHost.safeFileName(remoteName).let { if (it.contains('.')) it else "$it.gcode" }
        uploadCancelled = false
        upload = UploadState.Running(c.name, name, 0f)
        uploadJob = viewModelScope.launch {
            upload = try {
                val stored = withContext(Dispatchers.IO) {
                    host(c).upload(gcode, name, start, options) { sent, total ->
                        val f = if (total > 0) sent.toFloat() / total else 0f
                        viewModelScope.launch { if (upload is UploadState.Running) upload = UploadState.Running(c.name, name, f) }
                        !uploadCancelled
                    }
                }
                withContext(Dispatchers.IO) {
                    log.add(c.id, c.name, JobRecord(stored, if (start) JobOutcome.PRINTING else JobOutcome.UPLOADED, System.currentTimeMillis()))
                }
                store.lastUsed = c.id
                if (printerPreset.isNotEmpty() && c.printerPreset != printerPreset) save(c.copy(printerPreset = printerPreset))
                if (start) watch(c.id)
                UploadState.Done(c.name, stored, start)
            } catch (e: UploadCancelled) {
                UploadState.Idle
            } catch (e: IOException) {
                withContext(Dispatchers.IO) {
                    log.add(c.id, c.name, JobRecord(name, JobOutcome.UPLOAD_FAILED, System.currentTimeMillis(), message = e.message))
                }
                UploadState.Failed(e.message ?: "Upload failed")
            }
        }
    }

    fun cancelUpload() {
        uploadCancelled = true
    }

    fun clearUpload() {
        if (upload !is UploadState.Running) upload = UploadState.Idle
    }

    /** Polls [id]'s status every few seconds until [stopMonitor]. */
    fun watch(id: String) {
        if (monitored == id && monitorJob?.isActive == true) return
        stopMonitor()
        val c = connections.firstOrNull { it.id == id } ?: return
        monitored = id
        monitorJob = viewModelScope.launch {
            while (isActive) {
                try {
                    val s = withContext(Dispatchers.IO) { host(c).status().also { record(c.id, it) } }
                    status = s
                    statusError = null
                } catch (e: IOException) {
                    statusError = e.message ?: "The printer did not answer"
                }
                delay(if (status?.isActive == true) 2000 else 4000)
            }
        }
    }

    // Carries the job this app started through to its end in the log.
    private fun record(id: String, s: PrinterStatus) {
        val outcome = when (s.state) {
            PrintState.FINISHED -> JobOutcome.FINISHED
            PrintState.CANCELLED -> JobOutcome.CANCELLED
            PrintState.ERROR -> JobOutcome.FAILED
            else -> return
        }
        log.update(id, s.fileName, outcome)
    }

    fun stopMonitor() {
        monitorJob?.cancel()
        monitorJob = null
        monitored = null
        status = null
        statusError = null
    }

    fun pause() = control("Pausing") { it.pause() }
    fun resume() = control("Resuming") { it.resume() }
    fun cancelPrint() = control("Stopping the print") { it.cancel() }

    private fun control(label: String, action: (PrintHost) -> Unit) {
        val c = connections.firstOrNull { it.id == monitored } ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { action(host(c)) }
                _messages.tryEmit("$label…")
            } catch (e: IOException) {
                _messages.tryEmit(e.message ?: "The printer did not accept the command")
            }
        }
    }

    fun startDiscovery() {
        if (discovery != null) return
        discovered = emptyList()
        discovery = PrinterDiscovery(getApplication()) { found ->
            viewModelScope.launch {
                if (discovered.none { it.host == found.host && it.type == found.type }) discovered = discovered + found
            }
        }.also { it.start(viewModelScope) }
    }

    fun stopDiscovery() {
        discovery?.stop()
        discovery = null
    }

    @Synchronized
    private fun host(c: PrinterConnection): PrintHost =
        hosts[c.id]?.takeIf { connections.firstOrNull { it.id == c.id } == c } ?: run {
            hosts.remove(c.id)?.let(::closeQuietly)
            PrintHost.create(c).also { hosts[c.id] = it }
        }

    private fun closeQuietly(h: PrintHost) {
        viewModelScope.launch(Dispatchers.IO) { runCatching { h.close() } }
    }

    override fun onCleared() {
        stopDiscovery()
        uploadCancelled = true
        // viewModelScope is gone by now; close on a plain thread so MQTT sockets don't leak.
        val open = synchronized(this) { hosts.values.toList().also { hosts.clear() } }
        Thread { open.forEach { runCatching { it.close() } } }.start()
    }
}
