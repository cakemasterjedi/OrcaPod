package org.orcapod.app.printhost

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** A request body; files are streamed, never read into memory. */
sealed class Body {
    abstract val contentType: String
    abstract val length: Long
    abstract fun writeTo(out: OutputStream, progress: UploadProgress?)

    class Bytes(val bytes: ByteArray, override val contentType: String) : Body() {
        override val length get() = bytes.size.toLong()
        override fun writeTo(out: OutputStream, progress: UploadProgress?) = out.write(bytes)
    }

    class Json(json: JSONObject) : Body() {
        private val bytes = json.toString().toByteArray()
        override val contentType = "application/json"
        override val length get() = bytes.size.toLong()
        override fun writeTo(out: OutputStream, progress: UploadProgress?) = out.write(bytes)
    }

    class FileBody(val file: File, override val contentType: String = "application/octet-stream") : Body() {
        override val length get() = file.length()
        override fun writeTo(out: OutputStream, progress: UploadProgress?) = copyWithProgress(file, out, 0, length, progress)
    }

    /** multipart/form-data with plain fields followed by one file part, like desktop's form_add_file. */
    class Multipart(
        private val fields: List<Pair<String, String>>,
        private val fileField: String,
        private val file: File,
        private val fileName: String,
    ) : Body() {
        private val boundary = "----OrcaPod" + java.lang.Long.toHexString(System.nanoTime())
        override val contentType = "multipart/form-data; boundary=$boundary"
        private val head: ByteArray = buildString {
            for ((k, v) in fields) {
                append("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n")
            }
            append("--$boundary\r\nContent-Disposition: form-data; name=\"$fileField\"; filename=\"${fileName.replace("\"", "")}\"\r\n")
            append("Content-Type: application/octet-stream\r\n\r\n")
        }.toByteArray()
        private val tail = "\r\n--$boundary--\r\n".toByteArray()
        override val length get() = head.size + file.length() + tail.size

        override fun writeTo(out: OutputStream, progress: UploadProgress?) {
            out.write(head)
            copyWithProgress(file, out, head.size.toLong(), length, progress)
            out.write(tail)
        }
    }

    companion object {
        fun copyWithProgress(file: File, out: OutputStream, offset: Long, total: Long, progress: UploadProgress?) {
            val buf = ByteArray(64 * 1024)
            var sent = offset
            file.inputStream().use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    sent += n
                    if (progress != null && !progress(sent, total)) throw UploadCancelled()
                }
            }
        }
    }
}

class UploadCancelled : IOException("Upload cancelled")

class HttpResponse(val status: Int, val body: String) {
    fun json(): JSONObject = try {
        JSONObject(body)
    } catch (e: org.json.JSONException) {
        throw PrintHostException("Unexpected answer from the printer: ${body.take(120)}", status, e)
    }
}

/**
 * The HTTP side of the print hosts: a base URL, the API key header or digest
 * login they use, and errors turned into messages the user can act on.
 */
