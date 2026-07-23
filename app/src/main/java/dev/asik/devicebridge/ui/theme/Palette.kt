package dev.asik.devicebridge.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Glass & Depth design tokens that MaterialTheme's ColorScheme has no slot for:
 * the ambient "signal field" gradient, translucent glass surfaces, and the
 * accent/state colors used by gauges and live indicators.
 *
 * Read via [rememberGlassTokens]; values branch on the active surface luminance
 * so light and dark both look right without duplicating the theme's mode logic.
 */
data class GlassTokens(
    val isDark: Boolean,
    // Ambient field
    val fieldTop: Color,
    val fieldBottom: Color,
    val bloomIndigo: Color,
    val bloomCyan: Color,
    // Glass surfaces
    val glassFill: Color,
    val glassFillStrong: Color,
    val glassBorder: Color,
    val glassHighlight: Color,
    // Accents & state
    val accentStart: Color,
    val accentEnd: Color,
    val live: Color,
    val warn: Color,
    val danger: Color,
    // Text
    val textPrimary: Color,
    val textSecondary: Color,
    val textFaint: Color,
) {
    /** Diagonal accent used for active toggles, gauges and progress bars. */
    val accentBrush: Brush get() = Brush.horizontalGradient(listOf(accentStart, accentEnd))

    /** Top-edge sheen that sells the "glass" illusion without a real blur. */
    val glassSheen: Brush
        get() = Brush.verticalGradient(
            0f to glassHighlight,
            0.35f to Color.Transparent,
        )
}

private val Indigo = Color(0xFF4F7CFF)
private val Cyan = Color(0xFF38E1FF)
private val Mint = Color(0xFF34E5A0)
private val Amber = Color(0xFFFBBF24)
private val SoftRed = Color(0xFFFB6A6A)

private val DarkTokens = GlassTokens(
    isDark = true,
    fieldTop = Color(0xFF0A0E1A),
    fieldBottom = Color(0xFF0D1424),
    bloomIndigo = Indigo.copy(alpha = 0.22f),
    bloomCyan = Cyan.copy(alpha = 0.16f),
    glassFill = Color.White.copy(alpha = 0.07f),
    glassFillStrong = Color.White.copy(alpha = 0.11f),
    glassBorder = Color.White.copy(alpha = 0.14f),
    glassHighlight = Color.White.copy(alpha = 0.10f),
    accentStart = Indigo,
    accentEnd = Cyan,
    live = Mint,
    warn = Amber,
    danger = SoftRed,
    textPrimary = Color(0xFFF3F6FF),
    textSecondary = Color(0xFFA9B4CC),
    textFaint = Color(0xFF6B7690),
)

private val LightTokens = GlassTokens(
    isDark = false,
    fieldTop = Color(0xFFEEF3FF),
    fieldBottom = Color(0xFFE3ECFB),
    bloomIndigo = Indigo.copy(alpha = 0.16f),
    bloomCyan = Cyan.copy(alpha = 0.14f),
    glassFill = Color.White.copy(alpha = 0.55f),
    glassFillStrong = Color.White.copy(alpha = 0.72f),
    glassBorder = Color.White.copy(alpha = 0.80f),
    glassHighlight = Color.White.copy(alpha = 0.65f),
    accentStart = Color(0xFF2F5EE0),
    accentEnd = Color(0xFF1E9FD8),
    live = Color(0xFF12A970),
    warn = Color(0xFFB7791F),
    danger = Color(0xFFDC4B4B),
    textPrimary = Color(0xFF0C1526),
    textSecondary = Color(0xFF44506B),
    textFaint = Color(0xFF8A94A8),
)

/**
 * Returns the glass token set matching the current theme. Branches on surface
 * luminance so it tracks dynamic color, light, and dark automatically.
 */
@Composable
fun rememberGlassTokens(): GlassTokens {
    val surface = MaterialTheme.colorScheme.surface
    val dark = surface.luminance() < 0.5f
    return remember(dark) { if (dark) DarkTokens else LightTokens }
}
