package com.adamchesters.dazedev.mobile.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

private fun copyText(
    context: Context,
    label: String,
    text: String,
): String? =
    if (text.toByteArray().size > 250_000) {
        "This text is too large for the clipboard."
    } else {
        runCatching { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, text)) }.exceptionOrNull()?.let { "Could not copy this text." }
    }

/** A tapped web address or file path. Nothing opens until a button is tapped. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkSheet(
    target: TerminalTarget,
    host: String,
    onPreview: (String) -> Unit,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (target.kind == TerminalTarget.Kind.Url) "Web address" else "File path", style = MaterialTheme.typography.titleLarge)
            SelectionContainer { Text(target.text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium) }
            if (target.kind == TerminalTarget.Kind.Path) {
                Text("Read on ${host.ifBlank { "the terminal's host" }}. A relative path starts in the terminal's current folder; a nested SSH session does not change the host.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (target.kind == TerminalTarget.Kind.Url) {
                    Button(onClick = {
                        BrowserLauncher.open(context, target.text)?.let(onMessage)
                        onDismiss()
                    }) { Text("Open in browser") }
                } else {
                    Button(onClick = {
                        onDismiss()
                        onPreview(target.text)
                    }) { Text("Preview") }
                }
                OutlinedButton(onClick = {
                    onMessage(copyText(context, "Terminal link", target.text) ?: "Copied.")
                    onDismiss()
                }) { Text("Copy") }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Close") }
        }
    }
}

/** Full-screen read-only preview: source with search, inert Markdown and HTML, or a zoomable image. */
@Composable
fun PreviewDialog(
    preview: PreviewState,
    onToggleSource: () -> Unit,
    onClose: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(preview.path.orEmpty().substringAfterLast('/'), style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Text("${preview.hostLabel} · ${preview.path.orEmpty()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                    }
                    TextButton(onClick = onClose) { Text("Close") }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                    if (preview.page != null) TextButton(onClick = onToggleSource) { Text(if (preview.showSource) "Show preview" else "Show source") }
                    if (preview.text != null) {
                        TextButton(onClick = { onMessage(copyText(context, "File", preview.text) ?: "Copied the whole file.") }) { Text("Copy all") }
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        preview.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                        preview.error != null -> Text(preview.error, Modifier.padding(20.dp), color = MaterialTheme.colorScheme.error)
                        preview.image != null -> ZoomImage(preview.image)
                        preview.page != null && !preview.showSource -> InertPreview(preview.page, onMessage)
                        preview.text != null -> SourceText(preview.text)
                    }
                }
            }
        }
    }
}

@Composable
private fun InertPreview(
    page: InertPage,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    var stopped by remember(page) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        if (stopped) {
            Text("The preview stopped. Show source instead, or close and open it again.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            return@Column
        }
        Text("Static preview. Scripts, links and remote files are off.", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = false
                    settings.blockNetworkLoads = true
                    settings.blockNetworkImage = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.domStorageEnabled = false
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.setSupportMultipleWindows(false)
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    setBackgroundColor(android.graphics.Color.WHITE)
                    webViewClient =
                        object : WebViewClient() {
                            // No navigation of any kind: links, refreshes and form posts stay on this page.
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest,
                            ) = true

                            override fun shouldInterceptRequest(
                                view: WebView,
                                request: WebResourceRequest,
                            ) = WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))

                            // A killed renderer must not take the app down.
                            override fun onRenderProcessGone(
                                view: WebView,
                                detail: RenderProcessGoneDetail,
                            ): Boolean {
                                stopped = true
                                return true
                            }
                        }
                }
            },
            update = { it.loadDataWithBaseURL(null, page.html, "text/html", "utf-8", null) },
            onRelease = { it.destroy() },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        page.remoteImages.take(20).forEach { url ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Remote image not loaded: $url", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 2)
                TextButton(onClick = { BrowserLauncher.open(context, url)?.let(onMessage) }) { Text("Open in browser") }
            }
        }
    }
}

@Composable
private fun ZoomImage(bitmap: android.graphics.Bitmap) {
    var scale by remember(bitmap) { mutableFloatStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(bitmap) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(0.5f, 8f)
                    offset += pan
                }
            },
    ) {
        Image(
            image,
            contentDescription = "Previewed image",
            modifier =
                Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )
        TextButton(onClick = {
            scale = 1f
            offset = Offset.Zero
        }, modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)) { Text("Reset zoom", color = Color.White) }
    }
}

/** Read-only text with find (match case, regular expression, next and previous). */
@Composable
private fun SourceText(text: String) {
    val lines = remember(text) { text.split('\n') }
    var query by remember { mutableStateOf("") }
    var matchCase by remember { mutableStateOf(false) }
    var regex by remember { mutableStateOf(false) }
    var matches by remember { mutableStateOf(emptyList<LineMatch>()) }
    var current by remember { mutableIntStateOf(0) }
    var problem by remember { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()
    LaunchedEffect(lines, query, matchCase, regex) {
        delay(200)
        try {
            matches = withContext(Dispatchers.Default) { TextSearch.find(lines, SearchOptions(query, matchCase, regex)) }
            problem = if (query.isNotEmpty() && matches.isEmpty()) "No matches." else null
        } catch (e: Exception) {
            matches = emptyList()
            problem = e.message
        }
        current = 0
    }
    LaunchedEffect(matches, current) { matches.getOrNull(current)?.let { list.scrollToItem(it.line) } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(query, { query = it }, label = { Text("Find") }, singleLine = true, modifier = Modifier.weight(1f))
            TextButton(onClick = { if (matches.isNotEmpty()) current = (current - 1 + matches.size) % matches.size }, enabled = matches.isNotEmpty()) { Text("Prev") }
            TextButton(onClick = { if (matches.isNotEmpty()) current = (current + 1) % matches.size }, enabled = matches.isNotEmpty()) { Text("Next") }
        }
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = matchCase, onClick = { matchCase = !matchCase }, label = { Text("Match case") })
            FilterChip(selected = regex, onClick = { regex = !regex }, label = { Text("Regular expression") })
            Text(problem ?: if (matches.isEmpty()) "" else "${current + 1} of ${matches.size}${if (matches.size >= 1000) "+" else ""}", style = MaterialTheme.typography.bodySmall)
        }
        val highlight = MaterialTheme.colorScheme.tertiaryContainer
        SelectionContainer(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), state = list) {
                itemsIndexed(lines) { index, line ->
                    val hit = matches.getOrNull(current)?.takeIf { it.line == index }
                    Text(
                        if (hit == null) {
                            buildAnnotatedString { append(line) }
                        } else {
                            buildAnnotatedString {
                                append(line.substring(0, hit.start))
                                withStyle(SpanStyle(background = highlight)) { append(line.substring(hit.start, hit.end)) }
                                append(line.substring(hit.end))
                            }
                        },
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
