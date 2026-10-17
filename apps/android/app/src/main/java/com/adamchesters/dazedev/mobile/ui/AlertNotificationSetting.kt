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

/** Turns completion notifications on or off; turning them on asks for notification permission on Android 13 and later. */
@Composable
fun AlertNotificationSetting(
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
    onDenied: () -> Unit,
) {
    val context = LocalContext.current
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) onChange(true) else onDenied()
        }
    TextButton(
        onClick = {
            if (enabled) {
                onChange(false)
            } else if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                onChange(true)
            }
        },
        modifier =
            Modifier.semantics {
                contentDescription =
                    if (enabled) {
                        "Completion notifications are on. The open terminal stays attached while its host stays connected in the background."
                    } else {
                        "Completion notifications are off."
                    }
            },
    ) { Text(if (enabled) "Completion notifications: on" else "Completion notifications: off") }
}
