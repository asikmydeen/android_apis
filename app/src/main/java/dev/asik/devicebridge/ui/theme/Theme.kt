package dev.asik.devicebridge.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

import dev.asik.devicebridge.util.ThemeMode

private val DeepBlue = Color(0xFF1B6EF3)
private val LightBlue = Color(0xFF4285F4)
private val DarkBg = Color(0xFF090E17)
private val DarkSurface = Color(0xFF111827)
private val DarkSurfaceVariant = Color(0xFF1F2937)

private val ExpressiveDarkColors = darkColorScheme(
    primary = LightBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1E3A8A),
    onPrimaryContainer = Color(0xFFDBEAFE),
    secondary = Color(0xFF38BDF8),
    background = DarkBg,
    onBackground = Color(0xFFF3F4F6),
    surface = DarkSurface,
    onSurface = Color(0xFFF3F4F6),
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = Color(0xFF9CA3AF),
)

private val ExpressiveLightColors = lightColorScheme(
    primary = DeepBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBEAFE),
    onPrimaryContainer = Color(0xFF1E3A8A),
    secondary = Color(0xFF0284C7),
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF0F172A),
    surface = Color.White,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFF1F5F9),
    onSurfaceVariant = Color(0xFF64748B),
)

@Composable
fun DeviceBridgeTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> ExpressiveDarkColors
        else -> ExpressiveLightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
