package org.orcapod.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import org.orcapod.app.data.CrashReports
import org.orcapod.app.data.PrinterFleet
import org.orcapod.app.ui.AppViewModel
import org.orcapod.app.ui.OrcaPodApp
import org.orcapod.app.ui.Phase
import org.orcapod.app.ui.firstWebLink

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()
    private val pending = ArrayList<Uri>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashReports.install(this)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            collectShared(intent)
            PrinterFleet.handleIntent(this, intent)
        }
        setContent {
            OrcaPodApp(viewModel, pendingImports = pending) { pending.clear() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        collectShared(intent)
        PrinterFleet.handleIntent(this, intent)
        if (viewModel.phase == Phase.Ready && pending.isNotEmpty()) {
            viewModel.importUris(pending.toList())
            pending.clear()
        }
    }

    // A hardware keyboard (DeX, a keyboard cover): Ctrl+Z undoes, Ctrl+Y or
    // Ctrl+Shift+Z redoes, on the plate and while no text field takes the keys.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.isCtrlPressed && viewModel.phase == Phase.Ready &&
            viewModel.library == null && !typing()
        ) {
            val undos = viewModel.scene.snapshot
            when {
                event.keyCode == KeyEvent.KEYCODE_Z && !event.isShiftPressed -> {
                    if (undos.undoCount > 0) viewModel.undo()
                    return true
                }
                event.keyCode == KeyEvent.KEYCODE_Y || (event.keyCode == KeyEvent.KEYCODE_Z && event.isShiftPressed) -> {
                    if (undos.redoCount > 0) viewModel.redo()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun typing(): Boolean = getSystemService(InputMethodManager::class.java)?.isAcceptingText == true

    override fun onStop() {
        super.onStop()
        // The system may kill a background app: keep a copy of unsaved changes.
        viewModel.saveRecovery()
    }

    // Models opened from a file manager ("Open with") or shared from another app.
    private fun collectShared(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data?.let { pending += it }
            Intent.ACTION_SEND -> stream(intent)?.let { pending += it }
                // A model page shared from a browser opens in the model library.
                ?: firstWebLink(intent.getStringExtra(Intent.EXTRA_TEXT))?.let { viewModel.openLibrary(it) }
            Intent.ACTION_SEND_MULTIPLE -> streams(intent)?.let { pending += it }
        }
    }

    @Suppress("DEPRECATION")
    private fun stream(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun streams(intent: Intent): List<Uri>? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
}
