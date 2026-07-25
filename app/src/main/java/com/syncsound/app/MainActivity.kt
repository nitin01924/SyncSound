package com.syncsound.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.syncsound.app.ui.SyncSoundApp
import com.syncsound.app.ui.theme.SyncSoundTheme

/**
 * Single-activity Compose entry point. Networking and playback survive screen
 * recomposition because they are owned by repository-backed ViewModels.
 */
class MainActivity : ComponentActivity() {
    private val nearbyPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val notificationsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            nearbyPermission.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
            notificationsPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val repository = (application as SyncSoundApplication).container.repository
        setContent {
            SyncSoundTheme {
                SyncSoundApp(repository)
            }
        }
    }
}
