package org.orcapod.app.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import org.json.JSONObject
import org.orcapod.app.library.LibraryViewModel
import org.orcapod.app.library.Thing
import org.orcapod.app.library.ThingFile
import org.orcapod.app.library.Thingiverse
import java.io.File

/**
 * The model library: search Thingiverse through its API, or browse model
 * sites in an in-app browser whose downloads land on the plate.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(startUrl: String?, onImport: (List<File>) -> Unit, onClose: () -> Unit) {
    val lib: LibraryViewModel = viewModel()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableStateOf(if (startUrl != null) 1 else 0) }
    var web by remember { mutableStateOf<WebView?>(null) }
    var webTitle by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { lib.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) { lib.ready.collect { onImport(it) } }
    // A link shared while the library is already open.
    LaunchedEffect(startUrl) {
        if (startUrl != null && web != null) {
            tab = 1
            web?.loadUrl(startUrl)
        }
    }

    BackHandler {
        val w = web
        if (tab == 1 && w != null && w.canGoBack()) w.goBack() else onClose()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Model library") },
                    navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to the plate") } },
                    actions = {
                        if (tab == 1) IconButton(onClick = { web?.reload() }) { Icon(Icons.Filled.Refresh, "Reload") }
                    },
                )
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Thingiverse") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Websites") })
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            // The browser stays composed under the Thingiverse tab so its page and history survive tab switches.
            Column(Modifier.fillMaxSize()) {
                SiteChips(webTitle) { url -> web?.loadUrl(url) }
                Box(Modifier.weight(1f)) {
                    ModelBrowser(lib, startUrl ?: LibraryViewModel.SITES.first().url, onCreated = { web = it }, onTitle = { webTitle = it })
                }
            }
            if (tab == 0) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    ThingiverseTab(lib, onOpenPage = { url -> tab = 1; web?.loadUrl(url) })
                }
            }
            lib.download?.let { d ->
                Card(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(12.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Downloading ${d.label}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(6.dp))
                            val p = d.progress
                            if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                            else LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        IconButton(onClick = lib::cancelDownload) { Icon(Icons.Filled.Close, "Cancel download") }
                    }
                }
            }
        }
    }

    lib.openThing?.let { thing -> ThingSheet(thing, lib.thingFiles, lib::downloadThingFiles, lib::closeThing) }
    lib.choice?.let { files -> PickFilesDialog(files, onPick = lib::pick, onDismiss = { lib.pick(emptyList()) }) }
}

@Composable
private fun SiteChips(title: String, onOpen: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (site in LibraryViewModel.SITES) {
                FilterChip(selected = false, onClick = { onOpen(site.url) }, label = { Text(site.name) })
            }
        }
        if (title.isNotEmpty()) {
            Text(
                title,
                Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * A WebView for model sites that have no public API (Printables, MakerWorld,
 * …). The user signs in and downloads as in a browser; each download is
 * fetched again here with the page's cookies, or read from the page when the
 * site builds the file in a blob: URL.
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
private fun ModelBrowser(lib: LibraryViewModel, startUrl: String, onCreated: (WebView) -> Unit, onTitle: (String) -> Unit) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.setSupportMultipleWindows(false)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                // Only the page script injected for a blob download calls this, with a nonce it was given.
                addJavascriptInterface(
                    object {
                        @JavascriptInterface fun deliver(nonce: String, dataUrl: String) = lib.receiveBlob(nonce, dataUrl)
                        @JavascriptInterface fun fail(nonce: String) = lib.blobFailed(nonce)
                    },
                    "OrcaPodDownloads",
                )
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        // Keep web pages in the app; drop app links (intent:, market:) the sites offer.
                        val scheme = request.url.scheme
                        return scheme != "https" && scheme != "http"
                    }

                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) { onTitle(url ?: "") }
                    override fun onPageFinished(view: WebView, url: String?) { onTitle(view.title?.takeIf { it.isNotBlank() } ?: url ?: "") }
                }
                setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                    when {
                        url.startsWith("blob:") -> {
                            val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
                            val nonce = lib.expectBlob(name)
                            evaluateJavascript(blobScript(url, nonce), null)
                        }
                        url.startsWith("http") -> lib.downloadFromBrowser(
                            url,
                            userAgent,
                            contentDisposition.takeIf { it.isNotBlank() },
                            CookieManager.getInstance().getCookie(url),
                            this.url,
                        )
                    }
                }
                loadUrl(startUrl)
                onCreated(this)
            }
        },
    )
}

/** Reads a blob: URL in the page and hands it to the app as a data: URL. */
private fun blobScript(url: String, nonce: String): String {
    val u = JSONObject.quote(url)
    val n = JSONObject.quote(nonce)
    return """
        (function() {
          fetch($u).then(function(r) { return r.blob(); }).then(function(b) {
            var fr = new FileReader();
            fr.onloadend = function() { OrcaPodDownloads.deliver($n, fr.result); };
            fr.readAsDataURL(b);
          }).catch(function() { OrcaPodDownloads.fail($n); });
        })();
    """.trimIndent()
}

