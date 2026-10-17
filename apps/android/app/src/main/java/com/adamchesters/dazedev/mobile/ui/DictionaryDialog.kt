package com.adamchesters.dazedev.mobile.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.adamchesters.dazedev.mobile.host.ConnectionPhase
import com.adamchesters.dazedev.mobile.voice.DictionaryVersion
import com.adamchesters.dazedev.mobile.voice.DictionaryView
import com.adamchesters.dazedev.mobile.voice.VoiceDictionary

/** Phrases the voice draft corrects, with the desktop's conflict review and optional sync through one host. */
@Composable
fun DictionaryDialog(
    view: DictionaryView,
    hosts: List<HostSummary>,
    busy: Boolean,
    error: String,
    controller: DictionaryController,
) {
    var entry by remember { mutableStateOf<String?>(null) }
    var expected by remember { mutableStateOf<String?>(null) }
    var heard by remember { mutableStateOf("") }
    var preferred by remember { mutableStateOf("") }

    fun reset() {
        entry = null
        expected = null
        heard = ""
        preferred = ""
    }

    fun edit(
        key: String,
        version: DictionaryVersion,
    ) {
        entry = key
        expected = VoiceDictionary.entryRevision(view.state.dictionary, key)
        heard = version.aliases.joinToString("\n")
        preferred = version.preferred
    }
    val dictionary = view.state.dictionary
    val ambiguous = remember(dictionary) { VoiceDictionary.ambiguous(dictionary) }
    val conflicts = remember(dictionary) { VoiceDictionary.conflicts(dictionary) }
    Dialog(onDismissRequest = controller::close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Dictation dictionary", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = controller::close) { Text("Close") }
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Preferred spellings for voice drafts on this phone. Only new dictation changes; terminal history stays as it is.", style = MaterialTheme.typography.bodySmall)
                            OutlinedTextField(heard, { heard = it.take(4800) }, label = { Text("Heard as (one phrase per line)") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(preferred, { preferred = it.take(160) }, label = { Text("Write as") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            if (heard.isNotBlank() && preferred.isNotBlank()) Text("Example: “${heard.lines().first()}” → “$preferred”", style = MaterialTheme.typography.bodySmall)
                            Text("Use full phrases, not a common word such as “road”.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { controller.save(entry, preferred, heard.lines(), expected = expected) { reset() } }, enabled = !busy && view.loaded && heard.isNotBlank() && preferred.isNotBlank()) { Text(if (entry == null) "Add phrase" else "Save changes") }
                                if (entry != null) OutlinedButton(onClick = ::reset) { Text("Cancel edit") }
                            }
                            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
                            if (conflicts > 0) Text("Conflicting entries are paused. Choose a version, review it above, then save to resolve.", style = MaterialTheme.typography.bodySmall)
                            if (ambiguous.isNotEmpty()) Text("These heard phrases have more than one preferred spelling and are not corrected: ${ambiguous.joinToString("; ")}. Edit, disable or delete the duplicates below.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    items(
                        dictionary.entries.entries
                            .filter { (_, versions) -> versions.size > 1 || !versions[0].deleted }
                            .toList(),
                        key = { it.key },
                    ) { (key, versions) ->
                        Column {
                            if (versions.size > 1) Text("Concurrent changes", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
                            versions.forEach { version ->
                                Text((if (version.deleted) "Deleted entry" else version.preferred) + if (version.disabled == true) " (disabled)" else "", style = MaterialTheme.typography.titleSmall)
                                Text(version.aliases.joinToString(" / "), style = MaterialTheme.typography.bodySmall)
                                Row(Modifier.horizontalScroll(rememberScrollState())) {
                                    val revision = VoiceDictionary.entryRevision(dictionary, key)
                                    if (versions.size == 1) {
                                        TextButton(onClick = { controller.save(key, version.preferred, version.aliases, disabled = version.disabled != true, expected = revision) }, enabled = !busy) { Text(if (version.disabled == true) "Enable" else "Disable") }
                                    }
                                    TextButton(onClick = { edit(key, version) }, enabled = !busy) { Text(if (versions.size > 1) "Choose and review" else "Edit") }
                                    TextButton(onClick = { controller.save(key, version.preferred, version.aliases, deleted = true, expected = revision) }, enabled = !busy) { Text(if (versions.size > 1) "Keep deleted" else "Delete") }
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Sync", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                            Text("Optional. Uses the host's SSH connection and its private dictionary file, the same one the desktop uses. This phone keeps an offline copy and a backup. No DazeDev cloud account.", style = MaterialTheme.typography.bodySmall)
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilterChip(selected = view.state.syncHost.isEmpty(), onClick = { controller.setHost("") }, enabled = !busy, label = { Text("This phone only") })
                                hosts.forEach { h ->
                                    FilterChip(selected = view.state.syncHost == h.profile.id, onClick = { controller.setHost(h.profile.id) }, enabled = !busy, label = { Text(h.profile.name.ifBlank { h.profile.host } + if (h.phase != ConnectionPhase.Connected) " (offline)" else "") })
                                }
                            }
                            if (view.state.syncHost.isNotEmpty()) OutlinedButton(onClick = { controller.sync() }, enabled = !busy) { Text("Check and sync") }
                            view.adoption?.let { saved ->
                                Text("Use this saved dictionary?", style = MaterialTheme.typography.titleSmall)
                                Text("${saved.entries.size} saved entries, including deletion records. This phone's entries merge with them; competing edits stay visible for review.", style = MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { controller.sync(adopt = true) }, enabled = !busy) { Text("Merge and enable sync") }
                                    OutlinedButton(onClick = { controller.setHost("") }, enabled = !busy) { Text("Keep this phone separate") }
                                }
                            }
                            Text(view.status, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
