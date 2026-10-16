package com.adamchesters.dazedev.mobile.voice

enum class VoiceInputPhase {
    Idle,
    Loading,
    Listening,
    Stopping,
}

data class VoiceInputStatus(
    val phase: VoiceInputPhase = VoiceInputPhase.Idle,
    val downloadProgress: Float? = null,
    val currentFile: String? = null,
) {
    val isActive: Boolean
        get() = phase != VoiceInputPhase.Idle
}

internal class TranscriptAccumulator {
    private val completedLines = mutableListOf<String>()
    private var currentLine = ""

    @Synchronized
    fun reset() {
        completedLines.clear()
        currentLine = ""
    }

    @Synchronized
    fun partial(text: String): String {
        currentLine = text.trim()
        return combined()
    }

    @Synchronized
    fun complete(text: String): String {
        text.trim().takeIf(String::isNotEmpty)?.let(completedLines::add)
        currentLine = ""
        return combined()
    }

    @Synchronized
    fun hasPartial(): Boolean = currentLine.isNotEmpty()

    private fun combined(): String =
        buildList {
            addAll(completedLines)
            currentLine.takeIf(String::isNotEmpty)?.let(::add)
        }.joinToString(" ")
}

internal fun friendlyMessage(
    error: Throwable?,
    preparing: Boolean,
): String {
    val causes =
        error
            ?.let { generateSequence<Throwable>(it) { cause -> cause.cause } }
            ?.toList()
            .orEmpty()
    val detail = causes.mapNotNull { it.message }.joinToString(" ").lowercase()

    return when {
        causes.any { it is SecurityException } || "permission" in detail -> {
            "Microphone permission is required for voice input."
        }

        preparing -> {
            "Moonshine Medium could not be prepared. Check your connection and free storage, then try again."
        }

        "audiorecord" in detail || "microphone" in detail -> {
            "The microphone could not record audio."
        }

        else -> {
            "Moonshine voice input could not start on this phone."
        }
    }
}
