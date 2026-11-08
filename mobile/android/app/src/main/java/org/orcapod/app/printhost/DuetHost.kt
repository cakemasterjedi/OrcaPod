package org.orcapod.app.printhost

import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * RepRapFirmware on a Duet, standalone (rr_* requests with a session per
 * call) or with a single-board computer (DSF's /machine API), detected the
 * way desktop's Duet host does: rr_connect first, then /machine/status.
 */
class DuetHost(private val c: PrinterConnection) : PrintHost {
    private val http = HttpClient(c.host, c.allowSelfSigned)

    /** An open connection: SBC needs no session, standalone may hand out a session key. */
    private inner class Session(val dsf: Boolean, sessionKey: String?) {
        val headers = sessionKey?.let { mapOf("X-Session-Key" to it) } ?: emptyMap()
        fun close() {
            if (!dsf) runCatching { http.get("rr_disconnect", headers) }
        }
    }

    private fun connect(): Session {
        val pw = HttpClient.encode(c.password.ifEmpty { "reprap" })
        val r = try {
            http.get("rr_connect?password=$pw&${timestamp()}")
        } catch (e: PrintHostException) {
            // No rr_connect: try the SBC API before giving up.
            try {
                http.get("machine/status")
                return Session(true, null)
            } catch (_: PrintHostException) {
                throw e
            }
        }
        val j = r.json()
        when (j.optInt("err", 0)) {
            0 -> return Session(false, j.optStringOrNull("sessionKey") ?: j.optLong("sessionKey").takeIf { it != 0L }?.toString())
            1 -> throw PrintHostException("Wrong password")
            2 -> throw PrintHostException("The Duet has no free connection slots; close other web interfaces and try again")
            else -> throw PrintHostException("Unknown error from the Duet: ${r.body.take(80)}")
        }
    }

    private inline fun <T> session(block: (Session) -> T): T {
        val s = connect()
        try {
            return block(s)
        } finally {
            s.close()
        }
    }

    override fun test(): String = session { s ->
        if (s.dsf) "Duet with SBC (DSF)" else {
            val board = runCatching { model(s, "boards").optJSONArray("result")?.optJSONObject(0) }.getOrNull()
            listOfNotNull(board?.optStringOrNull("name"), board?.optStringOrNull("firmwareVersion")?.let { "RRF $it" })
                .joinToString(" · ").ifEmpty { "RepRapFirmware" }
        }
    }

    override fun upload(file: File, remoteName: String, startPrint: Boolean, options: PrintOptions, progress: UploadProgress): String = session { s ->
        val name = HttpClient.encode(remoteName)
        if (s.dsf) {
            val r = http.put("machine/file/gcodes/$name", Body.FileBody(file), progress)
            if (r.status != 201 && r.status != 200) throw PrintHostException("The Duet did not store the file (HTTP ${r.status})")
        } else {
            val r = http.post("rr_upload?name=0:/gcodes/$name&${timestamp()}", Body.FileBody(file), progress, s.headers)
            if (r.json().optInt("err", 0) != 0) throw PrintHostException("The Duet could not store the file")
        }
        if (startPrint) gcode(s, if (options.simulate) "M37 P\"0:/gcodes/$remoteName\"" else "M32 \"0:/gcodes/$remoteName\"")
        remoteName
    }

    override fun status(): PrinterStatus = session { s ->
        val m = if (s.dsf) {
            http.get("machine/status").json()
        } else {
            JSONObject()
                .put("state", model(s, "state").optJSONObject("result"))
                .put("job", model(s, "job").optJSONObject("result"))
                .put("heat", model(s, "heat").optJSONObject("result"))
        }
        parse(m)
    }

    override fun pause() = session { gcode(it, "M25") }
    override fun resume() = session { gcode(it, "M24") }

    // RRF cancels only a paused print: pause first, wait for it to settle, then M0.
    override fun cancel() = session { s ->
        gcode(s, "M25")
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            val st = if (s.dsf) http.get("machine/status").json().optJSONObject("state")
            else model(s, "state").optJSONObject("result")
            if (st?.optString("status") == "paused") break
            Thread.sleep(500)
        }
        gcode(s, "M0")
    }

    // RepRapFirmware lists the M486 objects of any print in job.build.
    override fun printObjects(labels: GcodeLabels?): List<PrintObject> = session { s ->
        val build = if (s.dsf) http.get("machine/status").json().optJSONObject("job")?.optJSONObject("build")
        else model(s, "job.build").optJSONObject("result")
        parseBuild(build)
    }

    override fun skipObjects(objects: List<PrintObject>, labels: GcodeLabels?) = session { s ->
        for (o in objects) gcode(s, "M486 P${o.id}")
    }

    private fun model(s: Session, key: String): JSONObject =
        http.get("rr_model?key=$key&flags=d99fn", s.headers).json()

    private fun gcode(s: Session, code: String) {
        if (s.dsf) http.post("machine/code", Body.Bytes(code.toByteArray(), "text/plain"))
        else http.get("rr_gcode?gcode=${HttpClient.encode(code)}", s.headers)
    }

    private fun timestamp() = "time=" + SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())

    companion object {
        /** job.build: the objects in M486 order, so an object's index is its number. */
        fun parseBuild(build: JSONObject?): List<PrintObject> {
            val list = build?.optJSONArray("objects") ?: return emptyList()
            val current = build.optInt("currentObject", -1)
            return (0 until list.length()).mapNotNull { i ->
                val o = list.optJSONObject(i) ?: return@mapNotNull null
                val x = o.optJSONArray("x")
                val y = o.optJSONArray("y")
                val outline = if (x != null && y != null && x.length() == 2 && y.length() == 2) {
                    val (x0, x1, y0, y1) = listOf(x.optDouble(0), x.optDouble(1), y.optDouble(0), y.optDouble(1))
                    listOf(x0 to y0, x1 to y0, x1 to y1, x0 to y1)
                } else emptyList()
                PrintObject(i.toString(), o.optStringOrNull("name") ?: "Object $i", outline, o.optBoolean("cancelled"), i == current)
            }
        }

        /** Reads the object model subset {state, job, heat}, whichever API it came from. */
        fun parse(m: JSONObject): PrinterStatus {
            val text = m.optJSONObject("state")?.optString("status").orEmpty().ifEmpty { "unknown" }
            val state = when (text) {
                "idle" -> PrintState.IDLE
                "processing", "simulating" -> PrintState.PRINTING
                "paused" -> PrintState.PAUSED
                "pausing", "resuming", "cancelling", "busy", "changingTool", "starting", "updating" -> PrintState.BUSY
                "halted" -> PrintState.ERROR
                "off" -> PrintState.OFFLINE
                else -> PrintState.UNKNOWN
            }
            val job = m.optJSONObject("job")
            val file = job?.optJSONObject("file")
            val size = file?.optDoubleOrNull("size")
            val pos = job?.optDoubleOrNull("filePosition")
            val progress = if (size != null && size > 0 && pos != null) (pos / size).coerceIn(0.0, 1.0) else null
            val heat = m.optJSONObject("heat")
            val heaters = heat?.optJSONArray("heaters")
            val bedIndex = heat?.optJSONArray("bedHeaters")?.optInt(0, -1) ?: -1
            // The first heater that is not the bed is the hot end on a typical single-tool machine.
            val nozzleIndex = (0 until (heaters?.length() ?: 0)).firstOrNull { it != bedIndex && heaters?.optJSONObject(it) != null } ?: -1
            fun heater(i: Int) = if (i >= 0) heaters?.optJSONObject(i) else null
            val active = state == PrintState.PRINTING || state == PrintState.PAUSED || state == PrintState.BUSY
            return PrinterStatus(
                state = state,
                stateText = text.replaceFirstChar { it.uppercase() },
                fileName = file?.optStringOrNull("fileName")?.substringAfterLast('/')?.takeIf { active },
                progress = progress?.takeIf { active },
                elapsedSeconds = job?.optDoubleOrNull("duration")?.toLong(),
                remainingSeconds = job?.optJSONObject("timesLeft")?.let { it.optDoubleOrNull("slicer") ?: it.optDoubleOrNull("file") }?.toLong(),
                nozzleTemp = heater(nozzleIndex)?.optDoubleOrNull("current"),
                nozzleTarget = heater(nozzleIndex)?.optDoubleOrNull("active"),
                bedTemp = heater(bedIndex)?.optDoubleOrNull("current"),
                bedTarget = heater(bedIndex)?.optDoubleOrNull("active"),
                layer = job?.optIntOrNull("layer"),
                totalLayers = file?.optIntOrNull("numLayers")?.takeIf { it > 0 },
            )
        }
    }
}
