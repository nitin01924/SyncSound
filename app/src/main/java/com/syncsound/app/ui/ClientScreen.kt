package com.syncsound.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syncsound.app.model.ClientConnectionState

/**
 * Discovery list and manual-IP fallback with clear approval/playback states.
 */
@Composable
fun ClientScreen(viewModel: ClientViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var manualAddress by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        ScreenHeader("Join a session", "Choose a Host on your local network.", onBack)

        if (state.connectionState == ClientConnectionState.DISCOVERING ||
            state.connectionState == ClientConnectionState.IDLE
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Nearby", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = viewModel::refresh) { Text("Refresh") }
            }
            if (state.sessions.isEmpty()) {
                Text("Searching for sessions…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.sessions.forEach { session ->
                SyncCard {
                    Text(session.name, style = MaterialTheme.typography.titleMedium)
                    Text(session.address.hostAddress.orEmpty())
                    Button(
                        onClick = { viewModel.join(session) },
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    ) { Text("Request to join") }
                }
            }

            Text("Manual connection", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = manualAddress,
                onValueChange = { manualAddress = it },
                label = { Text("Host IP address") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { viewModel.joinManual(manualAddress) },
                enabled = manualAddress.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Connect") }
        } else {
            SyncCard {
                Text(
                    when (state.connectionState) {
                        ClientConnectionState.CONNECTING -> "Connecting…"
                        ClientConnectionState.WAITING_APPROVAL -> "Waiting for Host approval"
                        ClientConnectionState.APPROVED -> "Connected"
                        ClientConnectionState.ERROR -> "Connection failed"
                        else -> state.connectionState.name
                    },
                    style = MaterialTheme.typography.headlineSmall,
                )
                state.connectedHost?.let { Text("Host: $it") }
                Text("Playback: ${state.playbackState.name.lowercase()}")
            }
        }
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
