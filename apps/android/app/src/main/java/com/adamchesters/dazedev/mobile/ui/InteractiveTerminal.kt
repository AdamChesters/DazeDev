package com.adamchesters.dazedev.mobile.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.accessibility.AccessibilityManager
import android.webkit.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.adamchesters.dazedev.mobile.ssh.TerminalLink
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong

private const val ORIGIN = "https://terminal.dazedev.invalid"

/** Unacknowledged output allowed in the page at once. */
private const val OUTPUT_WINDOW = 512L * 1024

class TerminalPageController {
    var web: WebView? = null
    var stream: TerminalLink? = null
    var ready by mutableStateOf(false)

    fun call(
        method: String,
        argument: String = "",
    ) {
        web?.evaluateJavascript("window.daze?.$method($argument)", null)
    }

    fun paste(text: String): Boolean {
        if (!ready || text.toByteArray().size > 65_536) return false
        call("paste", JSONObject.quote(text))
        return true
    }

    /** True when sent, false when refused, null when the page did not answer in time. */
    suspend fun submit(text: String): Boolean? {
        val page = web ?: return false
        val active = stream ?: return false
        if (!ready || text.isBlank() || text.toByteArray().size > 65_536) return false
        val result = CompletableDeferred<String?>()
        page.evaluateJavascript("window.daze?.submit(${JSONObject.quote(text)})") { value -> result.complete(value) }
        val raw = withTimeoutOrNull(5_000) { result.await() } ?: return null
        val payload = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull() ?: return false
        return active.send(payload.toByteArray())
    }

    fun clear() {
        call("clear")
    }
}

private fun inputRefused(stream: TerminalLink?) = if (stream?.connected == true) "Input is still being sent. Wait a moment, then try again." else "Input was not sent. Reconnect the terminal."

