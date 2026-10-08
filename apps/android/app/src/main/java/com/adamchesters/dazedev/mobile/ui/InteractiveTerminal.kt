package com.adamchesters.dazedev.mobile.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.webkit.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import com.adamchesters.dazedev.mobile.ssh.TerminalStream
import java.io.ByteArrayInputStream
import java.util.Base64
import kotlinx.coroutines.*
import org.json.JSONObject

class TerminalController {
    var web:WebView?=null
    var ready by mutableStateOf(false)
    fun call(method:String,argument:String="") {web?.evaluateJavascript("window.daze?.$method($argument)",null)}
    fun paste(text:String):Boolean {
        if(!ready || text.toByteArray().size>65_536)return false
        call("paste",JSONObject.quote(text));return true
    }
    suspend fun submit(text:String):Boolean {
        val page=web ?: return false
        if(!ready || text.isBlank() || text.toByteArray().size>65_536)return false
        val result=CompletableDeferred<Boolean>()
        page.evaluateJavascript("window.daze?.submit(${JSONObject.quote(text)})") {value->result.complete(value=="true")}
        return withTimeout(5_000){result.await()}
    }
    fun clear(){call("clear")}
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun InteractiveTerminal(stream:TerminalStream,controller:TerminalController,onMessage:(String)->Unit,modifier:Modifier=Modifier) {
    val context=LocalContext.current
    val fontScale=LocalConfiguration.current.fontScale
    val ready=remember(stream){CompletableDeferred<Unit>()}
    var viewport by remember(stream){mutableStateOf(IntSize.Zero)}
    val acknowledgements=remember(stream){java.util.concurrent.ConcurrentHashMap<Long,CompletableDeferred<Unit>>()}
    val web=remember(stream) {
        WebView(context).apply {
            settings.javaScriptEnabled=true
            settings.allowFileAccess=false;settings.allowContentAccess=false
            settings.blockNetworkLoads=true;settings.domStorageEnabled=false
            settings.mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.javaScriptCanOpenWindowsAutomatically=false
            settings.setSupportMultipleWindows(false)
            webViewClient=object:WebViewClient() {
                override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest)=true
                override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):WebResourceResponse {
                    val uri=request.url
                    val path=uri.path.orEmpty().removePrefix("/")
                    if(uri.scheme!="https" || uri.host!="terminal.dazedev.invalid" || path !in setOf("index.html","index.js","index.css"))return WebResourceResponse("text/plain","UTF-8",403,"Blocked",emptyMap(),ByteArrayInputStream(ByteArray(0)))
                    val mime=when(path){"index.html"->"text/html";"index.css"->"text/css";else->"text/javascript"}
                    return WebResourceResponse(mime,"UTF-8",context.assets.open("terminal/$path"))
                }
            }
            addJavascriptInterface(object {
                @JavascriptInterface fun submit(text:String):Boolean = stream.send(text.toByteArray())
                @JavascriptInterface fun event(text:String) {
                    if(text.length>500_000){post {onMessage("Selection is too large. Choose a smaller region.")};return}
                    runCatching {
                        val event=JSONObject(text)
                        when(event.getString("kind")) {
                            "ready"->{val size=event.getJSONObject("value");stream.resize(size.getInt("cols"),size.getInt("rows"));post {controller.ready=true};ready.complete(Unit)}
                            "resize"->{val size=event.getJSONObject("value");stream.resize(size.getInt("cols"),size.getInt("rows"))}
                            "input"->{if(!stream.send(event.getString("value").toByteArray()))post {onMessage("Input was not sent. Reconnect the terminal.")}}
                            "binary"->{val data=event.getString("value");if(!stream.send(ByteArray(data.length){data[it].code.toByte()}))post {onMessage("Input was not sent. Reconnect the terminal.")}}
                            "ack"->acknowledgements.remove(event.getLong("value"))?.complete(Unit)
                            "copy"->{val selected=event.getString("value");if(selected.toByteArray().size>250_000)post {onMessage("Selection is too large for the clipboard. Choose a smaller region.")} else if(selected.isNotEmpty())post {runCatching {(context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Terminal selection",selected))}.onFailure {onMessage("Could not copy this selection.")}}}
                        }
                    }
                }
            },"DazeBridge")
            controller.web=this
            loadUrl("https://terminal.dazedev.invalid/index.html")
        }
    }
    LaunchedEffect(web,stream) {
        try {
            withTimeout(15_000){ready.await()}
            var id=0L
            stream.output.collect { bytes->
                val current=++id;val ack=CompletableDeferred<Unit>();acknowledgements[current]=ack
                web.evaluateJavascript("window.daze.write('${Base64.getEncoder().encodeToString(bytes)}',$current)",null)
                withTimeout(10_000){ack.await()}
            }
            onMessage("Terminal disconnected. Refresh to reconnect; pending input is discarded.")
        }catch(e:CancellationException){throw e}catch(e:Exception){stream.close();onMessage(e.message?:"Terminal stopped.")}
    }
    LaunchedEffect(web,fontScale){withTimeout(15_000){ready.await()};controller.call("font",fontScale.toString())}
    LaunchedEffect(web,viewport){
        withTimeout(15_000){ready.await()}
        if(viewport.width>0 && viewport.height>0)controller.call("viewport","${viewport.width},${viewport.height}")
    }
    DisposableEffect(web) {onDispose {controller.clear();controller.web=null;controller.ready=false;web.removeJavascriptInterface("DazeBridge");web.destroy();stream.close();acknowledgements.values.forEach {it.cancel()};acknowledgements.clear()}}
    AndroidView(factory={web},modifier=modifier.onSizeChanged {viewport=it})
}
