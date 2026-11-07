package org.orcapod.app.printhost

import org.json.JSONObject
import java.io.File

/** OctoPrint's REST API, as desktop's OctoPrint host uses it: X-Api-Key and /api/files/local. */
class OctoPrintHost(c: PrinterConnection) : PrintHost {
    private val allowSelfSigned = c.allowSelfSigned
    private val http = HttpClient(c.host, c.allowSelfSigned, mapOf("X-Api-Key" to c.apiKey))

    override fun test(): String {
        val v = http.get("api/version").json()
        val text = v.optString("text")
        if (!v.has("api")) throw PrintHostException("This does not look like OctoPrint")
        if (text.isNotEmpty() && !text.startsWith("OctoPrint", true)) {
            throw PrintHostException("Mismatched type of print host: $text")
        }
        return text.ifEmpty { "OctoPrint ${v.optString("server")}" }
    }

    override fun upload(file: File, remoteName: String, startPrint: Boolean, options: PrintOptions, progress: UploadProgress): String {
        val body = Body.Multipart(
            fields = listOf("select" to startPrint.toString(), "print" to startPrint.toString()),
            fileField = "file", file = file, fileName = remoteName,
        )
        val r = http.post("api/files/local", body, progress)
        return runCatching { r.json().optJSONObject("files")?.optJSONObject("local")?.optStringOrNull("name") }.getOrNull() ?: remoteName
    }

    override fun status(): PrinterStatus {
        val job = http.get("api/job").json()
        // /api/printer answers 409 while the printer is not connected to OctoPrint.
        val printer = http.get("api/printer?exclude=sd,state") { it in 200..299 || it == 409 }
            .takeIf { it.status == 200 }?.json()
        return parse(job, printer)
    }

    override fun pause() = command(JSONObject().put("command", "pause").put("action", "pause"))
    override fun resume() = command(JSONObject().put("command", "pause").put("action", "resume"))
    override fun cancel() = command(JSONObject().put("command", "cancel"))

    override fun camera(): CameraSource? {
        val webcam = http.get("api/settings").json().optJSONObject("webcam") ?: return null
        if (!webcam.optBoolean("webcamEnabled", true)) return null
        val urls = CameraUrls(
            HttpCameraFeed.resolve(http.baseUrl, webcam.optStringOrNull("streamUrl")),
            HttpCameraFeed.resolve(http.baseUrl, webcam.optStringOrNull("snapshotUrl")),
        )
        return if (urls.stream == null && urls.snapshot == null) null else CameraSource.Frames(HttpCameraFeed(urls, allowSelfSigned))
    }

    private fun command(json: JSONObject) {
        http.post("api/job", Body.Json(json))
    }

    companion object {
        fun parse(job: JSONObject, printer: JSONObject?): PrinterStatus {
            val text = job.optString("state", "Unknown")
            val state = when {
                text.startsWith("Printing", true) -> PrintState.PRINTING
                text.startsWith("Paus", true) -> PrintState.PAUSED
                text.startsWith("Cancelling", true) -> PrintState.BUSY
                text.startsWith("Operational", true) -> PrintState.IDLE
                text.startsWith("Offline", true) || text.startsWith("Closed", true) -> PrintState.OFFLINE
                text.contains("Error", true) -> PrintState.ERROR
                text.startsWith("Connecting", true) || text.startsWith("Detecting", true) -> PrintState.BUSY
                else -> PrintState.UNKNOWN
            }
            val progress = job.optJSONObject("progress")
            val tool = printer?.optJSONObject("temperature")?.optJSONObject("tool0")
            val bed = printer?.optJSONObject("temperature")?.optJSONObject("bed")
            return PrinterStatus(
                state = state,
                stateText = text,
                fileName = job.optJSONObject("job")?.optJSONObject("file")?.optStringOrNull("name"),
                progress = progress?.optDoubleOrNull("completion")?.div(100.0),
                elapsedSeconds = progress?.optDoubleOrNull("printTime")?.toLong(),
                remainingSeconds = progress?.optDoubleOrNull("printTimeLeft")?.toLong(),
                nozzleTemp = tool?.optDoubleOrNull("actual"),
                nozzleTarget = tool?.optDoubleOrNull("target"),
                bedTemp = bed?.optDoubleOrNull("actual"),
                bedTarget = bed?.optDoubleOrNull("target"),
                message = job.optStringOrNull("error"),
            )
        }
    }
}

/** null for a missing or JSON-null value, which optDouble would turn into NaN. */
internal fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (isNull(key)) null else optDouble(key).takeIf { !it.isNaN() }

internal fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

internal fun JSONObject.optIntOrNull(key: String): Int? = optDoubleOrNull(key)?.toInt()
