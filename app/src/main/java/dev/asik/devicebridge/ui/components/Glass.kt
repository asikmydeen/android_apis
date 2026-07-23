package dev.asik.devicebridge.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.asik.devicebridge.ui.theme.GlassTokens
import dev.asik.devicebridge.ui.theme.rememberGlassTokens

/* ------------------------------------------------------------------ *
 *  Signal Field — the state-reactive ambient background.
 *  When [active] the aurora blooms breathe in accent color; when idle
 *  they fade to grey. The background itself is the "is it alive?" cue.
 * ------------------------------------------------------------------ */

@Composable
fun SignalField(
    active: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) = SignalFieldImpl(active, modifier, content)

// Re-export BoxScope so callers don't need the foundation import.
typealias BoxScope = androidx.compose.foundation.layout.BoxScope

@Composable
private fun SignalFieldImpl(
    active: Boolean,
    modifier: Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val g = rememberGlassTokens()
    val transition = rememberInfiniteTransition(label = "field")
    val breathe by transition.animateFloat(
        initialValue = 0.75f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3200), RepeatMode.Reverse),
        label = "breathe",
    )
    val idleGrey = if (g.isDark) Color(0xFF243044).copy(alpha = 0.5f) else Color(0xFFC2CBDC)
    val indigo by animateColorAsState(
        if (active) g.bloomIndigo else idleGrey, tween(700), label = "indigo",
    )
    val cyan by animateColorAsState(
        if (active) g.bloomCyan else idleGrey.copy(alpha = 0.35f), tween(700), label = "cyan",
    )
    val intensity = if (active) breathe else 0.6f

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(g.fieldTop, g.fieldBottom)))
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(indigo.copy(alpha = indigo.alpha * intensity), Color.Transparent),
                        center = Offset(size.width * 0.15f, size.height * 0.12f),
                        radius = size.maxDimension * 0.85f,
                    ),
                )
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(cyan.copy(alpha = cyan.alpha * intensity), Color.Transparent),
                        center = Offset(size.width * 0.9f, size.height * 0.85f),
                        radius = size.maxDimension * 0.75f,
                    ),
                )
            },
        content = content,
    )
}

/* ------------------------------------------------------------------ *
 *  Glass surface — alpha fill + hairline border + top sheen + shadow.
 *  A frosted look without Modifier.blur (which is API 31+, minSdk 26).
 * ------------------------------------------------------------------ */

fun Modifier.glass(
    tokens: GlassTokens,
    radius: Dp = 24.dp,
    strong: Boolean = false,
): Modifier = this
    .clip(RoundedCornerShape(radius))
    .background(if (strong) tokens.glassFillStrong else tokens.glassFill)
    .background(tokens.glassSheen)
    .border(1.dp, tokens.glassBorder, RoundedCornerShape(radius))

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    radius: Dp = 24.dp,
    strong: Boolean = false,
    contentPadding: Dp = 18.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val g = rememberGlassTokens()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .glass(g, radius, strong)
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/**
 * A glass card whose body collapses behind a tappable header. Used to tuck
 * reference/diagnostic content out of the way — visible only on demand.
 */
@Composable
fun CollapsibleGlassCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val g = rememberGlassTokens()
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, tween(220), label = "chevron")

    Column(
        modifier = modifier
            .fillMaxWidth()
            .glass(g, radius = 24.dp)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                SectionLabel(title)
                subtitle?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(it, color = g.textSecondary, fontSize = 12.sp)
                }
            }
            Icon(
                Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = g.textFaint,
                modifier = Modifier.size(22.dp).rotate(rotation),
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}

/* ------------------------------------------------------------------ *
 *  Small building blocks
 * ------------------------------------------------------------------ */

/** Uppercase, wide-tracked micro label — the structural voice of the UI. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    val g = rememberGlassTokens()
    Text(
        text.uppercase(),
        modifier = modifier,
        color = g.textFaint,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.6.sp,
    )
}

/** Pulsing status dot. Mint when live, amber when idle. */
@Composable
fun LiveDot(active: Boolean, modifier: Modifier = Modifier, size: Dp = 10.dp) {
    val g = rememberGlassTokens()
    val transition = rememberInfiniteTransition(label = "dot")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse),
        label = "pulse",
    )
    val color = if (active) g.live else g.warn
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color.copy(alpha = if (active) pulse else 0.6f)),
    )
}