class HttpClient(
    host: String,
    private val allowSelfSigned: Boolean = false,
    private val headers: Map<String, String> = emptyMap(),
    private val digestUser: String = "",
    private val digestPassword: String = "",
) {
    val baseUrl: String = normalize(host)
    private var digest: DigestChallenge? = null
    private var nonceCount = 0

    fun get(path: String, extra: Map<String, String> = emptyMap(), ok: (Int) -> Boolean = ::isSuccess) =
        request("GET", path, null, extra = extra, ok = ok)
    fun post(path: String, body: Body?, progress: UploadProgress? = null, extra: Map<String, String> = emptyMap(), ok: (Int) -> Boolean = ::isSuccess) =
        request("POST", path, body, progress, extra, ok)
    fun put(path: String, body: Body?, progress: UploadProgress? = null, extra: Map<String, String> = emptyMap(), ok: (Int) -> Boolean = ::isSuccess) =
        request("PUT", path, body, progress, extra, ok)
    fun delete(path: String) = request("DELETE", path, null)

    fun request(
        method: String,
        path: String,
        body: Body?,
        progress: UploadProgress? = null,
        extra: Map<String, String> = emptyMap(),
        ok: (Int) -> Boolean = ::isSuccess,
    ): HttpResponse {
        var response = perform(method, path, body, progress, extra)
        // Digest login (PrusaLink): answer the challenge once, then keep using its nonce.
        if (response.first.status == 401 && digestUser.isNotEmpty()) {
            val challenge = response.second?.let(DigestChallenge::parse)
            if (challenge != null) {
                digest = challenge
                nonceCount = 0
                response = perform(method, path, body, progress, extra)
            }
        }
        val r = response.first
        if (!ok(r.status)) throw PrintHostException(describe(r), r.status)
        return r
    }

    private fun perform(method: String, path: String, body: Body?, progress: UploadProgress?, extra: Map<String, String>): Pair<HttpResponse, String?> {
        val url = URL(baseUrl + path.removePrefix("/").let { "/$it" })
        val conn = try {
            url.openConnection() as HttpURLConnection
        } catch (e: IOException) {
            throw PrintHostException("Cannot reach $baseUrl: ${e.message}", 0, e)
        }
        try {
            if (conn is HttpsURLConnection && allowSelfSigned) {
                conn.sslSocketFactory = Tls.trustAll
                conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            }
            conn.requestMethod = method
            conn.connectTimeout = 8000
            conn.readTimeout = if (body is Body.FileBody || body is Body.Multipart) 180_000 else 20_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Accept", "application/json, */*")
            for ((k, v) in headers) if (v.isNotEmpty()) conn.setRequestProperty(k, v)
            for ((k, v) in extra) conn.setRequestProperty(k, v)
            digest?.let { conn.setRequestProperty("Authorization", it.authorization(method, url.file, digestUser, digestPassword, ++nonceCount)) }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", body.contentType)
                conn.setFixedLengthStreamingMode(body.length)
                conn.outputStream.use { body.writeTo(it, progress) }
            } else if (method == "POST" || method == "PUT") {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(0)
                conn.outputStream.close()
            }
            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            return HttpResponse(status, text) to conn.getHeaderField("WWW-Authenticate")
        } catch (e: UploadCancelled) {
            throw e
        } catch (e: PrintHostException) {
            throw e
        } catch (e: UnknownHostException) {
            throw PrintHostException("Unknown host ${url.host}", 0, e)
        } catch (e: SocketTimeoutException) {
            throw PrintHostException("${url.host} did not answer in time", 0, e)
        } catch (e: javax.net.ssl.SSLException) {
            throw PrintHostException("Secure connection to ${url.host} failed (${e.message}); for a self-signed certificate turn on \"Accept self-signed certificates\"", 0, e)
        } catch (e: IOException) {
            throw PrintHostException("Cannot reach ${url.host}: ${e.message}", 0, e)
        } finally {
            conn.disconnect()
        }
    }

    private fun describe(r: HttpResponse): String {
        val detail = runCatching {
            val j = JSONObject(r.body)
            j.optJSONObject("error")?.optString("message")?.takeIf { it.isNotEmpty() }
                ?: j.optString("error").takeIf { it.isNotEmpty() }
                ?: j.optString("message").takeIf { it.isNotEmpty() }
                ?: j.optString("title").takeIf { it.isNotEmpty() }
        }.getOrNull() ?: r.body.trim().take(160).takeIf { it.isNotEmpty() && !it.startsWith("<") }
        val what = when (r.status) {
            401, 403 -> "The printer refused the login (HTTP ${r.status}); check the API key or password"
            404 -> "Not found on the printer (HTTP 404); check the address and the printer type"
            409 -> "The printer is busy or not ready (HTTP 409)"
            else -> "The printer answered HTTP ${r.status}"
        }
        return if (detail != null) "$what: $detail" else what
    }

    companion object {
        fun isSuccess(code: Int) = code in 200..299

        fun normalize(host: String): String {
            val h = host.trim().trimEnd('/')
            if (h.isEmpty()) throw PrintHostException("Enter the printer's address")
            return if (h.startsWith("http://", true) || h.startsWith("https://", true)) h else "http://$h"
        }

        fun encode(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }
}

/** RFC 7616 digest login with MD5 and qop=auth, which PrusaLink uses. */
class DigestChallenge(private val realm: String, private val nonce: String, private val qop: String?, private val opaque: String?) {
    fun authorization(method: String, uri: String, user: String, password: String, nc: Int): String {
        val ha1 = md5("$user:$realm:$password")
        val ha2 = md5("$method:$uri")
        val ncText = "%08x".format(nc)
        val cnonce = java.lang.Long.toHexString(SecureRandom().nextLong())
        val response = if (qop != null) md5("$ha1:$nonce:$ncText:$cnonce:auth:$ha2") else md5("$ha1:$nonce:$ha2")
        return buildString {
            append("Digest username=\"$user\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\", response=\"$response\"")
            if (qop != null) append(", qop=auth, nc=$ncText, cnonce=\"$cnonce\"")
            if (opaque != null) append(", opaque=\"$opaque\"")
        }
    }

    companion object {
        fun parse(header: String): DigestChallenge? {
            if (!header.trimStart().startsWith("Digest", true)) return null
            val params = Regex("(\\w+)=(\"([^\"]*)\"|[^,\\s]+)").findAll(header.substringAfter(' '))
                .associate { m -> m.groupValues[1].lowercase() to (m.groups[3]?.value ?: m.groupValues[2]) }
            val realm = params["realm"] ?: return null
            val nonce = params["nonce"] ?: return null
            val qop = params["qop"]?.split(',')?.map { it.trim() }?.firstOrNull { it == "auth" }
            return DigestChallenge(realm, nonce, qop, params["opaque"])
        }

        fun md5(s: String): String = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

/** TLS helpers for printers on the local network, which use self-signed certificates. */
object Tls {
    private val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /** One context, so FTPS data connections can resume the control connection's session. */
    val trustAllContext: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom()) }
    }

    val trustAll: SSLSocketFactory get() = trustAllContext.socketFactory
}
