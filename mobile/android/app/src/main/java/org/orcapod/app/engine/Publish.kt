package org.orcapod.app.engine

import org.json.JSONArray
import org.json.JSONObject

// Desktop OrcaSlicer's "Publish 3MF" (orca_project_publish_* in the bridge): a 3MF that carries
// only the settings the author picked, which a receiver applies on top of its own printer and
// filaments.

/** One filament slot of the plate, as the publish sheet shows it. */
data class PublishFilament(
    val slot: Int,
    val name: String,
    /** The vendor-agnostic material family, such as "PLA". */
    val type: String,
    val colour: String,
    /** Printed with by the plate's objects; unused slots start unpublished. */
    val used: Boolean,
)

/** What a filament slot requires of the receiver's filament, or the whole preset with [full]. */
data class PublishMaterial(val slot: Int, val colour: Boolean, val type: Boolean, val full: Boolean) {
    val publishes: Boolean get() = colour || type || full
}

/** The settings a published 3MF carries. Empty: the geometry only. */
data class PublishSelection(val keys: List<String>, val materials: List<PublishMaterial>) {
    fun toJson(): String = JSONObject()
        .put("keys", JSONArray(keys))
        .put(
            "materials",
            JSONArray(materials.filter { it.publishes }.map {
                JSONObject().put("slot", it.slot).put("colour", it.colour).put("type", it.type).put("full", it.full)
            }),
        )
        .toString()

    companion object {
        fun parse(json: JSONObject) = PublishSelection(
            json.optJSONArray("keys").strings(),
            json.optJSONArray("materials").objects().map {
                PublishMaterial(it.getInt("slot"), it.optBoolean("colour"), it.optBoolean("type"), it.optBoolean("full"))
            },
        )
    }
}

/**
 * The choices for a published 3MF: the [process] and [printer] settings changed from the
 * selected presets' system base ("key#N" for one extruder of a per-extruder setting), the
 * plate's [filaments], and the selection of the published 3MF the plate came from, if any.
 */
data class PublishOptions(
    val process: List<String>,
    val printer: List<String>,
    val filaments: List<PublishFilament>,
    val previous: PublishSelection?,
) {
    /**
     * What the sheet starts with: the last publish (or the opened published file) where there is
     * one, otherwise every changed setting, like desktop pre-checks the modified ones, and the
     * colour and type of each used filament.
     */
    fun initialSelection(): PublishSelection = previous ?: PublishSelection(
        process + printer,
        filaments.filter { it.used }.map { PublishMaterial(it.slot, colour = true, type = true, full = false) },
    )

    companion object {
        fun parse(text: String): PublishOptions {
            val json = JSONObject(text)
            return PublishOptions(
                json.optJSONArray("process").strings(),
                json.optJSONArray("printer").strings(),
                json.optJSONArray("filaments").objects().map {
                    PublishFilament(it.getInt("slot"), it.optString("name"), it.optString("type"), it.optString("colour"), it.optBoolean("used"))
                },
                json.optJSONObject("previous")?.let { PublishSelection.parse(it) },
            )
        }
    }
}

/** What opening a published 3MF could not apply as its author published it. */
data class PublishNotes(val skipped: List<String>, val replaced: List<String>) {
    companion object {
        fun parse(text: String): PublishNotes {
            val json = JSONObject(text)
            return PublishNotes(json.optJSONArray("skipped").strings(), json.optJSONArray("replaced").strings())
        }
    }
}

private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else List(length()) { getString(it) }

private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else List(length()) { getJSONObject(it) }
