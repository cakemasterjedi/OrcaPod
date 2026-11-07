package org.orcapod.app.library

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** A model ("thing") listed by Thingiverse. */
data class Thing(
    val id: Long,
    val name: String,
    val creator: String,
    val thumbnail: String?,
    val pageUrl: String,
    val likes: Int,
)

/** One downloadable file of a thing. */
data class ThingFile(val id: Long, val name: String, val size: Long, val downloadUrl: String) {
    val isModel get() = ModelFiles.isModel(name)
}

/**
 * Thingiverse's official REST API (https://www.thingiverse.com/developers).
 * Every call needs an app token, which the user creates for free at
 * thingiverse.com/apps/create; OrcaPod does not ship one.
 */
class Thingiverse(private val token: String) {
    private val auth get() = mapOf("Authorization" to "Bearer $token", "Accept" to "application/json")

    /** A page of things matching [query], or the popular things when it is blank. */
    fun search(query: String, page: Int, perPage: Int = 30): List<Thing> {
        val q = query.trim()
        val url = if (q.isEmpty()) {
            "$API/search/?type=things&sort=popular&page=$page&per_page=$perPage"
        } else {
            "$API/search/${URLEncoder.encode(q, "UTF-8").replace("+", "%20")}?type=things&sort=relevant&page=$page&per_page=$perPage"
        }
        return parseThings(Http.getText(url, auth))
    }

    fun files(thingId: Long): List<ThingFile> = parseFiles(Http.getText("$API/things/$thingId/files", auth))

    /** Headers to send with [ThingFile.downloadUrl]. */
    fun downloadHeaders(): Map<String, String> = mapOf("Authorization" to "Bearer $token")

    companion object {
        const val API = "https://api.thingiverse.com"
        const val CREATE_APP_URL = "https://www.thingiverse.com/apps/create"

        /** Parses a search result: `{"total":…, "hits":[…]}`, or a bare array from older endpoints. */
        fun parseThings(body: String): List<Thing> {
            val trimmed = body.trimStart()
            val hits = if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed).optJSONArray("hits") ?: JSONArray()
            return (0 until hits.length()).mapNotNull { i ->
                val o = hits.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optLong("id", -1)
                if (id < 0) return@mapNotNull null
                Thing(
                    id = id,
                    name = o.optString("name").ifEmpty { "Thing $id" },
                    creator = o.optJSONObject("creator")?.let { c -> c.optString("name").ifEmpty { c.optString("first_name") } } ?: "",
                    thumbnail = o.optString("preview_image").ifEmpty { o.optString("thumbnail") }.ifEmpty { null },
                    pageUrl = o.optString("public_url").ifEmpty { "https://www.thingiverse.com/thing:$id" },
                    likes = o.optInt("like_count"),
                )
            }
        }

        fun parseFiles(body: String): List<ThingFile> {
            val arr = JSONArray(body)
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optLong("id", -1)
                if (id < 0) return@mapNotNull null
                ThingFile(
                    id = id,
                    name = o.optString("name").ifEmpty { "file-$id" },
                    size = o.optLong("size"),
                    // direct_url points at the CDN copy; the API download redirects there.
                    downloadUrl = o.optString("direct_url").ifEmpty { o.optString("download_url") }
                        .ifEmpty { "$API/files/$id/download" },
                )
            }
        }
    }
}
