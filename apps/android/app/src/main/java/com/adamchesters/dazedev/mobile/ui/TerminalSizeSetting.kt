package com.adamchesters.dazedev.mobile.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Chooses whether this phone's terminal size or the other devices' size wins in a shared tmux session. */
@Composable
fun TerminalSizeSetting(
    keepOtherSize: Boolean,
    onChange: (Boolean) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text(if (keepOtherSize) "Terminal size: keep the other devices' size" else "Terminal size: follow this phone") }
    if (!open) return
    val options = listOf(false to "Follow this phone (other devices resize)", true to "Keep the other devices' size")
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("Terminal size") },
        text = {
            Column {
                options.forEach { (value, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = keepOtherSize == value, role = Role.RadioButton) {
                                onChange(value)
                                open = false
                            }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = keepOtherSize == value, onClick = null)
                        Text(label, modifier = Modifier.padding(start = 12.dp))
                    }
                }
                Text("Keeping the other devices' size needs tmux 3.2 or newer on the host. It applies the next time a terminal opens.")
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } },
    )
}
