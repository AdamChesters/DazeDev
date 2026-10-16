package com.adamchesters.dazedev.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adamchesters.dazedev.mobile.data.AuthMethod
import com.adamchesters.dazedev.mobile.data.ConnectionProfile

@Composable
fun ConnectionScreen(
    uiState: DazeDevMobileUiState,
    contentPadding: PaddingValues,
    onPickPrivateKey: () -> Unit,
    onConnect: (ConnectionProfile, String) -> Unit,
    onCancel: () -> Unit,
) {
    val savedProfile = uiState.profile
    var host by rememberSaveable(savedProfile.id) { mutableStateOf(savedProfile.host) }
    var socket by rememberSaveable(savedProfile.id) { mutableStateOf(savedProfile.socket) }
    var hostName by rememberSaveable(savedProfile.id) { mutableStateOf(savedProfile.name) }
    var port by rememberSaveable(savedProfile.id) { mutableStateOf(savedProfile.port.toString()) }
    var username by rememberSaveable(savedProfile.id) { mutableStateOf(savedProfile.username) }
    var authMethod by rememberSaveable(savedProfile.id) { mutableStateOf(savedProfile.authMethod) }
    var secret by remember(savedProfile.id) { mutableStateOf("") }
    var formError by remember { mutableStateOf<String?>(null) }
    val isConnecting = uiState.connectionPhase == ConnectionPhase.Connecting

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            modifier = Modifier.size(64.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(18.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Rounded.Terminal,
                    contentDescription = null,
                    modifier = Modifier.size(34.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }

        Spacer(Modifier.height(18.dp))
        Text(
            text = "DazeDev Mobile",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = "Speak to a remote tmux session",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        uiState.linkError?.let { reason ->
            Spacer(Modifier.height(16.dp))
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Disconnected", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                    Text("$reason Connect again to reconnect.", color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
        uiState.pendingTerminalConnection?.let { pending ->
            Spacer(Modifier.height(16.dp))
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Terminal on another host", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    Text(pending.row.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    Text(if (savedProfile.host.isBlank()) "Its SSH address is not saved in this workspace. Enter the address and choose how to sign in." else "Connect to ${savedProfile.username}@${savedProfile.host}. The saved terminal opens after its host identity is verified.", color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        }
        Spacer(Modifier.height(28.dp))
        Card(
            modifier =
                Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = "SSH connection",
                    style = MaterialTheme.typography.titleLarge,
                )

                OutlinedTextField(value = hostName, onValueChange = { hostName = it }, label = { Text("Host name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = socket, onValueChange = { socket = it }, label = { Text("tmux socket (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("SSH host") },
                    supportingText = { Text("Hostname or IP address, for example ssh.example.com") },
                    isError = formError == "Enter a hostname or IP address.",
                    singleLine = true,
                    enabled = !isConnecting,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Username") },
                        singleLine = true,
                        enabled = !isConnecting,
                        isError = formError == "Enter the SSH username.",
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it },
                        label = { Text("Port") },
                        singleLine = true,
                        enabled = !isConnecting,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = formError == "Enter a valid SSH port.",
                        modifier = Modifier.weight(0.55f),
                    )
                }

                Text(
                    text = "Sign in with",
                    style = MaterialTheme.typography.labelLarge,
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = authMethod == AuthMethod.TailscaleSsh,
                        onClick = {
                            authMethod = AuthMethod.TailscaleSsh
                            secret = ""
                            formError = null
                        },
                        label = { Text("Tailscale") },
                        leadingIcon = {
                            Icon(Icons.Rounded.Shield, contentDescription = null)
                        },
                        enabled = !isConnecting,
                    )
                    FilterChip(
                        selected = authMethod == AuthMethod.PrivateKey,
                        onClick = {
                            authMethod = AuthMethod.PrivateKey
                            secret = ""
                            formError = null
                        },
                        label = { Text("Private key") },
                        leadingIcon = {
                            Icon(Icons.Rounded.Key, contentDescription = null)
                        },
                        enabled = !isConnecting,
                    )
                    FilterChip(
                        selected = authMethod == AuthMethod.Password,
                        onClick = {
                            authMethod = AuthMethod.Password
                            secret = ""
                            formError = null
                        },
                        label = { Text("Password") },
                        leadingIcon = {
                            Icon(Icons.Rounded.Lock, contentDescription = null)
                        },
                        enabled = !isConnecting,
                    )
                }

                if (authMethod == AuthMethod.TailscaleSsh) {
                    Text(
                        text = "Uses the Tailscale identity active on this phone. The destination must have Tailscale SSH enabled. Native Windows cannot serve Tailscale SSH: use the WSL/Linux device's own tailnet name and Linux username. DazeDev remote hosts need Python 3 and tmux. Ordinary SSH over Tailscale uses separate SSH credentials.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (authMethod == AuthMethod.PrivateKey) {
                    OutlinedButton(
                        onClick = onPickPrivateKey,
                        enabled = !isConnecting,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                    ) {
                        Icon(Icons.Rounded.Key, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text(
                            text = savedProfile.privateKeyName ?: "Choose private key file",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                if (authMethod != AuthMethod.TailscaleSsh) {
                    OutlinedTextField(
                        value = secret,
                        onValueChange = { secret = it },
                        label = {
                            Text(
                                if (authMethod == AuthMethod.PrivateKey) {
                                    "Key passphrase, if used"
                                } else {
                                    "SSH password"
                                },
                            )
                        },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        enabled = !isConnecting,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                formError?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Button(
                    onClick = {
                        val candidate =
                            savedProfile.copy(
                                host = host,
                                name = hostName,
                                socket = socket,
                                port = port.toIntOrNull() ?: 0,
                                username = username,
                                authMethod = authMethod,
                            )
                        formError =
                            candidate
                                .copy(
                                    host = candidate.host.trim(),
                                    username = candidate.username.trim(),
                                ).validationError(secret)
                        if (formError == null) {
                            onConnect(candidate, secret)
                            secret = ""
                        }
                    },
                    enabled = !isConnecting,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(54.dp),
                ) {
                    if (isConnecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.size(10.dp))
                        Text("Connecting")
                    } else {
                        Text("Connect", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        Text(
            text = "Connect over a reachable LAN, VPN, or internet connection. DazeDev Mobile verifies SSH host keys and never saves passwords or passphrases. Keys stay in the selected document provider; its storage and sync policy and your key passphrase protect them.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
                Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxWidth(),
        )
        if (isConnecting) OutlinedButton(onClick = onCancel) { Text("Cancel connection") }
    }
}
