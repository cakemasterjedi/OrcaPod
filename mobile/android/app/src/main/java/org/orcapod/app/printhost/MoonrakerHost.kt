package org.orcapod.app.printhost

import org.json.JSONObject
import java.io.File

/** Klipper through Moonraker's API, following desktop's Moonraker host. */
class MoonrakerHost(c: PrinterConnection) : PrintHost {
    private val http = HttpClient(c.host, c.allowSelfSigned, mapOf("X-Api-Key" to c.apiKey))

    override fun test(): String {
        val info = http.get("server/info").json().optJSONObject("result")
            ?: throw PrintHostException("This does not look like Moonraker")
        val klippy = info.optString("klippy_state")
        val version = info.optString("moonraker_version")
        return listOf("Moonraker $version".trim(), "Klipper $klippy".takeIf { klippy.isNotEmpty() })
            .filterNotNull().joinToString(" · ")
    }

    override fun upload(file: File, remoteName: String, startPrint: Boolean, options: PrintOptions, progress: UploadProgress): String {
        val body = Body.Multipart(
            fields = listOf("root" to "gcodes", "print" to startPrint.toString()),
            fileField = "file", file = file, fileName = remoteName,
        )
        // The upload answers with a bare object, not the usual {"result": ...} envelope.
        val response = http.post("server/files/upload", body, progress)
        val r = runCatching { response.json() }.getOrDefault(JSONObject())
        val started = r.optBoolean("print_started") || r.optBoolean("print_queued")
        val path = r.optJSONObject("item")?.optStringOrNull("path") ?: remoteName
        if (startPrint && !started) {
            // print=true is ignored when Moonraker cannot start right away; start explicitly so the error surfaces.
            http.post("printer/print/start", Body.Json(JSONObject().put("filename", path)))
        }
        return path
    }

    override fun status(): PrinterStatus {
        val r = http.get("printer/objects/query?print_stats&virtual_sdcard&extruder&heater_bed&display_status") { it in 200..299 || it == 503 }
        if (r.status == 503) return PrinterStatus(PrintState.OFFLINE, "Klipper not ready")
        return parse(r.json())
    }

    override fun pause() { http.post("printer/print/pause", null) }
    override fun resume() { http.post("printer/print/resume", null) }
    override fun cancel() { http.post("printer/print/cancel", null) }

    companion object {
        fun parse(query: JSONObject): PrinterStatus {
            val s = query.optJSONObject("result")?.optJSONObject("status") ?: JSONObject()
            val stats = s.optJSONObject("print_stats") ?: JSONObject()
            val text = stats.optString("state", "unknown")
            val state = when (text) {
                "standby" -> PrintState.IDLE
                "printing" -> PrintState.PRINTING
                "paused" -> PrintState.PAUSED
                "complete" -> PrintState.FINISHED
                "cancelled" -> PrintState.CANCELLED
                "error" -> PrintState.ERROR
                else -> PrintState.UNKNOWN
            }
            val progress = s.optJSONObject("virtual_sdcard")?.optDoubleOrNull("progress")
                ?: s.optJSONObject("display_status")?.optDoubleOrNull("progress")
            val elapsed = stats.optDoubleOrNull("print_duration")
            // Klipper has no remaining-time estimate; extrapolate from progress like Mainsail's file estimate.
            val remaining = if (state == PrintState.PRINTING && elapsed != null && progress != null && progress > 0.01) {
                (elapsed / progress - elapsed).toLong()
            } else null
            val info = stats.optJSONObject("info")
            val extruder = s.optJSONObject("extruder")
            val bed = s.optJSONObject("heater_bed")
            return PrinterStatus(
                state = state,
                stateText = text,
                fileName = stats.optStringOrNull("filename"),
                progress = progress,
                elapsedSeconds = elapsed?.toLong(),
                remainingSeconds = remaining,
                nozzleTemp = extruder?.optDoubleOrNull("temperature"),
                nozzleTarget = extruder?.optDoubleOrNull("target"),
                bedTemp = bed?.optDoubleOrNull("temperature"),
                bedTarget = bed?.optDoubleOrNull("target"),
                layer = info?.optIntOrNull("current_layer"),
                totalLayers = info?.optIntOrNull("total_layer"),
                message = stats.optStringOrNull("message"),
            )
        }
    }
}
