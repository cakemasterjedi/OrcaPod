package org.orcapod.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.orcapod.app.data.PrinterFleet
import org.orcapod.app.data.QueueNotifier

/**
 * Keeps the app alive while the print queue has work: uploads in progress,
 * prints being followed, plates waiting for a printer. Android stops a
 * background app's network within minutes, and a multi-printer queue runs for
 * hours with the phone in a pocket. "Connected device" is the service type
 * for talking to hardware on the local network; it has no daily time limit.
 * A partial wake lock keeps polling going with the screen off, renewed while
 * the queue's items change. The service
 * stops itself once nothing is left.
 */
class QueueService : Service() {
    private var watch: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        running = true
        val fleet = PrinterFleet.get(this)
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, QueueNotifier.ID_ONGOING, fleet.notifier.ongoing(fleet.queue.items.value), type)
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OrcaPod:queue")
                .apply { setReferenceCounted(false) }
        }
        wakeLock?.acquire(WAKE_TIMEOUT_MS)
        if (watch == null) {
            watch = fleet.scope.launch {
                fleet.queue.items.collect { items ->
                    if (items.none { it.state.isOpen }) {
                        stopSelf()
                    } else {
                        // Progress keeps the lock; a queue nobody tends lets the phone sleep after a while.
                        wakeLock?.acquire(WAKE_TIMEOUT_MS)
                        fleet.notifier.showOngoing(items)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        watch?.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        @Volatile private var running = false
        private const val WAKE_TIMEOUT_MS = 2 * 60 * 60 * 1000L

        fun start(context: Context) {
            // Android 12+ refuses a start from the background; the queue then
            // keeps going for as long as the app does, and the next start from
            // the app (a new plate, opening the dashboard) brings the service back.
            if (running) return
            runCatching { ContextCompat.startForegroundService(context, Intent(context, QueueService::class.java)) }
        }
    }
}
