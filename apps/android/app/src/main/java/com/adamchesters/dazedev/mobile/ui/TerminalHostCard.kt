package com.adamchesters.dazedev.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adamchesters.dazedev.mobile.host.ConnectionPhase
import com.adamchesters.dazedev.mobile.host.TerminalRow

@Composable
fun TerminalHostCard(
    state: DazeDevMobileUiState,
    row: TerminalRow,
    onChoose: () -> Unit,
    workspace: String = "",
) {
    val target = state.destination(row)
    val colors = MaterialTheme.colorScheme
    OutlinedButton(
        onClick = onChoose,
        enabled = target.host?.phase != ConnectionPhase.Connecting,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        contentPadding = PaddingValues(12.dp),
        shape = RoundedCornerShape(3.dp),
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (target.otherHost) {
                Surface(color = colors.secondaryContainer, contentColor = colors.onSecondaryContainer, shape = RoundedCornerShape(3.dp)) {
                    Text(target.label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Text(row.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, color = colors.onSurface)
            target.host
                ?.profile
                ?.id
                ?.let { state.alerts[TerminalAlertCenter.key(it, row.sessionId)] }
                ?.let { alert ->
                    Surface(color = colors.tertiaryContainer, contentColor = colors.onTertiaryContainer, shape = RoundedCornerShape(3.dp)) {
                        Text("● $alert", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            if (workspace.isNotBlank()) Text(workspace, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(target.status, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                Text(target.action, style = MaterialTheme.typography.labelLarge, color = colors.primary)
            }
        }
    }
}
