package org.orcapod.app.printhost

import java.io.Closeable
import java.io.File
import java.io.IOException

/**
 * The network protocols a saved printer connection can speak, matching the
 * print host types of OrcaSlicer's physical printer dialog plus Bambu Lab's
 * LAN mode (desktop reaches Bambu printers through Bambu's closed network
 * plugin; the app talks the printer's local MQTT and FTPS directly).
 */
enum class HostType(val label: String) {
    OCTOPRINT("OctoPrint"),
    MOONRAKER("Klipper (Moonraker)"),
    PRUSALINK("PrusaLink"),
    DUET("Duet (RepRapFirmware)"),
    BAMBU_LAN("Bambu Lab (LAN mode)");

    /** The credentials this protocol asks for, so the editor shows only those. */
    val usesApiKey get() = this == OCTOPRINT || this == MOONRAKER || this == PRUSALINK
    val usesLogin get() = this == PRUSALINK
    val usesPassword get() = this == DUET
    val usesSerial get() = this == BAMBU_LAN
}

/** A printer the user saved: where it is and how to log in. */
data class PrinterConnection(
    val id: String,
    val name: String,
    val type: HostType,
    /** Host name or IP, optionally with scheme, port and path for the HTTP hosts. */
    val host: String,
    val apiKey: String = "",
    val username: String = "",
    val password: String = "",
    /** Bambu: the printer's serial number, which names its MQTT topics. */
    val serial: String = "",
    /** Bambu: the LAN access code shown on the printer's screen. */
    val accessCode: String = "",
    /** Accept self-signed HTTPS certificates (desktop's "ignore certificate revocation" analogue). */
    val allowSelfSigned: Boolean = false,
    /** The printer preset this connection was last used with, to preselect it. */
    val printerPreset: String = "",
    /** A camera stream or snapshot URL that overrides what the printer reports. */
    val cameraUrl: String = "",
)

enum class PrintState { IDLE, PREPARING, PRINTING, PAUSED, FINISHED, CANCELLED, ERROR, BUSY, OFFLINE, UNKNOWN }

/** What the printer reports; fields it does not report stay null. */
data class PrinterStatus(
    val state: PrintState,
    /** The printer's own word for the state, shown as is. */
    val stateText: String = "",
    val fileName: String? = null,
    /** 0 to 1. */
    val progress: Double? = null,
    val elapsedSeconds: Long? = null,
    val remainingSeconds: Long? = null,
    val nozzleTemp: Double? = null,
    val nozzleTarget: Double? = null,
    val bedTemp: Double? = null,
    val bedTarget: Double? = null,
    val layer: Int? = null,
    val totalLayers: Int? = null,
    val message: String? = null,
) {
    val isActive get() = state == PrintState.PRINTING || state == PrintState.PAUSED || state == PrintState.PREPARING
}

enum class JobOutcome { UPLOADED, PRINTING, FINISHED, CANCELLED, FAILED, UPLOAD_FAILED }

/** One job in a printer's history, or in the app's own log of what it sent. */
data class JobRecord(
    val fileName: String,
    val outcome: JobOutcome,
    /** Epoch milliseconds. */
    val startedAt: Long,
    val durationSeconds: Long? = null,
    val filamentMm: Double? = null,
    val message: String? = null,
)

/** A failure the user should see as is; [status] is the HTTP status when there was one. */
class PrintHostException(message: String, val status: Int = 0, cause: Throwable? = null) : IOException(message, cause)

/**
 * How to start the print, from desktop's send dialogs. The calibration and
 * AMS switches apply to Bambu printers, [simulate] to Duet (M37).
 */
data class PrintOptions(
    val bedLeveling: Boolean = true,
    val flowCalibration: Boolean = false,
    val vibrationCalibration: Boolean = false,
    val timelapse: Boolean = false,
    val useAms: Boolean = false,
    /** AMS tray for filament 1, counted from 0. */
    val amsTray: Int = 0,
    val simulate: Boolean = false,
)

/** Called with bytes sent and total; return false to cancel the upload. */
typealias UploadProgress = (sent: Long, total: Long) -> Boolean

/**
 * One printer's network API. Every call blocks, so callers run them on an IO
 * thread; a host may keep a connection open between calls (Bambu's MQTT) and
 * releases it in [close].
 */
interface PrintHost : Closeable {
    /** Checks the address and credentials; returns a short description of what answered. */
    fun test(): String

    /**
     * Uploads [file] as [remoteName] and, with [startPrint], starts printing it.
     * Returns the name the file was stored under.
     */
    fun upload(file: File, remoteName: String, startPrint: Boolean, options: PrintOptions, progress: UploadProgress): String

    fun status(): PrinterStatus

    fun pause()
    fun resume()
    fun cancel()

    /** The printer's camera, or null when it reports none. */
    fun camera(): CameraSource? = null

    /** Past jobs as the printer records them, newest first; null when it keeps no history. */
    fun history(limit: Int = 50): List<JobRecord>? = null

    override fun close() {}

    companion object {
        /** The camera to show for [c]: its own URL if set, else what [host] reports. */
        fun cameraFor(c: PrinterConnection, host: PrintHost): CameraSource? {
            val url = c.cameraUrl.trim()
            if (url.isEmpty()) return host.camera()
            val full = HttpCameraFeed.resolve(HttpClient.normalize(c.host), url)
            val snapshot = Regex("snapshot|\\.jpe?g(\\?|$)", RegexOption.IGNORE_CASE).containsMatchIn(url)
            if (full!!.startsWith("rtsp", true)) return CameraSource.Rtsp(full, tls = full.startsWith("rtsps", true))
            return CameraSource.Frames(HttpCameraFeed(if (snapshot) CameraUrls(null, full) else CameraUrls(full, null), c.allowSelfSigned))
        }

        fun create(c: PrinterConnection): PrintHost = when (c.type) {
            HostType.OCTOPRINT -> OctoPrintHost(c)
            HostType.MOONRAKER -> MoonrakerHost(c)
            HostType.PRUSALINK -> PrusaLinkHost(c)
            HostType.DUET -> DuetHost(c)
            HostType.BAMBU_LAN -> BambuLanHost(c)
        }

        /** A file name every host accepts: no path separators or characters URLs and FAT storage choke on. */
        fun safeFileName(name: String): String {
            val cleaned = name.replace(Regex("[\\\\/:*?\"<>|#%&{}\\s]+"), "_").trim('_', '.')
            return cleaned.ifEmpty { "print.gcode" }
        }
    }
}
