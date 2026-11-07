package org.orcapod.app.printhost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.orcapod.app.data.PrintLog
import org.orcapod.app.printhost.MockHttpServer.Response
import java.io.ByteArrayOutputStream
import java.io.File

class CameraHistoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun jpeg(vararg body: Int) = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + body.map { it.toByte() }.toByteArray() + byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    private fun mjpeg(vararg frames: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for ((i, f) in frames.withIndex()) {
            // One part with a Content-Length, one without, as streamers differ.
            out.write("--boundarydonotcross\r\nContent-Type: image/jpeg\r\n".toByteArray())
            if (i % 2 == 0) out.write("Content-Length: ${f.size}\r\n".toByteArray())
            out.write("\r\n".toByteArray()); out.write(f); out.write("\r\n".toByteArray())
        }
        return out.toByteArray()
    }

    @Test
    fun splitsMjpegIntoFrames() {
        val a = jpeg(1, 2, 0xFF, 0x00, 3)
        val b = jpeg(4)
        val got = mutableListOf<List<Byte>>()
        HttpCameraFeed.readMjpeg(mjpeg(a, b, a).inputStream()) { got += it.toList(); true }
        assertEquals(listOf(a.toList(), b.toList(), a.toList()), got)
        // Stops when asked.
        got.clear()
        assertEquals(false, HttpCameraFeed.readMjpeg(mjpeg(a, b).inputStream()) { got += it.toList(); false })
        assertEquals(1, got.size)
    }

    @Test
    fun resolvesCameraUrlsAgainstThePrinter() {
        // Like a browser on OctoPrint's page: same origin, port included.
        assertEquals("http://10.0.0.5:5000/webcam/?action=stream", HttpCameraFeed.resolve("http://10.0.0.5:5000", "/webcam/?action=stream"))
        // Moonraker's API port is not where the web server publishes the webcam.
        assertEquals("http://10.0.0.5/webcam/?action=stream", HttpCameraFeed.resolve("http://10.0.0.5:7125", "/webcam/?action=stream", keepPort = false))
        assertEquals("http://cam.local/x", HttpCameraFeed.resolve("http://10.0.0.5", "http://cam.local/x"))
        assertEquals("rtsp://10.0.0.9/live", HttpCameraFeed.resolve("http://10.0.0.5", "rtsp://10.0.0.9/live"))
        assertNull(HttpCameraFeed.resolve("http://10.0.0.5", " "))
    }

    @Test
    fun moonrakerCameraAndHistory() {
        val frame = jpeg(7, 7)
        MockHttpServer { r ->
            when (r.path) {
                "/server/webcams/list" -> Response(
                    body = """{"result":{"webcams":[{"name":"off","enabled":false,"stream_url":"/off"},
                        {"name":"cam","enabled":true,"stream_url":"http://${'$'}HOST/stream","snapshot_url":"/snap"}]}}""".replace("\$HOST", r.header("Host")!!),
                )
                "/stream" -> Response(bytes = mjpeg(frame, frame), headers = mapOf("Content-Type" to "multipart/x-mixed-replace;boundary=boundarydonotcross"))
                "/server/history/list" -> Response(
                    body = """{"result":{"count":2,"jobs":[
                        {"filename":"cube.gcode","status":"completed","start_time":1700000000.5,"print_duration":3600.2,"filament_used":1234.5},
                        {"filename":"benchy.gcode","status":"klippy_shutdown","start_time":1690000000,"print_duration":60}]}}""",
                )
                else -> Response(404, "")
            }
        }.use { server ->
            val host = MoonrakerHost(PrinterConnection("m", "Voron", HostType.MOONRAKER, server.host))
            val feed = (host.camera() as CameraSource.Frames).feed
            assertTrue(feed.description, feed.description.endsWith("/stream"))
            var n = 0
            feed.run { n++; n < 2 }
            assertEquals(2, n)

            val jobs = host.history()
            assertEquals(2, jobs.size)
            assertEquals(JobRecord("cube.gcode", JobOutcome.FINISHED, 1700000000500, 3600, 1234.5), jobs[0])
            assertEquals(JobOutcome.FAILED, jobs[1].outcome)
            assertEquals("klippy shutdown", jobs[1].message)
            assertEquals("limit=50&order=desc", server.find("GET", "/server/history/list")!!.query)
        }
    }

    @Test
    fun octoPrintSnapshotWhenStreamFails() {
        val frame = jpeg(5)
        MockHttpServer { r ->
            when (r.path) {
                "/api/settings" -> Response(body = """{"webcam":{"webcamEnabled":true,"streamUrl":"/webcam/?action=stream","snapshotUrl":"/snap.jpg"}}""")
                "/snap.jpg" -> Response(bytes = frame)
                else -> Response(404, "")
            }
        }.use { server ->
            val source = OctoPrintHost(PrinterConnection("o", "Ender", HostType.OCTOPRINT, server.host)).camera() as CameraSource.Frames
            val frames = mutableListOf<List<Byte>>()
            source.feed.run { frames += it.toList(); frames.size < 2 }
            assertEquals(listOf(frame.toList(), frame.toList()), frames)
        }
    }

    @Test
    fun cameraUrlOnTheConnectionWins() {
        val c = PrinterConnection("d", "Duet", HostType.DUET, "10.0.0.7", cameraUrl = "/webcam/snapshot")
        val host = DuetHost(c)
        val snap = PrintHost.cameraFor(c, host) as CameraSource.Frames
        assertEquals("http://10.0.0.7/webcam/snapshot", snap.feed.description)
        val rtsp = PrintHost.cameraFor(c.copy(cameraUrl = "rtsps://10.0.0.7:322/live"), host)
        assertEquals(CameraSource.Rtsp("rtsps://10.0.0.7:322/live", tls = true), rtsp)
        assertNull(PrintHost.cameraFor(c.copy(cameraUrl = ""), host))
    }

    @Test
    fun printLogFollowsAJobToItsEnd() {
        val log = PrintLog(File(tmp.root, "log.json"))
        log.add("p1", "P1S", JobRecord("cube.gcode.3mf", JobOutcome.PRINTING, 1000))
        log.add("p1", "P1S", JobRecord("other.gcode.3mf", JobOutcome.UPLOADED, 2000))
        log.add("p2", "Voron", JobRecord("cube.gcode", JobOutcome.PRINTING, 3000))

        // The Bambu printer reports the job as "cube"; only p1's printing entry moves on.
        assertTrue(log.update("p1", "cube", JobOutcome.FINISHED, now = 61_000))
        assertEquals(false, log.update("p1", "cube", JobOutcome.FINISHED, now = 70_000))
        val p1 = log.forConnection("p1").map { it.job }
        assertEquals(JobRecord("other.gcode.3mf", JobOutcome.UPLOADED, 2000), p1[0])
        assertEquals(JobRecord("cube.gcode.3mf", JobOutcome.FINISHED, 1000, 60), p1[1])
        assertEquals(JobOutcome.PRINTING, log.forConnection("p2").single().job.outcome)

        // Survives reopening, and clearing one printer keeps the other.
        val reopened = PrintLog(File(tmp.root, "log.json"))
        assertEquals(3, reopened.all().size)
        reopened.clear("p1")
        assertEquals(listOf("p2"), reopened.all().map { it.connectionId })
    }
}
