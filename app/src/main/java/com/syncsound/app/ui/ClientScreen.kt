package com.syncsound.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syncsound.app.model.ClientConnectionState
import com.syncsound.app.model.PlaybackState

/** Discovery and connection UI with explicit progress, health, and recovery states. */
@Composable
fun ClientScreen(viewModel: ClientViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var manualAddress by remember { mutableStateOf("") }
    var sessionCode by remember { mutableStateOf("") }
    val choosing = state.connectionState in listOf(
        ClientConnectionState.DISCOVERING,
        ClientConnectionState.IDLE,
    )

    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        ScreenHeader("Join a session", "Find a Host on your local network.", onBack)
        AnimatedContent(choosing, label = "joinState") { showDiscovery ->
            if (showDiscovery) {
                Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Nearby",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        TextButton(onClick = viewModel::refresh) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null)
                            Text(" Refresh")
                        }
                    }
                    if (state.sessions.isEmpty()) {
                        EmptyState(
                            "Searching nearby",
                            "Keep the Host screen open on another phone.",
                            Icons.Rounded.Search,
                        )
                    }
                    state.sessions.forEach { session ->
                        SyncCard {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Rounded.Router,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(session.name, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        session.address.hostAddress.orEmpty(),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Button(
                                onClick = { viewModel.join(session) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                            ) { Text("Request to join") }
                        }
                    }

                    SectionTitle("Session code")
                    OutlinedTextField(
                        value = sessionCode,
                        onValueChange = { sessionCode = it.filter(Char::isDigit).take(6) },
                        label = { Text("6-digit code") },
                        leadingIcon = { Icon(Icons.Rounded.Devices, contentDescription = null) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { viewModel.joinByCode(sessionCode) },
                        enabled = sessionCode.length == 6,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                    ) { Text("Join with code") }

                    SectionTitle("Manual connection")
                    OutlinedTextField(
                        value = manualAddress,
                        onValueChange = { manualAddress = it.trim().take(45) },
                        label = { Text("Host IP address") },
                        placeholder = { Text("192.168.1.10") },
                        leadingIcon = { Icon(Icons.Rounded.Wifi, contentDescription = null) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(
                        onClick = { viewModel.joinManual(manualAddress, sessionCode) },
                        enabled = manualAddress.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                    ) { Text("Connect manually") }
                }
            } else {
                val loading = state.connectionState in listOf(
                    ClientConnectionState.CONNECTING,
                    ClientConnectionState.WAITING_APPROVAL,
                    ClientConnectionState.RECONNECTING,
                )
                val statusLabel = when (state.connectionState) {
                    ClientConnectionState.CONNECTING -> "Connecting"
                    ClientConnectionState.WAITING_APPROVAL -> "Waiting for approval"
                    ClientConnectionState.APPROVED -> "Connected"
                    ClientConnectionState.RECONNECTING -> "Reconnecting"
                    ClientConnectionState.ERROR -> "Connection failed"
                    else -> state.connectionState.name.lowercase()
                }
                Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    SyncCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    state.hostName ?: "SyncSound Host",
                                    style = MaterialTheme.typography.headlineSmall,
                                )
                                state.connectedHost?.let {
                                    Text(
                                        it,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            StatusPill(
                                statusLabel,
                                when (state.connectionState) {
                                    ClientConnectionState.APPROVED ->
                                        MaterialTheme.colorScheme.secondary
                                    ClientConnectionState.ERROR -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.primary
                                },
                                loading,
                            )
                        }
                    }
                    SyncCard {
                        SectionTitle("Playback")
                        Text(
                            when (state.playbackState) {
                                PlaybackState.PLAYING -> "Playing with the Host"
                                PlaybackState.BUFFERING -> "Buffering synchronized audio…"
                                PlaybackState.PAUSED -> "Paused by the Host"
                                else -> "Waiting for the Host to play music"
                            },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "Connection quality: ${state.connectionQuality}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(
                        onClick = viewModel::disconnect,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                    ) { Text("Disconnect") }
                }
            }
        }
        state.message?.let {
            SyncCard { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
