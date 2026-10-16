package com.adamchesters.dazedev.mobile.voice

interface SpeechEngine {
    val isAvailable: Boolean

    fun start()

    fun stop()

    fun destroy()
}