/** A large headline metric with a small caption below it. */
@Composable
fun MetricValue(
    value: String,
    caption: String,
    modifier: Modifier = Modifier,
    valueColor: Color? = null,
) {
    val g = rememberGlassTokens()
    Column(modifier = modifier) {
        Text(
            value,
            color = valueColor ?: g.textPrimary,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.5).sp,
        )
        Spacer(Modifier.height(2.dp))
        SectionLabel(caption)
    }
}

/** Gradient-filled progress bar (battery, sensor ranges, levels). */
@Composable
fun GradientBar(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 10.dp,
    trackAlpha: Float = 0.12f,
) {
    val g = rememberGlassTokens()
    val p = progress.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .height(height)
            .clip(CircleShape)
            .background(g.textSecondary.copy(alpha = trackAlpha)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(p)
                .height(height)
                .clip(CircleShape)
                .background(g.accentBrush),
        )
    }
}

/** Thin animated equalizer bars — a live "signal present" flourish. */
@Composable
fun SignalBars(active: Boolean, modifier: Modifier = Modifier, bars: Int = 5, barHeight: Dp = 20.dp) {
    val g = rememberGlassTokens()
    val transition = rememberInfiniteTransition(label = "eq")
    Row(
        modifier = modifier.height(barHeight),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        repeat(bars) { i ->
            val h by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    tween(500 + i * 120), RepeatMode.Reverse,
                ),
                label = "bar$i",
            )
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight(if (active) h else 0.2f)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (active) g.accentBrush else SolidFaint(g.textFaint)),
            )
        }
    }
}

private fun SolidFaint(c: Color): Brush = Brush.verticalGradient(listOf(c, c))

/** Circular ring gauge (battery / signal strength). Drawn on Canvas. */
@Composable
fun RingGauge(
    progress: Float,
    modifier: Modifier = Modifier,
    diameter: Dp = 96.dp,
    stroke: Dp = 9.dp,
    centerLabel: String,
    centerCaption: String,
) {
    val g = rememberGlassTokens()
    val p = progress.coerceIn(0f, 1f)
    Box(modifier = modifier.size(diameter), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.size(diameter)) {
            val sw = stroke.toPx()
            val inset = sw / 2
            val arcSize = androidx.compose.ui.geometry.Size(size.width - sw, size.height - sw)
            val topLeft = Offset(inset, inset)
            drawArc(
                color = g.textSecondary.copy(alpha = 0.14f),
                startAngle = 0f, sweepAngle = 360f, useCenter = false,
                topLeft = topLeft, size = arcSize,
                style = Stroke(width = sw, cap = StrokeCap.Round),
            )
            drawArc(
                brush = Brush.sweepGradient(listOf(g.accentStart, g.accentEnd, g.accentStart)),
                startAngle = -90f, sweepAngle = 360f * p, useCenter = false,
                topLeft = topLeft, size = arcSize,
                style = Stroke(width = sw, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(centerLabel, color = g.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(centerCaption.uppercase(), color = g.textFaint, fontSize = 9.sp, letterSpacing = 1.4.sp)
        }
    }
}

/** Monospace machine string (URLs, tokens, coords) in muted small text. */
@Composable
fun MonoText(text: String, modifier: Modifier = Modifier, color: Color? = null) {
    val g = rememberGlassTokens()
    Text(
        text,
        modifier = modifier,
        color = color ?: g.textSecondary,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 18.sp,
    )
}

/** Body copy in the glass palette. */
@Composable
fun GlassText(
    text: String,
    modifier: Modifier = Modifier,
    secondary: Boolean = false,
    weight: FontWeight = FontWeight.Normal,
    size: androidx.compose.ui.unit.TextUnit = 14.sp,
) {
    val g = rememberGlassTokens()
    Text(
        text,
        modifier = modifier,
        color = if (secondary) g.textSecondary else g.textPrimary,
        fontSize = size,
        fontWeight = weight,
        lineHeight = (size.value * 1.4f).sp,
    )
}
