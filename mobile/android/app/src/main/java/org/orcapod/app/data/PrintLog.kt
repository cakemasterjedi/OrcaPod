package org.orcapod.app.data

import org.json.JSONArray
import org.json.JSONObject
import org.orcapod.app.printhost.JobOutcome
import org.orcapod.app.printhost.JobRecord
import java.io.File

/**
 * The app's own record of the jobs it sent, per printer, so every printer
 * type has a history: most keep none the app can read (only Moonraker does),
 * and Bambu's lives in Bambu's cloud. While a printer is watched, the entry
 * of the job it is printing follows the print to its end.
 */
class PrintLog(private val file: File, private val capacity: Int = 300) {
    data class Entry(val id: Long, val connectionId: String, val printer: String, val job: JobRecord)

    @Synchronized
    fun all(): List<Entry> {
        val array = runCatching { JSONArray(file.readText()) }.getOrElse { return emptyList() }
        return (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let(::fromJson) }
    }

    fun forConnection(id: String) = all().filter { it.connectionId == id }

    @Synchronized
    fun add(connectionId: String, printer: String, job: JobRecord): Entry {
        val list = all()
        val e = Entry((list.maxOfOrNull { it.id } ?: 0) + 1, connectionId, printer, job)
        write((listOf(e) + list).take(capacity))
        return e
    }

    /**
     * Matches a status report to the job this app started: the newest entry
     * for the printer still printing whose file name the printer's matches
     * (printers report "cube", "cube.gcode" or "cube.gcode.3mf").
     */
    @Synchronized
    fun update(connectionId: String, reportedFile: String?, outcome: JobOutcome, now: Long = System.currentTimeMillis()): Boolean {
        val list = all()
        val stem = reportedFile?.let(::stem)
        val i = list.indexOfFirst {
            it.connectionId == connectionId && it.job.outcome == JobOutcome.PRINTING && (stem == null || stem(it.job.fileName) == stem)
        }
        if (i < 0 || list[i].job.outcome == outcome) return false
        val e = list[i]
        val duration = if (outcome == JobOutcome.PRINTING) null else (now - e.job.startedAt) / 1000
        write(list.toMutableList().apply { set(i, e.copy(job = e.job.copy(outcome = outcome, durationSeconds = duration))) })
        return true
    }

    @Synchronized
    fun clear(connectionId: String) = write(all().filterNot { it.connectionId == connectionId })

    private fun write(list: List<Entry>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(JSONArray(list.map(::toJson)).toString())
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    companion object {
        fun stem(name: String): String =
            name.substringAfterLast('/').lowercase().removeSuffix(".3mf").removeSuffix(".gcode").removeSuffix(".gco").removeSuffix(".bgcode")

        private fun toJson(e: Entry) = JSONObject()
            .put("id", e.id).put("connection", e.connectionId).put("printer", e.printer)
            .put("file", e.job.fileName).put("outcome", e.job.outcome.name).put("startedAt", e.job.startedAt)
            .put("duration", e.job.durationSeconds ?: JSONObject.NULL).put("message", e.job.message ?: JSONObject.NULL)

        private fun fromJson(j: JSONObject): Entry? {
            val outcome = runCatching { JobOutcome.valueOf(j.optString("outcome")) }.getOrNull() ?: return null
            return Entry(
                j.optLong("id"), j.optString("connection"), j.optString("printer"),
                JobRecord(
                    fileName = j.optString("file"),
                    outcome = outcome,
                    startedAt = j.optLong("startedAt"),
                    durationSeconds = if (j.isNull("duration")) null else j.optLong("duration"),
                    message = if (j.isNull("message")) null else j.optString("message"),
                ),
            )
        }
    }
}
