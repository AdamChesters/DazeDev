package com.adamchesters.dazedev.mobile.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adamchesters.dazedev.mobile.host.ConnectionPhase
import com.adamchesters.dazedev.mobile.host.HostUpload

/** Confirms host and folder, uploads with progress and Cancel, then offers the path to the terminal. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UploadSheet(
    upload: UploadUiState,
    hosts: List<HostSummary>,
    viewModel: MainViewModel,
) {
    val context = LocalContext.current
    val connected = hosts.filter { it.phase == ConnectionPhase.Connected }
    val host = hosts.firstOrNull { it.profile.id == upload.hostId }
    val hostName = host?.profile?.let { it.name.ifBlank { it.host } } ?: "No host"
    ModalBottomSheet(onDismissRequest = viewModel::dismissUpload, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .imePadding()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                if (upload.quick) {
                    "Paste clipboard file"
                } else if (upload.files.isEmpty()) {
                    "Shared text"
                } else {
                    "Upload to a host"
                },
                style = MaterialTheme.typography.headlineSmall,
            )
            Text("Files are written on the selected host. A nested SSH session inside the terminal does not change it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (connected.isEmpty()) Text("Connect a host first. Nothing has been sent.", color = MaterialTheme.colorScheme.error)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                connected.forEach { h ->
                    FilterChip(selected = h.profile.id == upload.hostId, onClick = { viewModel.uploads.setHost(h.profile.id) }, enabled = !upload.uploading, label = { Text(h.profile.name.ifBlank { h.profile.host }, maxLines = 1) })
                }
            }
            upload.text?.let { text ->
                Text(text.take(400) + if (text.length > 400) "…" else "", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, maxLines = 8, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (upload.terminalKey != null) Button(onClick = viewModel::pasteSharedText) { Text("Paste into terminal") }
                    OutlinedButton(onClick = viewModel.uploads::textAsFile) { Text("Upload as file") }
                }
                if (upload.terminalKey != null) Text("Paste inserts the text without pressing Return.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (upload.files.isNotEmpty()) {
                upload.files.forEach { file -> Text("${file.name} · ${file.size?.let(HostUpload::bytesLabel) ?: "size unknown"}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                if (upload.quick) {
                    Text("Goes to the DazeDev uploads folder on $hostName with a unique name. Quick paste allows up to 10 MiB.", style = MaterialTheme.typography.bodySmall)
                } else {
                    OutlinedTextField(upload.folder, viewModel.uploads::setFolder, label = { Text("Destination folder") }, singleLine = true, enabled = !upload.uploading, modifier = Modifier.fillMaxWidth())
                    if (upload.files.size == 1) {
                        OutlinedTextField(upload.name, viewModel.uploads::setName, label = { Text("Destination filename") }, singleLine = true, enabled = !upload.uploading, isError = upload.name.isNotEmpty() && !HostUpload.validName(upload.name.trim()), modifier = Modifier.fillMaxWidth())
                    }
                    Text("$hostName: ${upload.capacity?.directory ?: upload.folder.trimEnd('/')}/${if (upload.files.size == 1) upload.name.ifBlank { "…" } else "…"} · Existing files are never overwritten. Maximum 256 MiB.", style = MaterialTheme.typography.bodySmall)
                }
                when {
                    upload.checking -> Text("Checking destination storage…", style = MaterialTheme.typography.bodySmall)
                    upload.storageError != null -> Text(upload.storageError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    upload.capacity != null -> Text("Available: ${HostUpload.bytesLabel(upload.capacity.availableBytes)} (${HostUpload.bytesLabel(upload.capacity.freeBytes)} free, ${HostUpload.bytesLabel(upload.capacity.reservedBytes)} reserved by other uploads). Space is checked again before sending.", style = MaterialTheme.typography.bodySmall)
                }
                val tooLarge = upload.capacity != null && upload.files.all { it.size != null } && !upload.capacity.fits(upload.totalSize)
                if (tooLarge) Text("Not enough destination storage or over the upload limit. No data will be sent.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                if (upload.uploading) {
                    LinearProgressIndicator(progress = { upload.progress }, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${upload.status} · ${(upload.progress * 100).toInt()}%", Modifier.weight(1f))
                        TextButton(onClick = viewModel.uploads::cancel) { Text("Cancel upload") }
                    }
                } else if (upload.uploaded.isEmpty()) {
                    Button(onClick = viewModel.uploads::start, enabled = upload.capacity != null && !upload.checking && !tooLarge && host?.phase == ConnectionPhase.Connected, modifier = Modifier.fillMaxWidth()) { Text(if (upload.quick) "Upload and show path" else "Upload") }
                }
            }
            upload.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (upload.uploaded.isNotEmpty()) {
                if (upload.status.isNotEmpty()) Text(upload.status)
                SelectionContainer { Text(viewModel.uploads.pathText(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (upload.terminalKey != null) Button(onClick = viewModel::pasteUploadedPath) { Text("Paste path into terminal") }
                    OutlinedButton(onClick = {
                        runCatching { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Uploaded path", viewModel.uploads.pathText())) }
                    }) { Text("Copy path") }
                }
                Text("The path is pasted for review; Return is not pressed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = viewModel::dismissUpload, enabled = !upload.uploading, modifier = Modifier.align(Alignment.End)) { Text("Close") }
        }
    }
}
