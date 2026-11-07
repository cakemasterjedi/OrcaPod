package org.orcapod.app.data

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.orcapod.app.MainActivity
import org.orcapod.app.R
import org.orcapod.app.printhost.QueueEvent
import org.orcapod.app.printhost.QueueItem
import org.orcapod.app.printhost.QueueState

/**
 * The queue's notifications: one ongoing summary while the queue works (the
 * foreground service's), and an alert when a print ends, fails, or waits for
 * its bed to be cleared.
 */
class QueueNotifier(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    init {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "Print queue", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Shown while the print queue sends and watches prints" },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Print alerts", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Prints that finished or failed, and beds to clear before the next print" },
        )
    }

    /** The ongoing summary, e.g. "2 printing · 1 uploading · 3 waiting". */
    fun ongoing(items: List<QueueItem>): Notification {
        val printing = items.count { it.state == QueueState.PRINTING }
        val uploading = items.count { it.state == QueueState.UPLOADING }
        val waiting = items.count { it.state == QueueState.WAITING }
        val text = listOfNotNull(
            printing.takeIf { it > 0 }?.let { "$it printing" },
            uploading.takeIf { it > 0 }?.let { "$it uploading" },
            waiting.takeIf { it > 0 }?.let { "$it waiting" },
        ).joinToString(" · ").ifEmpty { "Watching printers" }
        val upload = items.firstOrNull { it.state == QueueState.UPLOADING }
        return NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_queue)
            .setContentTitle("Print queue")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openQueue())
            .apply { if (upload != null) setProgress(100, (upload.progress * 100).toInt(), false) }
            .build()
    }

    @SuppressLint("MissingPermission") // Checked by canNotify; without it the post is dropped.
    fun showOngoing(items: List<QueueItem>) {
        if (PrinterFleet.canNotify(context)) manager.notify(ID_ONGOING, ongoing(items))
    }

    fun cancelOngoing() = manager.cancel(ID_ONGOING)

    @SuppressLint("MissingPermission")
    fun event(e: QueueEvent) {
        if (!PrinterFleet.canNotify(context)) return
        val i = e.item
        val (title, text) = when (e) {
            is QueueEvent.Started -> return // the ongoing summary already says so
            is QueueEvent.Ended -> when (i.state) {
                QueueState.CANCELLED -> "Print stopped on ${i.printerName}" to i.fileName
                else -> "Print finished on ${i.printerName}" to i.fileName
            }
            is QueueEvent.Failed ->
                (if (i.startedAt == null) "Could not send to ${i.printerName}" else "Print failed on ${i.printerName}") to
                    listOfNotNull(i.fileName, i.message).joinToString(": ")
            is QueueEvent.BedClearNeeded ->
                "Clear the bed on ${i.printerName}" to
                    "${e.finished?.let { "${it.fileName} is done. " }.orEmpty()}Next up: ${i.fileName}. Confirm the bed is clear in the print queue to start it."
        }
        manager.notify(
            i.id.hashCode(),
            NotificationCompat.Builder(context, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_stat_queue)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(openQueue())
                .build(),
        )
    }

    private fun openQueue(): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(PrinterFleet.EXTRA_OPEN_QUEUE, true),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val CHANNEL_STATUS = "queue_status"
        const val CHANNEL_ALERTS = "queue_alerts"
        const val ID_ONGOING = 1001
    }
}
