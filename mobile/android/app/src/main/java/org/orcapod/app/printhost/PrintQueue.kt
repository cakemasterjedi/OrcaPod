package org.orcapod.app.printhost

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

enum class QueueState {
    /** Waiting for its turn: an earlier job on the printer, the printer to finish, or the bed to be cleared. */
    WAITING,
    UPLOADING,
    /** Sent and started; followed until the printer reports the print over. */
    PRINTING,
    /** Uploaded without starting. */
    UPLOADED,
    FINISHED,
    CANCELLED,
    FAILED,
    ;

    val isOpen get() = this == WAITING || this == UPLOADING || this == PRINTING
}

/** One plate for one printer. A plate sent to three printers is three items sharing [gcodePath]. */
data class QueueItem(
    val id: String,
    val connectionId: String,
    val printerName: String,
    val fileName: String,
    val gcodePath: String,
    val start: Boolean,
    val options: PrintOptions,
    val state: QueueState = QueueState.WAITING,
    /** Upload progress while [QueueState.UPLOADING], print progress while [QueueState.PRINTING]; 0 to 1. */
    val progress: Float = 0f,
    val message: String? = null,
    /** Epoch milliseconds. */
    val addedAt: Long,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
    /** The printer reported this print running at least once, so a later idle report means it is over. */
    val seenActive: Boolean = false,
)

/** What the queue knows about one printer: its last status and whether it waits for the bed to be cleared. */
data class FleetPrinter(
    val status: PrinterStatus? = null,
    val error: String? = null,
    /** Epoch milliseconds of the last answer, or 0 before the first. */
    val updatedAt: Long = 0,
    /**
     * The last print on it ended and a queued print is next: a person has to
     * take the finished part off the bed before the queue starts another.
     */
    val needsBedClear: Boolean = false,
)

/** Something worth telling the user about while they are not looking at the queue. */
sealed interface QueueEvent {
    val item: QueueItem
    data class Started(override val item: QueueItem) : QueueEvent
    data class Ended(override val item: QueueItem) : QueueEvent
    data class Failed(override val item: QueueItem) : QueueEvent
    /** A print ended on [item]'s printer ([finished] when the queue sent it) and [item] waits for the bed to be cleared. */
    data class BedClearNeeded(override val item: QueueItem, val finished: QueueItem?) : QueueEvent
}

/** Where the queue keeps its items between launches. */
interface QueueStorage {
    fun load(): List<QueueItem>
    fun save(items: List<QueueItem>)
}

/**
 * The print queue: plates waiting for printers, sent to several printers at
 * once, and the status of every printer it watches.
 *
 * Each printer works through its own items in order, one at a time; printers
 * run in parallel. An upload-only item goes as soon as the printer's earlier
 * items are sent. An item that starts a print waits until the printer is idle
 * and, after a print the queue saw end, until someone confirms the bed is
 * clear ([confirmBedClear]): no printer here can take a part off its own bed.
 *
 * [poll] and [dispatch] do the work and are called by [start]'s loop; tests
 * call them directly. Every host call blocks and runs on [io].
 */
