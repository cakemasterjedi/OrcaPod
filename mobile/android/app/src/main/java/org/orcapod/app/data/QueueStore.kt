package org.orcapod.app.data

import org.json.JSONArray
import org.json.JSONObject
import org.orcapod.app.printhost.PrintOptions
import org.orcapod.app.printhost.QueueItem
import org.orcapod.app.printhost.QueueState
import org.orcapod.app.printhost.QueueStorage
import java.io.File

/** The print queue in a JSON file in app-private storage, written atomically like [PrintLog]. */
class QueueStore(private val file: File) : QueueStorage {
    override fun load(): List<QueueItem> {
        val array = runCatching { JSONArray(file.readText()) }.getOrElse { return emptyList() }
        return (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let(::fromJson) }
    }

    override fun save(items: List<QueueItem>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(JSONArray(items.map(::toJson)).toString())
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    companion object {
        fun toJson(i: QueueItem): JSONObject = JSONObject()
            .put("id", i.id).put("connection", i.connectionId).put("printer", i.printerName)
            .put("file", i.fileName).put("gcode", i.gcodePath).put("start", i.start)
            .put("options", options(i.options)).put("state", i.state.name).put("progress", i.progress.toDouble())
            .put("message", i.message ?: JSONObject.NULL).put("addedAt", i.addedAt)
            .put("startedAt", i.startedAt ?: JSONObject.NULL).put("endedAt", i.endedAt ?: JSONObject.NULL)
            .put("seenActive", i.seenActive)

        fun fromJson(j: JSONObject): QueueItem? {
            val state = runCatching { QueueState.valueOf(j.optString("state")) }.getOrNull() ?: return null
            return QueueItem(
                id = j.optString("id").ifEmpty { return null },
                connectionId = j.optString("connection"),
                printerName = j.optString("printer"),
                fileName = j.optString("file"),
                gcodePath = j.optString("gcode"),
                start = j.optBoolean("start"),
                options = j.optJSONObject("options")?.let(::options) ?: PrintOptions(),
                state = state,
                progress = j.optDouble("progress", 0.0).toFloat(),
                message = if (j.isNull("message")) null else j.optString("message"),
                addedAt = j.optLong("addedAt"),
                startedAt = if (j.isNull("startedAt")) null else j.optLong("startedAt"),
                endedAt = if (j.isNull("endedAt")) null else j.optLong("endedAt"),
                seenActive = j.optBoolean("seenActive"),
            )
        }

        private fun options(o: PrintOptions) = JSONObject()
            .put("bedLeveling", o.bedLeveling).put("flowCalibration", o.flowCalibration)
            .put("vibrationCalibration", o.vibrationCalibration).put("timelapse", o.timelapse)
            .put("useAms", o.useAms).put("amsTray", o.amsTray).put("simulate", o.simulate)

        private fun options(j: JSONObject) = PrintOptions(
            bedLeveling = j.optBoolean("bedLeveling", true),
            flowCalibration = j.optBoolean("flowCalibration"),
            vibrationCalibration = j.optBoolean("vibrationCalibration"),
            timelapse = j.optBoolean("timelapse"),
            useAms = j.optBoolean("useAms"),
            amsTray = j.optInt("amsTray"),
            simulate = j.optBoolean("simulate"),
        )
    }
}
