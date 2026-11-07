package org.orcapod.app.printhost

import org.json.JSONObject
import org.junit.AfterClass
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.security.KeyStore
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * BambuLanHost against a mock printer: an MQTT broker on TLS and an implicit
 * FTPS server that, like the real printer, insists the data connection
 * resumes the control connection's TLS session.
 */
class BambuLanHostTest {
    @Test
    fun testsUploadsStartsAndReportsStatus() {
        MockBambu().use { printer ->
            val host = BambuLanHost(conn(), printer.mqttPort, printer.ftpPort)
            assertEquals("Bambu Lab printer · $SERIAL", host.test())

            val gcode = File(Files.createTempDirectory("bambu").toFile(), "cube.gcode").apply { writeText("G28\nG1 X1\n".repeat(5000)) }
            val stored = host.upload(gcode, "cube.gcode", true, PrintOptions(timelapse = true, useAms = true, amsTray = 2), { _, _ -> true })
            assertEquals("cube.gcode.3mf", stored)

            // The file went up as a .gcode.3mf holding the G-code and its MD5, over a resumed TLS session.
            assertEquals("/cube.gcode.3mf", printer.storedPath)
            assertTrue("data connection did not resume the TLS session", printer.dataSessionResumed)
            val entries = unzip(printer.storedBytes!!)
            assertArrayEquals(gcode.readBytes(), entries["Metadata/plate_1.gcode"])
            val md5 = MessageDigest.getInstance("MD5").digest(gcode.readBytes()).joinToString("") { "%02X".format(it) }
            assertEquals(md5, String(entries["Metadata/plate_1.gcode.md5"]!!))
            assertTrue(entries.containsKey("[Content_Types].xml") && entries.containsKey("3D/3dmodel.model"))

            val job = printer.requests.map { it.optJSONObject("print") }.first { it?.optString("command") == "project_file" }!!
            assertEquals("ftp:///cube.gcode.3mf", job.getString("url"))
            assertEquals("Metadata/plate_1.gcode", job.getString("param"))
            assertTrue(job.getBoolean("timelapse"))
            assertTrue(job.getBoolean("use_ams"))
            assertEquals(2, job.getJSONArray("ams_mapping").getInt(0))

            val s = host.status()
            assertEquals(PrintState.PRINTING, s.state)
            assertEquals(0.37, s.progress!!, 1e-9)
            assertEquals(42 * 60L, s.remainingSeconds)
            assertEquals(31, s.layer)
            assertEquals(220.0, s.nozzleTarget!!, 1e-9)

            host.pause()
            waitFor { printer.requests.any { it.optJSONObject("print")?.optString("command") == "pause" } }
            host.close()
        }
    }

    @Test
    fun wrongAccessCodeIsRefused() {
        MockBambu().use { printer ->
            try {
                BambuLanHost(conn(code = "00000000"), printer.mqttPort, printer.ftpPort).test()
                fail()
            } catch (e: PrintHostException) {
                assertEquals("The printer refused the access code", e.message)
            }
        }
    }

    @Test
    fun refusedJobSurfacesTheReason() {
        MockBambu().use { printer ->
            printer.rejectJobs = true
            val gcode = File(Files.createTempDirectory("bambu").toFile(), "cube.gcode").apply { writeText("G28\n") }
            BambuLanHost(conn(), printer.mqttPort, printer.ftpPort).use { host ->
                try {
                    host.upload(gcode, "cube.gcode", true, PrintOptions(), { _, _ -> true })
                    fail()
                } catch (e: PrintHostException) {
                    assertEquals("The printer refused the job: sdcard is busy", e.message)
                }
            }
        }
    }

    @Test
    fun parsesBambuAnnouncements() {
        val notify = "NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nServer: UPnP/1.0\r\nLocation: 192.168.1.42\r\n" +
            "NT: urn:bambulab-com:device:3dprinter:1\r\nUSN: 01P00A123456789\r\nDevModel.bambu.com: C12\r\nDevName.bambu.com: Workshop P1S\r\n\r\n"
        val d = DiscoveredPrinter.parseBambuNotify(notify)!!
        assertEquals(DiscoveredPrinter("Workshop P1S", HostType.BAMBU_LAN, "192.168.1.42", "01P00A123456789", "P1S"), d)
        assertEquals(null, DiscoveredPrinter.parseBambuNotify("NOTIFY * HTTP/1.1\r\nNT: upnp:rootdevice\r\nLocation: http://10.0.0.1/\r\n"))
    }

