package dev.asik.devicebridge.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Blue = Color(0xFF1B6EF3)
private val DarkBg = Color(0xFF0B1220)

private val DarkColors = darkColorScheme(
    primary = Blue,
    background = DarkBg,
    surface = Color(0xFF121A2B),
    onPrimary = Color.White,
    onBackground = Color(0xFFE8EEF9),
    onSurface = Color(0xFFE8EEF9),
)

private val LightColors = lightColorScheme(
    primary = Blue,
    background = Color(0xFFF5F7FB),
    surface = Color.White,
    onPrimary = Color.White,
)

@Composable
fun DeviceBridgeTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content,
    )
}
