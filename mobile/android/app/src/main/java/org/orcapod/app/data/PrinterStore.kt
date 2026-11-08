package org.orcapod.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.orcapod.app.printhost.HostType
import org.orcapod.app.printhost.PrinterConnection

/**
 * Saved printer connections, desktop's "physical printers", in app-private
 * preferences. The file is left out of cloud backups (see
 * res/xml/backup_rules.xml) because it holds API keys and access codes.
 */
class PrinterStore(context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(): List<PrinterConnection> {
        val array = runCatching { JSONArray(prefs.getString(KEY_LIST, "[]")) }.getOrElse { JSONArray() }
        return (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let(::fromJson) }
    }

    fun save(list: List<PrinterConnection>) {
        prefs.edit().putString(KEY_LIST, JSONArray(list.map(::toJson)).toString()).apply()
    }

    var lastUsed: String?
        get() = prefs.getString(KEY_LAST, null)
        set(value) = prefs.edit().putString(KEY_LAST, value).apply()

    companion object {
        const val FILE = "printers"
        private const val KEY_LIST = "connections"
        private const val KEY_LAST = "last_used"

        fun toJson(c: PrinterConnection): JSONObject = JSONObject()
            .put("id", c.id).put("name", c.name).put("type", c.type.name).put("host", c.host)
            .put("apiKey", c.apiKey).put("username", c.username).put("password", c.password)
            .put("serial", c.serial).put("accessCode", c.accessCode)
            .put("allowSelfSigned", c.allowSelfSigned).put("printerPreset", c.printerPreset)
            .put("cameraUrl", c.cameraUrl)

        fun fromJson(j: JSONObject): PrinterConnection? {
            val type = runCatching { HostType.valueOf(j.optString("type")) }.getOrNull() ?: return null
            return PrinterConnection(
                id = j.optString("id").ifEmpty { return null },
                name = j.optString("name"),
                type = type,
                host = j.optString("host"),
                apiKey = j.optString("apiKey"),
                username = j.optString("username"),
                password = j.optString("password"),
                serial = j.optString("serial"),
                accessCode = j.optString("accessCode"),
                allowSelfSigned = j.optBoolean("allowSelfSigned"),
                printerPreset = j.optString("printerPreset"),
                cameraUrl = j.optString("cameraUrl"),
            )
        }
    }
}
