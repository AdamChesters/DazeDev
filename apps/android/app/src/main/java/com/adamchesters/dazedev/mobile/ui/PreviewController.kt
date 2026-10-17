package com.adamchesters.dazedev.mobile.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.adamchesters.dazedev.mobile.host.HostFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PreviewState(
    /** The link sheet: a tapped web address or path. */
    val link: TerminalTarget? = null,
    val linkHost: String = "",
    /** The open preview's path on [hostLabel]; null when no preview is open. */
    val path: String? = null,
    val hostLabel: String = "",
    val kind: PreviewKind = PreviewKind.Text,
    val loading: Boolean = false,
    val error: String? = null,
    val text: String? = null,
    val page: InertPage? = null,
    val image: Bitmap? = null,
    val showSource: Boolean = false,
)

/** Images are decoded at most this wide or tall, and to at most this many pixels. */
object ImageBounds {
    const val MAX_SIDE = 4096
    const val MAX_PIXELS = 16_000_000L

    fun sampleSize(
        width: Int,
        height: Int,
    ): Int {
        var sample = 1
        while (width / sample > MAX_SIDE || height / sample > MAX_SIDE || (width.toLong() / sample) * (height.toLong() / sample) > MAX_PIXELS) sample *= 2
        return sample
    }

    fun decode(bytes: ByteArray): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) error("This phone cannot show this image format.")
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight) }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: error("This phone cannot show this image format.")
    }
}

/** Link sheets and read-only previews of files on the open terminal's host. */
class PreviewController(
    private val terminal: TerminalController,
    private val scope: CoroutineScope,
) {
    private val preview = MutableStateFlow(PreviewState())
    val state: StateFlow<PreviewState> = preview.asStateFlow()
    private var loading: Job? = null

    fun showLink(target: TerminalTarget) {
        preview.update { it.copy(link = target, linkHost = terminal.openHost()?.label.orEmpty()) }
    }

    fun dismissLink() {
        preview.update { it.copy(link = null) }
    }

    /** Reads [path] on the open terminal's host, relative to the terminal's current folder. */
    fun open(path: String) {
        val h = terminal.openHost()
        val kind = TerminalLinks.previewKind(path)
        loading?.cancel()
        preview.value = PreviewState(path = path, hostLabel = h?.label.orEmpty(), kind = kind, loading = true)
        if (h == null) {
            preview.update { it.copy(loading = false, error = "Reconnect the terminal's host to preview this file.") }
            return
        }
        loading =
            scope.launch {
                try {
                    val directory = terminal.currentDirectory()
                    val files = HostFiles(h.link::request)
                    if (kind == PreviewKind.Image) {
                        val image = files.image(path, directory)
                        val bitmap = withContext(Dispatchers.Default) { ImageBounds.decode(image.bytes) }
                        preview.update { it.copy(path = image.path, image = bitmap, loading = false) }
                    } else {
                        val text = files.text(path, directory)
                        val page =
                            when (kind) {
                                PreviewKind.Markdown -> withContext(Dispatchers.Default) { InertHtml.markdown(text.text) }
                                PreviewKind.Html -> withContext(Dispatchers.Default) { InertHtml.html(text.text) }
                                else -> null
                            }
                        preview.update { it.copy(path = text.path, text = text.text, page = page, loading = false) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    preview.update { it.copy(loading = false, error = e.message ?: "Could not read this file.") }
                }
            }
    }

    fun toggleSource() {
        preview.update { it.copy(showSource = !it.showSource) }
    }

    fun close() {
        loading?.cancel()
        preview.update { PreviewState(link = it.link, linkHost = it.linkHost) }
    }
}
