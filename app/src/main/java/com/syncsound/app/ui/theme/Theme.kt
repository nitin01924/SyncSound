package com.syncsound.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA991FF),
    onPrimary = Color(0xFF1D0F57),
    secondary = Color(0xFF62D9C5),
    background = Color(0xFF090B10),
    surface = Color(0xFF121620),
    surfaceVariant = Color(0xFF1B2130),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF6042D0),
    secondary = Color(0xFF006B5D),
    background = Color(0xFFF8F7FC),
    surface = Color.White,
)

/**
 * Material 3 theme with a purpose-designed dark palette and system preference.
 */
@Composable
fun SyncSoundTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = MaterialTheme.typography,
        content = content,
    )
}
