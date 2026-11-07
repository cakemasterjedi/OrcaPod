package org.orcapod.app.printhost

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.Socket

/**
 * The slice of MQTT 3.1.1 a Bambu printer's local broker needs: connect with
 * a login, subscribe and publish at QoS 0, keep-alive pings. Small enough to
 * not pull a client library into the app for one printer brand.
 */
class MqttClient(private val socket: Socket, private val keepAliveSeconds: Int = 30) : Closeable {
    private val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
    private val output: OutputStream = socket.getOutputStream()
    private var nextPacketId = 1
    @Volatile var isOpen = false
        private set

    /** Sends CONNECT and waits for CONNACK; throws on a refused login. */
    fun connect(clientId: String, username: String, password: String) {
        val body = ByteArrayOutputStream().apply {
            writeString("MQTT")
            write(4) // protocol level 3.1.1
            write(0x80 or 0x40 or 0x02) // username, password, clean session
            write(keepAliveSeconds shr 8); write(keepAliveSeconds and 0xff)
            writeString(clientId); writeString(username); writeString(password)
        }
        send(0x10, body.toByteArray())
        val (type, payload) = readPacket()
        if (type shr 4 != 2 || payload.size < 2) throw PrintHostException("The printer's MQTT broker answered unexpectedly")
        when (payload[1].toInt()) {
            0 -> isOpen = true
            4, 5 -> throw PrintHostException("The printer refused the access code")
            else -> throw PrintHostException("The printer refused the connection (MQTT code ${payload[1]})")
        }
    }

    fun subscribe(topic: String) {
        val body = ByteArrayOutputStream().apply {
            val id = nextPacketId++
            write(id shr 8); write(id and 0xff)
            writeString(topic)
            write(0) // QoS 0
        }
        send(0x82, body.toByteArray())
    }

    fun publish(topic: String, payload: ByteArray) {
        val body = ByteArrayOutputStream().apply { writeString(topic); write(payload) }
        send(0x30, body.toByteArray())
    }

    /**
     * Reads packets until the connection closes, handing PUBLISH payloads to
     * [onMessage]; pings the broker in between so it keeps the session.
     * Blocks; run it on its own thread.
     */
    fun run(onMessage: (topic: String, payload: ByteArray) -> Unit) {
        val pinger = Thread {
            try {
                while (isOpen) {
                    Thread.sleep(keepAliveSeconds * 500L)
                    if (isOpen) send(0xC0, ByteArray(0))
                }
            } catch (_: InterruptedException) {
            } catch (_: IOException) {
            }
        }.apply { isDaemon = true; name = "mqtt-ping"; start() }
        try {
            while (isOpen) {
                val (header, body) = readPacket()
                if (header shr 4 == 3) {
                    val topicLen = ((body[0].toInt() and 0xff) shl 8) or (body[1].toInt() and 0xff)
                    val topic = String(body, 2, topicLen, Charsets.UTF_8)
                    var offset = 2 + topicLen
                    val qos = (header shr 1) and 3
                    if (qos > 0) {
                        val id = body.copyOfRange(offset, offset + 2)
                        offset += 2
                        if (qos == 1) send(0x40, id) // PUBACK
                    }
                    onMessage(topic, body.copyOfRange(offset, body.size))
                }
            }
        } finally {
            isOpen = false
            pinger.interrupt()
        }
    }

    override fun close() {
        if (isOpen) runCatching { send(0xE0, ByteArray(0)) }
        isOpen = false
        runCatching { socket.close() }
    }

    @Synchronized
    private fun send(header: Int, body: ByteArray) {
        val packet = ByteArrayOutputStream(body.size + 5)
        packet.write(header)
        var len = body.size
        do {
            var b = len % 128
            len /= 128
            if (len > 0) b = b or 0x80
            packet.write(b)
        } while (len > 0)
        packet.write(body)
        output.write(packet.toByteArray())
        output.flush()
    }

    private fun readPacket(): Pair<Int, ByteArray> {
        val header = input.read()
        if (header < 0) throw EOFException("The printer closed the connection")
        var len = 0
        var mult = 1
        while (true) {
            val b = input.readUnsignedByte()
            len += (b and 0x7f) * mult
            if (b and 0x80 == 0) break
            mult *= 128
            if (mult > 128 * 128 * 128) throw IOException("Malformed MQTT packet")
        }
        val body = ByteArray(len)
        input.readFully(body)
        return header to body
    }

    private fun ByteArrayOutputStream.writeString(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        write(b.size shr 8); write(b.size and 0xff); write(b)
    }
}
