package org.orcapod.app.ui

import android.graphics.Bitmap
import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.orcapod.app.data.RecentProject

/** Desktop's recent projects list: tap to open, the bin forgets one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentProjectsSheet(vm: AppViewModel, onOpen: (RecentProject) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding()) {
            Text("Recent projects", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp))
            if (vm.recentProjects.isEmpty()) {
                Text(
                    "Projects you save or open appear here.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
            LazyColumn {
                items(vm.recentProjects, key = { it.uri }) { project ->
                    var thumb by remember(project) { mutableStateOf<Bitmap?>(null) }
                    LaunchedEffect(project) { thumb = withContext(Dispatchers.IO) { vm.recentThumbnail(project) } }
                    ListItem(
                        modifier = Modifier.clickable { onOpen(project) },
                        leadingContent = {
                            Box(
                                Modifier.size(56.dp).clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center,
                            ) {
                                thumb?.let { Image(it.asImageBitmap(), contentDescription = null, modifier = Modifier.size(56.dp)) }
                            }
                        },
                        headlineContent = { Text(project.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(DateUtils.getRelativeTimeSpanString(project.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString())
                        },
                        trailingContent = {
                            IconButton(onClick = { vm.forgetRecent(project) }) { Icon(Icons.Filled.Delete, "Remove from the list") }
                        },
                    )
                }
            }
        }
    }
}

/** Desktop's "Save changes to the project?" before the plate is replaced. */
@Composable
fun UnsavedChangesDialog(name: String?, onSave: () -> Unit, onDiscard: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (name != null) "Save changes to $name?" else "Save this plate as a project?") },
        text = { Text("Your changes are lost if you don't save them.") },
        confirmButton = { TextButton(onClick = onSave) { Text("Save") } },
        dismissButton = {
            Row {
                TextButton(onClick = onCancel) { Text("Cancel") }
                TextButton(onClick = onDiscard) { Text("Don't save") }
            }
        },
    )
}

/** Offered once at startup when the last session ended with unsaved changes. */
@Composable
fun RecoveryDialog(vm: AppViewModel) {
    val r = vm.recovery ?: return
    val what = r.name ?: "your plate"
    val ago = DateUtils.getRelativeTimeSpanString(r.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Restore unsaved changes?") },
        text = { Text("OrcaPod closed $ago with unsaved changes to $what. Restore them?") },
        confirmButton = { TextButton(onClick = vm::restoreRecovery) { Text("Restore") } },
        dismissButton = { TextButton(onClick = vm::discardRecovery) { Text("Discard") } },
    )
}

/** Desktop's question when a project 3MF is imported: open it as a project, or take only its geometry? */
@Composable
fun ProjectChoiceDialog(onOpen: () -> Unit, onGeometry: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Open as a project?") },
        text = {
            Text(
                "This 3MF is a slicer project. Open it as a project to load its plates and settings in place " +
                    "of the current plate, or import only its objects onto this plate.",
            )
        },
        confirmButton = { TextButton(onClick = onOpen) { Text("Open project") } },
        dismissButton = { TextButton(onClick = onGeometry) { Text("Import objects") } },
    )
}
