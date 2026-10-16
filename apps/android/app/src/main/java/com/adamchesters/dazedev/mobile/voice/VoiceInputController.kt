package com.adamchesters.dazedev.mobile.voice

import ai.moonshine.voice.JNI
import ai.moonshine.voice.AssetDownloader
import ai.moonshine.voice.ModelCache
import ai.moonshine.voice.ModelSpec
import ai.moonshine.voice.MicTranscriber
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "dazedev-mobile-moonshine").apply { isDaemon = true }
    }
    private val stateLock = Any()
    private val transcript = TranscriptAccumulator()
    private var status = VoiceInputStatus()
    private var generation = 0L
    @Volatile private var modelLoaded = false
    @Volatile private var lastTranscriptAt = 0L
    @Volatile private var stoppedAt = 0L
    private var destroyed = false
    private var initializationError: Throwable? = null

    private val hasMicrophone = context.packageManager.hasSystemFeature(
        PackageManager.FEATURE_MICROPHONE,
    )

    @Volatile private var microphone: MicTranscriber? = null
    @Volatile private var preparation: VerifiedModelFiles? = null
    @Volatile private var preparingTask: java.util.concurrent.Future<*>? = null
    override val isAvailable: Boolean get() = hasMicrophone
    suspend fun hasPreparedModel(): Boolean = withContext(Dispatchers.IO) {
        VerifiedModelFiles(appContext).present()
    }

    override fun start() {
        if (!hasMicrophone) {
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

        preparingTask = worker.submit {
            var activeMicrophone: MicTranscriber? = null
            try {
                val files = VerifiedModelFiles(appContext)
                preparation = files
                val directory = files.prepare(::handleProgress)
                if (!isCurrent(runGeneration)) return@submit
                VoiceModel.listener = object : VoiceModel.Listener {
                    override fun text(value: String) {if(isCurrent(runGeneration))handlePartialTranscript(value)}
                    override fun line(value: String) {if(isCurrent(runGeneration))handleCompletedLine(value)}
                    override fun error(error: Throwable) {if(isCurrent(runGeneration))handleMicrophoneError(error)}
                }
                // The loaded model is shared and kept while the terminal screen is open.
                val capture = VoiceModel.acquire(appContext, directory)
                modelLoaded = true
                activeMicrophone = capture
                microphone = capture
                if (!isCurrent(runGeneration)) return@submit
                capture.start()

                val listeningStatus = VoiceInputStatus(phase = VoiceInputPhase.Listening)
                if (!transition(runGeneration, listeningStatus)) {
                    runCatching(capture::stop)
                }
            } catch (error: Throwable) {
                runCatching {activeMicrophone?.stop()}
                fail(runGeneration, error, preparing = !modelLoaded)
            } finally {
                preparation = null
                if(!isCurrent(runGeneration)){runCatching {activeMicrophone?.stop()}}
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

        if(stopGeneration == null){preparation?.cancel();preparingTask?.cancel(true)}
        postStatus(nextStatus)
        microphone?.let { activeMicrophone ->
            worker.execute {
                stoppedAt = System.currentTimeMillis()
                activeMicrophone.stop()
                stopGeneration?.let {
                    awaitFinalTranscript()
                    microphone = null
                    finishStopping(it)
                }
            }
        }
    }

    /**
     * After stop, Moonshine flushes the last audio and reports the final line.
     * Wait for that line (or a quiet period when no words are pending), with a cap.
     */
    private fun awaitFinalTranscript() {
        val deadline = stoppedAt + STOP_FINALIZATION_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val quietFor = System.currentTimeMillis() - maxOf(lastTranscriptAt, stoppedAt)
            if (!transcript.hasPartial() && quietFor >= QUIET_AFTER_STOP_MS) return
            try { Thread.sleep(25) } catch (_: InterruptedException) { return }
        }
    }

    fun clearTranscript() {transcript.reset()}
    fun cancelCapture() {
        val active = synchronized(stateLock) {generation+=1;status=VoiceInputStatus();microphone.also {microphone=null}}
        preparation?.cancel();preparingTask?.cancel(true)
        worker.execute {runCatching {active?.stop()}}
        postStatus(VoiceInputStatus())
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
        preparation?.cancel();preparingTask?.cancel(true)
        transcript.reset()
        microphone?.let { activeMicrophone ->
            worker.execute {runCatching(activeMicrophone::stop)}
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
        lastTranscriptAt = System.currentTimeMillis()
        val runGeneration = transcriptGeneration() ?: return
        val combined = transcript.partial(text)
        if (combined.isNotBlank()) postTranscript(runGeneration, combined, isFinal = false)
    }

    private fun handleCompletedLine(text: String) {
        lastTranscriptAt = System.currentTimeMillis()
        val runGeneration = transcriptGeneration() ?: return
        val combined = transcript.complete(text)
        if (combined.isNotBlank()) postTranscript(runGeneration, combined, isFinal = true)

        val isStopping = synchronized(stateLock) {
            generation == runGeneration && status.phase == VoiceInputPhase.Stopping
        }
        // stop() finalizes the transcript; the worker closes capture before reporting idle.
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

        worker.execute {
            VoiceModel.release()
            microphone = null
            modelLoaded = false
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
        const val STOP_FINALIZATION_TIMEOUT_MS = 5_000L
        const val QUIET_AFTER_STOP_MS = 400L
    }
}
