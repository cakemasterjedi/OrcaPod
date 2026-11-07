package org.orcapod.app.printhost

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * Upload-only FTP over implicit TLS, which Bambu printers serve on port 990
 * for their SD card. The printer requires the data connection to resume the
 * control connection's TLS session. Client session caches (JSSE and Android's
 * Conscrypt alike) look sessions up by the peer's host and port, and a
 * layered TLS socket takes its port from the socket beneath it, so the data
 * connection's plain socket reports the control port to find that session.
 */
class FtpsClient(
    private val host: String,
    private val port: Int,
    private val user: String,
    private val password: String,
    private val tls: SSLContext = Tls.trustAllContext,
) {
    private lateinit var control: SSLSocket
    private lateinit var reader: BufferedReader
    private lateinit var writer: OutputStream

    fun upload(file: File, remotePath: String, progress: UploadProgress) {
        control = tls.socketFactory.createSocket() as SSLSocket
        try {
            control.connect(InetSocketAddress(host, port), 8000)
            control.soTimeout = 30_000
            control.startHandshake()
            reader = BufferedReader(InputStreamReader(control.inputStream, Charsets.UTF_8))
            writer = control.outputStream
            expect(reply(), 220)
            expect(command("USER $user"), 331, 230).let { if (it.first == 331) expect(command("PASS $password"), 230) }
            expect(command("PBSZ 0"), 200)
            expect(command("PROT P"), 200)
            expect(command("TYPE I"), 200)
            val pasv = expect(command("PASV"), 227).second
            val nums = Regex("(\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+)").find(pasv)?.groupValues?.drop(1)?.map { it.toInt() }
                ?: throw PrintHostException("Bad passive mode reply from the printer: $pasv")
            val dataPort = nums[4] * 256 + nums[5]
            // The printer's own address, not the one in the reply, which may be unroutable behind NAT.
            val plain = object : Socket() {
                override fun getPort(): Int = this@FtpsClient.port
            }
            plain.connect(InetSocketAddress(control.inetAddress, dataPort), 8000)
            expect(command("STOR $remotePath"), 150, 125)
            val data = tls.socketFactory.createSocket(plain, host, port, true) as SSLSocket
            data.useClientMode = true
            data.use { s ->
                s.startHandshake()
                val out = s.outputStream
                Body.copyWithProgress(file, out, 0, file.length(), progress)
                out.flush()
            }
            expect(reply(), 226, 250)
            runCatching { command("QUIT") }
        } catch (e: PrintHostException) {
            throw e
        } catch (e: UploadCancelled) {
            throw e
        } catch (e: java.io.IOException) {
            throw PrintHostException("Uploading to the printer's storage failed: ${e.message}", 0, e)
        } finally {
            runCatching { control.close() }
        }
    }

    private fun command(line: String): Pair<Int, String> {
        writer.write("$line\r\n".toByteArray(Charsets.UTF_8))
        writer.flush()
        return reply()
    }

    /** Reads one reply, following multi-line "123-" continuations to the closing "123 " line. */
    private fun reply(): Pair<Int, String> {
        var line = reader.readLine() ?: throw PrintHostException("The printer closed the FTP connection")
        val code = line.take(3).toIntOrNull() ?: throw PrintHostException("Bad FTP reply: $line")
        val text = StringBuilder(line.drop(4))
        if (line.getOrNull(3) == '-') {
            while (true) {
                line = reader.readLine() ?: break
                text.append('\n').append(line)
                if (line.startsWith("$code ")) break
            }
        }
        return code to text.toString()
    }

    private fun expect(r: Pair<Int, String>, vararg codes: Int): Pair<Int, String> {
        if (r.first in codes) return r
        throw PrintHostException(
            when (r.first) {
                530 -> "The printer refused the access code"
                550, 553 -> "The printer could not store the file (${r.second.trim()}); check its SD card"
                else -> "The printer's FTP server answered ${r.first} ${r.second.trim()}"
            },
        )
    }
}
