package org.orcapod.app.printhost

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.net.ssl.SSLSocket

/**
 * A Bambu Lab printer in LAN mode: status and commands over the printer's
 * MQTT broker (TLS, port 8883), files over implicit FTPS (port 990), both
 * logged in as "bblp" with the access code from the printer's screen.
 *
 * Desktop sends Bambu jobs through Bambu's closed network plugin; this talks
 * the same local protocol directly. Like desktop's "Send to printer", the
 * G-code goes out wrapped in a .gcode.3mf and starts with "project_file",
 * which current firmware accepts for LAN prints.
 */
class BambuLanHost(
    private val c: PrinterConnection,
    private val mqttPort: Int = 8883,
    private val ftpPort: Int = 990,
) : PrintHost {
    private val host = c.host.trim().substringAfter("://").substringBefore('/').substringBefore(':')
    private val lock = Object()
    private var mqtt: MqttClient? = null
    private val report = JSONObject()
    private var reports = 0
    private var commandFailure: String? = null
    private var sequence = 0

    override fun test(): String {
        if (c.serial.isBlank()) throw PrintHostException("Enter the printer's serial number")
        connect()
        if (!awaitReport(8000)) {
            throw PrintHostException("Logged in, but the printer sent no status; check the serial number")
        }
        return "Bambu Lab printer · ${c.serial}"
    }

    override fun upload(file: File, remoteName: String, startPrint: Boolean, options: PrintOptions, progress: UploadProgress): String {
        val name = remoteName.removeSuffix(".gcode").removeSuffix(".3mf").removeSuffix(".gcode") + ".gcode.3mf"
        val pkg = File(file.parentFile, name)
        try {
            packageGcode(file, pkg)
            FtpsClient(host, ftpPort, USER, c.accessCode).upload(pkg, "/$name", progress)
        } finally {
            pkg.delete()
        }
        if (startPrint) {
            connect()
            synchronized(lock) { commandFailure = null }
            publish(projectFile(name, options))
            // The printer answers a refused job on the report topic within a moment.
            val deadline = System.currentTimeMillis() + 4000
            synchronized(lock) {
                while (commandFailure == null && System.currentTimeMillis() < deadline) lock.wait(250)
                commandFailure?.let { throw PrintHostException("The printer refused the job: $it") }
            }
        }
        return name
    }

    override fun status(): PrinterStatus {
        connect()
        if (!awaitReport(5000)) return PrinterStatus(PrintState.UNKNOWN, "Waiting for the printer")
        return synchronized(lock) { parse(JSONObject(report.toString())) }
    }

    override fun pause() = command("pause")
    override fun resume() = command("resume")
    override fun cancel() = command("stop")

    override fun close() {
        synchronized(lock) {
            mqtt?.close()
            mqtt = null
        }
    }

    private fun command(name: String) {
        connect()
        publish(JSONObject().put("print", JSONObject().put("sequence_id", seq()).put("command", name).put("param", "")))
    }

    private fun connect() {
        synchronized(lock) {
            if (mqtt?.isOpen == true) return
            if (c.serial.isBlank()) throw PrintHostException("Enter the printer's serial number")
            if (c.accessCode.isBlank()) throw PrintHostException("Enter the printer's access code")
            val socket = Tls.trustAll.createSocket() as SSLSocket
            try {
                socket.connect(InetSocketAddress(host, mqttPort), 8000)
                socket.startHandshake()
            } catch (e: java.io.IOException) {
                socket.close()
                throw PrintHostException("Cannot reach the printer at $host; check the IP address and that LAN mode is on", 0, e)
            }
            val client = MqttClient(socket)
            try {
                client.connect("orcapod-" + UUID.randomUUID().toString().take(8), USER, c.accessCode)
                client.subscribe("device/${c.serial}/report")
            } catch (e: Exception) {
                client.close()
                throw if (e is PrintHostException) e else PrintHostException("The printer dropped the connection: ${e.message}", 0, e)
            }
            mqtt = client
            reports = 0
            Thread({
                runCatching { client.run { _, payload -> onReport(payload) } }
                synchronized(lock) { if (mqtt === client) mqtt = null; lock.notifyAll() }
            }, "bambu-mqtt").apply { isDaemon = true }.start()
        }
        // P1 and A1 printers only send changes; ask for everything once.
        publish(JSONObject().put("pushing", JSONObject().put("sequence_id", seq()).put("command", "pushall")))
    }

    private fun onReport(payload: ByteArray) {
        val msg = runCatching { JSONObject(String(payload, Charsets.UTF_8)) }.getOrNull() ?: return
        val print = msg.optJSONObject("print") ?: return
        synchronized(lock) {
            if (print.optString("command") == "project_file" && print.optString("result").equals("fail", true)) {
                commandFailure = print.optString("reason").ifEmpty { "unknown reason" }
            }
            for (key in print.keys()) report.put(key, print.get(key))
            reports++
            lock.notifyAll()
        }
    }

    private fun awaitReport(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            while (reports == 0 && mqtt != null && System.currentTimeMillis() < deadline) lock.wait(200)
            return reports > 0
        }
    }

    private fun publish(json: JSONObject) {
        val client = synchronized(lock) { mqtt } ?: throw PrintHostException("Lost the connection to the printer")
        try {
            client.publish("device/${c.serial}/request", json.toString().toByteArray())
        } catch (e: java.io.IOException) {
            throw PrintHostException("Lost the connection to the printer", 0, e)
        }
    }

    private fun seq() = synchronized(lock) { (++sequence).toString() }

    private fun projectFile(name: String, o: PrintOptions) = JSONObject().put(
        "print",
        JSONObject()
            .put("sequence_id", seq())
            .put("command", "project_file")
            .put("param", "Metadata/plate_1.gcode")
            .put("project_id", "0").put("profile_id", "0").put("task_id", "0").put("subtask_id", "0")
            .put("subtask_name", name.removeSuffix(".gcode.3mf"))
            .put("url", "ftp:///$name")
            .put("md5", "")
            .put("timelapse", o.timelapse)
            .put("bed_type", "auto")
            .put("bed_levelling", o.bedLeveling)
            .put("flow_cali", o.flowCalibration)
            .put("vibration_cali", o.vibrationCalibration)
            .put("layer_inspect", false)
            .put("use_ams", o.useAms)
            .put("ams_mapping", if (o.useAms) JSONArray().put(o.amsTray) else JSONArray()),
    )

    companion object {
        const val USER = "bblp"

        /** Wraps plain G-code in the smallest .gcode.3mf the printer accepts: plate 1 and its MD5. */
        fun packageGcode(gcode: File, out: File) {
            val md5 = MessageDigest.getInstance("MD5")
            gcode.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md5.update(buf, 0, n)
                }
            }
            val digest = md5.digest().joinToString("") { "%02X".format(it) }
            ZipOutputStream(out.outputStream().buffered()).use { zip ->
                fun text(name: String, content: String) {
                    zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray()); zip.closeEntry()
                }
                text("[Content_Types].xml", CONTENT_TYPES)
                text("_rels/.rels", RELS)
                text("3D/3dmodel.model", MODEL)
                text("Metadata/slice_info.config", SLICE_INFO)
                zip.putNextEntry(ZipEntry("Metadata/plate_1.gcode"))
                gcode.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                text("Metadata/plate_1.gcode.md5", digest)
            }
        }

        fun parse(p: JSONObject): PrinterStatus {
            val text = p.optString("gcode_state", "UNKNOWN")
            val state = when (text.uppercase()) {
                "IDLE" -> PrintState.IDLE
                "PREPARE", "SLICING" -> PrintState.PREPARING
                "RUNNING" -> PrintState.PRINTING
                "PAUSE" -> PrintState.PAUSED
                "FINISH" -> PrintState.FINISHED
                "FAILED" -> PrintState.ERROR
                else -> PrintState.UNKNOWN
            }
            val error = p.optLong("print_error", 0)
            val active = state == PrintState.PRINTING || state == PrintState.PAUSED || state == PrintState.PREPARING
            return PrinterStatus(
                state = state,
                stateText = text.lowercase().replaceFirstChar { it.uppercase() },
                fileName = (p.optStringOrNull("subtask_name") ?: p.optStringOrNull("gcode_file"))?.takeIf { active || state == PrintState.FINISHED },
                progress = p.optDoubleOrNull("mc_percent")?.div(100.0),
                remainingSeconds = p.optDoubleOrNull("mc_remaining_time")?.times(60)?.toLong()?.takeIf { active },
                nozzleTemp = p.optDoubleOrNull("nozzle_temper"),
                nozzleTarget = p.optDoubleOrNull("nozzle_target_temper"),
                bedTemp = p.optDoubleOrNull("bed_temper"),
                bedTarget = p.optDoubleOrNull("bed_target_temper"),
                layer = p.optIntOrNull("layer_num")?.takeIf { active },
                totalLayers = p.optIntOrNull("total_layer_num")?.takeIf { it > 0 && active },
                message = if (error != 0L) "Printer error %08X; see the printer's screen".format(error) else null,
            )
        }

        private const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
 <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
 <Default Extension="model" ContentType="application/vnd.ms-package.3dmanufacturing-3dmodel+xml"/>
 <Default Extension="gcode" ContentType="text/x.gcode"/>
</Types>
"""
        private const val RELS = """<?xml version="1.0" encoding="UTF-8"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
 <Relationship Target="/3D/3dmodel.model" Id="rel-1" Type="http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel"/>
</Relationships>
"""
        private const val MODEL = """<?xml version="1.0" encoding="UTF-8"?>
<model unit="millimeter" xml:lang="en-US" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02">
 <metadata name="Application">OrcaPod</metadata>
 <resources/>
 <build/>
</model>
"""
        private const val SLICE_INFO = """<?xml version="1.0" encoding="UTF-8"?>
<config>
  <plate>
    <metadata key="index" value="1"/>
  </plate>
</config>
"""
    }
}
