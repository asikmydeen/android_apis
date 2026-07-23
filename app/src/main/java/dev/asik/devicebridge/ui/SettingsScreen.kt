package dev.asik.devicebridge.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.asik.devicebridge.BridgeRuntime
import dev.asik.devicebridge.ui.components.CollapsibleGlassCard
import dev.asik.devicebridge.ui.components.GlassCard
import dev.asik.devicebridge.ui.components.GlassText
import dev.asik.devicebridge.ui.components.SectionLabel
import dev.asik.devicebridge.ui.components.glass
import dev.asik.devicebridge.ui.theme.rememberGlassTokens
import dev.asik.devicebridge.util.BridgePrefs
import dev.asik.devicebridge.util.NetworkMode
import dev.asik.devicebridge.util.ThemeMode
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val g = rememberGlassTokens()
    val running by BridgeRuntime.running.collectAsState()

    var currentTheme by remember { mutableStateOf(BridgePrefs.themeMode(context)) }
    var keepAwake by remember { mutableStateOf(BridgePrefs.keepAwake(context)) }
    var startOnBoot by remember { mutableStateOf(BridgePrefs.startOnBoot(context)) }
    var streamLoc by remember { mutableStateOf(BridgePrefs.streamLocation(context)) }
    var streamSensors by remember { mutableStateOf(BridgePrefs.streamSensors(context)) }
    var streamAudio by remember { mutableStateOf(BridgePrefs.streamAudio(context)) }
    var streamTouch by remember { mutableStateOf(BridgePrefs.streamTouch(context)) }
    var streamUsb by remember { mutableStateOf(BridgePrefs.streamUsb(context)) }
    var portText by remember { mutableStateOf(BridgePrefs.port(context).toString()) }
    var authLocal by remember {
        mutableStateOf(BridgePrefs.authEnabled(context) && BridgePrefs.networkMode(context) == NetworkMode.LOCAL)
    }
    var showAccessibilityDisclosure by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        GlassText("Settings", weight = FontWeight.Bold, size = 24.sp)

        // ---- Appearance ----------------------------------------------
        GlassCard {
            SectionLabel("Appearance")
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { m ->
                    val label = when (m) {
                        ThemeMode.SYSTEM -> "System"
                        ThemeMode.LIGHT -> "Light"
                        ThemeMode.DARK -> "Dark"
                    }
                    SegChip(
                        label = label,
                        selected = currentTheme == m,
                        modifier = Modifier.weight(1f),
                    ) {
                        currentTheme = m
                        BridgePrefs.setThemeMode(context, m)
                    }
                }
            }
        }

        // ---- Service protection --------------------------------------
        GlassCard {
            SectionLabel("Service protection")
            GlassText(
                "Keeps the bridge alive and prevents CPU/Wi-Fi sleep when the screen is off.",
                secondary = true,
                size = 12.sp,
            )
            PrefSwitch("Keep CPU & Wi-Fi awake", keepAwake) {
                keepAwake = it
                BridgePrefs.setKeepAwake(context, it)
            }
            PrefSwitch("Start on boot", startOnBoot) {
                startOnBoot = it
                BridgePrefs.setStartOnBoot(context, it)
            }
        }

        // ---- Server --------------------------------------------------
        GlassCard {
            SectionLabel("Server")
            OutlinedTextField(
                value = portText,
                onValueChange = { portText = it.filter { ch -> ch.isDigit() }.take(5) },
                label = { Text("Port") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = fieldColors(g),
            )
            ActionButton("Apply port", primary = true) {
                val p = portText.toIntOrNull() ?: 8765
                BridgePrefs.setPort(context, p)
                scope.launch {
                    if (running) BridgeRuntime.restartServerIfRunning()
                    else BridgeRuntime.refreshNetworkConfigFromPrefs()
                }
                Toast.makeText(context, "Port set to $p", Toast.LENGTH_SHORT).show()
            }
            if (BridgePrefs.networkMode(context) == NetworkMode.LOCAL) {
                PrefSwitch("Require token (even on localhost)", authLocal) {
                    authLocal = it
                    BridgePrefs.setAuthEnabled(context, it)
                    scope.launch { if (running) BridgeRuntime.restartServerIfRunning() }
                }
            } else {
                GlassText(
                    "Token auth is always on in ${BridgePrefs.networkMode(context).name} mode.",
                    secondary = true,
                    size = 12.sp,
                )
            }
        }

        // ---- Collectors ----------------------------------------------
        GlassCard {
            SectionLabel("Collectors")
            GlassText("Disable to save battery. Restart the bridge to apply.", secondary = true, size = 12.sp)
            PrefSwitch("Location", streamLoc) { streamLoc = it; BridgePrefs.setStreamLocation(context, it) }
            PrefSwitch("Sensors", streamSensors) { streamSensors = it; BridgePrefs.setStreamSensors(context, it) }
            PrefSwitch("Microphone", streamAudio) { streamAudio = it; BridgePrefs.setStreamAudio(context, it) }
            PrefSwitch("Touchscreen", streamTouch) { streamTouch = it; BridgePrefs.setStreamTouch(context, it) }
            PrefSwitch("USB", streamUsb) { streamUsb = it; BridgePrefs.setStreamUsb(context, it) }
            ActionButton("Enable global touch tracking (Accessibility)", fullWidth = true) {
                showAccessibilityDisclosure = true
            }
            ActionButton("Restart bridge to apply", primary = true, fullWidth = true) {
                scope.launch {
                    if (running) BridgeRuntime.restartServerIfRunning()
                    Toast.makeText(context, "Collectors reapplied", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // ---- Advanced & diagnostics (hidden by default) --------------
        CollapsibleGlassCard(
            title = "Advanced & diagnostics",
            subtitle = "Sensor inventory, USB, cameras, logs",
        ) {
            AdvancedDiagnostics()
        }
    }

    if (showAccessibilityDisclosure) {
        AlertDialog(
            onDismissRequest = { showAccessibilityDisclosure = false },
            title = {
                Text(
                    "Accessibility service disclosure",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "SensIO uses the AccessibilityService API to capture touchscreen tap locations (X, Y) across apps when you enable Global Touch Tracking.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text("Purpose & usage", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Tap locations are processed locally and streamed only to your local SensIO API server on your private network for gesture automation and spatial reasoning.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text("Privacy", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "SensIO does NOT collect, store, transmit, or share personal or screen data with external servers or third parties. All data stays local to your device and private network.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.Button(
                    onClick = {
                        showAccessibilityDisclosure = false
                        runCatching {
                            context.startActivity(
                                android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS),
                            )
                        }.onFailure {
                            Toast.makeText(context, "Could not open Accessibility Settings", Toast.LENGTH_SHORT).show()
                        }
                    },
                ) { Text("Agree & enable") }
            },
            dismissButton = {
                androidx.compose.material3.OutlinedButton(
                    onClick = { showAccessibilityDisclosure = false },
                ) { Text("Decline") }
            },
        )
    }
}

@Composable
private fun SegChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val g = rememberGlassTokens()
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .then(
                if (selected) Modifier.background(g.accentBrush)
                else Modifier.glass(g, radius = 14.dp),
            )
            .clickable { onClick() }
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) Color.White else g.textSecondary,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun PrefSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val g = rememberGlassTokens()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        GlassText(label, size = 14.sp)
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = g.accentStart,
                uncheckedThumbColor = g.textSecondary,
                uncheckedTrackColor = g.glassFillStrong,
                uncheckedBorderColor = g.glassBorder,
            ),
        )
    }
}

@Composable
private fun ActionButton(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    fullWidth: Boolean = false,
    onClick: () -> Unit,
) {
    val g = rememberGlassTokens()
    Box(
        modifier = modifier
            .then(if (fullWidth) Modifier.fillMaxWidth() else Modifier)
            .clip(RoundedCornerShape(14.dp))
            .then(
                if (primary) Modifier.background(g.accentBrush)
                else Modifier.glass(g, radius = 14.dp, strong = true),
            )
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (primary) Color.White else g.textPrimary,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun fieldColors(g: dev.asik.devicebridge.ui.theme.GlassTokens) = TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    focusedTextColor = g.textPrimary,
    unfocusedTextColor = g.textPrimary,
    focusedLabelColor = g.accentEnd,
    unfocusedLabelColor = g.textFaint,
    cursorColor = g.accentEnd,
    focusedIndicatorColor = g.accentEnd,
    unfocusedIndicatorColor = g.glassBorder,
)
