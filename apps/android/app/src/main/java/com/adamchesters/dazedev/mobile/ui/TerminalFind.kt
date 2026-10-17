package com.adamchesters.dazedev.mobile.ui

import org.json.JSONObject

/** Logical lines of the terminal buffer; [first] holds each line's first buffer row. */
class TerminalLines(
    val first: List<Int>,
    val text: List<String>,
) {
    companion object {
        /** Five thousand rows of scrollback stay far below this; a larger reply is refused. */
        const val MAX_CHARS = 4_000_000

        fun parse(json: String): TerminalLines {
            require(json.length <= MAX_CHARS) { "The terminal buffer is too large to search." }
            val value = JSONObject(json)
            val first = value.getJSONArray("first")
            val text = value.getJSONArray("text")
            require(first.length() == text.length()) { "Invalid terminal buffer." }
            return TerminalLines((0 until first.length()).map { first.getInt(it) }, (0 until text.length()).map { text.getString(it) })
        }
    }
}

/** Find results for the open terminal and the selected one; next and previous wrap around. */
class TerminalFind(
    val lines: TerminalLines,
    val matches: List<LineMatch>,
) {
    /** Starts at the latest match, nearest the live output. */
    var current = (matches.size - 1).coerceAtLeast(0)
        private set

    val size get() = matches.size

    fun next(): LineMatch? {
        if (matches.isEmpty()) return null
        current = (current + 1) % matches.size
        return matches[current]
    }

    fun previous(): LineMatch? {
        if (matches.isEmpty()) return null
        current = (current - 1 + matches.size) % matches.size
        return matches[current]
    }

    fun selected(): LineMatch? = matches.getOrNull(current)

    /** Where to reveal [match]: its buffer row and the line text it was found in. */
    fun place(match: LineMatch) = lines.first[match.line] to lines.text[match.line]

    companion object {
        fun search(
            lines: TerminalLines,
            options: SearchOptions,
            limit: Int = 1000,
        ) = TerminalFind(lines, TextSearch.find(lines.text, options, limit))
    }
}
