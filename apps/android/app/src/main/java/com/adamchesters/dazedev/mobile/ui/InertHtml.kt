package com.adamchesters.dazedev.mobile.ui

/** HTML for a preview page, and remote images that were left out (listed for an explicit Open in browser). */
data class InertPage(
    val html: String,
    val remoteImages: List<String>,
)

/**
 * Builds static preview pages. The page also loads in a WebView with JavaScript, network and file access off;
 * this removes scripts, frames, forms, links and remote media first, as the desktop preview does.
 */
object InertHtml {
    private const val POLICY = "default-src 'none'; style-src 'unsafe-inline'; img-src data:; form-action 'none'; base-uri 'none'"
    private val localImage = Regex("""^data:image/(png|jpeg|gif|webp|bmp|avif);base64,[a-z0-9+/=]+$""", RegexOption.IGNORE_CASE)
    private val remote = Regex("""^https?://""", RegexOption.IGNORE_CASE)

    /** Elements removed together with their content. */
    private val dropped = listOf("script", "iframe", "object", "embed", "noscript", "template", "frameset", "applet", "textarea", "select", "button", "title")

    /** Elements removed while their content stays. */
    private val stripped = setOf("form", "input", "meta", "link", "base", "frame", "html", "head", "body", "option", "portal", "math", "svg", "use", "image", "a")
    private val blockedAttributes = setOf("href", "xlink:href", "action", "formaction", "srcset", "target", "ping", "background", "poster", "data", "codebase", "lowsrc", "dynsrc", "manifest")
    private val tag = Regex("""<(/?)([a-zA-Z][a-zA-Z0-9:-]*)([^<>]*)>""")
    private val attribute = Regex("""([^\s"'=<>/`]+)(?:\s*=\s*("[^"]*"|'[^']*'|[^\s"'=<>`]+))?""")

    fun page(body: String): String =
        "<!doctype html><html><head><meta charset=\"utf-8\"><meta http-equiv=\"Content-Security-Policy\" content=\"$POLICY\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><style>body{font:15px system-ui,sans-serif;line-height:1.45;padding:12px;margin:0;word-wrap:break-word}pre{white-space:pre-wrap}img{max-width:100%}</style></head><body>" +
            body + "</body></html>"

    fun escape(text: String) =
        buildString(text.length) {
            for (c in text) {
                when (c) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&#39;")
                    else -> append(c)
                }
            }
        }

    /** A small inert Markdown preview: no raw HTML, remote media or scripts (the desktop's markdownPreview). */
    fun markdown(source: String): InertPage {
        val remoteImages = mutableListOf<String>()

        fun images(text: String) =
            Regex("""!\[([^\]]*)\]\(([^()\s]+)\)""").replace(text) { m ->
                val alt = m.groupValues[1]
                val url = m.groupValues[2]
                when {
                    localImage.matches(url) -> {
                        "<img src=\"$url\" alt=\"$alt\">"
                    }

                    remote.containsMatchIn(url) -> {
                        if (url !in remoteImages) remoteImages.add(url)
                        "<span>Remote image not loaded: <code>$url</code></span>"
                    }

                    else -> {
                        m.value
                    }
                }
            }
        var code = false
        val out = StringBuilder()
        for (line in source.split('\n')) {
            if (Regex("""^\s*```""").containsMatchIn(line)) {
                out.append(if (code) "</code></pre>" else "<pre><code>")
                code = !code
                continue
            }
            if (code) {
                out.append(escape(line)).append('\n')
                continue
            }
            val heading = Regex("""^(#{1,6})\s+(.*)$""").find(line)
            val text =
                images(escape(heading?.groupValues?.get(2) ?: line))
                    .replace(Regex("`([^`]+)`"), "<code>$1</code>")
                    .replace(Regex("""\*\*([^*]+)\*\*"""), "<strong>$1</strong>")
            if (heading != null) {
                val level = heading.groupValues[1].length
                out.append("<h$level>$text</h$level>")
            } else {
                out.append("<p>").append(text.ifEmpty { "<br>" }).append("</p>")
            }
        }
        if (code) out.append("</code></pre>")
        return InertPage(page(out.toString()), remoteImages)
    }

    /** Static HTML: inline CSS stays; scripts, links, forms, frames and remote files are removed. */
    fun html(source: String): InertPage {
        val remoteImages = mutableListOf<String>()
        var text = source.replace(Regex("<!--.*?(-->|$)", RegexOption.DOT_MATCHES_ALL), "")
        for (name in dropped) {
            text = text.replace(Regex("<$name\\b[^>]*>.*?(</$name\\s*>|$)", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
            text = text.replace(Regex("</?$name\\b[^>]*>", RegexOption.IGNORE_CASE), "")
        }
        text =
            tag.replace(text) { m ->
                val closing = m.groupValues[1]
                val name = m.groupValues[2].lowercase()
                if (name in stripped || name.contains(':')) return@replace ""
                if (closing.isNotEmpty()) return@replace "</$name>"
                val kept = StringBuilder()
                for (a in attribute.findAll(m.groupValues[3])) {
                    val key = a.groupValues[1].lowercase()
                    val value = a.groupValues[2].trim('"', '\'')
                    if (key.startsWith("on") || key in blockedAttributes || key.contains(':')) continue
                    if (key == "src") {
                        if (name == "img" && localImage.matches(value.trim())) {
                            kept.append(" src=\"").append(escape(value.trim())).append('"')
                        } else if (name == "img") {
                            if (value.isNotBlank() && value !in remoteImages) remoteImages.add(value)
                            kept.append(" alt=\"").append(escape("Remote image not loaded: $value")).append('"')
                        }
                        continue
                    }
                    if (key == "style" && Regex("""url\s*\(|expression\s*\(|@import""", RegexOption.IGNORE_CASE).containsMatchIn(value)) continue
                    if (key == "http-equiv") continue
                    kept.append(' ').append(key)
                    if (a.groupValues[2].isNotEmpty()) kept.append("=\"").append(escape(value)).append('"')
                }
                "<$name$kept${if (m.groupValues[3].trimEnd().endsWith("/")) " /" else ""}>"
            }
        // CSS may not fetch anything either; the page policy blocks it, and imports are removed here.
        text = text.replace(Regex("""@import[^;]*;?""", RegexOption.IGNORE_CASE), "")
        return InertPage(page(text), remoteImages.filter { remote.containsMatchIn(it) })
    }
}
