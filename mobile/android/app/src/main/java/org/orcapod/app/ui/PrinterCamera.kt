package org.orcapod.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.orcapod.app.printhost.CameraSource
import org.orcapod.app.printhost.JobOutcome
import org.orcapod.app.printhost.JobRecord
import org.orcapod.app.printhost.Tls
import java.io.IOException
import java.text.DateFormat
import java.util.Date

/** The printer's live camera: JPEG frames drawn as they arrive, or RTSP(S) video in a player. */
@Composable
fun CameraDialog(vm: PrinterViewModel, connectionId: String, title: String, onDismiss: () -> Unit) {
    var source by remember { mutableStateOf<CameraSource?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(connectionId) {
        try {
            source = vm.camera(connectionId)
        } catch (e: IOException) {
            error = e.message ?: "The camera is not available"
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black), contentAlignment = Alignment.Center) {
                when (val s = source) {
                    is CameraSource.Frames -> FrameView(s) { error = it }
                    is CameraSource.Rtsp -> RtspView(s) { error = it }
                    null -> if (error == null) CircularProgressIndicator()
                }
                error?.let {
                    Text(it, color = Color.White, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun FrameView(source: CameraSource.Frames, onError: (String) -> Unit) {
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(source) {
        try {
            withContext(Dispatchers.IO) {
                source.feed.run { jpeg ->
                    val bmp: Bitmap? = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                    if (bmp != null) launch(Dispatchers.Main) { frame = bmp.asImageBitmap() }
                    isActive
                }
            }
        } catch (e: IOException) {
            onError(e.message ?: "The camera stream stopped")
        }
    }
    frame?.let { Image(it, contentDescription = "Printer camera", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
        ?: CircularProgressIndicator()
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun RtspView(source: CameraSource.Rtsp, onError: (String) -> Unit) {
    val context = LocalContext.current
    val player = remember(source) {
        ExoPlayer.Builder(context).build().apply {
            val factory = RtspMediaSource.Factory().setForceUseRtpTcp(true)
            // RTSPS: the printer's certificate is self-signed; the access code is the credential.
            if (source.tls) factory.setSocketFactory(Tls.trustAll)
            setMediaSource(factory.createMediaSource(MediaItem.fromUri(Uri.parse(source.url))))
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    onError("The camera stream failed (${error.errorCodeName}). On X1 and H2 printers, turn on LAN mode liveview in the printer's settings.")
                }
            })
            playWhenReady = true
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx -> PlayerView(ctx).apply { useController = false; this.player = player } },
    )
}

/** What the printer itself recorded (Moonraker) and what this phone sent to it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistorySheet(vm: PrinterViewModel, connectionId: String, title: String, onDismiss: () -> Unit) {
    var printer by remember { mutableStateOf<List<JobRecord>?>(null) }
    var printerError by remember { mutableStateOf<String?>(null) }
    var sent by remember { mutableStateOf<List<JobRecord>?>(null) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(connectionId) {
        sent = vm.sentHistory(connectionId)
        try {
            printer = vm.printerHistory(connectionId)
        } catch (e: IOException) {
            printerError = e.message
        }
        loaded = true
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().height(560.dp).padding(horizontal = 16.dp)) {
            item { Text("$title history", style = MaterialTheme.typography.titleLarge) }
            if (!loaded) item { CircularProgressIndicator(Modifier.padding(16.dp)) }
            printer?.let { jobs ->
                item { SectionTitle("On the printer") }
                if (jobs.isEmpty()) item { Text("No jobs recorded yet.", style = MaterialTheme.typography.bodySmall) }
                items(jobs) { JobRow(it) }
            }
            printerError?.let { item { SectionTitle("On the printer"); Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }
            sent?.let { jobs ->
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionTitle("Sent from this phone", Modifier.weight(1f))
                        if (jobs.isNotEmpty()) TextButton(onClick = { vm.clearSentHistory(connectionId); sent = emptyList() }) { Text("Clear") }
                    }
                }
                if (jobs.isEmpty()) item { Text("Nothing sent to this printer yet.", style = MaterialTheme.typography.bodySmall) }
                items(jobs) { JobRow(it) }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) =
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = modifier.padding(top = 16.dp, bottom = 4.dp))

@Composable
private fun JobRow(job: JobRecord) {
    val outcome = when (job.outcome) {
        JobOutcome.UPLOADED -> "Uploaded"
        JobOutcome.PRINTING -> "Printing"
        JobOutcome.FINISHED -> "Finished"
        JobOutcome.CANCELLED -> "Cancelled"
        JobOutcome.FAILED -> "Failed"
        JobOutcome.UPLOAD_FAILED -> "Upload failed"
    }
    val failed = job.outcome == JobOutcome.FAILED || job.outcome == JobOutcome.UPLOAD_FAILED
    ListItem(
        headlineContent = { Text(job.fileName.substringAfterLast('/')) },
        supportingContent = {
            Column {
                Text(
                    listOfNotNull(
                        job.startedAt.takeIf { it > 0 }?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) },
                        job.durationSeconds?.takeIf { it > 0 }?.let { formatDuration(it) },
                        job.filamentMm?.takeIf { it > 0 }?.let { String.format(java.util.Locale.US, "%.1f m filament", it / 1000) },
                    ).joinToString(" · "),
                )
                job.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        },
        trailingContent = {
            Text(outcome, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        },
    )
    HorizontalDivider()
}

private fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return if (h > 0) "$h h $m min" else "$m min"
}
