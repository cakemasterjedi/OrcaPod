package org.orcapod.app.engine

import org.json.JSONObject

/**
 * One printable instance's clearance outline when printing by object: its footprint grown by
 * the toolhead clearance, as x, y pairs in world millimetres. Two outlines that overlap are too
 * close, and both are [colliding].
 */
class ClearanceOutline(val objectId: Long, val instanceId: Long, val colliding: Boolean, val points: FloatArray)

/**
 * Desktop OrcaSlicer's print-by-object clearance for the selected plate
 * (orca_plater_sequential_clearance): no outlines when the plate prints by layer, and the
 * reason the plate cannot print in [message], empty when it can.
 */
data class SequentialClearance(val byObject: Boolean, val message: String, val outlines: List<ClearanceOutline>) {
    companion object {
        val NONE = SequentialClearance(false, "", emptyList())

        fun parse(text: String): SequentialClearance {
            val json = JSONObject(text)
            val outlines = json.optJSONArray("outlines")
            return SequentialClearance(
                json.optBoolean("by_object"),
                json.optString("message"),
                List(outlines?.length() ?: 0) { i ->
                    val o = outlines!!.getJSONObject(i)
                    val points = o.getJSONArray("points")
                    ClearanceOutline(
                        o.getLong("object"),
                        o.getLong("instance"),
                        o.optBoolean("colliding"),
                        FloatArray(points.length()) { points.getDouble(it).toFloat() },
                    )
                },
            )
        }
    }
}
