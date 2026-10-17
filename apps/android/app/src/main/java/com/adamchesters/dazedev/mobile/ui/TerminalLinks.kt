package com.adamchesters.dazedev.mobile.ui

/** A web address or file path found in terminal output. [start] and [end] index the scanned text. */
data class TerminalTarget(
    val kind: Kind,
    val text: String,
    val start: Int,
    val end: Int,
) {
    enum class Kind { Url, Path }
}

/** What a file preview can show for a path. */
enum class PreviewKind { Image, Markdown, Html, Text }

/**
 * Finds links in terminal text the way the desktop terminal does: web addresses, and file paths with a
 * viewable extension (or a known file name). Detection only reports text; opening is a separate user action.
 */
object TerminalLinks {
    private val url = Regex("""https?://[^\s"'!*(){}|\\^<>`]*[^\s"':,.!?{}|\\^~\[\]`()<>]""", RegexOption.IGNORE_CASE)
    private const val EXTENSIONS =
        "png|jpe?g|gif|webp|bmp|ico|avif|html?|md|mdc|markdown|txt|log|json|jsonc|ya?ml|toml|ini|cfg|conf|csv|tsv|xml|svg|css|scss|less|[cm]?[jt]sx?|py|rs|sh|bash|ps1|psm1|psd1|bat|cmd|sql|c|h|cpp|hpp|java|go|rb|php|svelte|vue|kt|kts|gradle"
    private const val SUFFIX = """(?:\.(?:$EXTENSIONS)|[/\\](?:Dockerfile|Makefile|LICENSE|README|\.cursorrules|\.clinerules))"""
    private const val ROOT = """(?:[A-Za-z]:[\\/]|/|~/|(?:[\w.-]+[\\/])|(?=[\w.-]+\.))"""
    private const val ENCLOSED_ROOT = """(?:$ROOT|[\w.-])"""

    // Markdown destinations keep spaces; plain paths use quotes to include spaces.
    private val path =
        Regex(
            """\]\(($ENCLOSED_ROOT[^\r\n]*?$SUFFIX)\)|["'`]($ENCLOSED_ROOT[^\r\n]*?$SUFFIX)["'`]|($ROOT[^\s<>"`]*?$SUFFIX)(?=$|[\s)\],:;`])""",
            RegexOption.IGNORE_CASE,
        )
    private val scheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
    private val drive = Regex("""^[A-Za-z]:[\\/]""")
    private val image = Regex("""\.(png|jpe?g|gif|webp|bmp|ico|avif)$""", RegexOption.IGNORE_CASE)

    /** Web addresses in [text]; only HTTP and HTTPS. */
    fun urls(text: String): List<TerminalTarget> = url.findAll(text).map { TerminalTarget(TerminalTarget.Kind.Url, it.value, it.range.first, it.range.last + 1) }.toList()

    /** File paths in [text] that do not overlap a web address. */
    fun paths(text: String): List<TerminalTarget> {
        val web = urls(text)
        return path
            .findAll(text)
            .mapNotNull { match ->
                val group = (1..3).firstNotNullOfOrNull { match.groups[it] } ?: return@mapNotNull null
                val value = group.value
                if (scheme.containsMatchIn(value) && !drive.containsMatchIn(value)) return@mapNotNull null
                val start = group.range.first
                if (start > 0 && (text[start - 1].isLetterOrDigit() || text[start - 1] in "_:/")) return@mapNotNull null
                val end = start + value.length
                if (web.any { it.start < end && start < it.end }) return@mapNotNull null
                TerminalTarget(TerminalTarget.Kind.Path, value, start, end)
            }.toList()
    }

    /** The link that covers [index] in [text], preferring a web address; null when the tap is not on a link. */
    fun at(
        text: String,
        index: Int,
    ): TerminalTarget? {
        if (text.length > MAX_TEXT || index !in text.indices) return null
        return urls(text).firstOrNull { index in it.start until it.end } ?: paths(text).firstOrNull { index in it.start until it.end }
    }

    fun previewKind(path: String): PreviewKind {
        val bare = path.substringBefore('?').substringBefore('#')
        return when {
            image.containsMatchIn(bare) -> PreviewKind.Image
            Regex("""\.(md|mdc|markdown)$""", RegexOption.IGNORE_CASE).containsMatchIn(bare) || Regex("""(^|[/\\])\.(cursor|cline)rules$""").containsMatchIn(bare) -> PreviewKind.Markdown
            Regex("""\.html?$""", RegexOption.IGNORE_CASE).containsMatchIn(bare) -> PreviewKind.Html
            else -> PreviewKind.Text
        }
    }

    /** Scanned text is a few terminal rows; anything longer is not a tap target. */
    const val MAX_TEXT = 65_536
}
