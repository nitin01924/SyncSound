package com.syncsound.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Role selection is intentionally explicit so a phone never opens a listening
 * socket or advertises itself without a user action.
 */
@Composable
fun HomeScreen(onCreateSession: () -> Unit, onJoinSession: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("SYNC", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text("Sound", style = MaterialTheme.typography.displayLarge)
            Text(
                "Turn nearby phones into one speaker.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(48.dp))
            Button(
                onClick = onCreateSession,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text("Create Session") }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onJoinSession,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text("Join Session") }
            Spacer(Modifier.height(28.dp))
            Text(
                "All phones must be on the same Wi-Fi or hotspot.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
