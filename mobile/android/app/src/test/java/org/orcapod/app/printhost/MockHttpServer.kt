package org.orcapod.app.printhost

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * A local HTTP/1.1 server standing in for a printer: records requests and
 * answers from [handler]. Hand-rolled because unit tests compile against
 * android.jar, which has no com.sun.net.httpserver.
 */
class MockHttpServer(private val handler: (Request) -> Response) : Closeable {
    class Request(val method: String, val pathAndQuery: String, val headers: Map<String, String>, val body: ByteArray) {
        val path get() = pathAndQuery.substringBefore('?')
        val query get() = pathAndQuery.substringAfter('?', "")
        val text get() = String(body, Charsets.UTF_8)
        fun header(name: String) = headers[name.lowercase()]
    }

    class Response(val status: Int = 200, val body: String = "{}", val headers: Map<String, String> = emptyMap())

    val requests = mutableListOf<Request>()
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val host: String get() = "127.0.0.1:${server.localPort}"

    init {
        Thread {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (_: IOException) { break }
                Thread { s.use(::serve) }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun serve(s: Socket) {
        val input = DataInputStream(s.getInputStream().buffered())
        val requestLine = readLine(input) ?: return
        val (method, target) = requestLine.split(' ').let { it[0] to it[1] }
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            headers[line.substringBefore(':').trim().lowercase()] = line.substringAfter(':').trim()
        }
        val body = ByteArray(headers["content-length"]?.toInt() ?: 0).also { input.readFully(it) }
        val req = Request(method, target, headers, body)
        synchronized(requests) { requests += req }
        val r = handler(req)
        val bytes = r.body.toByteArray()
        val head = buildString {
            append("HTTP/1.1 ${r.status} X\r\n")
            for ((k, v) in r.headers) append("$k: $v\r\n")
            append("Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n")
        }
        s.getOutputStream().apply { write(head.toByteArray()); write(bytes); flush() }
    }

    private fun readLine(input: DataInputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("UTF-8")
            if (b == '\n'.code) return buf.toString("UTF-8").trimEnd('\r')
            buf.write(b)
        }
    }

    fun find(method: String, path: String) = synchronized(requests) { requests.firstOrNull { it.method == method && it.path == path } }

    override fun close() = server.close()
}
