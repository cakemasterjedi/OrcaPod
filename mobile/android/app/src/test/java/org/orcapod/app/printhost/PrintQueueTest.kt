package org.orcapod.app.printhost

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.orcapod.app.data.QueueStore
import org.orcapod.app.printhost.MockHttpServer.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PrintQueueTest {
    @get:Rule val tmp = TemporaryFolder()

    /** A printer that reports [state] and records what it was sent. */
    private class FakeHost(var state: PrintState = PrintState.IDLE) : PrintHost {
        val uploads = mutableListOf<Pair<String, Boolean>>()
        var fail: String? = null
        var gate: CountDownLatch? = null
        var started = CountDownLatch(1)
        var unreachable = false

        override fun test() = "fake"
        override fun upload(file: File, remoteName: String, startPrint: Boolean, options: PrintOptions, progress: UploadProgress): String {
            started.countDown()
            gate?.await(5, TimeUnit.SECONDS)
            if (!progress(file.length(), file.length())) throw UploadCancelled()
            fail?.let { throw PrintHostException(it) }
            synchronized(uploads) { uploads += remoteName to startPrint }
            if (startPrint) state = PrintState.PREPARING
            return remoteName
        }
        override fun status(): PrinterStatus {
            if (unreachable) throw IOException("No route to host")
            return PrinterStatus(state, progress = if (state == PrintState.PRINTING) 0.5 else null)
        }
        override fun pause() {}
        override fun resume() {}
        override fun cancel() {}
    }

    private fun conn(id: String) = PrinterConnection(id = id, name = "Printer $id", type = HostType.OCTOPRINT, host = "10.0.0.$id")

    private class Mem : QueueStorage {
        var saved: List<QueueItem> = emptyList()
        override fun load() = saved
        override fun save(items: List<QueueItem>) { saved = items }
    }

    private class Setup(
        val queue: PrintQueue,
        val hosts: Map<String, FakeHost>,
        val events: MutableList<QueueEvent>,
        val clock: LongArray,
        val spool: File,
    )

    // The queue runs on its own threads, as in the app, so a test can block while an upload runs.
    private val scope = CoroutineScope(Dispatchers.Default)

    private fun setup(vararg ids: String, storage: QueueStorage = Mem(), connections: MutableList<PrinterConnection>? = null): Setup {
        val conns = connections ?: ids.map(::conn).toMutableList()
        val hosts = ids.associateWith { FakeHost() }
        val events = mutableListOf<QueueEvent>()
        val clock = longArrayOf(1_000_000)
        val spool = tmp.newFolder()
        val q = PrintQueue(
            storage = storage, spool = spool, connections = { conns.toList() }, host = { hosts.getValue(it.id) },
            scope = scope, now = { clock[0] }, onEvent = { synchronized(events) { events += it } },
        )
        return Setup(q, hosts, events, clock, spool)
    }

    private fun gcode(): File = tmp.newFile("plate.gcode").apply { writeText("G28\nG1 X10\n") }

    private fun PrintQueue.state(i: Int) = items.value[i].state

    @Test
    fun sendsOnePlateToSeveralPrintersInParallel() = runBlocking {
        val t = setup("1", "2", "3")
        t.queue.watchAll = true
        t.queue.poll()
        // Printer 2 holds its upload until printer 3 has started its own: both run at once.
        t.hosts.getValue("2").gate = t.hosts.getValue("3").started
        val added = t.queue.enqueue(gcode(), "cube", listOf(conn("1"), conn("2"), conn("3")), start = true, options = PrintOptions())
        assertEquals(3, added.size)
        assertEquals(1, added.map { it.gcodePath }.distinct().size)
        assertTrue(File(added[0].gcodePath).parentFile == t.spool)
        t.queue.awaitUploads()
        for (id in listOf("1", "2", "3")) assertEquals(listOf("cube.gcode" to true), t.hosts.getValue(id).uploads)
        assertTrue(t.queue.items.value.all { it.state == QueueState.PRINTING })
        assertEquals(3, t.events.count { it is QueueEvent.Started })
    }

    @Test
    fun waitsForABusyPrinterAndAClearBed() = runBlocking {
        val t = setup("1")
        val host = t.hosts.getValue("1")
        host.state = PrintState.PRINTING
        t.queue.poll()
        val (a, b) = listOf("a", "b").map { t.queue.enqueue(File(f(it)), it, listOf(conn("1")), true, PrintOptions()).single() }
        // Busy with a print the queue did not send: nothing goes.
        t.queue.poll()
        t.queue.awaitUploads()
        assertTrue(host.uploads.isEmpty())

        // That print ends: its part is on the bed, so the queue asks first.
        host.state = PrintState.FINISHED
        t.queue.poll()
        assertTrue(t.queue.printers.value.getValue("1").needsBedClear)
        assertEquals(a.id, (t.events.last() as QueueEvent.BedClearNeeded).item.id)
        assertTrue(host.uploads.isEmpty())

        t.queue.confirmBedClear("1")
        t.queue.awaitUploads()
        assertEquals(listOf("a.gcode" to true), host.uploads)
        assertEquals(QueueState.PRINTING, t.queue.state(0))
        assertEquals(QueueState.WAITING, t.queue.state(1))

        // a runs and ends; b waits for the bed again.
        host.state = PrintState.PRINTING
        t.queue.poll()
        assertTrue(t.queue.items.value[0].seenActive)
        assertEquals(0.5f, t.queue.items.value[0].progress)
        host.state = PrintState.IDLE
        t.queue.poll()
        t.queue.awaitUploads()
        assertEquals(QueueState.FINISHED, t.queue.state(0))
        assertEquals(1, host.uploads.size)
        val ask = t.events.last() as QueueEvent.BedClearNeeded
        assertEquals(b.id, ask.item.id)
        assertEquals(a.id, ask.finished!!.id)
        t.queue.confirmBedClear("1")
        t.queue.awaitUploads()
        assertEquals("b.gcode", host.uploads.last().first)
    }

    private fun f(name: String) = tmp.newFile("$name.gcode").apply { writeText("G28 ; $name\n") }.path

    @Test
    fun uploadOnlyItemsDoNotWaitForThePrinter() = runBlocking {
        val t = setup("1")
        t.hosts.getValue("1").state = PrintState.PRINTING
        t.queue.poll()
        t.queue.enqueue(gcode(), "later.gcode", listOf(conn("1")), start = false, options = PrintOptions())
        t.queue.awaitUploads()
        assertEquals(listOf("later.gcode" to false), t.hosts.getValue("1").uploads)
        assertEquals(QueueState.UPLOADED, t.queue.state(0))
    }

    @Test
    fun doesNotStartOnAnUnknownOrUnreachablePrinter() = runBlocking {
        val t = setup("1")
        t.queue.enqueue(gcode(), "x", listOf(conn("1")), true, PrintOptions())
        // No status yet.
        assertEquals(QueueState.WAITING, t.queue.state(0))
        t.hosts.getValue("1").unreachable = true
        t.queue.poll()
        assertEquals("No route to host", t.queue.printers.value.getValue("1").error)
        assertEquals(QueueState.WAITING, t.queue.state(0))
        t.hosts.getValue("1").unreachable = false
        t.queue.poll()
        t.queue.awaitUploads()
        assertEquals(QueueState.PRINTING, t.queue.state(0))
    }

    @Test
    fun doesNotStartOnAStaleStatus() = runBlocking {
        val t = setup("1")
        t.queue.watchAll = true
        t.queue.poll()
        t.queue.watchAll = false
        // The idle report is old; the printer may be printing by now.
        t.clock[0] += PrintQueue.FRESH_STATUS_MS + 1
        t.queue.enqueue(gcode(), "x", listOf(conn("1")), true, PrintOptions())
        assertEquals(QueueState.WAITING, t.queue.state(0))
        t.queue.poll()
        t.queue.awaitUploads()
        assertEquals(QueueState.PRINTING, t.queue.state(0))
    }

    @Test
    fun aStartIsGivenTimeToShowUp() = runBlocking {
        val t = setup("1")
        t.queue.watchAll = true
        t.queue.poll()
        t.queue.enqueue(gcode(), "x", listOf(conn("1")), true, PrintOptions())
        t.queue.awaitUploads()
        // Still reports the last job's end right after the start: not this print's end.
        t.hosts.getValue("1").state = PrintState.FINISHED
        t.queue.poll()
        assertEquals(QueueState.PRINTING, t.queue.state(0))
        t.clock[0] += PrintQueue.START_GRACE_MS + 1
        t.queue.poll()
        assertEquals(QueueState.FINISHED, t.queue.state(0))
        assertEquals("The printer never reported this print running", t.queue.items.value[0].message)
    }

    @Test
    fun failuresAreReportedAndCanBeRetried() = runBlocking {
        val t = setup("1")
        val host = t.hosts.getValue("1")
        host.fail = "Disk full"
        t.queue.watchAll = true
        t.queue.poll()
        t.queue.enqueue(gcode(), "x", listOf(conn("1")), true, PrintOptions())
        t.queue.awaitUploads()
        assertEquals(QueueState.FAILED, t.queue.state(0))
        assertEquals("Disk full", t.queue.items.value[0].message)
        assertTrue(t.events.last() is QueueEvent.Failed)

        host.fail = null
        t.queue.retry(t.queue.items.value[0].id)
        t.queue.awaitUploads()
        assertEquals(QueueState.PRINTING, t.queue.state(0))

        // A print that errors out fails too.
        host.state = PrintState.PRINTING
        t.queue.poll()
        host.state = PrintState.ERROR
        t.queue.poll()
        assertEquals(QueueState.FAILED, t.queue.state(0))
    }

    @Test
    fun cancelsWaitingItemsAndUploads() = runBlocking {
        val t = setup("1")
        val host = t.hosts.getValue("1")
        host.gate = CountDownLatch(1)
        t.queue.poll()
        t.queue.enqueue(gcode(), "a", listOf(conn("1")), false, PrintOptions())
        t.queue.enqueue(tmp.newFile("b.gcode"), "b", listOf(conn("1")), false, PrintOptions())
        assertEquals(QueueState.UPLOADING, t.queue.state(0))
        assertEquals(QueueState.WAITING, t.queue.state(1))
        t.queue.cancel(t.queue.items.value[1].id)
        assertEquals(QueueState.CANCELLED, t.queue.state(1))
        host.started.await(5, TimeUnit.SECONDS)
        t.queue.cancel(t.queue.items.value[0].id)
        host.gate!!.countDown()
        t.queue.awaitUploads()
        assertEquals(QueueState.CANCELLED, t.queue.state(0))
        assertTrue(host.uploads.isEmpty())
    }

    @Test
    fun reordersAndRemovesItemsWithTheirSpooledFiles() = runBlocking {
        val t = setup("1", "2")
        t.hosts.getValue("1").state = PrintState.PRINTING
        t.queue.poll()
        val a = t.queue.enqueue(gcode(), "a", listOf(conn("1")), true, PrintOptions()).single()
        val other = t.queue.enqueue(tmp.newFile("o.gcode"), "o", listOf(conn("2")), true, PrintOptions()).single()
        val b = t.queue.enqueue(tmp.newFile("b.gcode"), "b", listOf(conn("1")), true, PrintOptions()).single()
        t.queue.moveUp(b.id)
        assertEquals(listOf(b.id, a.id, other.id), t.queue.items.value.map { it.id })
        // Already first among printer 1's items: stays.
        t.queue.moveUp(b.id)
        assertEquals(b.id, t.queue.items.value[0].id)

        // Open items are not removed; ended ones go with their file.
        t.queue.remove(listOf(a.id))
        assertEquals(3, t.queue.items.value.size)
        t.queue.cancel(a.id)
        t.queue.clearEnded()
        assertEquals(listOf(b.id, other.id), t.queue.items.value.map { it.id })
        assertFalse(File(a.gcodePath).exists())
        assertTrue(File(b.gcodePath).exists())
    }

    @Test
    fun itemsForARemovedPrinterFail() = runBlocking {
        val conns = mutableListOf(conn("1"))
        val t = setup("1", connections = conns)
        t.hosts.getValue("1").state = PrintState.PRINTING
        t.queue.poll()
        t.queue.enqueue(gcode(), "a", listOf(conn("1")), true, PrintOptions())
        conns.clear()
        t.queue.dispatch()
        assertEquals(QueueState.FAILED, t.queue.state(0))
        assertEquals("The printer was removed", t.queue.items.value[0].message)
        assertTrue(t.queue.polledConnections().isEmpty())
    }

    @Test
    fun pollsOnlyPrintersWithWorkUnlessWatched() = runBlocking {
        val t = setup("1", "2")
        assertTrue(t.queue.polledConnections().isEmpty())
        t.hosts.getValue("2").state = PrintState.PRINTING
        t.queue.enqueue(gcode(), "a", listOf(conn("2")), true, PrintOptions())
        assertEquals(listOf("2"), t.queue.polledConnections().map { it.id })
        t.queue.watchAll = true
        assertEquals(listOf("1", "2"), t.queue.polledConnections().map { it.id })
    }

    @Test
    fun survivesARestart() = runBlocking {
        val store = QueueStore(File(tmp.root, "queue.json"))
        val t = setup("1", "2", storage = store)
        t.hosts.getValue("1").gate = CountDownLatch(1)
        t.hosts.getValue("2").state = PrintState.PRINTING
        t.queue.watchAll = true
        t.queue.poll()
        t.queue.enqueue(gcode(), "a", listOf(conn("1"), conn("2")), true, PrintOptions(useAms = true, amsTray = 2))
        assertEquals(QueueState.UPLOADING, t.queue.state(0))

        val restored = PrintQueue(store, t.spool, { listOf(conn("1"), conn("2")) }, { FakeHost() }, scope)
        val items = restored.items.value
        assertEquals(t.queue.items.value.map { it.id }, items.map { it.id })
        // The interrupted upload goes again.
        assertEquals(QueueState.WAITING, items[0].state)
        assertEquals(PrintOptions(useAms = true, amsTray = 2), items[1].options)
        assertEquals("a.gcode", items[1].fileName)
        assertNull(items[1].startedAt)
        t.hosts.getValue("1").gate!!.countDown()
        t.queue.awaitUploads()
    }

    @Test
    fun drivesARealOctoPrintHost() = runBlocking {
        var state = "Operational"
        MockHttpServer { r ->
            when (r.path) {
                "/api/files/local" -> { state = "Printing"; Response(201, """{"done":true,"files":{"local":{"name":"cube.gcode"}}}""") }
                "/api/job" -> Response(body = """{"state":"$state","job":{"file":{"name":"cube.gcode"}},"progress":{"completion":${if (state == "Printing") 30 else 100}}}""")
                "/api/printer" -> Response(body = """{"temperature":{}}""")
                else -> Response(404, "")
            }
        }.use { server ->
            val c = PrinterConnection("op", "Prusa MK3", HostType.OCTOPRINT, server.host, apiKey = "k")
            val host = OctoPrintHost(c)
            val q = PrintQueue(Mem(), tmp.newFolder(), { listOf(c) }, { host }, scope)
            q.watchAll = true
            q.poll()
            assertEquals(PrintState.IDLE, q.printers.value.getValue("op").status!!.state)
            q.enqueue(gcode(), "cube.gcode", listOf(c), true, PrintOptions())
            q.awaitUploads()
            q.poll()
            assertEquals(0.3f, q.items.value[0].progress, 1e-6f)
            state = "Operational"
            q.poll()
            assertEquals(QueueState.FINISHED, q.items.value[0].state)
        }
    }
}
