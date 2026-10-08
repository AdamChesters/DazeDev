package com.adamchesters.dazedev.mobile.voice

import ai.moonshine.voice.JNI
import ai.moonshine.voice.MicTranscriber
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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

    private fun combined(): String = buildList {
        addAll(completedLines)
        currentLine.takeIf(String::isNotEmpty)?.let(::add)
    }.joinToString(" ")
}

class VoiceInputController(
    context: Context,
    private val onStatusChanged: (VoiceInputStatus) -> Unit,
    private val onTranscript: (String, Boolean) -> Unit,
    private val onError: (String) -> Unit,
) : SpeechEngine {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "dazedev-mobile-moonshine").apply { isDaemon = true }
    }
    private val stateLock = Any()
    private val transcript = TranscriptAccumulator()
    private var status = VoiceInputStatus()
    private var generation = 0L
    private var modelLoaded = false
    private var destroyed = false
    private var initializationError: Throwable? = null

    private val hasMicrophone = context.packageManager.hasSystemFeature(
        PackageManager.FEATURE_MICROPHONE,
    )

    private val microphone: MicTranscriber? = if (hasMicrophone) {
        try {
            MicTranscriber(context.applicationContext)
                .language("en")
                .modelArch(JNI.MOONSHINE_MODEL_ARCH_MEDIUM_STREAMING)
                .callbacksOnMainThread(false)
                .onProgress { fraction, file -> handleProgress(fraction, file) }
                .onText(::handlePartialTranscript)
                .onLine { line -> handleCompletedLine(line.text.orEmpty()) }
                .onError(::handleMicrophoneError)
        } catch (error: Throwable) {
            initializationError = error
            null
        }
    } else {
        null
    }

    override val isAvailable: Boolean
        get() = microphone != null

    override fun start() {
        val activeMicrophone = microphone
        if (activeMicrophone == null) {
            postError(
                if (!hasMicrophone) {
                    "This phone does not report a microphone."
                } else {
                    friendlyMessage(initializationError, preparing = false)
                },
            )
            return
        }

        val runGeneration: Long
        val loadingStatus = VoiceInputStatus(phase = VoiceInputPhase.Loading)
        synchronized(stateLock) {
            if (destroyed || status.isActive) return
            generation += 1
            runGeneration = generation
            transcript.reset()
            status = loadingStatus
        }
        postStatus(loadingStatus)

        worker.execute {
            try {
                if (!modelLoaded) {
                    activeMicrophone.load()
                    modelLoaded = true
                }

                if (!isCurrent(runGeneration)) return@execute
                activeMicrophone.start()

                val listeningStatus = VoiceInputStatus(phase = VoiceInputPhase.Listening)
                if (!transition(runGeneration, listeningStatus)) {
                    activeMicrophone.stop()
                }
            } catch (error: Throwable) {
                fail(runGeneration, error, preparing = !modelLoaded)
            }
        }
    }

    override fun stop() {
        val stopGeneration: Long?
        val nextStatus: VoiceInputStatus
        synchronized(stateLock) {
            when (status.phase) {
                VoiceInputPhase.Loading -> {
                    generation += 1
                    stopGeneration = null
                    nextStatus = VoiceInputStatus()
                }
                VoiceInputPhase.Listening -> {
                    stopGeneration = generation
                    nextStatus = VoiceInputStatus(phase = VoiceInputPhase.Stopping)
                }
                VoiceInputPhase.Idle,
                VoiceInputPhase.Stopping,
                -> return
            }
            status = nextStatus
        }

        postStatus(nextStatus)
        microphone?.let { activeMicrophone ->
            worker.execute {
                activeMicrophone.stop()
                stopGeneration?.let {
                    runCatching { Thread.sleep(STOP_FINALIZATION_TIMEOUT_MS) }
                    finishStopping(it)
                }
            }
        }
    }

    override fun destroy() {
        val shouldDestroy = synchronized(stateLock) {
            if (destroyed) {
                false
            } else {
                destroyed = true
                generation += 1
                status = VoiceInputStatus()
                true
            }
        }
        if (!shouldDestroy) return

        microphone?.let { activeMicrophone ->
            worker.execute {
                runCatching(activeMicrophone::stop)
                runCatching(activeMicrophone::close)
            }
        }
        worker.shutdown()
    }

    private fun handleProgress(fraction: Float, file: String) {
        val progress = fraction.takeIf(Float::isFinite)?.coerceIn(0f, 1f)
        val currentFile = file
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .takeIf(String::isNotBlank)
        val updated = synchronized(stateLock) {
            if (destroyed || status.phase != VoiceInputPhase.Loading) {
                null
            } else if (
                status.currentFile == currentFile &&
                status.downloadProgress?.times(100)?.toInt() == progress?.times(100)?.toInt()
            ) {
                null
            } else {
                status.copy(
                    downloadProgress = progress,
                    currentFile = currentFile,
                ).also { status = it }
            }
        }
        updated?.let(::postStatus)
    }

    private fun handlePartialTranscript(text: String) {
        val runGeneration = transcriptGeneration() ?: return
        val combined = transcript.partial(text)
        if (combined.isNotBlank()) postTranscript(runGeneration, combined, isFinal = false)
    }

    private fun handleCompletedLine(text: String) {
        val runGeneration = transcriptGeneration() ?: return
        val combined = transcript.complete(text)
        if (combined.isNotBlank()) postTranscript(runGeneration, combined, isFinal = true)

        val isStopping = synchronized(stateLock) {
            generation == runGeneration && status.phase == VoiceInputPhase.Stopping
        }
        if (isStopping) finishStopping(runGeneration)
    }

    private fun handleMicrophoneError(error: Throwable) {
        val shouldReport = synchronized(stateLock) {
            if (destroyed || !status.isActive) {
                false
            } else {
                generation += 1
                status = VoiceInputStatus()
                true
            }
        }
        if (!shouldReport) return

        microphone?.let { activeMicrophone ->
            worker.execute {
                runCatching(activeMicrophone::close)
                modelLoaded = false
            }
        }
        postStatus(VoiceInputStatus())
        postError(friendlyMessage(error, preparing = false))
    }

    private fun transition(runGeneration: Long, newStatus: VoiceInputStatus): Boolean {
        val accepted = synchronized(stateLock) {
            if (destroyed || generation != runGeneration || !status.isActive) {
                false
            } else {
                status = newStatus
                true
            }
        }
        if (accepted) postStatus(newStatus)
        return accepted
    }

    private fun fail(runGeneration: Long, error: Throwable, preparing: Boolean) {
        val shouldReport = synchronized(stateLock) {
            if (destroyed || generation != runGeneration) {
                false
            } else {
                generation += 1
                status = VoiceInputStatus()
                true
            }
        }
        if (!shouldReport) return

        postStatus(VoiceInputStatus())
        postError(friendlyMessage(error, preparing))
    }

    private fun isCurrent(runGeneration: Long): Boolean = synchronized(stateLock) {
        !destroyed && generation == runGeneration && status.isActive
    }

    private fun transcriptGeneration(): Long? = synchronized(stateLock) {
        generation.takeIf {
            !destroyed && (status.phase == VoiceInputPhase.Listening ||
                status.phase == VoiceInputPhase.Stopping)
        }
    }

    private fun finishStopping(runGeneration: Long) {
        val finished = synchronized(stateLock) {
            if (destroyed || generation != runGeneration || status.phase != VoiceInputPhase.Stopping) {
                false
            } else {
                status = VoiceInputStatus()
                true
            }
        }
        if (finished) postStatus(VoiceInputStatus())
    }

    private fun postStatus(newStatus: VoiceInputStatus) {
        mainHandler.post { onStatusChanged(newStatus) }
    }

    private fun postTranscript(runGeneration: Long, text: String, isFinal: Boolean) {
        mainHandler.post {
            val stillListening = synchronized(stateLock) {
                !destroyed && generation == runGeneration &&
                    (status.phase == VoiceInputPhase.Listening ||
                        status.phase == VoiceInputPhase.Stopping)
            }
            if (stillListening) onTranscript(text, isFinal)
        }
    }

    private fun postError(message: String) {
        mainHandler.post { onError(message) }
    }

    private fun friendlyMessage(error: Throwable?, preparing: Boolean): String {
        val causes = error
            ?.let { generateSequence<Throwable>(it) { cause -> cause.cause } }
            ?.toList()
            .orEmpty()
        val detail = causes.mapNotNull { it.message }.joinToString(" ").lowercase()

        return when {
            causes.any { it is SecurityException } || "permission" in detail ->
                "Microphone permission is required for voice input."
            preparing ->
                "Moonshine Medium could not be prepared. Check your connection and free storage, then try again."
            "audiorecord" in detail || "microphone" in detail ->
                "The microphone could not record audio."
            else ->
                "Moonshine voice input could not start on this phone."
        }
    }

    private companion object {
        const val STOP_FINALIZATION_TIMEOUT_MS = 1_000L
    }
}
