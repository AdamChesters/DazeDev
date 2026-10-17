package com.adamchesters.dazedev.mobile.ui

import com.adamchesters.dazedev.mobile.host.ConnectionPhase
import com.adamchesters.dazedev.mobile.host.GithubRepository
import com.adamchesters.dazedev.mobile.host.HostEntry
import com.adamchesters.dazedev.mobile.host.HostRegistry
import com.adamchesters.dazedev.mobile.host.Repositories
import com.adamchesters.dazedev.mobile.host.RepositoryInfo
import com.adamchesters.dazedev.mobile.host.RepositoryInventory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

/** A clone waiting for confirmation: repository, destination folder and GitHub login. */
data class CloneReview(
    val repository: String,
    val root: String,
    val destination: String,
    val identity: String,
)

/** A new worktree being prepared for [repository]; [script] is set when the host asks to run its worktree script. */
data class WorktreeDraft(
    val repository: RepositoryInfo,
    val branch: String = "",
    val destination: String = "",
    val customFolder: Boolean = false,
    val script: String? = null,
)

data class RepositoryUiState(
    val hostId: String? = null,
    val hostLabel: String = "",
    val inventory: RepositoryInventory? = null,
    val loading: Boolean = false,
    val identity: String = "",
    val github: List<GithubRepository>? = null,
    val filter: String = "",
    val clone: CloneReview? = null,
    val worktree: WorktreeDraft? = null,
    val busy: String? = null,
    val message: String = "",
    val error: String = "",
) {
    val open get() = hostId != null
}

/** Lists a host's repositories, clones from GitHub through the host's own gh login, and creates worktrees. */
class RepositoryController(
    private val registry: HostRegistry,
    private val scope: CoroutineScope,
) {
    private val repos = MutableStateFlow(RepositoryUiState())
    val state: StateFlow<RepositoryUiState> = repos.asStateFlow()

    private fun host(): HostEntry? = registry.entry(repos.value.hostId)?.takeIf { it.phase == ConnectionPhase.Connected }

    fun open(hostId: String) {
        val h = registry.entry(hostId) ?: return
        repos.value = RepositoryUiState(hostId = hostId, hostLabel = h.label)
        refresh()
    }

    fun close() {
        if (repos.value.busy == null) repos.value = RepositoryUiState()
    }

    private fun work(
        label: String,
        block: suspend (HostEntry) -> Unit,
    ) {
        if (repos.value.busy != null) return
        val h = host() ?: return repos.update { it.copy(error = "Connect ${it.hostLabel} first.") }
        repos.update { it.copy(busy = label, error = "", message = "") }
        scope.launch {
            try {
                block(h)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                repos.update { it.copy(error = e.message ?: "The host could not finish this.") }
            } finally {
                repos.update { it.copy(busy = null) }
            }
        }
    }

    fun refresh() {
        work("Listing repositories…") { h ->
            repos.update { it.copy(loading = true) }
            try {
                val inventory = Repositories.inventory(h.link.request("inventory", JSONObject(), 40_000) as JSONObject)
                repos.update { it.copy(inventory = inventory, identity = it.identity.ifEmpty { inventory.activeIdentity }) }
                registry.refresh(h)
            } finally {
                repos.update { it.copy(loading = false) }
            }
        }
    }

    fun setIdentity(login: String) {
        repos.update { it.copy(identity = login, github = null) }
    }

    fun setFilter(text: String) {
        repos.update { it.copy(filter = text) }
    }

    fun loadGithub() =
        work("Loading GitHub repositories…") { h ->
            val list = Repositories.github(h.link.request("github_repos", JSONObject().put("identity", repos.value.identity), 40_000))
            repos.update { it.copy(github = list) }
        }

    /** Shows the clone destination for review; nothing is cloned until [confirmClone]. */
    fun reviewClone(repository: String) {
        val h = host() ?: return
        val root =
            h.shared
                ?.document
                ?.optJSONObject("preferences")
                ?.optJSONArray("roots")
                ?.optString(0)
                .orEmpty()
        if (root.isBlank()) return repos.update { it.copy(error = "Set a repository root for ${h.label} on the desktop first. Nothing was cloned.") }
        repos.update { it.copy(clone = CloneReview(repository, root, Repositories.cloneDestination(root, repository), it.identity)) }
    }

    fun cancelClone() {
        if (repos.value.busy == null) repos.update { it.copy(clone = null) }
    }

    fun confirmClone() {
        val review = repos.value.clone ?: return
        work("Cloning ${review.repository}… This can take a few minutes.") { h ->
            val result = Repositories.info(h.link.request("clone", Repositories.cloneArgs(review.repository, review.root, review.identity), 250_000) as JSONObject)
            repos.update { it.copy(clone = null, message = "Cloned into ${result.path} on ${h.label}.") }
            refreshQuietly(h)
        }
    }

    fun startWorktree(repository: RepositoryInfo) {
        repos.update { it.copy(worktree = WorktreeDraft(repository)) }
    }

    /** The suggested folder follows the branch until the user edits the folder. */
    fun setBranch(branch: String) {
        val draft = repos.value.worktree ?: return
        if (repos.value.busy != null) return
        repos.update { it.copy(worktree = draft.copy(branch = branch, destination = if (draft.customFolder) draft.destination else Repositories.worktreeDestination(draft.repository.path, branch), script = null)) }
    }

    fun setDestination(destination: String) {
        val draft = repos.value.worktree ?: return
        if (repos.value.busy != null) return
        repos.update { it.copy(worktree = draft.copy(destination = destination, customFolder = true, script = null)) }
    }

    fun cancelWorktree() {
        if (repos.value.busy == null) repos.update { it.copy(worktree = null) }
    }

    /** Creates the worktree; [runScript] answers the host's question about the repository's own script. */
    fun createWorktree(runScript: Boolean? = null) {
        val draft = repos.value.worktree ?: return
        val args =
            try {
                Repositories.worktreeArgs(draft.repository.path, draft.branch.trim(), draft.destination.trim(), "", runScript)
            } catch (e: IllegalArgumentException) {
                return repos.update { it.copy(error = e.message.orEmpty()) }
            }
        work("Creating worktree ${draft.branch}…") { h ->
            val result = h.link.request("worktree", args, 200_000) as JSONObject
            if (result.optBoolean("needsConfirmation") && runScript == null) {
                repos.update { it.copy(worktree = draft.copy(script = result.optString("script").ifEmpty { "scripts/new-worktree.sh" })) }
                return@work
            }
            repos.update { it.copy(worktree = null, message = "Created branch ${draft.branch}. The original checkout was not changed.") }
            refreshQuietly(h)
        }
    }

    private suspend fun refreshQuietly(h: HostEntry) {
        runCatching {
            val inventory = Repositories.inventory(h.link.request("inventory", JSONObject(), 40_000) as JSONObject)
            repos.update { it.copy(inventory = inventory) }
            registry.refresh(h)
        }
    }
}
