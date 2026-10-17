package com.adamchesters.dazedev.mobile.ui

data class SearchOptions(
    val query: String,
    val matchCase: Boolean = false,
    val regex: Boolean = false,
)

/** One match: [line] indexes the searched lines; [start] and [end] are character offsets in it. */
data class LineMatch(
    val line: Int,
    val start: Int,
    val end: Int,
)

class SearchTimeoutException : Exception("Search timed out. Simplify the pattern or turn Regular expression off.")

/** Finds text in lines with the desktop's options: match case and regular expression, empty matches skipped. */
object TextSearch {
    fun pattern(options: SearchOptions): Regex? {
        if (options.query.isEmpty()) return null
        val flags = if (options.matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE)
        return try {
            if (options.regex) Regex(options.query, flags) else Regex(Regex.escape(options.query), flags)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid regular expression: ${e.message?.lineSequence()?.firstOrNull().orEmpty()}")
        }
    }

    /**
     * Returns at most [limit] matches. A pattern that runs past [timeoutMs] (for example catastrophic
     * backtracking) stops with [SearchTimeoutException] instead of freezing the app.
     */
    fun find(
        lines: List<String>,
        options: SearchOptions,
        limit: Int = 1000,
        timeoutMs: Long = 500,
    ): List<LineMatch> {
        val pattern = pattern(options) ?: return emptyList()
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        val matches = ArrayList<LineMatch>()
        for ((index, line) in lines.withIndex()) {
            if (matches.size >= limit) break
            if (System.nanoTime() > deadline) throw SearchTimeoutException()
            for (match in pattern.findAll(Deadline(line, deadline))) {
                if (match.range.isEmpty()) continue
                matches.add(LineMatch(index, match.range.first, match.range.last + 1))
                if (matches.size >= limit) break
            }
        }
        return matches
    }

    /** Text that throws once the deadline passes; the regular expression engine reads it character by character. */
    private class Deadline(
        private val text: CharSequence,
        private val deadline: Long,
    ) : CharSequence {
        private var reads = 0
        override val length get() = text.length

        override fun get(index: Int): Char {
            if (++reads and 0xfff == 0 && System.nanoTime() > deadline) throw SearchTimeoutException()
            return text[index]
        }

        override fun subSequence(
            startIndex: Int,
            endIndex: Int,
        ): CharSequence = Deadline(text.subSequence(startIndex, endIndex), deadline)

        override fun toString() = text.toString()
    }
}
