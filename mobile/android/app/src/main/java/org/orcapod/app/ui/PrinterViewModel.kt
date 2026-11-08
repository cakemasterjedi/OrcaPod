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
import org.orcapod.app.data.PrinterDiscovery
import org.orcapod.app.data.PrinterFleet
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
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Printer connections, sending G-code and watching prints: desktop's physical
 * printers and device tab. Connections, their open hosts and the print queue
 * live in [PrinterFleet] so they outlive this view model.
 */
class PrinterViewModel(app: Application) : AndroidViewModel(app) {
    val fleet = PrinterFleet.get(app)
    private val store = fleet.store
    private val log = fleet.log
    private var monitorJob: Job? = null
    private var discovery: PrinterDiscovery? = null

    var connections by mutableStateOf(fleet.connections.value)
        private set
    var queue by mutableStateOf(fleet.queue.items.value)
        private set
    var fleetStatus by mutableStateOf(fleet.queue.printers.value)
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

    init {
        viewModelScope.launch { fleet.connections.collect { connections = it } }
        viewModelScope.launch { fleet.queue.items.collect { queue = it } }
        viewModelScope.launch { fleet.queue.printers.collect { fleetStatus = it } }
    }

    /** The connection to preselect for [printerPreset]: the one used with it last, else the last used. */
    fun preferredFor(printerPreset: String): PrinterConnection? =
        connections.firstOrNull { it.printerPreset == printerPreset && printerPreset.isNotEmpty() }
            ?: connections.firstOrNull { it.id == store.lastUsed }
            ?: connections.firstOrNull()

    fun newId(): String = UUID.randomUUID().toString()

    fun save(c: PrinterConnection) {
        fleet.save(c)
        connections = fleet.connections.value
    }

    fun delete(id: String) {
        if (monitored == id) stopMonitor()
        fleet.delete(id)
        connections = fleet.connections.value
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

    /**
     * Queues [gcode] for every printer in [targets]: each gets it as soon as
     * it can take it (see [PrintQueue]). Returns the new items' ids.
     */
    suspend fun enqueue(targets: List<PrinterConnection>, gcode: File, remoteName: String, start: Boolean, options: PrintOptions, printerPreset: String): Set<String> {
        // Remember the preset for each printer, so the next send preselects it.
        if (printerPreset.isNotEmpty()) for (c in targets) if (c.printerPreset != printerPreset) save(c.copy(printerPreset = printerPreset))
        targets.lastOrNull()?.let { store.lastUsed = it.id }
        val ids = targets.map { it.id }.toSet()
        val current = connections.filter { it.id in ids }
        return try {
            // Copying the G-code into the queue's spool can take a moment for a big plate.
            withContext(Dispatchers.IO) { fleet.queue.enqueue(gcode, remoteName, current, start, options) }.map { it.id }.toSet()
        } catch (e: IOException) {
            _messages.tryEmit("Could not queue the plate: ${e.message}")
            emptySet()
        }
    }

    fun cancelQueued(id: String) = fleet.queue.cancel(id)
    fun retryQueued(id: String) = fleet.queue.retry(id)
    fun moveQueuedUp(id: String) = fleet.queue.moveUp(id)
    fun removeQueued(id: String) = fleet.queue.remove(listOf(id))
    fun clearEndedQueue() = fleet.queue.clearEnded()
    fun confirmBedClear(connectionId: String) = fleet.queue.confirmBedClear(connectionId)

    /** Pause, resume or stop on any printer of the dashboard. */
    fun pause(id: String) = control(id, "Pausing") { it.pause() }
    fun resume(id: String) = control(id, "Resuming") { it.resume() }
    fun cancelPrint(id: String) = control(id, "Stopping the print") { it.cancel() }

    private fun control(id: String, label: String, action: (PrintHost) -> Unit) {
        val name = connections.firstOrNull { it.id == id }?.name ?: return
        viewModelScope.launch {
            try {
                fleet.queue.control(id, action)
                _messages.tryEmit("$label on $name…")
                fleet.queue.poll()
            } catch (e: IOException) {
                _messages.tryEmit(e.message ?: "The printer did not accept the command")
            }
        }
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
        monitored?.let { control(it, label, action) }
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

    private fun host(c: PrinterConnection): PrintHost = fleet.host(c)

    override fun onCleared() {
        stopDiscovery()
    }
}