@Composable
private fun ThingiverseTab(lib: LibraryViewModel, onOpenPage: (String) -> Unit) {
    var editingToken by rememberSaveable { mutableStateOf(false) }
    if (lib.token.isEmpty() || editingToken) {
        TokenSetup(
            initial = lib.token,
            canCancel = lib.token.isNotEmpty(),
            onSave = { lib.saveToken(it); editingToken = false },
            onCancel = { editingToken = false },
            onCreateApp = { onOpenPage(Thingiverse.CREATE_APP_URL) },
        )
        return
    }
    LaunchedEffect(lib.token) { if (lib.things.isEmpty() && lib.searchError == null) lib.search() }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = lib.query,
            onValueChange = { lib.query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            placeholder = { Text("Search Thingiverse") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { lib.search() }),
        )
        lib.searchError?.let { err ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(err, Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = lib::search) { Text("Retry") }
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(160.dp),
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(lib.things, key = { it.id }) { thing ->
                ThingCard(thing) { lib.showThing(thing) }
                if (thing == lib.things.last()) LaunchedEffect(thing.id) { lib.loadMore() }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    when {
                        lib.searching -> CircularProgressIndicator()
                        lib.things.isEmpty() && lib.searchError == null -> Text("No models found")
                        else -> TextButton(onClick = { editingToken = true }) { Text("Change token") }
                    }
                }
            }
        }
    }
}

@Composable
private fun TokenSetup(
    initial: String,
    canCancel: Boolean,
    onSave: (String) -> Unit,
    onCancel: () -> Unit,
    onCreateApp: () -> Unit,
) {
    var value by rememberSaveable { mutableStateOf(initial) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Connect Thingiverse", style = MaterialTheme.typography.titleLarge)
        Text(
            "Thingiverse's API needs an app token. Sign in to Thingiverse, create a free app " +
                "(any name, type \"Web App\"), and paste the App Token it shows here. " +
                "The token stays on this device.",
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(onClick = onCreateApp) { Text("Create a Thingiverse app") }
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("App token") },
            visualTransformation = PasswordVisualTransformation(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (canCancel) TextButton(onClick = onCancel) { Text("Cancel") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { onSave(value) }, enabled = value.isNotBlank()) { Text("Save") }
        }
        Text(
            "Printables, MakerWorld and other sites have no public API; browse them on the Websites tab " +
                "and their downloads are added to the plate.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ThingCard(thing: Thing, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        AsyncImage(
            model = thing.thumbnail,
            contentDescription = null,
            modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f).background(MaterialTheme.colorScheme.surfaceVariant),
            contentScale = ContentScale.Crop,
        )
        Column(Modifier.padding(8.dp)) {
            Text(thing.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (thing.creator.isNotEmpty()) {
                Text(
                    thing.creator,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThingSheet(thing: Thing, files: List<ThingFile>?, onDownload: (List<ThingFile>) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding()) {
            Text(thing.name, style = MaterialTheme.typography.titleMedium)
            if (thing.creator.isNotEmpty()) Text("by ${thing.creator}", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            if (files == null) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                return@Column
            }
            var chosen by remember(files) { mutableStateOf(files.filter { it.isModel }.map { it.id }.toSet()) }
            if (files.none { it.isModel }) Text("This model has no STL, 3MF, OBJ, AMF or STEP files.")
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(files, key = { it.id }) { f ->
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = f.isModel) {
                            chosen = if (f.id in chosen) chosen - f.id else chosen + f.id
                        }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = f.id in chosen, onCheckedChange = null, enabled = f.isModel)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (f.size > 0) Text(formatSize(f.size), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    HorizontalDivider()
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.End) {
                Button(
                    onClick = { onDownload(files.filter { it.id in chosen }) },
                    enabled = chosen.isNotEmpty(),
                ) { Text(if (chosen.size == 1) "Add 1 file to the plate" else "Add ${chosen.size} files to the plate") }
            }
        }
    }
}

@Composable
private fun PickFilesDialog(files: List<File>, onPick: (List<File>) -> Unit, onDismiss: () -> Unit) {
    var chosen by remember(files) { mutableStateOf(files.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add which files?") },
        text = {
            LazyColumn {
                items(files, key = { it.path }) { f ->
                    Row(
                        Modifier.fillMaxWidth().clickable { chosen = if (f in chosen) chosen - f else chosen + f },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = f in chosen, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(files.filter { it in chosen }) }, enabled = chosen.isNotEmpty()) {
                Text(if (chosen.size == 1) "Add 1 file" else "Add ${chosen.size} files")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1 shl 20 -> "%.1f MB".format(bytes / 1048576.0)
    bytes >= 1 shl 10 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

/** The first web link in shared text, such as a model page shared from a browser. */
fun firstWebLink(text: String?): String? =
    text?.let { Regex("https?://\\S+").find(it)?.value?.trimEnd('.', ',', ')', '"', '\'') }
        ?.takeIf { Uri.parse(it).host != null }
