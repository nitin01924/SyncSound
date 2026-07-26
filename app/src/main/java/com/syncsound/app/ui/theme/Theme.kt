package com.syncsound.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB9A6FF),
    onPrimary = Color(0xFF24105F),
    primaryContainer = Color(0xFF40258E),
    onPrimaryContainer = Color(0xFFE7DEFF),
    secondary = Color(0xFF71DBC8),
    onSecondary = Color(0xFF00382F),
    secondaryContainer = Color(0xFF005045),
    onSecondaryContainer = Color(0xFF95F8E3),
    background = Color(0xFF090B10),
    onBackground = Color(0xFFF7F7FC),
    surface = Color(0xFF10131A),
    onSurface = Color(0xFFF2F2FA),
    surfaceVariant = Color(0xFF1A1F2A),
    onSurfaceVariant = Color(0xFFC3C7D3),
    outline = Color(0xFF8D919D),
    outlineVariant = Color(0xFF3E434E),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF6042D0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7DEFF),
    onPrimaryContainer = Color(0xFF1C0060),
    secondary = Color(0xFF006B5D),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF9CF2DF),
    onSecondaryContainer = Color(0xFF00201B),
    background = Color(0xFFF8F7FC),
    onBackground = Color(0xFF1B1B20),
    surface = Color.White,
    onSurface = Color(0xFF1B1B20),
    surfaceVariant = Color(0xFFE7E3EE),
    onSurfaceVariant = Color(0xFF49454F),
    outline = Color(0xFF79747E),
    outlineVariant = Color(0xFFCAC4D0),
)

private val SyncTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 54.sp,
        lineHeight = 60.sp,
        letterSpacing = (-1.5).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.2.sp,
    ),
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
        typography = SyncTypography,
        content = content,
    )
}
