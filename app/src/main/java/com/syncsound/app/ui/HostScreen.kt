package com.syncsound.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.media.projection.MediaProjectionManager
import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syncsound.app.model.DeviceStatus
import com.syncsound.app.model.PlaybackState
import com.syncsound.app.model.SyncTestStatus

/** Host dashboard focused on session health, approvals, and shared playback. */
@Composable
fun HostScreen(viewModel: HostViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        viewModel.selectAudio(it)
    }
    val context = LocalContext.current
    val projectionManager = context.getSystemService(MediaProjectionManager::class.java)
    val capturePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        viewModel.startSystemAudioCapture(result.resultCode, result.data)
    }
    val recordPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            capturePermission.launch(projectionManager.createScreenCaptureIntent())
        } else {
            viewModel.permissionDenied(Manifest.permission.RECORD_AUDIO)
        }
    }
    val approvedCount = state.devices.count { it.status == DeviceStatus.APPROVED }

    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        ScreenHeader("Your session", "Invite nearby phones and control the room.", onBack)
        SyncCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(state.hostName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Session code",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusPill(
                    if (state.isHosting) "Live" else "Offline",
                    if (state.isHosting) MaterialTheme.colorScheme.secondary
                    else MaterialTheme.colorScheme.error,
                )
            }
            Text(
                state.sessionCode.chunked(3).joinToString(" "),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                "Manual address  ${state.hostAddress}:45121",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionTitle("Connected devices", "$approvedCount connected")
        if (state.devices.isEmpty()) {
            EmptyState(
                "Waiting for phones",
                "Join requests will appear here.",
                Icons.Rounded.Devices,
            )
        }
        state.devices.forEach { device ->
            SyncCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(device.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            device.address,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val statusColor = when (device.status) {
                        DeviceStatus.APPROVED -> MaterialTheme.colorScheme.secondary
                        DeviceStatus.PENDING -> MaterialTheme.colorScheme.primary
                        DeviceStatus.REJECTED, DeviceStatus.DISCONNECTED ->
                            MaterialTheme.colorScheme.error
                    }
                    StatusPill(
                        device.status.name.lowercase().replaceFirstChar(Char::uppercase),
                        statusColor,
                        device.status == DeviceStatus.PENDING,
                    )
                }
                if (device.status == DeviceStatus.APPROVED) {
                    val (label, color) = when (device.syncTestStatus) {
                        SyncTestStatus.NOT_TESTED ->
                            "Sync not tested" to MaterialTheme.colorScheme.onSurfaceVariant
                        SyncTestStatus.TESTING ->
                            "Testing synchronization…" to MaterialTheme.colorScheme.primary
                        SyncTestStatus.SUCCESS ->
                            "Synchronization confirmed" to MaterialTheme.colorScheme.secondary
                        SyncTestStatus.FAILED ->
                            "Synchronization test failed" to MaterialTheme.colorScheme.error
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            when (device.syncTestStatus) {
                                SyncTestStatus.SUCCESS -> Icons.Rounded.CheckCircle
                                SyncTestStatus.FAILED -> Icons.Rounded.Error
                                else -> Icons.Rounded.HourglassTop
                            },
                            contentDescription = null,
                            tint = color,
                        )
                        Text(label, color = color, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (device.status == DeviceStatus.PENDING) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                    ) {
                        TextButton(onClick = { viewModel.reject(device.id) }) { Text("Reject") }
                        Button(
                            onClick = { viewModel.approve(device.id) },
                            shape = RoundedCornerShape(14.dp),
                        ) { Text("Approve") }
                    }
                } else if (device.status in listOf(
                        DeviceStatus.APPROVED,
                        DeviceStatus.DISCONNECTED,
                    )
                ) {
                    TextButton(onClick = { viewModel.remove(device.id) }) {
                        Text("Remove device")
                    }
                }
            }
        }
        Button(
            onClick = viewModel::testSync,
            enabled = approvedCount > 0,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
        ) {
            Icon(Icons.Rounded.GraphicEq, contentDescription = null)
            Text("  Test Sync")
        }

        SectionTitle("Now playing")
        SyncCard {
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.Audiotrack,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        state.selectedTrack ?: "Choose music to begin",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        if (state.queueSize > 0) {
                            "Track ${state.queuePosition} of ${state.queueSize}"
                        } else {
                            "MP3, WAV, AAC, FLAC or M4A"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                StatusPill(
                    state.playbackState.name.lowercase().replaceFirstChar(Char::uppercase),
                    when (state.playbackState) {
                        PlaybackState.PLAYING -> MaterialTheme.colorScheme.secondary
                        PlaybackState.BUFFERING -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    state.playbackState == PlaybackState.BUFFERING,
                )
            }
            OutlinedButton(
                onClick = {
                    picker.launch(
                        arrayOf(
                            "audio/mpeg", "audio/wav", "audio/x-wav", "audio/aac",
                            "audio/flac", "audio/mp4", "audio/x-m4a",
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                shape = RoundedCornerShape(14.dp),
            ) { Text("Choose music") }
        }
        PlaybackControls(
            enabled = state.selectedTrack != null,
            state = state.playbackState,
            onPlay = viewModel::play,
            onPause = viewModel::pause,
            onStop = viewModel::stop,
        )
        AnimatedVisibility(state.selectedTrack != null) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Rounded.VolumeUp, contentDescription = "Volume")
                    Slider(
                        value = state.volume,
                        onValueChange = viewModel::setVolume,
                        modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                    )
                    Text("${(state.volume * 100).toInt()}%")
                }
                TextButton(
                    onClick = viewModel::skip,
                    enabled = state.queueSize > 1,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = null)
                    Text(" Skip")
                }
            }
        }
        SectionTitle("System audio", "Experimental")
        SyncCard {
            Text(
                "Share audio from apps that permit Android playback capture.",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "DRM services and apps that disable capture cannot be transmitted.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(
                onClick = {
                    if (ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO,
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        capturePermission.launch(projectionManager.createScreenCaptureIntent())
                    } else {
                        recordPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                enabled = state.systemCaptureSupported && approvedCount > 0,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(
                    if (state.systemCaptureActive) "Restart system audio capture"
                    else "Share supported system audio",
                )
            }
            if (!state.systemCaptureSupported) {
                Text(
                    "Requires Android 10 or newer.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (state.systemCaptureActive) {
                TextButton(
                    onClick = viewModel::stop,
                    modifier = Modifier.align(Alignment.End),
                ) { Text("Stop sharing") }
            }
        }
        state.message?.let {
            SyncCard {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        OutlinedButton(
            onClick = {
                viewModel.disconnect()
                onBack()
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
        ) { Text("End session") }
    }
}
