package org.orcapod.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.orcapod.app.printhost.FleetPrinter
import org.orcapod.app.printhost.PrintState
import org.orcapod.app.printhost.PrinterConnection
import org.orcapod.app.printhost.QueueItem
import org.orcapod.app.printhost.QueueState
import kotlin.math.roundToInt

/**
 * The print farm dashboard: every saved printer with what it is doing and its
 * line of queued plates. Cards flow into as many columns as the screen fits,
 * one on a phone, several on a tablet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrintQueueSheet(vm: PrinterViewModel, onDismiss: () -> Unit) {
    DisposableEffect(Unit) {
        vm.fleet.watch()
        onDispose { vm.fleet.unwatch() }
    }
    val items = vm.queue
    val orphans = items.filter { i -> vm.connections.none { it.id == i.connectionId } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(320.dp),
            modifier = Modifier.fillMaxHeight(0.92f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { QueueHeader(vm) }
            if (vm.connections.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text("Add printers under Printers in the menu, then send plates to one or several of them from the sliced preview.")
                }
            }
            items(vm.connections.sortedWith(compareBy({ rank(vm, it) }, { it.name.lowercase() })), key = { it.id }) { c ->
                PrinterCard(vm, c, vm.fleetStatus[c.id], items.filter { it.connectionId == c.id })
            }
            if (orphans.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        Text("Removed printers", style = MaterialTheme.typography.titleSmall)
                        for (i in orphans) QueueRow(vm, i, isNext = false)
                    }
                }
            }
        }
    }
}

// Printers that need someone first, then those printing, then the rest.
private fun rank(vm: PrinterViewModel, c: PrinterConnection): Int {
    val p = vm.fleetStatus[c.id]
    return when {
        p?.needsBedClear == true || p?.status?.state == PrintState.ERROR -> 0
        p?.status?.isActive == true -> 1
        vm.queue.any { it.connectionId == c.id && it.state.isOpen } -> 2
        else -> 3
    }
}

@Composable
private fun QueueHeader(vm: PrinterViewModel) {
    val items = vm.queue
    val printing = vm.fleetStatus.values.count { it.status?.isActive == true }
    val waiting = items.count { it.state == QueueState.WAITING }
    val attention = vm.fleetStatus.values.count { it.needsBedClear || it.status?.state == PrintState.ERROR }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Print queue", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (items.any { !it.state.isOpen }) TextButton(onClick = vm::clearEndedQueue) { Text("Clear finished") }
        }
        Text(
            listOf(
                "$printing of ${vm.connections.size} printing",
                "$waiting waiting",
            ).plus(listOfNotNull(attention.takeIf { it > 0 }?.let { "$it need you" })).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun PrinterCard(vm: PrinterViewModel, c: PrinterConnection, p: FleetPrinter?, items: List<QueueItem>) {
    var cameraOpen by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    var skipOpen by remember { mutableStateOf(false) }
    val s = p?.status
    val nextStart = items.firstOrNull { it.state == QueueState.WAITING }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(c.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(c.type.label, style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { cameraOpen = true }) { Icon(AppIcons.Camera, "Camera") }
            }
            Spacer(Modifier.height(6.dp))
            when {
                s == null && p?.error == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Connecting…", style = MaterialTheme.typography.bodySmall)
                }
                s == null -> Text(p?.error.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                else -> StatusDetails(s, p.error)
            }
            if (p?.needsBedClear == true && nextStart != null) {
                Spacer(Modifier.height(8.dp))
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Column(Modifier.padding(10.dp)) {
                        Text("Take the finished print off the bed", style = MaterialTheme.typography.titleSmall)
                        Text("Next up: ${nextStart.fileName}", style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Button(onClick = { vm.confirmBedClear(c.id) }) {
                                Icon(Icons.Filled.Check, null); Spacer(Modifier.width(4.dp)); Text("Bed is clear, start")
                            }
                        }
                    }
                }
            }
            if (s != null && s.isActive) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    if (s.state == PrintState.PAUSED) {
                        FilledTonalButton(onClick = { vm.resume(c.id) }) { Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text("Resume") }
                    } else {
                        FilledTonalButton(onClick = { vm.pause(c.id) }) { Icon(AppIcons.Pause, null); Spacer(Modifier.width(4.dp)); Text("Pause") }
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { confirmStop = true }) { Icon(AppIcons.Stop, null); Spacer(Modifier.width(4.dp)); Text("Stop") }
                }
                if (c.type.canSkipObjects) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { skipOpen = true }) { Text("Skip objects…") }
                    }
                }
            }
            // Open items, then the last few that ended.
            val shown = items.filter { it.state.isOpen } + items.filter { !it.state.isOpen }.sortedByDescending { it.endedAt ?: 0 }.take(3)
            if (shown.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                val firstWaiting = items.firstOrNull { it.state == QueueState.WAITING }?.id
                for (i in shown) QueueRow(vm, i, isNext = i.id == firstWaiting)
            }
        }
    }
    if (cameraOpen) CameraDialog(vm, c.id, c.name) { cameraOpen = false }
    if (skipOpen) SkipObjectsSheet(vm, c.id) { skipOpen = false }
    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("Stop the print on ${c.name}?") },
            text = { Text("The print is cancelled and cannot be resumed.") },
            confirmButton = { TextButton(onClick = { vm.cancelPrint(c.id); confirmStop = false }) { Text("Stop print") } },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("Keep printing") } },
        )
    }
}

@Composable
private fun QueueRow(vm: PrinterViewModel, i: QueueItem, isNext: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(i.fileName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                queueStateText(i), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (i.state == QueueState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (i.state == QueueState.UPLOADING) LinearProgressIndicator(progress = { i.progress }, modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
        }
        when (i.state) {
            QueueState.WAITING -> {
                if (!isNext) IconButton(onClick = { vm.moveQueuedUp(i.id) }) { Icon(Icons.Filled.KeyboardArrowUp, "Move up") }
                IconButton(onClick = { vm.cancelQueued(i.id) }) { Icon(Icons.Filled.Close, "Remove from queue") }
            }
            QueueState.UPLOADING -> IconButton(onClick = { vm.cancelQueued(i.id) }) { Icon(Icons.Filled.Close, "Cancel upload") }
            QueueState.PRINTING -> {}
            else -> {
                if (vm.connections.any { it.id == i.connectionId }) {
                    IconButton(onClick = { vm.retryQueued(i.id) }) {
                        Icon(Icons.Filled.Refresh, if (i.state == QueueState.FAILED) "Try again" else "Queue again")
                    }
                }
                IconButton(onClick = { vm.removeQueued(i.id) }) { Icon(Icons.Filled.Delete, "Remove") }
            }
        }
    }
}

/** One line on where a queued plate is. */
internal fun queueStateText(i: QueueItem): String = when (i.state) {
    QueueState.WAITING -> if (i.start) "Waiting for the printer" else "Waiting to upload"
    QueueState.UPLOADING -> "Uploading… ${(i.progress * 100).roundToInt()}%"
    QueueState.PRINTING -> if (i.seenActive) "Printing ${(i.progress * 100).roundToInt()}%" else "Sent, starting…"
    QueueState.UPLOADED -> "Uploaded, not started"
    QueueState.FINISHED -> listOfNotNull("Finished", i.durationText(), i.message).joinToString(" · ")
    QueueState.CANCELLED -> "Cancelled"
    QueueState.FAILED -> i.message ?: "Failed"
}

private fun QueueItem.durationText(): String? {
    val a = startedAt ?: return null
    val b = endedAt ?: return null
    return "in ${hm((b - a) / 1000)}"
}
