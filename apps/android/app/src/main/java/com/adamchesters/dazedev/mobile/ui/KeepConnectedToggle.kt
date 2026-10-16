package com.adamchesters.dazedev.mobile.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.core.content.ContextCompat

/** Turns "Keep hosts connected in the background" on or off, asking for notifications on Android 13 and later. */
@Composable
fun KeepConnectedToggle(
    on: Boolean,
    onChange: (Boolean) -> Unit,
    onNotificationsDenied: () -> Unit,
) {
    val context = LocalContext.current
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // The service runs either way; without permission its notice is hidden.
            onChange(true)
            if (!granted) onNotificationsDenied()
        }
    TextButton(
        onClick = {
            val needsPermission =
                Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            when {
                on -> onChange(false)
                needsPermission -> permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else -> onChange(true)
            }
        },
        modifier =
            Modifier.semantics {
                contentDescription =
                    if (on) {
                        "Keep hosts connected in the background is on. A notification shows while hosts are connected."
                    } else {
                        "Keep hosts connected in the background is off."
                    }
            },
    ) { Text(if (on) "Keep hosts connected in the background: on" else "Keep hosts connected in the background: off") }
}
