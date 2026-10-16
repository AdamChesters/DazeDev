package com.adamchesters.dazedev.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun ReleaseHistoryDialog(
    state: DazeDevMobileUiState,
    viewModel: MainViewModel,
    onClose: () -> Unit,
) {
    var filter by remember { mutableStateOf("all") }
    LaunchedEffect(Unit) { viewModel.refreshReleaseHistory() }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Changelog", style = MaterialTheme.typography.headlineSmall)
                    TextButton(onClick = onClose) { Text("Close") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("all", "desktop", "mobile").forEach { platform -> FilterChip(selected = filter == platform, onClick = { filter = platform }, label = { Text(platform.replaceFirstChar { it.uppercase() }) }) }
                }
                if (state.releaseHistoryStatus.isNotBlank()) Text(state.releaseHistoryStatus, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { viewModel.checkUpdates() }, enabled = !state.checkingUpdates, modifier = Modifier.fillMaxWidth()) { Text(if (state.checkingUpdates) "Checking…" else "Check public Android releases") }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(state.releaseHistory.filter { filter == "all" || it.platform == filter }, key = { "${it.platform}:${it.version}" }) { entry ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("${entry.platform.replaceFirstChar {it.uppercase()}} · ${entry.version}", style = MaterialTheme.typography.titleMedium)
                            Text(entry.date + if (entry.channel == "development") " · Development build" else "", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(entry.notes, style = MaterialTheme.typography.bodyMedium)
                            HorizontalDivider(Modifier.padding(top = 12.dp))
                        }
                    }
                }
            }
        }
    }
}
