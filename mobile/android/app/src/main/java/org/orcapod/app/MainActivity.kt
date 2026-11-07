package org.orcapod.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import org.orcapod.app.data.PrinterFleet
import org.orcapod.app.ui.AppViewModel
import org.orcapod.app.ui.OrcaPodApp
import org.orcapod.app.ui.Phase

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()
    private val pending = ArrayList<Uri>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

    // Models opened from a file manager ("Open with") or shared from another app.
    private fun collectShared(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data?.let { pending += it }
            Intent.ACTION_SEND -> stream(intent)?.let { pending += it }
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
