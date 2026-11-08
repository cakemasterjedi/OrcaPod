package org.orcapod.app.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.orcapod.app.QueueService
import org.orcapod.app.printhost.JobOutcome
import org.orcapod.app.printhost.JobRecord
import org.orcapod.app.printhost.PrintHost
import org.orcapod.app.printhost.PrintQueue
import org.orcapod.app.printhost.PrinterConnection
import org.orcapod.app.printhost.QueueItem
import org.orcapod.app.printhost.QueueState
import java.io.File

/**
 * The saved printers, one open connection per printer, and the print queue,
 * for the whole process. They outlive screens and view models so the queue
 * keeps sending and watching while the user slices the next plate, and so
 * the queue and the printer screens share one connection to each printer
 * (Bambu printers take only a few MQTT clients).
 */
class PrinterFleet private constructor(context: Context) {
    private val app = context.applicationContext
    val store = PrinterStore(app)
    val log = PrintLog(File(app.filesDir, "print-log.json"))
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val hosts = HashMap<String, PrintHost>()

    private val _connections = MutableStateFlow(store.load())
    val connections: StateFlow<List<PrinterConnection>> = _connections

    val notifier = QueueNotifier(app)

    val queue = PrintQueue(
        storage = QueueStore(File(app.filesDir, "print-queue.json")),
        spool = File(app.filesDir, "print-queue"),
        connections = { _connections.value },
        host = ::host,
        scope = scope,
        onEvent = notifier::event,
        onRecord = ::record,
    )

    /** Set when a notification asks to open the dashboard; the plate screen opens it and clears this. */
    val dashboardRequested = MutableStateFlow(false)

    private var watchers = 0

    init {
        // The loop and the service run while there is work, or someone watches.
        scope.launch { queue.items.collect { update() } }
    }

    fun save(c: PrinterConnection) {
        val old = _connections.value.firstOrNull { it.id == c.id }
        if (old == null || !sameLink(old, c)) synchronized(this) { hosts.remove(c.id) }?.let(::closeQuietly)
        _connections.value = _connections.value.filterNot { it.id == c.id } + c
        store.save(_connections.value)
    }

    fun delete(id: String) {
        synchronized(this) { hosts.remove(id) }?.let(::closeQuietly)
        _connections.value = _connections.value.filterNot { it.id == id }
        store.save(_connections.value)
        queue.dispatch()
    }

    /** The open connection to [c], made again when its settings changed. */
    @Synchronized
    fun host(c: PrinterConnection): PrintHost =
        hosts[c.id]?.takeIf { h -> _connections.value.firstOrNull { it.id == c.id }?.let { sameLink(it, c) } == true } ?: run {
            hosts.remove(c.id)?.let(::closeQuietly)
            PrintHost.create(c).also { hosts[c.id] = it }
        }

    /** The dashboard is on screen: poll every printer often. Pair with [unwatch]. */
    fun watch() {
        watchers++
        queue.watchAll = true
        update()
        scope.launch { queue.poll() }
    }

    fun unwatch() {
        watchers = (watchers - 1).coerceAtLeast(0)
        queue.watchAll = watchers > 0
        update()
    }

    private fun update() {
        val work = queue.hasOpenWork
        if (work || watchers > 0) queue.start() else queue.stop()
        if (work) QueueService.start(app) else notifier.cancelOngoing()
    }

    // A new name or remembered preset keeps the open connection; anything else reconnects.
    private fun sameLink(a: PrinterConnection, b: PrinterConnection) =
        a.copy(name = "", printerPreset = "") == b.copy(name = "", printerPreset = "")

    // The app's print history covers queued jobs too.
    private fun record(i: QueueItem) {
        when (i.state) {
            QueueState.PRINTING, QueueState.UPLOADED ->
                log.add(i.connectionId, i.printerName, JobRecord(i.fileName, if (i.start) JobOutcome.PRINTING else JobOutcome.UPLOADED, i.startedAt ?: System.currentTimeMillis()))
            QueueState.FAILED ->
                if (i.startedAt == null) log.add(i.connectionId, i.printerName, JobRecord(i.fileName, JobOutcome.UPLOAD_FAILED, i.endedAt ?: System.currentTimeMillis(), message = i.message))
                else log.update(i.connectionId, i.fileName, JobOutcome.FAILED)
            QueueState.FINISHED -> log.update(i.connectionId, i.fileName, JobOutcome.FINISHED)
            QueueState.CANCELLED -> log.update(i.connectionId, i.fileName, JobOutcome.CANCELLED)
            else -> {}
        }
    }

    private fun closeQuietly(h: PrintHost) {
        Thread { runCatching { h.close() } }.start()
    }

    companion object {
        // Holds only the application context, which lives as long as the process.
        @SuppressLint("StaticFieldLeak")
        @Volatile private var instance: PrinterFleet? = null

        fun get(context: Context): PrinterFleet =
            instance ?: synchronized(this) { instance ?: PrinterFleet(context).also { instance = it } }

        /** The intent extra a notification sets to open the dashboard. */
        const val EXTRA_OPEN_QUEUE = "org.orcapod.app.OPEN_QUEUE"

        fun handleIntent(context: Context, intent: Intent?) {
            if (intent?.getBooleanExtra(EXTRA_OPEN_QUEUE, false) == true) get(context).dashboardRequested.value = true
        }

        /** Whether notifications can be shown; the caller asks for the permission when not. */
        fun canNotify(context: Context): Boolean =
            android.os.Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}
