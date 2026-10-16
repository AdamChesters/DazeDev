package com.adamchesters.dazedev.mobile.ssh

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class HostKeyChallenge(
    val id: Long,
    val message: String,
)

class HostKeyPromptCoordinator {
    private data class PendingChallenge(
        val challenge: HostKeyChallenge,
        val answer: CompletableFuture<Boolean>,
    )

    private val nextId = AtomicLong(1)
    private val pending = AtomicReference<PendingChallenge?>(null)
    private val _challenge = MutableStateFlow<HostKeyChallenge?>(null)

    val challenge: StateFlow<HostKeyChallenge?> = _challenge.asStateFlow()

    fun request(message: String): Boolean {
        val request =
            PendingChallenge(
                challenge = HostKeyChallenge(nextId.getAndIncrement(), message),
                answer = CompletableFuture(),
            )

        if (!pending.compareAndSet(null, request)) {
            return false
        }

        _challenge.value = request.challenge
        return try {
            request.answer.get(HOST_KEY_PROMPT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: Exception) {
            false
        } finally {
            pending.compareAndSet(request, null)
            if (_challenge.value?.id == request.challenge.id) {
                _challenge.value = null
            }
        }
    }

    fun answer(
        challengeId: Long,
        trust: Boolean,
    ) {
        val request = pending.get() ?: return
        if (request.challenge.id != challengeId) return

        _challenge.value = null
        request.answer.complete(trust)
    }

    fun cancel() {
        val request = pending.getAndSet(null) ?: return
        _challenge.value = null
        request.answer.complete(false)
    }

    private companion object {
        const val HOST_KEY_PROMPT_TIMEOUT_SECONDS = 120L
    }
}