/** One WebView per attachment: the caller keys this on the terminal epoch. The page measures before the stream attaches. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun InteractiveTerminal(
    stream: TerminalLink?,
    controller: TerminalPageController,
    onMeasured: (Int, Int) -> Unit,
    onScrolled: (Boolean) -> Unit,
    onMessage: (String) -> Unit,
    onRendererGone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val fontScale = LocalConfiguration.current.fontScale
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val reply = remember { MutableStateFlow<JavaScriptReplyProxy?>(null) }
    val acknowledged = remember { AtomicLong(0) }
    val ackSignal = remember { Channel<Unit>(Channel.CONFLATED) }
    val currentStream by rememberUpdatedState(stream)
    val scope = rememberCoroutineScope()
    val web =
        remember {
            // Screen reader mode costs CPU on every write, so it follows TalkBack.
            val accessibility = (context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager).let { it.isEnabled && it.isTouchExplorationEnabled }
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.blockNetworkLoads = true
                settings.domStorageEnabled = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.setSupportMultipleWindows(false)
                webViewClient =
                    object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest,
                        ) = true

                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest,
                        ): WebResourceResponse {
                            val uri = request.url
                            val path = uri.path.orEmpty().removePrefix("/")
                            if (uri.scheme != "https" || uri.host != "terminal.dazedev.invalid" || path !in setOf("index.html", "index.js", "index.css")) return WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                            val mime =
                                when (path) {
                                    "index.html" -> "text/html"
                                    "index.css" -> "text/css"
                                    else -> "text/javascript"
                                }
                            return WebResourceResponse(mime, "UTF-8", context.assets.open("terminal/$path"))
                        }

                        // A killed renderer must not take the app down; the caller reattaches with a new view.
                        override fun onRenderProcessGone(
                            view: WebView,
                            detail: RenderProcessGoneDetail,
                        ): Boolean {
                            controller.ready = false
                            onRendererGone()
                            return true
                        }
                    }
                if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                    // Only this page's own origin, in the main frame, can reach the terminal.
                    WebViewCompat.addWebMessageListener(this, "DazeBridge", setOf(ORIGIN)) { _, message, origin, isMainFrame, proxy ->
                        if (!isMainFrame || origin.toString().trimEnd('/') != ORIGIN) return@addWebMessageListener
                        if (message.type != WebMessageCompat.TYPE_STRING) return@addWebMessageListener
                        val text = message.data ?: return@addWebMessageListener
                        if (text.length > 500_000) {
                            onMessage("Selection is too large. Choose a smaller region.")
                            return@addWebMessageListener
                        }
                        runCatching {
                            val event = JSONObject(text)
                            when (event.getString("kind")) {
                                "ready", "resize" -> {
                                    val size = event.getJSONObject("value")
                                    val cols = size.getInt("cols")
                                    val rows = size.getInt("rows")
                                    currentStream?.resize(cols, rows)
                                    onMeasured(cols, rows)
                                    if (event.getString("kind") == "ready") reply.value = proxy
                                }

                                "input" -> {
                                    if (!controller.ready || currentStream?.send(event.getString("value").toByteArray()) != true) onMessage(inputRefused(currentStream))
                                }

                                "binary" -> {
                                    val data = event.getString("value")
                                    if (!controller.ready || currentStream?.send(ByteArray(data.length) { data[it].code.toByte() }) != true) onMessage(inputRefused(currentStream))
                                }

                                "ack" -> {
                                    acknowledged.addAndGet(event.getLong("value"))
                                    ackSignal.trySend(Unit)
                                }

                                "scrolled" -> {
                                    onScrolled(event.getBoolean("value"))
                                }

                                "hint" -> {
                                    onMessage(event.getString("value"))
                                }

                                "scrollBottom" -> {
                                    currentStream?.let { active ->
                                        scope.launch {
                                            try {
                                                active.cancelCopyMode()
                                            } catch (e: CancellationException) {
                                                throw e
                                            } catch (e: Exception) {
                                                onMessage(e.message ?: "Could not return to live output.")
                                            }
                                        }
                                    }
                                }

                                "openUrl" -> {
                                    BrowserLauncher.open(context, event.getString("value"))?.let(onMessage)
                                }

                                "copy" -> {
                                    val selected = event.getString("value")
                                    if (selected.toByteArray().size > 250_000) {
                                        onMessage("Selection is too large for the clipboard. Choose a smaller region.")
                                    } else if (selected.isNotEmpty()) {
                                        runCatching { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Terminal selection", selected)) }.onFailure { onMessage("Could not copy this selection.") }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    post { onMessage("Update Android System WebView to use the terminal.") }
                }
                controller.web = this
                loadUrl("$ORIGIN/index.html" + if (accessibility) "#a11y" else "")
            }
        }
    LaunchedEffect(web, stream) {
        if (stream == null) return@LaunchedEffect
        val proxy = withTimeoutOrNull(15_000) { reply.filterNotNull().first() }
        if (proxy == null) {
            stream.close()
            onMessage("Terminal renderer did not start. Reconnect before sending input.")
            return@LaunchedEffect
        }
        controller.stream = stream
        controller.ready = true
        var sent = 0L
        try {
            // Several chunks stay in flight; a slow page only slows output, it never ends the stream.
            stream.output.collect { bytes ->
                while (sent - acknowledged.get() > OUTPUT_WINDOW)ackSignal.receive()
                if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_ARRAY_BUFFER)) {
                    proxy.postMessage(bytes)
                } else {
                    val text = "o" + withContext(Dispatchers.Default) { Base64.getEncoder().encodeToString(bytes) }
                    if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) proxy.postMessage(text)
                }
                sent += bytes.size
            }
            onMessage("Terminal disconnected. Refresh to reconnect; pending input is discarded.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onMessage(e.message ?: "Terminal stopped.")
        } finally {
            controller.ready = false
            stream.close()
        }
    }
    LaunchedEffect(web, fontScale) {
        withTimeoutOrNull(15_000) { reply.filterNotNull().first() } ?: return@LaunchedEffect
        controller.call("font", fontScale.toString())
    }
    // While an on-screen keyboard covers part of the view (also while it animates), the page keeps the
    // terminal size and scrolls instead, so other devices do not resize.
    val keyboard = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    LaunchedEffect(web, viewport, keyboard) {
        withTimeoutOrNull(15_000) { reply.filterNotNull().first() } ?: return@LaunchedEffect
        if (viewport.width > 0 && viewport.height > 0) controller.call("viewport", "${viewport.width},${viewport.height},$keyboard")
    }
    DisposableEffect(web) {
        onDispose {
            controller.clear()
            controller.web = null
            controller.stream = null
            controller.ready = false
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) runCatching { WebViewCompat.removeWebMessageListener(web, "DazeBridge") }
            web.destroy()
        }
    }
    AndroidView(factory = { web }, modifier = modifier.onSizeChanged { viewport = it })
}
