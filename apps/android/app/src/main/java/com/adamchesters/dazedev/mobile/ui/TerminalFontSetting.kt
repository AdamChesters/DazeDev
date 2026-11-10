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

/** Chooses the terminal font: the bundled Cascadia Mono or the phone's own fixed-width font. */
@Composable
fun TerminalFontSetting(
    font: String,
    onChange: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val options = listOf("cascadia" to "Cascadia Mono", "system" to "Phone default")
    TextButton(onClick = { open = true }) { Text("Terminal font: " + (options.firstOrNull { it.first == font } ?: options.first()).second) }
    if (!open) return
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("Terminal font") },
        text = {
            Column {
                options.forEach { (value, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = font == value, role = Role.RadioButton) {
                                onChange(value)
                                open = false
                            }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = font == value, onClick = null)
                        Text(label, modifier = Modifier.padding(start = 12.dp))
                    }
                }
                Text("Cascadia Mono is the font Windows Terminal uses and comes with the app. Phone default is the fixed-width font built into this phone.")
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } },
    )
}
