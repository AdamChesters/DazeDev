package com.adamchesters.dazedev.mobile.voice

import ai.moonshine.voice.JNI
import ai.moonshine.voice.MicTranscriber
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import java.io.File

/**
 * Keeps one loaded Moonshine model while the terminal screen is open, so each
 * voice tap does not reload about 269 MB. The screen releases it on exit, and
 * it is released on low memory when not recording.
 */
internal object VoiceModel {
    interface Listener {
        fun text(value: String)

        fun line(value: String)

        fun error(error: Throwable)
    }

    @Volatile var listener: Listener? = null
    private var loaded: MicTranscriber? = null
    private var callbacks: ComponentCallbacks2? = null

    @Synchronized fun acquire(
        context: Context,
        directory: File,
    ): MicTranscriber {
        loaded?.let { return it }
        val app = context.applicationContext
        val capture =
            MicTranscriber(app)
                .language("en")
                .modelArch(JNI.MOONSHINE_MODEL_ARCH_MEDIUM_STREAMING)
                .callbacksOnMainThread(false)
                .onText { listener?.text(it) }
                .onLine { listener?.line(it.text.orEmpty()) }
                .onError { listener?.error(it) }
        try {
            capture.loadFromFiles(directory.absolutePath, JNI.MOONSHINE_MODEL_ARCH_MEDIUM_STREAMING)
        } catch (error: Throwable) {
            runCatching(capture::close)
            throw error
        }
        loaded = capture
        if (callbacks == null) {
            callbacks =
                object : ComponentCallbacks2 {
                    override fun onTrimMemory(level: Int) {
                        @Suppress("DEPRECATION")
                        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) releaseIfIdle()
                    }

                    override fun onConfigurationChanged(newConfig: Configuration) = Unit

                    @Deprecated("Deprecated in Java")
                    override fun onLowMemory() = releaseIfIdle()
                }.also(app::registerComponentCallbacks)
        }
        return capture
    }

    @Synchronized fun releaseIfIdle() {
        if (loaded?.isRunning == true) return
        release()
    }

    @Synchronized fun release() {
        val capture = loaded ?: return
        loaded = null
        runCatching(capture::stop)
        runCatching(capture::close)
    }
}