    private fun conn(code: String = CODE) =
        PrinterConnection("b", "P1S", HostType.BAMBU_LAN, "127.0.0.1", serial = SERIAL, accessCode = code)

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                out[e.name] = z.readBytes()
            }
        }
        return out
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 3000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("timed out")
            Thread.sleep(20)
        }
    }

    /** The printer side of the LAN protocol, just enough to exercise the client. */
    private class MockBambu : AutoCloseable {
        private val mqttServer = tls.serverSocketFactory.createServerSocket(0, 50, InetAddress.getLoopbackAddress()) as SSLServerSocket
        private val ftpServer = (tls.serverSocketFactory.createServerSocket(0, 50, InetAddress.getLoopbackAddress()) as SSLServerSocket)
            .apply { enabledProtocols = arrayOf("TLSv1.2") }
        val mqttPort get() = mqttServer.localPort
        val ftpPort get() = ftpServer.localPort
        val requests = java.util.Collections.synchronizedList(mutableListOf<JSONObject>())
        @Volatile var storedPath: String? = null
        @Volatile var storedBytes: ByteArray? = null
        @Volatile var dataSessionResumed = false
        @Volatile var rejectJobs = false

        init {
            serve(mqttServer, ::mqtt)
            serve(ftpServer, ::ftp)
        }

        private fun serve(server: ServerSocket, session: (Socket) -> Unit) {
            Thread {
                while (!server.isClosed) {
                    val s = try { server.accept() } catch (_: Exception) { break }
                    Thread { runCatching { s.use(session) } }.apply { isDaemon = true }.start()
                }
            }.apply { isDaemon = true }.start()
        }

        private fun mqtt(s: Socket) {
            val input = DataInputStream(s.getInputStream())
            val out = s.getOutputStream()
            var topic: String? = null
            while (true) {
                val header = input.read()
                if (header < 0) return
                var len = 0
                var mult = 1
                while (true) {
                    val b = input.readUnsignedByte()
                    len += (b and 0x7f) * mult
                    if (b and 0x80 == 0) break
                    mult *= 128
                }
                val body = ByteArray(len).also { input.readFully(it) }
                when (header shr 4) {
                    1 -> { // CONNECT: protocol name, level, flags, keep-alive, then client id, user, password
                        var i = 10
                        fun str(): String { val n = ((body[i].toInt() and 0xff) shl 8) or (body[i + 1].toInt() and 0xff); val v = String(body, i + 2, n); i += 2 + n; return v }
                        str()
                        val user = str()
                        val pass = str()
                        val ok = user == BambuLanHost.USER && pass == CODE
                        send(out, 0x20, byteArrayOf(0, if (ok) 0 else 5))
                        if (!ok) return
                    }
                    8 -> { // SUBSCRIBE
                        val n = ((body[2].toInt() and 0xff) shl 8) or (body[3].toInt() and 0xff)
                        topic = String(body, 4, n)
                        send(out, 0x90, byteArrayOf(body[0], body[1], 0))
                    }
                    3 -> { // PUBLISH
                        val n = ((body[0].toInt() and 0xff) shl 8) or (body[1].toInt() and 0xff)
                        val json = JSONObject(String(body, 2 + n, body.size - 2 - n))
                        requests += json
                        val reply = when {
                            json.optJSONObject("pushing")?.optString("command") == "pushall" -> JSONObject().put("print", REPORT)
                            json.optJSONObject("print")?.optString("command") == "project_file" && rejectJobs ->
                                JSONObject("""{"print":{"command":"project_file","result":"FAIL","reason":"sdcard is busy"}}""")
                            else -> null
                        }
                        if (reply != null && topic == "device/$SERIAL/report") publish(out, topic, reply.toString())
                    }
                    12 -> send(out, 0xD0, ByteArray(0))
                    14 -> return
                }
            }
        }

        private fun publish(out: OutputStream, topic: String, payload: String) {
            val b = ByteArrayOutputStream()
            val t = topic.toByteArray()
            b.write(t.size shr 8); b.write(t.size and 0xff); b.write(t); b.write(payload.toByteArray())
            send(out, 0x30, b.toByteArray())
        }

        private fun send(out: OutputStream, header: Int, body: ByteArray) = synchronized(out) {
            val p = ByteArrayOutputStream()
            p.write(header)
            var len = body.size
            do { var d = len % 128; len /= 128; if (len > 0) d = d or 0x80; p.write(d) } while (len > 0)
            p.write(body)
            out.write(p.toByteArray()); out.flush()
        }

        private fun ftp(s: Socket) {
            val control = s as SSLSocket
            val reader = control.inputStream.bufferedReader()
            val out = control.outputStream
            fun reply(text: String) { out.write("$text\r\n".toByteArray()); out.flush() }
            reply("220 Bambu FTP")
            var passive: ServerSocket? = null
            while (true) {
                val line = reader.readLine() ?: return
                val arg = line.substringAfter(' ', "")
                when (line.substringBefore(' ').uppercase()) {
                    "USER" -> reply(if (arg == BambuLanHost.USER) "331 Password required" else "530 No")
                    "PASS" -> reply(if (arg == CODE) "230 Logged in" else "530 Login incorrect")
                    "PBSZ" -> reply("200 PBSZ=0")
                    "PROT" -> reply("200 Protection set to Private")
                    "TYPE" -> reply("200 Type set to I")
                    "PASV" -> {
                        passive = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
                        val p = passive.localPort
                        // A deliberately wrong address: the client must use the control connection's.
                        reply("227 Entering Passive Mode (10,9,8,7,${p / 256},${p % 256})")
                    }
                    "STOR" -> {
                        reply("150 Opening data connection")
                        val plain = passive!!.accept()
                        val data = tls.socketFactory.createSocket(plain, plain.inetAddress.hostAddress, plain.port, true) as SSLSocket
                        data.useClientMode = false
                        data.enabledProtocols = arrayOf("TLSv1.2")
                        data.startHandshake()
                        dataSessionResumed = data.session.id.contentEquals(control.session.id)
                        if (!dataSessionResumed) {
                            data.close()
                            reply("522 SSL connection failed: session reuse required")
                            continue
                        }
                        storedBytes = data.inputStream.readBytes()
                        storedPath = arg
                        data.close()
                        passive.close()
                        reply("226 Transfer complete")
                    }
                    "QUIT" -> { reply("221 Bye"); return }
                    else -> reply("502 Not implemented")
                }
            }
        }

        override fun close() {
            mqttServer.close()
            ftpServer.close()
        }
    }

    companion object {
        const val SERIAL = "01P00A123456789"
        const val CODE = "12345678"
        val REPORT = JSONObject(
            """{"gcode_state":"RUNNING","mc_percent":37,"mc_remaining_time":42,"layer_num":31,"total_layer_num":120,
               "nozzle_temper":219.5,"nozzle_target_temper":220,"bed_temper":55,"bed_target_temper":55,"subtask_name":"cube","print_error":0}""",
        )
        private lateinit var tls: SSLContext
        private lateinit var dir: File

        /** A throwaway self-signed certificate, like the printer's own. */
        @BeforeClass @JvmStatic
        fun makeCertificate() {
            // Stateless session tickets give a resumed session a fresh ID; turn them off so the
            // mock can tell resumption from the session ID, as an ID-based server cache does.
            System.setProperty("jdk.tls.server.enableSessionTicketExtension", "false")
            dir = Files.createTempDirectory("bambu-tls").toFile()
            val store = File(dir, "printer.p12")
            val keytool = File(System.getProperty("java.home"), "bin/keytool").path
            val p = ProcessBuilder(
                keytool, "-genkeypair", "-alias", "printer", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                "-dname", "CN=$SERIAL", "-keystore", store.path, "-storetype", "PKCS12", "-storepass", "secret", "-keypass", "secret",
            ).redirectErrorStream(true).start()
            val log = p.inputStream.bufferedReader().readText()
            check(p.waitFor() == 0) { "keytool failed: $log" }
            val ks = KeyStore.getInstance("PKCS12").apply { store.inputStream().use { load(it, "secret".toCharArray()) } }
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, "secret".toCharArray()) }
            tls = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        }

        @AfterClass @JvmStatic
        fun cleanUp() {
            dir.deleteRecursively()
        }
    }
}
