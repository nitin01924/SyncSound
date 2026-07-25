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
    private val runtimePermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val repository = (application as SyncSoundApplication).container.repository
            results.filterValues { granted -> !granted }.keys.forEach(repository::permissionDenied)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runtimePermissions.launch(
                arrayOf(
                    Manifest.permission.NEARBY_WIFI_DEVICES,
                    Manifest.permission.POST_NOTIFICATIONS,
                ),
            )
        }
        val repository = (application as SyncSoundApplication).container.repository
        setContent {
            SyncSoundTheme {
                SyncSoundApp(repository)
            }
        }
    }
}
