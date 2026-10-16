package com.adamchesters.dazedev.mobile.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.adamchesters.dazedev.mobile.releases.MobileUpdate
import java.io.File

/** Download, check and install an Android update, with the release page as a fallback. */
@Composable
fun UpdateDialog(
    update: MobileUpdate,
    state: DazeDevMobileUiState,
    viewModel: MainViewModel,
) {
    val context = LocalContext.current
    val busy = state.updateProgress != null
    AlertDialog(
        onDismissRequest = { if (!busy) viewModel.dismissUpdate() },
        title = { Text("Android update available") },
        text = {
            val status =
                when {
                    state.updateError != null -> state.updateError
                    busy -> state.updateProgress
                    state.verifiedUpdate != null -> "Download checked against SHA256SUMS. Tap Install and confirm in Android."
                    else -> "DazeDev Mobile ${update.version} is available. The app downloads the APK, checks it against the release's SHA256SUMS, then asks Android to install it."
                }
            Text(
                "$status\n\n${update.notes.take(1500)}",
                color = if (state.updateError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        },
        confirmButton = {
            val verified = state.verifiedUpdate
            if (verified != null) {
                TextButton(onClick = { installApk(context, File(verified))?.let(viewModel::installFailed) ?: viewModel.installStarted() }) { Text("Install") }
            } else {
                TextButton(onClick = viewModel::downloadUpdate, enabled = !busy) { Text(if (state.updateError != null) "Try again" else "Download") }
            }
        },
        dismissButton = {
            Row {
                if (update.page.isNotEmpty()) {
                    TextButton(onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.page))) }
                            .onFailure { viewModel.installFailed("Could not open the release page.") }
                    }) { Text("Open release page") }
                }
                TextButton(onClick = viewModel::dismissUpdate, enabled = !busy) { Text("Later") }
            }
        },
    )
}

/** Hands a verified APK to the package installer. Returns a message when it cannot. */
fun installApk(
    context: Context,
    file: File,
): String? {
    if (!file.isFile) return "The checked download is gone. Download it again."
    if (!context.packageManager.canRequestPackageInstalls()) {
        runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))) }
        return "Allow DazeDev to install apps in Android settings, then tap Install again."
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
    val intent =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return try {
        context.startActivity(intent)
        null
    } catch (_: ActivityNotFoundException) {
        "No package installer is available. Use Open release page."
    }
}