class PrintQueue(
    private val storage: QueueStorage,
    /** Where queued G-code is copied, so slicing the next plate does not change what is queued. */
    private val spool: File,
    /** The saved printers; items whose printer was deleted fail. */
    private val connections: () -> List<PrinterConnection>,
    /** A shared, cached host per connection, so the queue and the printer screens use one MQTT session. */
    private val host: (PrinterConnection) -> PrintHost,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
    private val onEvent: (QueueEvent) -> Unit = {},
    /** Called when a job is sent or its outcome is known, for the app's own print history. */
    private val onRecord: (QueueItem) -> Unit = {},
) {
    private val _items = MutableStateFlow(
        // A restart interrupts uploads; they go again from the start.
        storage.load().map { if (it.state == QueueState.UPLOADING) it.copy(state = QueueState.WAITING, progress = 0f) else it },
    )
    val items: StateFlow<List<QueueItem>> = _items

    private val _printers = MutableStateFlow<Map<String, FleetPrinter>>(emptyMap())
    val printers: StateFlow<Map<String, FleetPrinter>> = _printers

    private val uploads = HashMap<String, Job>()
    private val cancelled = HashSet<String>()
    private var loop: Job? = null

    /** True while the dashboard is open: every saved printer is polled, not only those with queued work. */
    @Volatile var watchAll = false

    /** Whether anything is left to do, so the app knows to keep running. */
    val hasOpenWork get() = _items.value.any { it.state.isOpen }

    /** The printers [poll] asks: all of them while watched, else those with open items. */
    fun polledConnections(): List<PrinterConnection> {
        val all = connections()
        if (watchAll) return all
        val busy = _items.value.filter { it.state.isOpen }.map { it.connectionId }.toSet()
        return all.filter { it.id in busy }
    }

    /** Queues [gcode] for each of [targets], copying it into the spool once. */
    fun enqueue(gcode: File, fileName: String, targets: List<PrinterConnection>, start: Boolean, options: PrintOptions): List<QueueItem> {
        if (targets.isEmpty()) return emptyList()
        spool.mkdirs()
        val copy = File(spool, "${UUID.randomUUID()}.gcode")
        gcode.copyTo(copy)
        val name = PrintHost.safeFileName(fileName).let { if (it.contains('.')) it else "$it.gcode" }
        val t = now()
        val added = targets.map {
            QueueItem(
                id = UUID.randomUUID().toString(), connectionId = it.id, printerName = it.name, fileName = name,
                gcodePath = copy.path, start = start, options = options, addedAt = t,
            )
        }
        // Sending a plate to a printer with nothing else queued says its bed is ready.
        val idle = targets.filter { t -> _items.value.none { it.connectionId == t.id && it.state.isOpen } }
        for (t in idle) setPrinter(t.id) { it.copy(needsBedClear = false) }
        mutate { it + added }
        dispatch()
        return added
    }

    /** Stops an item: a waiting one is dropped from the line, an upload is aborted. A running print is stopped on the printer by [cancelPrint]. */
    fun cancel(id: String) {
        val item = _items.value.firstOrNull { it.id == id } ?: return
        when (item.state) {
            QueueState.WAITING -> {
                setItem(id) { it.copy(state = QueueState.CANCELLED, endedAt = now(), message = null) }
                dispatch()
            }
            QueueState.UPLOADING -> synchronized(cancelled) { cancelled += id }
            else -> {}
        }
    }

    /** Puts a failed, cancelled or finished item back in line for the same printer. */
    fun retry(id: String) {
        val item = _items.value.firstOrNull { it.id == id } ?: return
        if (item.state.isOpen || !File(item.gcodePath).exists()) return
        setItem(id) { it.copy(state = QueueState.WAITING, progress = 0f, message = null, startedAt = null, endedAt = null, seenActive = false, addedAt = now()) }
        dispatch()
    }

    /** Moves a waiting item one place earlier among its printer's waiting items. */
    fun moveUp(id: String) = mutate { list ->
        val i = list.indexOfFirst { it.id == id }
        if (i < 0 || list[i].state != QueueState.WAITING) return@mutate list
        val j = (i - 1 downTo 0).firstOrNull { list[it].connectionId == list[i].connectionId && list[it].state == QueueState.WAITING }
            ?: return@mutate list
        list.toMutableList().apply { add(j, removeAt(i)) }
    }

    /** Removes items that are over, and their spooled G-code once no item uses it. */
    fun remove(ids: Collection<String>) {
        val gone = _items.value.filter { it.id in ids && !it.state.isOpen }
        if (gone.isEmpty()) return
        mutate { list -> list.filterNot { it in gone } }
        val used = _items.value.map { it.gcodePath }.toSet()
        gone.map { File(it.gcodePath) }.distinct()
            .filter { it.path !in used && it.parentFile == spool }
            .forEach { it.delete() }
    }

    fun clearEnded() = remove(_items.value.filter { !it.state.isOpen }.map { it.id })

    /** The user took the last part off [connectionId]'s bed: the next print may start. */
    fun confirmBedClear(connectionId: String) {
        setPrinter(connectionId) { it.copy(needsBedClear = false) }
        dispatch()
    }

    /** Pauses, resumes or stops what [connectionId] is printing. */
    suspend fun control(connectionId: String, action: (PrintHost) -> Unit) = withContext(io) {
        val c = connections().firstOrNull { it.id == connectionId } ?: throw IOException("Unknown printer")
        action(host(c))
    }

    /** Asks every polled printer for its status and moves the queue on. Printers are asked in parallel. */
    suspend fun poll() {
        val jobs = polledConnections().map { c -> scope.launch { pollOne(c) } }
        jobs.forEach { it.join() }
        dispatch()
    }

    private suspend fun pollOne(c: PrinterConnection) {
        val result = runCatching { withContext(io) { host(c).status() } }
        val s = result.getOrNull()
        val error = result.exceptionOrNull()?.let { it.message ?: "The printer did not answer" }
        val wasActive = _printers.value[c.id]?.status?.isActive == true
        setPrinter(c.id) { p -> if (s != null) p.copy(status = s, error = null, updatedAt = now()) else p.copy(error = error) }
        if (s == null) return
        val ended = follow(c.id, s)
        // A print ended, the queue's or one started elsewhere: the part is still on the bed.
        if (ended != null || (wasActive && !s.isActive)) {
            val next = _items.value.firstOrNull { it.connectionId == c.id && it.state == QueueState.WAITING && it.start }
            if (next != null && _printers.value[c.id]?.needsBedClear != true) {
                setPrinter(c.id) { it.copy(needsBedClear = true) }
                onEvent(QueueEvent.BedClearNeeded(next, ended))
            } else if (ended != null) {
                onEvent(if (ended.state == QueueState.FAILED) QueueEvent.Failed(ended) else QueueEvent.Ended(ended))
            }
        }
    }

    // Carries the item printing on [id] through to its end; returns it once ended.
    private fun follow(id: String, s: PrinterStatus): QueueItem? {
        val item = _items.value.firstOrNull { it.connectionId == id && it.state == QueueState.PRINTING } ?: return null
        if (s.isActive) {
            val first = !item.seenActive
            setItem(item.id, persist = first) { it.copy(seenActive = true, progress = s.progress?.toFloat() ?: it.progress) }
            return null
        }
        val outcome = when {
            s.state == PrintState.FINISHED -> QueueState.FINISHED
            s.state == PrintState.CANCELLED -> QueueState.CANCELLED
            s.state == PrintState.ERROR -> QueueState.FAILED
            s.state == PrintState.IDLE -> QueueState.FINISHED
            else -> return null // offline, busy or unknown: wait for a clearer answer
        }
        // Printers can report the previous job's end, or idle, for a while
        // after a start; only an end after the print was seen running counts.
        if (!item.seenActive) {
            if (now() - (item.startedAt ?: now()) < START_GRACE_MS) return null
            setItem(item.id) {
                it.copy(state = QueueState.FINISHED, endedAt = now(), message = "The printer never reported this print running")
            }
        } else {
            setItem(item.id) {
                it.copy(
                    state = outcome, endedAt = now(), progress = if (outcome == QueueState.FINISHED) 1f else it.progress,
                    message = if (outcome == QueueState.FAILED) s.message ?: s.stateText.ifEmpty { null } else null,
                )
            }
        }
        val ended = _items.value.first { it.id == item.id }
        onRecord(ended)
        return ended
    }

    /** Starts the next upload for every printer that can take one. */
    @Synchronized
    fun dispatch() {
        val list = _items.value
        val conns = connections()
        for (connId in list.filter { it.state == QueueState.WAITING }.map { it.connectionId }.distinct()) {
            if (list.any { it.connectionId == connId && (it.state == QueueState.UPLOADING || it.state == QueueState.PRINTING) }) continue
            val next = list.first { it.connectionId == connId && it.state == QueueState.WAITING }
            val c = conns.firstOrNull { it.id == connId }
            if (c == null) {
                setItem(next.id) { it.copy(state = QueueState.FAILED, endedAt = now(), message = "The printer was removed") }
                continue
            }
            if (next.start && !readyToStart(connId)) continue
            upload(c, next)
        }
    }

    private fun readyToStart(connId: String): Boolean {
        val p = _printers.value[connId] ?: return false
        val s = p.status ?: return false
        if (p.needsBedClear || p.error != null || now() - p.updatedAt > FRESH_STATUS_MS) return false
        return s.state == PrintState.IDLE || s.state == PrintState.FINISHED || s.state == PrintState.CANCELLED
    }

    private fun upload(c: PrinterConnection, item: QueueItem) {
        setItem(item.id) { it.copy(state = QueueState.UPLOADING, progress = 0f, message = null) }
        var lastReport = 0L
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val result = runCatching {
                withContext(io) {
                    host(c).upload(File(item.gcodePath), item.fileName, item.start, item.options) { sent, total ->
                        val t = now()
                        if (t - lastReport > 250 || sent == total) {
                            lastReport = t
                            val f = if (total > 0) sent.toFloat() / total else 0f
                            setItem(item.id, persist = false) { if (it.state == QueueState.UPLOADING) it.copy(progress = f) else it }
                        }
                        synchronized(cancelled) { item.id !in cancelled }
                    }
                }
            }
            synchronized(cancelled) { cancelled -= item.id }
            val e = result.exceptionOrNull()
            val t = now()
            when {
                e == null -> {
                    setItem(item.id) {
                        it.copy(
                            state = if (item.start) QueueState.PRINTING else QueueState.UPLOADED,
                            fileName = result.getOrThrow(), progress = 0f, startedAt = t, endedAt = if (item.start) null else t,
                        )
                    }
                    val sent = _items.value.first { it.id == item.id }
                    onRecord(sent)
                    if (item.start) onEvent(QueueEvent.Started(sent))
                }
                e is UploadCancelled -> setItem(item.id) { it.copy(state = QueueState.CANCELLED, endedAt = t, progress = 0f) }
                e is IOException -> {
                    setItem(item.id) { it.copy(state = QueueState.FAILED, endedAt = t, message = e.message ?: "Upload failed") }
                    val failed = _items.value.first { it.id == item.id }
                    onRecord(failed)
                    onEvent(QueueEvent.Failed(failed))
                }
                else -> throw e
            }
            synchronized(uploads) { uploads.remove(item.id) }
            dispatch()
        }
        synchronized(uploads) { uploads[item.id] = job }
        job.start()
    }

    /**
     * Polls until [stop]: every few seconds while someone watches, less often
     * otherwise, since this runs for hours while the phone is in a pocket.
     */
    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            while (isActive) {
                poll()
                delay(if (watchAll) 3000 else 10_000)
            }
        }
    }

    fun stop() {
        loop?.cancel()
        loop = null
    }

    /** Waits for running uploads; for tests. */
    suspend fun awaitUploads() {
        while (true) (synchronized(uploads) { uploads.values.firstOrNull() } ?: return).join()
    }

    // Progress ticks are not saved: a restart sends an interrupted upload again anyway.
    private fun mutate(persist: Boolean = true, f: (List<QueueItem>) -> List<QueueItem>) {
        val after = synchronized(this) { _items.update(f); _items.value }
        if (persist) synchronized(storage) { storage.save(after) }
    }

    private fun setItem(id: String, persist: Boolean = true, f: (QueueItem) -> QueueItem) =
        mutate(persist) { list -> list.map { if (it.id == id) f(it) else it } }

    private fun setPrinter(id: String, f: (FleetPrinter) -> FleetPrinter) =
        _printers.update { m -> m + (id to f(m[id] ?: FleetPrinter())) }

    companion object {
        /** How long a started print may take to show up as running before the queue stops waiting for it. */
        const val START_GRACE_MS = 10 * 60 * 1000L
        /** How old an idle report may be for a print to start on it. */
        const val FRESH_STATUS_MS = 30_000L
    }
}
