package org.orcapod.app.ui

import android.content.Intent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import org.orcapod.app.data.CrashReport

/** Offers the report of the app's last unexpected close for sharing. */
@Composable
fun CrashReportDialog(report: CrashReport, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val doing = report.doing?.let { "It was ${it.replaceFirstChar { c -> c.lowercaseChar() }}." } ?: ""
    val advice = if (report.outOfMemory) {
        "Android closed it because the phone ran out of memory. Fewer or smaller objects on a plate, or a larger layer height, need less."
    } else {
        "Sharing the report helps find the cause."
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("OrcaPod closed unexpectedly") },
        text = { Text(listOf(doing, advice).filter { it.isNotEmpty() }.joinToString(" ")) },
        confirmButton = {
            TextButton(onClick = {
                val send = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "OrcaPod crash report")
                    .putExtra(Intent.EXTRA_TEXT, report.text)
                context.startActivity(Intent.createChooser(send, "Share crash report"))
                onDismiss()
            }) { Text("Share report") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
