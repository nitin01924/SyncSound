package com.syncsound.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syncsound.app.model.DeviceStatus

/**
 * Host control surface: address, approval queue, track selection, and transport.
 */
@Composable
fun HostScreen(viewModel: HostViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::selectAudio)
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        ScreenHeader("Hosting", "Invite phones on this network.", onBack)
        SyncCard {
            Text("Manual join address", style = MaterialTheme.typography.labelMedium)
            Text(state.hostAddress, style = MaterialTheme.typography.headlineMedium)
            Text("Port 45121", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Text("Devices (${state.devices.count { it.status == DeviceStatus.APPROVED }})",
            style = MaterialTheme.typography.titleLarge)
        if (state.devices.isEmpty()) {
            Text("Waiting for join requests…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.devices.forEach { device ->
            SyncCard {
                Text(device.name, style = MaterialTheme.typography.titleMedium)
                Text("${device.address} · ${device.status.name.lowercase()}")
                if (device.status == DeviceStatus.PENDING) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { viewModel.reject(device.id) }) { Text("Reject") }
                        Button(onClick = { viewModel.approve(device.id) }) { Text("Approve") }
                    }
                }
            }
        }

        Text("Audio", style = MaterialTheme.typography.titleLarge)
        SyncCard {
            Text(state.selectedTrack ?: "No MP3 selected")
            OutlinedButton(
                onClick = { picker.launch(arrayOf("audio/mpeg", "audio/mp3")) },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) { Text("Select MP3") }
        }
        PlaybackControls(
            enabled = state.selectedTrack != null,
            onPlay = viewModel::play,
            onPause = viewModel::pause,
            onStop = viewModel::stop,
        )
        Text("Status: ${state.playbackState.name.lowercase()}")
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    }
}
