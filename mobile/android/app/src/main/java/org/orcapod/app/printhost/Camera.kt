package org.orcapod.app.printhost

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket

/**
 * A printer camera as a stream of JPEG frames. [run] blocks, handing each
 * frame to the callback until it returns false or the stream fails.
 */
interface CameraFeed {
    val description: String
    fun run(onFrame: (jpeg: ByteArray) -> Boolean)
}

/** How to show a printer's camera: decoded JPEG frames, or an RTSP video stream for a video player. */
sealed interface CameraSource {
    data class Frames(val feed: CameraFeed) : CameraSource
    /** [tls] for RTSPS: the player connects over TLS without verifying the printer's certificate. */
    data class Rtsp(val url: String, val tls: Boolean) : CameraSource
}

/** Where a printer's camera is: an MJPEG stream, a snapshot to poll, or both. */
data class CameraUrls(val stream: String?, val snapshot: String?)

/**
 * The webcam URLs OctoPrint and Moonraker publish, which are usually paths on
 * the printer's own host ("/webcam/?action=stream"), served by mjpg-streamer,
 * ustreamer or crowsnest.
 */
class HttpCameraFeed(
    private val urls: CameraUrls,
    private val allowSelfSigned: Boolean = false,
    private val snapshotIntervalMs: Long = 1000,
) : CameraFeed {
    override val description get() = urls.stream ?: urls.snapshot.orEmpty()

    override fun run(onFrame: (ByteArray) -> Boolean) {
        val stream = urls.stream
        if (stream != null) {
            try {
                open(stream).use { input -> if (!readMjpeg(input, onFrame)) return }
                return
            } catch (e: IOException) {
                if (urls.snapshot == null) throw PrintHostException("The camera stream failed: ${e.message}", 0, e)
            }
        }
        val snapshot = urls.snapshot ?: throw PrintHostException("This printer has no camera configured")
        while (true) {
            val frame = try {
                open(snapshot).use { it.readBytes() }
            } catch (e: IOException) {
                throw PrintHostException("The camera snapshot failed: ${e.message}", 0, e)
            }
            if (!onFrame(frame)) return
            Thread.sleep(snapshotIntervalMs)
        }
    }

    private fun open(url: String): InputStream {
        val conn = URL(url).openConnection() as HttpURLConnection
        if (conn is HttpsURLConnection && allowSelfSigned) {
            conn.sslSocketFactory = Tls.trustAll
            conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
        }
        conn.connectTimeout = 8000
        conn.readTimeout = 15_000
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            throw IOException("HTTP $code from $url")
        }
        return conn.inputStream
    }

    companion object {
        /**
         * Splits a multipart/x-mixed-replace MJPEG stream into JPEG frames by
         * their start and end markers, which works whether or not the parts
         * carry a Content-Length. Returns false when [onFrame] asked to stop.
         */
        fun readMjpeg(input: InputStream, onFrame: (ByteArray) -> Boolean): Boolean {
            val src = BufferedInputStream(input, 64 * 1024)
            val frame = ByteArrayOutputStream(256 * 1024)
            var inFrame = false
            var prev = -1
            while (true) {
                val b = src.read()
                if (b < 0) return true
                if (!inFrame) {
                    if (prev == 0xFF && b == 0xD8) {
                        inFrame = true
                        frame.reset()
                        frame.write(0xFF); frame.write(0xD8)
                        prev = -1
                        continue
                    }
                } else {
                    frame.write(b)
                    if (prev == 0xFF && b == 0xD9) {
                        inFrame = false
                        if (!onFrame(frame.toByteArray())) return false
                        prev = -1
                        continue
                    }
                    if (frame.size() > MAX_FRAME) throw IOException("Camera frame too large")
                }
                prev = b
            }
        }

        /**
         * Resolves a camera URL the printer gave relative to its own address,
         * as a browser on its web page would; without [keepPort] a path goes
         * to the host's default port, where Klipper web servers publish webcams.
         */
        fun resolve(baseUrl: String, url: String?, keepPort: Boolean = true): String? {
            val u = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return when {
                "://" in u -> u
                u.startsWith("//") -> baseUrl.substringBefore("//") + u
                u.startsWith("/") -> {
                    val authority = baseUrl.substringAfter("://").substringBefore('/')
                    baseUrl.substringBefore("://") + "://" + (if (keepPort) authority else authority.substringBefore(':')) + u
                }
                else -> "$baseUrl/$u"
            }
        }

        private const val MAX_FRAME = 8 * 1024 * 1024
    }
}

/**
 * The camera of Bambu's P1 and A1 printers in LAN mode: JPEG frames over TLS
 * on port 6000, after an 80-byte login with the access code. Each frame has a
 * 16-byte header whose first four bytes are the JPEG's length, little-endian.
 * (X1 and H2 printers stream RTSPS video instead; see [BambuLanHost.camera].)
 */
class BambuCameraFeed(
    private val host: String,
    private val accessCode: String,
    private val port: Int = 6000,
) : CameraFeed {
    override val description get() = "Bambu camera at $host"

    override fun run(onFrame: (ByteArray) -> Boolean) {
        val socket = Tls.trustAll.createSocket() as SSLSocket
        try {
            try {
                socket.connect(InetSocketAddress(host, port), 8000)
                socket.soTimeout = 15_000
                socket.startHandshake()
            } catch (e: IOException) {
                throw PrintHostException("No camera stream from the printer; check that it has a camera and LAN only mode is on", 0, e)
            }
            socket.outputStream.apply { write(authPacket(BambuLanHost.USER, accessCode)); flush() }
            val input = DataInputStream(BufferedInputStream(socket.inputStream))
            val header = ByteArray(16)
            while (true) {
                try {
                    input.readFully(header)
                } catch (e: EOFException) {
                    throw PrintHostException("The printer closed the camera stream; check the access code", 0, e)
                }
                val size = le32(header, 0)
                if (size <= 0 || size > 8 * 1024 * 1024) throw PrintHostException("Unexpected camera data from the printer")
                val jpeg = ByteArray(size)
                input.readFully(jpeg)
                if (!onFrame(jpeg)) return
            }
        } finally {
            runCatching { socket.close() }
        }
    }

    companion object {
        /** 0x40 length, 0x3000 command, two zero words, then user and access code in 32-byte fields. */
        fun authPacket(user: String, code: String): ByteArray {
            val p = ByteArray(80)
            fun put32(offset: Int, v: Int) { for (i in 0 until 4) p[offset + i] = (v shr (8 * i)).toByte() }
            put32(0, 0x40)
            put32(4, 0x3000)
            user.toByteArray().copyInto(p, 16, 0, minOf(32, user.length))
            code.toByteArray().copyInto(p, 48, 0, minOf(32, code.length))
            return p
        }

        fun le32(b: ByteArray, o: Int) =
            (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)
    }
}
