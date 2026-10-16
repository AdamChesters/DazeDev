package com.adamchesters.dazedev.mobile.ui

import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.adamchesters.dazedev.mobile.ssh.TerminalLink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The terminal WebView, its message channel and the page's keys on a device or emulator.
 * Run with `./gradlew connectedDebugAndroidTest`; no host or network is needed.
 */
@RunWith(AndroidJUnit4::class)
class TerminalBridgeDeviceTest {
    @get:Rule val compose = createComposeRule()

    private class FakeLink : TerminalLink {
        val emitted = MutableSharedFlow<ByteArray>(replay = 64)
        val sent = LinkedBlockingQueue<String>()

        @Volatile var size: Pair<Int, Int>? = null
        override val output: Flow<ByteArray> = emitted
        override val state = MutableStateFlow(true)
        override val connected = true

        override fun send(bytes: ByteArray) = sent.add(String(bytes, Charsets.UTF_8))

        override fun resize(
            cols: Int,
            rows: Int,
        ) {
            size = cols to rows
        }

        override suspend fun cancelCopyMode() = Unit

        override fun close() = Unit
    }

    private val link = FakeLink()
    private val page = TerminalPageController()

    @Before fun openTerminal() {
        compose.setContent { InteractiveTerminal(link, page, { _, _ -> }, {}, {}, {}, Modifier.fillMaxSize()) }
        compose.waitUntil(20_000) { page.ready }
    }

    private fun js(script: String): String? {
        val result = CompletableDeferred<String?>()
        compose.runOnUiThread { page.web!!.evaluateJavascript(script) { result.complete(it) } }
        return runBlocking { withTimeout(5_000) { result.await() } }
    }

    /** Writes output to the page and waits until xterm has drawn [marker]. */
    private fun output(
        text: String,
        marker: String,
    ) {
        link.emitted.tryEmit("$text$marker\r\n".toByteArray())
        compose.waitUntil(10_000) { js("document.querySelector('.xterm-rows').textContent.includes('$marker')") == "true" }
    }

    /** Collects what the page sent until [done] holds or five seconds pass. */
    private fun sentUntil(done: (String) -> Boolean): String {
        val text = StringBuilder()
        val end = System.currentTimeMillis() + 5_000
        while (!done(text.toString()) && System.currentTimeMillis() < end) {
            link.sent.poll(100, TimeUnit.MILLISECONDS)?.let(text::append)
        }
        return text.toString()
    }

    @Test fun messageChannelCarriesOutputInAndInputBack() {
        assertNotNull("The page reports its size before output starts", link.size)
        // Output turns on bracketed paste; a paste must come back wrapped, so both directions worked.
        output("\u001b[?2004h", "bridge-ready")
        compose.runOnUiThread { assertTrue(page.paste("hi")) }
        assertEquals("\u001b[200~hi\u001b[201~", sentUntil { it.endsWith("\u001b[201~") })
    }

    @Test fun dragsBecomeWheelEventsOnlyWithMouseTracking() {
        output("\u001b[?1000h\u001b[?1006h", "mouse-on")
        compose.onRoot().performTouchInput { swipeDown() }
        assertTrue("SGR wheel report expected", sentUntil { it.contains("\u001b[<6") }.contains("\u001b[<6"))
        link.sent.clear()
        output("\u001b[?1000l\u001b[?1006l", "mouse-off")
        compose.onRoot().performTouchInput { swipeUp() }
        // Without mouse tracking a drag scrolls locally and never sends wheel or arrow keys.
        assertFalse(sentUntil { false }.contains("\u001b["))
    }

    @Test fun extraKeysSendKeysAndModifiers() {
        output("", "keys-ready")
        js("window.daze.key('Escape')")
        assertEquals("\u001b", sentUntil { it.isNotEmpty() })
        // Ctrl is one-shot after a short tap.
        js(
            "(()=>{const b=document.getElementById('Control');" +
                "b.dispatchEvent(new PointerEvent('pointerdown',{bubbles:true,cancelable:true}));" +
                "b.dispatchEvent(new PointerEvent('pointerup',{bubbles:true}));})()",
        )
        js("window.daze.key('c')")
        assertEquals("\u0003", sentUntil { it.isNotEmpty() })
        js("window.daze.key('c')")
        assertEquals("c", sentUntil { it.isNotEmpty() })
    }

    @Test fun keyboardTextReachesTheTerminal() {
        output("", "ime-ready")
        js("window.daze.focus()")
        compose.waitUntil(5_000) { js("document.activeElement === document.querySelector('textarea')") == "true" }
        compose.runOnUiThread {
            val web = page.web!!
            web.requestFocus()
            assertTrue(web.onCheckIsTextEditor())
            val connection = web.onCreateInputConnection(EditorInfo())
            assertNotNull("The WebView offers the keyboard an input connection", connection)
            connection!!.commitText("ls", 1)
        }
        assertEquals("ls", sentUntil { it.length >= 2 })
    }
}
