package org.orcapod.app.printhost

import org.json.JSONObject
import java.io.File

/** Klipper through Moonraker's API, following desktop's Moonraker host. */
class MoonrakerHost(c: PrinterConnection) : PrintHost {
    private val allowSelfSigned = c.allowSelfSigned
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

    /** The first enabled webcam from Moonraker's list (crowsnest and Mainsail register theirs there). */
    override fun camera(): CameraSource? {
        val list = http.get("server/webcams/list").json().optJSONObject("result")?.optJSONArray("webcams") ?: return null
        for (i in 0 until list.length()) {
            val w = list.optJSONObject(i) ?: continue
            if (!w.optBoolean("enabled", true)) continue
            val urls = CameraUrls(
                HttpCameraFeed.resolve(http.baseUrl, w.optStringOrNull("stream_url"), keepPort = false),
                HttpCameraFeed.resolve(http.baseUrl, w.optStringOrNull("snapshot_url"), keepPort = false),
            )
            if (urls.stream != null || urls.snapshot != null) return CameraSource.Frames(HttpCameraFeed(urls, allowSelfSigned))
        }
        return null
    }

    override fun history(limit: Int): List<JobRecord> =
        parseHistory(http.get("server/history/list?limit=$limit&order=desc").json())

    // Klipper lists the objects of any print itself, with [exclude_object] in printer.cfg.
    override fun printObjects(labels: GcodeLabels?): List<PrintObject> =
        parseObjects(http.get("printer/objects/query?exclude_object").json())

    override fun skipObjects(objects: List<PrintObject>, labels: GcodeLabels?) {
        for (o in objects) http.post("printer/gcode/script", Body.Json(JSONObject().put("script", "EXCLUDE_OBJECT NAME=${o.id}")))
    }

    companion object {
        fun parseObjects(query: JSONObject): List<PrintObject> {
            val e = query.optJSONObject("result")?.optJSONObject("status")?.optJSONObject("exclude_object") ?: return emptyList()
            val list = e.optJSONArray("objects") ?: return emptyList()
            val excluded = e.optJSONArray("excluded_objects")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }.orEmpty()
            val current = e.optStringOrNull("current_object")
            return (0 until list.length()).mapNotNull { i ->
                val o = list.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optStringOrNull("name") ?: return@mapNotNull null
                val polygon = o.optJSONArray("polygon")
                val outline = (0 until (polygon?.length() ?: 0)).mapNotNull { j ->
                    polygon?.optJSONArray(j)?.takeIf { it.length() >= 2 }?.let { it.optDouble(0) to it.optDouble(1) }
                }
                PrintObject(name, name, outline, skipped = name in excluded, current = name == current)
            }
        }

        fun parseHistory(json: JSONObject): List<JobRecord> {
            val jobs = json.optJSONObject("result")?.optJSONArray("jobs") ?: return emptyList()
            return (0 until jobs.length()).mapNotNull { i ->
                val j = jobs.optJSONObject(i) ?: return@mapNotNull null
                val status = j.optString("status")
                JobRecord(
                    fileName = j.optString("filename"),
                    outcome = when (status) {
                        "completed" -> JobOutcome.FINISHED
                        "cancelled" -> JobOutcome.CANCELLED
                        "in_progress" -> JobOutcome.PRINTING
                        else -> JobOutcome.FAILED
                    },
                    startedAt = ((j.optDoubleOrNull("start_time") ?: 0.0) * 1000).toLong(),
                    durationSeconds = j.optDoubleOrNull("print_duration")?.toLong(),
                    filamentMm = j.optDoubleOrNull("filament_used"),
                    message = status.takeIf { it !in setOf("completed", "cancelled", "in_progress") }?.replace('_', ' '),
                )
            }
        }

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
