package com.adamchesters.dazedev.mobile.host

import com.adamchesters.dazedev.mobile.ssh.HostKeyChallenge
import com.adamchesters.dazedev.mobile.ui.BrowserUrl
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/** Questions hosts ask the phone user: host key checks, sign-in messages and browser links. */
class HostPrompts(
    private val openBrowser: (String) -> String?,
    private val message: (String) -> Unit,
) {
    private val challenges = linkedMapOf<String, Pair<String, HostKeyChallenge>>()
    private val ids = AtomicLong(1)
    private var browserAnswer: CompletableDeferred<BrowserAnswer>? = null
    private val currentChallenge = MutableStateFlow<HostChallenge?>(null)
    private val currentNotice = MutableStateFlow<AuthNotice?>(null)
    private val currentBrowserRequest = MutableStateFlow<BrowserRequest?>(null)

    val challenge: StateFlow<HostChallenge?> = currentChallenge.asStateFlow()
    val authNotice: StateFlow<AuthNotice?> = currentNotice.asStateFlow()
    val browserRequest: StateFlow<BrowserRequest?> = currentBrowserRequest.asStateFlow()

    /** False while no screen is visible; hosts cannot open the phone browser then. */
    @Volatile var foreground = true

    fun challengeChanged(
        h: HostEntry,
        challenge: HostKeyChallenge?,
    ) {
        if (challenge == null) challenges.remove(h.profile.id) else challenges[h.profile.id] = h.profile.host to challenge
        val first = challenges.entries.firstOrNull()
        currentChallenge.value = first?.let { HostChallenge(it.key, it.value.first, it.value.second) }
    }

    fun answerChallenge(
        h: HostEntry,
        id: Long,
        trust: Boolean,
    ) {
        if (challenges[h.profile.id]?.second?.id != id) return
        h.prompts.answer(id, trust)
        challengeChanged(h, null)
    }

    fun showAuthNotice(
        h: HostEntry,
        text: String,
    ) {
        val url =
            Regex("https://[^\\s]+")
                .find(text)
                ?.value
                ?.trimEnd('.', ',', ')')
        currentNotice.value = AuthNotice(h.profile.id, h.label, text, url)
    }

    fun clearAuthNotice(h: HostEntry) {
        if (currentNotice.value?.hostId == h.profile.id) currentNotice.value = null
    }

    fun dismissAuthNotice() {
        currentNotice.value = null
    }

    /** Host-initiated links ask first, unless allowed for this connection. */
    suspend fun openHostUrl(
        h: HostEntry,
        url: String,
    ): String? {
        if (!foreground) return "Return to DazeDev before opening a browser link."
        runCatching { BrowserUrl.validate(url) }.onFailure { return it.message }
        if (!h.allowBrowser) {
            if (browserAnswer != null) return "Another link is waiting for approval on the phone."
            val answer = CompletableDeferred<BrowserAnswer>()
            browserAnswer = answer
            currentBrowserRequest.value = BrowserRequest(ids.getAndIncrement(), h.label, url)
            val result =
                try {
                    withTimeoutOrNull(120_000) { answer.await() }
                } finally {
                    browserAnswer = null
                    currentBrowserRequest.value = null
                }
            when (result) {
                BrowserAnswer.Connection -> h.allowBrowser = true
                BrowserAnswer.Once -> Unit
                else -> return "The phone user did not open this link."
            }
        }
        return openBrowser(url).also { error -> if (error != null) message(error) }
    }

    fun answerBrowser(answer: BrowserAnswer) {
        browserAnswer?.complete(answer)
    }
}
