package com.adamchesters.dazedev.mobile.ui

import com.adamchesters.dazedev.mobile.host.HostRegistry
import com.adamchesters.dazedev.mobile.ssh.KnownHostKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** First-connection key checks and the saved host key manager. */
class HostKeyController(
    private val ui: UiStore,
    private val registry: HostRegistry,
    private val scope: CoroutineScope,
    private val knownHosts: (String) -> File,
) {
    fun start() {
        scope.launch {
            registry.prompts.challenge.collect { challenge ->
                ui.update {
                    it.copy(
                        hostKeyChallenge = challenge?.challenge,
                        challengeHostId = challenge?.hostId.orEmpty(),
                        challengeHost = challenge?.host.orEmpty(),
                    )
                }
            }
        }
    }

    fun answer(
        hostId: String,
        id: Long,
        trust: Boolean,
    ) = registry.answerChallenge(hostId, id, trust)

    fun openHostKeys() {
        scope.launch {
            val hosts = registry.all.toList()
            val list =
                withContext(Dispatchers.IO) {
                    hosts.flatMap { h ->
                        KnownHostKeys.list(knownHosts(h.profile.id)).map { HostKeyEntry(h.profile.id, h.label, it.type, it.fingerprint) }
                    }
                }
            ui.update { it.copy(hostKeys = list, hostKeyChange = null) }
        }
    }

    fun closeHostKeys() {
        ui.update { it.copy(hostKeys = null) }
    }

    fun dismissHostKeyChange() {
        ui.update { it.copy(hostKeyChange = null) }
    }

    fun forgetHostKey(entry: HostKeyEntry) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { KnownHostKeys.forget(knownHosts(entry.hostId), entry.fingerprint) }
                ui.message("Forgot the saved key for ${entry.label}. The next connection shows the new fingerprint to check.")
            } catch (e: Exception) {
                ui.message(e.message ?: "Could not forget this key.")
            }
            openHostKeys()
        }
    }
}
