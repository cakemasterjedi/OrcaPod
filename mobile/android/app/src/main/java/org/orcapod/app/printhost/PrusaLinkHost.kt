package org.orcapod.app.printhost

import org.json.JSONObject
import java.io.File

/**
 * PrusaLink (MK4, MK3.9, XL, MINI and the MK3's Raspberry Pi build), following
 * desktop's PrusaLink host: API key or digest login, PUT upload to the first
 * writable storage when the printer offers it, else OctoPrint's form upload.
 */
class PrusaLinkHost(c: PrinterConnection) : PrintHost {
    private val http = HttpClient(
        c.host, c.allowSelfSigned,
        headers = if (c.username.isEmpty()) mapOf("X-Api-Key" to c.apiKey) else emptyMap(),
        digestUser = c.username, digestPassword = c.password,
    )
    private var uploadByPut: Boolean? = null

    override fun test(): String {
        val v = http.get("api/version").json()
        if (!v.has("api")) throw PrintHostException("This does not look like PrusaLink")
        uploadByPut = v.optJSONObject("capabilities")?.optBoolean("upload-by-put") == true
        return listOfNotNull(v.optString("text").ifEmpty { "PrusaLink" }, v.optStringOrNull("hostname")).joinToString(" · ")
    }

    override fun upload(file: File, remoteName: String, startPrint: Boolean, options: PrintOptions, progress: UploadProgress): String {
        if (uploadByPut == null) test()
        if (uploadByPut == true) {
            val storage = storage()
            http.put(
                "api/v1/files/$storage/${HttpClient.encode(remoteName)}",
                Body.FileBody(file, "text/x.gcode"),
                progress,
                extra = buildMap {
                    put("Overwrite", "?1")
                    if (startPrint) put("Print-After-Upload", "?1")
                },
            )
        } else {
            val body = Body.Multipart(
                listOf("print" to startPrint.toString(), "path" to ""), "file", file, remoteName,
            )
            http.post("api/files/local", body, progress)
        }
        return remoteName
    }

    /** The first available, writable storage; older firmware has no list and takes "usb". */
    private fun storage(): String {
        val r = http.get("api/v1/storage") { it in 200..299 || it == 404 }
        if (r.status == 404) return "usb"
        val list = r.json().optJSONArray("storage_list") ?: return "usb"
        for (i in 0 until list.length()) {
            val s = list.optJSONObject(i) ?: continue
            val path = s.optString("path").trim('/')
            val readOnly = s.optBoolean("read_only", s.optBoolean("ro", false))
            if (path.isNotEmpty() && s.optBoolean("available", true) && !readOnly) return path
        }
        throw PrintHostException("The printer has no writable storage; insert a USB drive")
    }

    override fun status(): PrinterStatus {
        val r = http.get("api/v1/status") { it in 200..299 || it == 404 }
        if (r.status == 404) return legacy().status()
        val s = parse(r.json())
        if (!s.isActive) return s
        val file = runCatching { http.get("api/v1/job").json().optJSONObject("file") }.getOrNull()
        return s.copy(fileName = file?.optStringOrNull("display_name") ?: file?.optStringOrNull("name"))
    }

    override fun pause() = jobAction("pause")
    override fun resume() = jobAction("resume")
    override fun cancel() {
        val id = jobId() ?: return legacy().cancel()
        http.delete("api/v1/job/$id")
    }

    private fun jobAction(action: String) {
        val id = jobId() ?: return if (action == "pause") legacy().pause() else legacy().resume()
        http.put("api/v1/job/$id/$action", null)
    }

    private fun jobId(): Long? {
        val r = http.get("api/v1/status") { it in 200..299 || it == 404 }
        if (r.status == 404) return null
        return r.json().optJSONObject("job")?.optLong("id")?.takeIf { it > 0 }
            ?: throw PrintHostException("No print is running")
    }

    // Firmware before the v1 API speaks OctoPrint's job API.
    private fun legacy() = LegacyApi(http)

    private class LegacyApi(private val http: HttpClient) {
        fun status() = OctoPrintHost.parse(http.get("api/job").json(), runCatching { http.get("api/printer").json() }.getOrNull())
        fun pause() { http.post("api/job", Body.Json(JSONObject().put("command", "pause").put("action", "pause"))) }
        fun resume() { http.post("api/job", Body.Json(JSONObject().put("command", "pause").put("action", "resume"))) }
        fun cancel() { http.post("api/job", Body.Json(JSONObject().put("command", "cancel"))) }
    }

    companion object {
        fun parse(status: JSONObject): PrinterStatus {
            val p = status.optJSONObject("printer") ?: JSONObject()
            val job = status.optJSONObject("job")
            val text = p.optString("state", "UNKNOWN")
            val state = when (text.uppercase()) {
                "IDLE", "READY" -> PrintState.IDLE
                "PRINTING" -> PrintState.PRINTING
                "PAUSED" -> PrintState.PAUSED
                "FINISHED" -> PrintState.FINISHED
                "STOPPED" -> PrintState.CANCELLED
                "ERROR" -> PrintState.ERROR
                "BUSY", "ATTENTION" -> PrintState.BUSY
                else -> PrintState.UNKNOWN
            }
            return PrinterStatus(
                state = state,
                stateText = text.lowercase().replaceFirstChar { it.uppercase() },
                progress = job?.optDoubleOrNull("progress")?.div(100.0),
                elapsedSeconds = job?.optDoubleOrNull("time_printing")?.toLong(),
                remainingSeconds = job?.optDoubleOrNull("time_remaining")?.toLong(),
                nozzleTemp = p.optDoubleOrNull("temp_nozzle"),
                nozzleTarget = p.optDoubleOrNull("target_nozzle"),
                bedTemp = p.optDoubleOrNull("temp_bed"),
                bedTarget = p.optDoubleOrNull("target_bed"),
                message = if (state == PrintState.BUSY && text.equals("ATTENTION", true)) "The printer needs attention" else null,
            )
        }
    }
}
