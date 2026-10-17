package com.adamchesters.dazedev.mobile.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.adamchesters.dazedev.mobile.host.BroadcastScope

/** Compose, choose the scope, review the frozen recipient list, then send once. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BroadcastSheet(
    state: BroadcastUiState,
    controller: BroadcastController,
) {
    ModalBottomSheet(onDismissRequest = controller::close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .imePadding()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Broadcast", style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(
                state.text,
                controller::setText,
                label = { Text("Message") },
                minLines = 2,
                maxLines = 6,
                enabled = !state.busy && state.result.isEmpty(),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BroadcastScope.entries.forEach { option ->
                    FilterChip(selected = state.scope == option, onClick = { controller.setScope(option) }, enabled = !state.busy && state.result.isEmpty(), label = { Text(option.label) })
                }
            }
            Text("Each running session receives the text once, followed by Return. Disconnected hosts and ended sessions are skipped. Nothing is retried.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.notice.isNotEmpty()) Text(state.notice, color = MaterialTheme.colorScheme.error)
            val snapshot = state.snapshot
            if (state.result.isNotEmpty()) {
                Text(state.result)
            } else if (snapshot == null) {
                Button(onClick = controller::review, enabled = !state.busy && state.text.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Review recipients") }
            } else {
                Text("${snapshot.recipients.size} recipients", style = MaterialTheme.typography.titleMedium)
                snapshot.recipients.forEach { Text("• ${it.label}", style = MaterialTheme.typography.bodyMedium) }
                if (snapshot.skipped.isNotEmpty()) Text("Skipped (offline or ended): ${snapshot.skipped.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
                if (snapshot.mirrors > 0) Text("${snapshot.mirrors} views of the same session are sent once.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = controller::send, enabled = !state.busy && snapshot.recipients.isNotEmpty()) { Text("Send to ${snapshot.recipients.size}") }
                    OutlinedButton(onClick = controller::review, enabled = !state.busy) { Text("Review again") }
                }
            }
            TextButton(onClick = controller::close, enabled = !state.busy, modifier = Modifier.align(Alignment.End)) { Text("Close") }
        }
    }
}
