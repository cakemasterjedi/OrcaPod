package org.orcapod.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.orcapod.app.BuildConfig
import org.orcapod.app.data.AppUpdate
import org.orcapod.app.data.AppUpdates
import java.io.File

/** What the update dialog shows. */
sealed interface UpdateUi {
    data object Checking : UpdateUi
    data object UpToDate : UpdateUi
    data class Available(val update: AppUpdate) : UpdateUi
    data class Downloading(val update: AppUpdate, val progress: Float?) : UpdateUi
    data class Ready(val update: AppUpdate, val apk: File) : UpdateUi
    data class Failed(val message: String) : UpdateUi
}

/** The app-wide update state, so the side menu can start a check the dialog shows. */
object UpdateController {
    var ui by mutableStateOf<UpdateUi?>(null)
        private set

    /** [manual] checks from the menu: they show "up to date" and failures, and ignore "Later". */
    fun check(context: Context, scope: CoroutineScope, manual: Boolean) {
        if (ui is UpdateUi.Checking || ui is UpdateUi.Downloading) return
        if (!manual && !AppUpdates.autoCheckDue(context)) return
        if (manual) ui = UpdateUi.Checking
        scope.launch {
            val found = runCatching {
                withContext(Dispatchers.IO) { AppUpdates.check(BuildConfig.UPDATE_REPO, BuildConfig.VERSION_CODE) }
            }
            ui = found.fold(
                onSuccess = { update ->
                    when {
                        update != null && (manual || update.versionCode > AppUpdates.skipped(context)) -> UpdateUi.Available(update)
                        manual -> UpdateUi.UpToDate
                        else -> null
                    }
                },
                onFailure = { if (manual) UpdateUi.Failed(it.message ?: "Could not reach GitHub") else null },
            )
        }
    }

    fun download(context: Context, scope: CoroutineScope, update: AppUpdate) {
        ui = UpdateUi.Downloading(update, null)
        scope.launch {
            var shown = -1
            ui = runCatching {
                withContext(Dispatchers.IO) {
                    AppUpdates.download(context, update) { read, total ->
                        // Redraw once per percent, not once per buffer.
                        val percent = if (total > 0) (read * 100 / total).toInt() else -1
                        if (percent != shown) {
                            shown = percent
                            ui = UpdateUi.Downloading(update, if (percent >= 0) percent / 100f else null)
                        }
                    }
                }
            }.fold(
                onSuccess = { UpdateUi.Ready(update, it) },
                onFailure = { UpdateUi.Failed("Download failed: ${it.message}") },
            )
        }
    }

    fun later(context: Context) {
        (ui as? UpdateUi.Available)?.let { AppUpdates.skip(context, it.update.versionCode) }
        ui = null
    }

    fun dismiss() {
        ui = null
    }
}

/** Checks for a newer build on launch and shows the update dialog when there is something to show. */
@Composable
fun UpdateDialog() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { UpdateController.check(context.applicationContext, scope, manual = false) }
    val ui = UpdateController.ui ?: return
    val start = { intent: Intent ->
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
        }
    }
    val current = BuildConfig.VERSION_NAME
    when (ui) {
        UpdateUi.Checking -> AlertDialog(
            onDismissRequest = UpdateController::dismiss,
            title = { Text("Checking for updates") },
            text = { LinearProgressIndicator(Modifier.fillMaxWidth()) },
            confirmButton = {},
        )
        UpdateUi.UpToDate -> AlertDialog(
            onDismissRequest = UpdateController::dismiss,
            title = { Text("OrcaPod is up to date") },
            text = { Text("You have the newest build ($current).") },
            confirmButton = { TextButton(onClick = UpdateController::dismiss) { Text("OK") } },
        )
        is UpdateUi.Available -> AlertDialog(
            onDismissRequest = UpdateController::dismiss,
            title = { Text("Update available") },
            text = { Text("${ui.update.name} is ready to install over this version ($current). Your projects, printers and settings are kept.") },
            confirmButton = {
                TextButton(onClick = { UpdateController.download(context.applicationContext, scope, ui.update) }) { Text("Download") }
            },
            dismissButton = { TextButton(onClick = { UpdateController.later(context) }) { Text("Later") } },
        )
        is UpdateUi.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Downloading ${ui.update.name}") },
            text = {
                val progress = ui.progress
                if (progress != null) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            },
            confirmButton = {},
        )
        is UpdateUi.Ready -> {
            val allowed = AppUpdates.canInstall(context)
            AlertDialog(
                onDismissRequest = UpdateController::dismiss,
                title = { Text("Install ${ui.update.name}") },
                text = {
                    Column {
                        Text("Android asks you to confirm the install. OrcaPod restarts on the new version.")
                        if (!allowed) {
                            Spacer(Modifier.height(8.dp))
                            Text("First allow OrcaPod to install apps (\"Install unknown apps\"), then come back and tap Install.")
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (AppUpdates.canInstall(context)) start(AppUpdates.installIntent(context, ui.apk))
                        else start(AppUpdates.allowInstallsIntent(context))
                    }) { Text(if (allowed) "Install" else "Allow installs") }
                },
                dismissButton = { TextButton(onClick = UpdateController::dismiss) { Text("Not now") } },
            )
        }
        is UpdateUi.Failed -> AlertDialog(
            onDismissRequest = UpdateController::dismiss,
            title = { Text("Could not update") },
            text = { Text(ui.message) },
            confirmButton = {
                TextButton(onClick = {
                    UpdateController.dismiss()
                    start(Intent(Intent.ACTION_VIEW, Uri.parse(AppUpdates.releasesPage(BuildConfig.UPDATE_REPO))))
                }) { Text("Open download page") }
            },
            dismissButton = { TextButton(onClick = UpdateController::dismiss) { Text("Close") } },
        )
    }
}
