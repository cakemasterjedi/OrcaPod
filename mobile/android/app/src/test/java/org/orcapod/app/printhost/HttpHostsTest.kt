package org.orcapod.app.printhost

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.orcapod.app.printhost.MockHttpServer.Response
import java.io.File

/** Each HTTP print host against a mock of its printer's API. */
class HttpHostsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun gcode(): File = File(tmp.newFolder(), "cube.gcode").apply { writeText("; sliced by OrcaPod\nG28\nG1 X10 Y10 E1\n".repeat(2000)) }

    private fun conn(type: HostType, host: String, apiKey: String = "", username: String = "", password: String = "") =
        PrinterConnection("id", "Test", type, host, apiKey = apiKey, username = username, password = password)

    private val noProgress: UploadProgress = { _, _ -> true }

    // --- OctoPrint ---

    @Test
    fun octoPrintSendsApiKeyUploadsAndStarts() {
        MockHttpServer { r ->
            if (r.header("X-Api-Key") != "secret") Response(403, """{"error":"Invalid API key"}""")
            else when (r.path) {
                "/api/version" -> Response(body = """{"api":"0.1","server":"1.10.2","text":"OctoPrint 1.10.2"}""")
                "/api/files/local" -> Response(201, """{"done":true,"files":{"local":{"name":"cube.gcode"}}}""")
                "/api/job" -> if (r.method == "GET") Response(
                    body = """{"state":"Printing","job":{"file":{"name":"cube.gcode"}},"progress":{"completion":42.5,"printTime":600,"printTimeLeft":900}}""",
                ) else Response(204, "")
                "/api/printer" -> Response(body = """{"temperature":{"tool0":{"actual":214.8,"target":215},"bed":{"actual":60.1,"target":60}}}""")
                else -> Response(404, "")
            }
        }.use { server ->
            val host = OctoPrintHost(conn(HostType.OCTOPRINT, server.host, apiKey = "secret"))
            assertEquals("OctoPrint 1.10.2", host.test())

            val file = gcode()
            var lastSent = 0L
            val stored = host.upload(file, "cube.gcode", true, PrintOptions()) { sent, _ -> lastSent = sent; true }
            assertEquals("cube.gcode", stored)
            val up = server.find("POST", "/api/files/local")!!
            assertTrue(up.header("Content-Type")!!.startsWith("multipart/form-data"))
            assertTrue(up.text.contains("name=\"print\"\r\n\r\ntrue"))
            assertTrue(up.text.contains("filename=\"cube.gcode\""))
            assertTrue(up.text.contains(file.readText()))
            assertTrue(lastSent > file.length() && lastSent < up.body.size)

            val s = host.status()
            assertEquals(PrintState.PRINTING, s.state)
            assertEquals(0.425, s.progress!!, 1e-9)
            assertEquals(900L, s.remainingSeconds)
            assertEquals(214.8, s.nozzleTemp!!, 1e-9)
            assertEquals(60.0, s.bedTarget!!, 1e-9)

            host.pause()
            val cmd = JSONObject(server.requests.last { it.method == "POST" && it.path == "/api/job" }.text)
            assertEquals("pause", cmd.getString("command"))
            assertEquals("pause", cmd.getString("action"))
        }
    }

    @Test
    fun octoPrintWrongKeyExplainsTheLogin() {
        MockHttpServer { Response(403, """{"error":"Invalid API key"}""") }.use { server ->
            try {
                OctoPrintHost(conn(HostType.OCTOPRINT, server.host, apiKey = "nope")).test()
                fail()
            } catch (e: PrintHostException) {
                assertEquals(403, e.status)
                assertTrue(e.message!!, e.message!!.contains("API key"))
                assertTrue(e.message!!, e.message!!.contains("Invalid API key"))
            }
        }
    }

    @Test
    fun uploadCanBeCancelled() {
        MockHttpServer { Response(201, "{}") }.use { server ->
            try {
                OctoPrintHost(conn(HostType.OCTOPRINT, server.host)).upload(gcode(), "cube.gcode", false, PrintOptions()) { _, _ -> false }
                fail()
            } catch (_: UploadCancelled) {
            }
        }
    }

    @Test
    fun unreachableHostIsReported() {
        try {
            OctoPrintHost(conn(HostType.OCTOPRINT, "127.0.0.1:1")).test()
            fail()
        } catch (e: PrintHostException) {
            assertTrue(e.message!!, e.message!!.startsWith("Cannot reach"))
        }
    }

    // --- Moonraker ---

    @Test
    fun moonrakerStartsThePrintWhenUploadDidNot() {
        MockHttpServer { r ->
            when (r.path) {
                "/server/info" -> Response(body = """{"result":{"klippy_state":"ready","moonraker_version":"v0.9.3"}}""")
                // Bare object, not wrapped in "result", as Moonraker really answers.
                "/server/files/upload" -> Response(201, """{"item":{"path":"sub/cube.gcode","root":"gcodes"},"print_started":false,"print_queued":false,"action":"create_file"}""")
                "/printer/print/start" -> Response(body = """{"result":"ok"}""")
                "/printer/objects/query" -> Response(
                    body = """{"result":{"status":{"print_stats":{"state":"printing","filename":"cube.gcode","print_duration":300.0,"info":{"current_layer":12,"total_layer":80}},
                        "virtual_sdcard":{"progress":0.25},"extruder":{"temperature":240.2,"target":240.0},"heater_bed":{"temperature":80.0,"target":80.0}}}}""",
                )
                else -> Response(404, "")
            }
        }.use { server ->
            val host = MoonrakerHost(conn(HostType.MOONRAKER, server.host))
            assertEquals("Moonraker v0.9.3 · Klipper ready", host.test())
            assertEquals("sub/cube.gcode", host.upload(gcode(), "cube.gcode", true, PrintOptions(), noProgress))
            val up = server.find("POST", "/server/files/upload")!!
            assertTrue(up.text.contains("name=\"root\"\r\n\r\ngcodes"))
            assertTrue(up.text.contains("name=\"print\"\r\n\r\ntrue"))
            assertEquals("sub/cube.gcode", JSONObject(server.find("POST", "/printer/print/start")!!.text).getString("filename"))

            val s = host.status()
            assertEquals(PrintState.PRINTING, s.state)
            assertEquals(12, s.layer)
            assertEquals(80, s.totalLayers)
            assertEquals(900L, s.remainingSeconds) // 300 s for 25 %
        }
    }

    @Test
    fun moonrakerDoesNotStartTwice() {
        MockHttpServer { r ->
            if (r.path == "/server/files/upload") Response(201, """{"item":{"path":"cube.gcode"},"print_started":true}""") else Response(404, "")
        }.use { server ->
            MoonrakerHost(conn(HostType.MOONRAKER, server.host)).upload(gcode(), "cube.gcode", true, PrintOptions(), noProgress)
            assertNull(server.find("POST", "/printer/print/start"))
        }
    }

    @Test
    fun moonrakerListsAndExcludesObjects() {
        MockHttpServer { r ->
            when (r.path) {
                "/printer/objects/query" -> Response(
                    body = """{"result":{"status":{"exclude_object":{"objects":[
                        {"name":"CUBE_ID_0_COPY_0","center":[100,100],"polygon":[[90,90],[110,90],[110,110],[90,110]]},
                        {"name":"CUBE_ID_0_COPY_1","center":[150,100],"polygon":[[140,90],[160,90],[160,110],[140,110]]},
                        {"name":"CUBE_ID_0_COPY_2"}],
                        "excluded_objects":["CUBE_ID_0_COPY_2"],"current_object":"CUBE_ID_0_COPY_1"}}}}""",
                )
                "/printer/gcode/script" -> Response(body = """{"result":"ok"}""")
                else -> Response(404, "")
            }
        }.use { server ->
            val host = MoonrakerHost(conn(HostType.MOONRAKER, server.host))
            val objects = host.printObjects(null)
            assertEquals(3, objects.size)
            assertEquals(listOf(false, false, true), objects.map { it.skipped })
            assertEquals(listOf(false, true, false), objects.map { it.current })
            assertEquals(4, objects[0].outline.size)
            assertTrue(objects[2].outline.isEmpty())

            host.skipObjects(objects.take(1), null)
            val script = JSONObject(server.find("POST", "/printer/gcode/script")!!.text).getString("script")
            assertEquals("EXCLUDE_OBJECT NAME=CUBE_ID_0_COPY_0", script)
        }
    }

    @Test
    fun moonrakerWithoutExcludeObjectListsNothing() {
        assertTrue(MoonrakerHost.parseObjects(JSONObject("""{"result":{"status":{}}}""")).isEmpty())
    }

    // --- OctoPrint object skipping ---

    @Test
    fun octoPrintSkipsWithTheGcodesOwnCommand() {
        MockHttpServer { r -> if (r.path == "/api/printer/command") Response(204, "") else Response(404, "") }.use { server ->
            val host = OctoPrintHost(conn(HostType.OCTOPRINT, server.host))
            assertTrue(host.printObjects(null).isEmpty())
            val labels = GcodeLabels.parse("M486 S0\nM486 A Cube\nM486 S1\nM486 A Ring\nM486 S-1\n".lineSequence())!!
            assertEquals(listOf("Cube", "Ring"), host.printObjects(labels).map { it.name })
            host.skipObjects(labels.objects, labels)
            val commands = JSONObject(server.find("POST", "/api/printer/command")!!.text).getJSONArray("commands")
            assertEquals("M486 P0", commands.getString(0))
            assertEquals("M486 P1", commands.getString(1))
        }
    }

    // --- PrusaLink ---

    @Test
    fun prusaLinkDigestLoginPutUploadAndJobControl() {
        val realm = "Printer API"
        val nonce = "abc123"
        MockHttpServer { r ->
            val auth = r.header("Authorization")
            if (auth == null || !digestValid(auth, r.method, realm, nonce, "maker", "pass")) {
                Response(401, "", mapOf("WWW-Authenticate" to """Digest realm="$realm", nonce="$nonce", qop="auth""""))
            } else when {
                r.path == "/api/version" -> Response(body = """{"api":"2.0.0","text":"PrusaLink","hostname":"mk4","capabilities":{"upload-by-put":true}}""")
                r.path == "/api/v1/storage" -> Response(
                    body = """{"storage_list":[{"path":"/local","read_only":true,"available":true},{"path":"/usb","read_only":false,"available":true,"free_space":1000000}]}""",
                )
                r.path.startsWith("/api/v1/files/") -> Response(201, "")
                r.path == "/api/v1/status" -> Response(
                    body = """{"job":{"id":7,"progress":55.0,"time_remaining":1200,"time_printing":1500},"printer":{"state":"PRINTING","temp_nozzle":215.1,"target_nozzle":215.0,"temp_bed":60.0,"target_bed":60.0}}""",
                )
                r.path == "/api/v1/job" -> Response(body = """{"id":7,"file":{"name":"CUBE~1.GCO","display_name":"cube.gcode"}}""")
                r.path == "/api/v1/job/7/pause" -> Response(204, "")
                r.method == "DELETE" && r.path == "/api/v1/job/7" -> Response(204, "")
                else -> Response(404, "")
            }
        }.use { server ->
            val host = PrusaLinkHost(conn(HostType.PRUSALINK, server.host, username = "maker", password = "pass"))
            assertEquals("PrusaLink · mk4", host.test())
            val file = gcode()
            host.upload(file, "my cube.gcode", true, PrintOptions(), noProgress)
            val put = server.requests.last { it.method == "PUT" }
            assertEquals("/api/v1/files/usb/my%20cube.gcode", put.path)
            assertEquals("?1", put.header("Print-After-Upload"))
            assertEquals("?1", put.header("Overwrite"))
            assertEquals(file.readText(), put.text)

            val s = host.status()
            assertEquals(PrintState.PRINTING, s.state)
            assertEquals("cube.gcode", s.fileName)
            assertEquals(0.55, s.progress!!, 1e-9)

            host.pause()
            assertNotNull(server.find("PUT", "/api/v1/job/7/pause"))
            host.cancel()
            assertNotNull(server.find("DELETE", "/api/v1/job/7"))
        }
    }

    @Test
    fun prusaLinkWithoutPutFallsBackToFormUpload() {
        MockHttpServer { r ->
            when (r.path) {
                "/api/version" -> Response(body = """{"api":"0.1","text":"PrusaLink 0.6"}""")
                "/api/files/local" -> Response(201, "{}")
                else -> Response(404, "")
            }
        }.use { server ->
            PrusaLinkHost(conn(HostType.PRUSALINK, server.host, apiKey = "k")).upload(gcode(), "cube.gcode", false, PrintOptions(), noProgress)
            val post = server.find("POST", "/api/files/local")!!
            assertEquals("k", post.header("X-Api-Key"))
            assertTrue(post.text.contains("name=\"print\"\r\n\r\nfalse"))
        }
    }

    // --- Duet ---

    @Test
    fun duetStandaloneUploadsAndStartsWithSessionKey() {
        MockHttpServer { r ->
            when (r.path) {
                "/rr_connect" -> if (r.query.contains("password=secret")) Response(body = """{"err":0,"sessionKey":4242}""") else Response(body = """{"err":1}""")
                "/rr_upload" -> Response(body = """{"err":0}""")
                "/rr_gcode" -> Response(body = """{"buff":255}""")
                "/rr_disconnect" -> Response(body = """{"err":0}""")
                else -> Response(404, "")
            }
        }.use { server ->
            try {
                DuetHost(conn(HostType.DUET, server.host, password = "wrong")).upload(gcode(), "cube.gcode", false, PrintOptions(), noProgress)
                fail()
            } catch (e: PrintHostException) {
                assertEquals("Wrong password", e.message)
            }
            val file = gcode()
            DuetHost(conn(HostType.DUET, server.host, password = "secret")).upload(file, "cube.gcode", true, PrintOptions(), noProgress)
            val up = server.find("POST", "/rr_upload")!!
            assertTrue(up.query, up.query.startsWith("name=0:/gcodes/cube.gcode&time="))
            assertEquals("4242", up.header("X-Session-Key"))
            assertEquals(file.readText(), up.text)
            val g = server.find("GET", "/rr_gcode")!!
            assertEquals("gcode=M32%20%220%3A%2Fgcodes%2Fcube.gcode%22", g.query)
            assertNotNull(server.find("GET", "/rr_disconnect"))
        }
    }

    @Test
    fun duetFallsBackToSbcApi() {
        MockHttpServer { r ->
            when {
                r.path == "/machine/status" -> Response(
                    body = """{"state":{"status":"processing"},"job":{"file":{"fileName":"0:/gcodes/cube.gcode","size":1000,"numLayers":50},"filePosition":250,"layer":10,"timesLeft":{"file":600}},
                        "heat":{"bedHeaters":[0],"heaters":[{"current":60.2,"active":60},{"current":209.9,"active":210}]}}""",
                )
                r.path == "/machine/file/gcodes/cube.gcode" -> Response(201, "")
                r.path == "/machine/code" -> Response(body = "")
                else -> Response(404, "")
            }
        }.use { server ->
            val host = DuetHost(conn(HostType.DUET, server.host))
            assertEquals("Duet with SBC (DSF)", host.test())
            host.upload(gcode(), "cube.gcode", true, PrintOptions(simulate = true), noProgress)
            assertEquals("M37 P\"0:/gcodes/cube.gcode\"", server.find("POST", "/machine/code")!!.text)
            val s = host.status()
            assertEquals(PrintState.PRINTING, s.state)
            assertEquals("cube.gcode", s.fileName)
            assertEquals(0.25, s.progress!!, 1e-9)
            assertEquals(209.9, s.nozzleTemp!!, 1e-9)
            assertEquals(60.0, s.bedTarget!!, 1e-9)
            assertEquals(50, s.totalLayers)
        }
    }

    @Test
    fun duetListsAndCancelsM486Objects() {
        MockHttpServer { r ->
            when (r.path) {
                "/rr_connect" -> Response(body = """{"err":0}""")
                "/rr_model" -> Response(
                    body = """{"key":"job.build","result":{"currentObject":1,"m486Names":true,"m486Numbers":true,"objects":[
                        {"cancelled":false,"name":"Cube","x":[10,30],"y":[20,40]},
                        {"cancelled":true,"name":"Ring","x":[60,80],"y":[20,45]}]}}""",
                )
                "/rr_gcode" -> Response(body = """{"buff":255}""")
                else -> Response(body = """{"err":0}""")
            }
        }.use { server ->
            val host = DuetHost(conn(HostType.DUET, server.host))
            val objects = host.printObjects(null)
            assertEquals(listOf("0", "1"), objects.map { it.id })
            assertEquals(listOf(false, true), objects.map { it.skipped })
            assertEquals(listOf(false, true), objects.map { it.current })
            assertEquals(listOf(10.0 to 20.0, 30.0 to 20.0, 30.0 to 40.0, 10.0 to 40.0), objects[0].outline)
            assertTrue(server.find("GET", "/rr_model")!!.query.startsWith("key=job.build"))

            host.skipObjects(objects.take(1), null)
            assertEquals("gcode=M486%20P0", server.find("GET", "/rr_gcode")!!.query)
        }
    }

    @Test
    fun fileNamesAreMadeSafe() {
        assertEquals("my_cube_v2.gcode", PrintHost.safeFileName("my cube/v2.gcode"))
        assertEquals("print.gcode", PrintHost.safeFileName("///"))
    }

    private fun digestValid(header: String, method: String, realm: String, nonce: String, user: String, password: String): Boolean {
        val p = Regex("(\\w+)=(\"([^\"]*)\"|[^,\\s]+)").findAll(header.substringAfter(' '))
            .associate { it.groupValues[1] to (it.groups[3]?.value ?: it.groupValues[2]) }
        if (p["username"] != user || p["realm"] != realm || p["nonce"] != nonce) return false
        val ha1 = DigestChallenge.md5("$user:$realm:$password")
        val ha2 = DigestChallenge.md5("$method:${p["uri"]}")
        return p["response"] == DigestChallenge.md5("$ha1:$nonce:${p["nc"]}:${p["cnonce"]}:${p["qop"]}:$ha2")
    }
}
