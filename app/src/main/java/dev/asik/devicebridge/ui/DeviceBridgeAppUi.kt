package dev.asik.devicebridge.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.asik.devicebridge.BridgeRuntime
import dev.asik.devicebridge.service.BridgeForegroundService
import dev.asik.devicebridge.ui.components.LiveDot
import dev.asik.devicebridge.ui.components.SignalField
import dev.asik.devicebridge.ui.components.glass
import dev.asik.devicebridge.ui.theme.rememberGlassTokens

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.pointerInteropFilter

private data class Tab(val label: String, val icon: ImageVector)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DeviceBridgeAppUi() {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val running by BridgeRuntime.running.collectAsState()
    val g = rememberGlassTokens()

    val tabs = listOf(
        Tab("Dashboard", Icons.Default.Dashboard),
        Tab("Remote", Icons.Default.Public),
        Tab("Settings", Icons.Default.Settings),
    )

    SignalField(
        active = running,
        modifier = Modifier.pointerInteropFilter { motionEvent ->
            if (running) {
                BridgeRuntime.touchCollector.onMotionEvent(motionEvent)
            }
            false
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            // Transparent header floating over the field.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 12.dp, top = 20.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "SensIO",
                    color = g.textPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.3).sp,
                )
                Spacer(Modifier.width(10.dp))
                LiveDot(active = running, size = 9.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    if (running) "LIVE" else "IDLE",
                    color = if (running) g.live else g.textFaint,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.4.sp,
                )
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .glass(g, radius = 14.dp, strong = true)
                        .selectable(selected = running, onClick = {
                            if (running) BridgeForegroundService.stop(context)
                            else BridgeForegroundService.start(context)
                        }),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.PowerSettingsNew,
                        contentDescription = if (running) "Stop bridge" else "Start bridge",
                        tint = if (running) g.live else g.textSecondary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            // Screen content.
            Box(Modifier.fillMaxSize().weight(1f)) {
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = {
                        (fadeIn(tween(260))) togetherWith (fadeOut(tween(180)))
                    },
                    label = "tab_transition",
                ) { targetTab ->
                    when (targetTab) {
                        0 -> DashboardScreen()
                        1 -> RemoteScreen()
                        else -> SettingsScreen()
                    }
                }
            }

            GlassBottomNav(tabs = tabs, selected = tab, onSelect = { tab = it })
        }
    }
}

@Composable
private fun GlassBottomNav(
    tabs: List<Tab>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    val g = rememberGlassTokens()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .glass(g, radius = 26.dp, strong = true)
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { index, t ->
            val isSel = selected == index
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .selectable(selected = isSel, onClick = { onSelect(index) })
                    .then(
                        if (isSel) Modifier.background(g.accentStart.copy(alpha = if (g.isDark) 0.18f else 0.14f))
                        else Modifier,
                    )
                    .padding(vertical = 9.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    t.icon,
                    contentDescription = t.label,
                    tint = if (isSel) g.accentEnd else g.textFaint,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    t.label,
                    color = if (isSel) g.textPrimary else g.textFaint,
                    fontSize = 10.sp,
                    fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}
