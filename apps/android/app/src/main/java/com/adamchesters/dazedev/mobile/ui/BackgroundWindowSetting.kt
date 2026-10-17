package com.adamchesters.dazedev.mobile.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.adamchesters.dazedev.mobile.host.BackgroundWindow

/** Chooses how long hosts stay connected after the app leaves the screen, asking for notifications on Android 13 and later. */
@Composable
fun BackgroundWindowSetting(
    window: BackgroundWindow,
    onChange: (BackgroundWindow) -> Unit,
    onNotificationsDenied: () -> Unit,
) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var asked by remember { mutableStateOf<BackgroundWindow?>(null) }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // The service runs either way; without permission its notice is hidden.
            asked?.let(onChange)
            asked = null
            if (!granted) onNotificationsDenied()
        }
    TextButton(onClick = { open = true }) { Text("Stay connected in the background: ${window.label.replaceFirstChar(Char::lowercase)}") }
    if (!open) return
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("Stay connected in the background") },
        text = {
            Column {
                BackgroundWindow.entries.forEach { option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = window == option, role = Role.RadioButton) {
                                open = false
                                val needsPermission =
                                    option.keepsConnections &&
                                        Build.VERSION.SDK_INT >= 33 &&
                                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                                if (needsPermission) {
                                    asked = option
                                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    onChange(option)
                                }
                            }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = window == option, onClick = null)
                        Text(option.label, modifier = Modifier.padding(start = 12.dp))
                    }
                }
                Text(
                    "While the time runs, a notification keeps SSH connections open and offers Disconnect all. " +
                        "When it ends, hosts disconnect; hosts that need no typed password reconnect when you return. " +
                        "Off: Android may pause connections as soon as you leave; DazeDev checks them when you return.",
                )
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } },
    )
}
