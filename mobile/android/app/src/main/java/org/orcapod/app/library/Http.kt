package org.orcapod.app.library

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Thrown for an HTTP error status, so callers can tell a bad token (401) from other failures. */
class HttpException(val status: Int, message: String) : IOException(message)

/** Blocking HTTP helpers for the model library; call them off the main thread. */
object Http {
    private const val MAX_DOWNLOAD_BYTES = 1L shl 30
    private const val MAX_REDIRECTS = 8

    /** GETs [url] and returns the body as text. */
    fun getText(url: String, headers: Map<String, String> = emptyMap()): String =
        open(url, headers).let { (conn, _) ->
            try {
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }

    /**
     * Downloads [url] into [dir], named by the server's Content-Disposition or
     * the URL, and returns the file. [onProgress] gets bytes read and the total
     * (-1 when unknown).
     */
    fun download(
        url: String,
        dir: File,
        headers: Map<String, String> = emptyMap(),
        nameHint: String? = null,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): File {
        val (conn, finalUrl) = open(url, headers)
        try {
            val name = nameHint?.let { ModelFiles.safeName(it) }
                ?: ModelFiles.nameFor(conn.getHeaderField("Content-Disposition"), finalUrl)
            val total = conn.contentLengthLong
            if (total > MAX_DOWNLOAD_BYTES) throw IOException("$name is larger than 1 GB")
            dir.mkdirs()
            val file = File(dir, name)
            var read = 0L
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        read += n
                        if (read > MAX_DOWNLOAD_BYTES) throw IOException("$name is larger than 1 GB")
                        out.write(buf, 0, n)
                        onProgress(read, total)
                    }
                }
            }
            return file
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Opens [url], following redirects by hand so that credentials meant for
     * one host (an API token, site cookies) are not sent on to another, such
     * as the CDN a download redirects to.
     */
    private fun open(url: String, headers: Map<String, String>): Pair<HttpURLConnection, String> {
        var current = URL(url)
        val origin = current.host
        repeat(MAX_REDIRECTS) {
            if (current.protocol != "https" && current.protocol != "http") throw IOException("Unsupported link: $current")
            val conn = current.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            val sameHost = current.host.equals(origin, ignoreCase = true)
            for ((k, v) in headers) {
                if (!sameHost && (k.equals("Authorization", true) || k.equals("Cookie", true))) continue
                conn.setRequestProperty(k, v)
            }
            val status = conn.responseCode
            if (status in 300..399) {
                val location = conn.getHeaderField("Location") ?: throw IOException("Redirect without a location")
                conn.disconnect()
                current = URL(current, location)
                return@repeat
            }
            if (status !in 200..299) {
                conn.disconnect()
                throw HttpException(status, "Server answered $status for ${current.host}")
            }
            return conn to current.toString()
        }
        throw IOException("Too many redirects")
    }
}
