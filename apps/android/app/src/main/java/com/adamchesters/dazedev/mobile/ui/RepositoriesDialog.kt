package com.adamchesters.dazedev.mobile.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Repositories on one host: clone from GitHub and create worktrees, each after an explicit review. */
@Composable
fun RepositoriesDialog(
    state: RepositoryUiState,
    controller: RepositoryController,
) {
    Dialog(onDismissRequest = controller::close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Repositories", style = MaterialTheme.typography.titleLarge)
                        Text(state.hostLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = controller::refresh, enabled = state.busy == null) { Text("Refresh") }
                    TextButton(onClick = controller::close, enabled = state.busy == null) { Text("Close") }
                }
                state.busy?.let {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(it, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
                }
                if (state.error.isNotEmpty()) Text(state.error, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
                if (state.message.isNotEmpty()) Text(state.message, Modifier.padding(horizontal = 16.dp))
                val inventory = state.inventory
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentPadding =
                        androidx.compose.foundation.layout
                            .PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    inventory?.warnings?.forEach { warning -> item { Text(warning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                    if (inventory != null && inventory.repositories.isEmpty()) item { Text("No repositories found under this host's repository roots.") }
                    items(inventory?.repositories.orEmpty(), key = { it.path }) { repo ->
                        Column(Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(repo.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(listOfNotNull(repo.branch.ifEmpty { null }, if (repo.dirty) "uncommitted changes" else null).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                                    Text(repo.path, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                TextButton(onClick = { controller.startWorktree(repo) }, enabled = state.busy == null) { Text("New worktree") }
                            }
                            HorizontalDivider()
                        }
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Clone from GitHub", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                            Text("Uses the GitHub login of the GitHub CLI on this host. Existing folders are never overwritten.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                (inventory?.identities.orEmpty().ifEmpty { listOf("") }).forEach { login ->
                                    FilterChip(selected = state.identity == login, onClick = { controller.setIdentity(login) }, label = { Text(login.ifEmpty { "Host's default login" }) })
                                }
                            }
                            Button(onClick = controller::loadGithub, enabled = state.busy == null && inventory != null) { Text("Load GitHub repositories") }
                            if (state.github != null) OutlinedTextField(state.filter, controller::setFilter, label = { Text("Filter") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            if (state.github?.isEmpty() == true) Text("No repositories for this login.")
                        }
                    }
                    items(
                        state.github
                            .orEmpty()
                            .filter { it.fullName.contains(state.filter, ignoreCase = true) }
                            .take(300),
                        key = { "gh:" + it.fullName },
                    ) { repo ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(repo.fullName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (repo.description.isNotEmpty()) Text(repo.description, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            TextButton(onClick = { controller.reviewClone(repo.fullName) }, enabled = state.busy == null) { Text("Clone…") }
                        }
                    }
                }
            }
        }
    }
    state.clone?.let { review ->
        AlertDialog(
            onDismissRequest = controller::cancelClone,
            title = { Text("Clone ${review.repository}?") },
            text = { Text("On ${state.hostLabel} into\n${review.destination}\n\nGitHub login: ${review.identity.ifEmpty { "the host's default" }}. If the folder exists, nothing is changed.") },
            confirmButton = { TextButton(onClick = controller::confirmClone, enabled = state.busy == null) { Text("Clone") } },
            dismissButton = { TextButton(onClick = controller::cancelClone, enabled = state.busy == null) { Text("Cancel") } },
        )
    }
    state.worktree?.let { draft ->
        val script = draft.script
        if (script != null) {
            AlertDialog(
                onDismissRequest = controller::cancelWorktree,
                title = { Text("Run the repository's script?") },
                text = { Text("${draft.repository.name} has $script. Run it on ${state.hostLabel} to create the worktree for ${draft.branch}, or create the worktree with git directly.") },
                confirmButton = { TextButton(onClick = { controller.createWorktree(true) }, enabled = state.busy == null) { Text("Run script") } },
                dismissButton = {
                    Row {
                        TextButton(onClick = { controller.createWorktree(false) }, enabled = state.busy == null) { Text("Without script") }
                        TextButton(onClick = controller::cancelWorktree, enabled = state.busy == null) { Text("Cancel") }
                    }
                },
            )
        } else {
            AlertDialog(
                onDismissRequest = controller::cancelWorktree,
                title = { Text("New worktree for ${draft.repository.name}") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(draft.branch, controller::setBranch, label = { Text("New branch") }, singleLine = true)
                        OutlinedTextField(draft.destination, controller::setDestination, label = { Text("Worktree folder") }, singleLine = true)
                        Text(
                            "Creates the branch from the current commit in a new folder. " +
                                if (draft.repository.dirty) "The checkout at ${draft.repository.path} has uncommitted changes; they stay there untouched." else "The existing checkout is not changed.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                confirmButton = { TextButton(onClick = { controller.createWorktree() }, enabled = state.busy == null && draft.branch.isNotBlank() && draft.destination.isNotBlank()) { Text("Create worktree") } },
                dismissButton = { TextButton(onClick = controller::cancelWorktree, enabled = state.busy == null) { Text("Cancel") } },
            )
        }
    }
}
