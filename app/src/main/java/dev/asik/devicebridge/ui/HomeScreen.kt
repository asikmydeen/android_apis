package dev.asik.devicebridge.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.accompanist.permissions.rememberPermissionState
import dev.asik.devicebridge.BridgeRuntime
import dev.asik.devicebridge.service.BridgeForegroundService
import dev.asik.devicebridge.ui.components.GlassCard
import dev.asik.devicebridge.ui.components.GlassText
import dev.asik.devicebridge.ui.components.GradientBar
import dev.asik.devicebridge.ui.components.LiveDot
import dev.asik.devicebridge.ui.components.MonoText
import dev.asik.devicebridge.ui.components.SectionLabel
import dev.asik.devicebridge.ui.components.SignalBars
import dev.asik.devicebridge.ui.components.glass
import dev.asik.devicebridge.ui.theme.rememberGlassTokens
import dev.asik.devicebridge.util.BridgePrefs
import dev.asik.devicebridge.util.DiagnosticsBuilder
import dev.asik.devicebridge.util.PermissionHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Dashboard — the "is it working?" screen. Live vitals as designed metrics,
 * the master start/stop, endpoint, and setup health. Detailed per-device data
 * lives on the Hardware tab, not here.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun DashboardScreen() {
    val context = LocalContext.current
    val g = rememberGlassTokens()
    val running by BridgeRuntime.running.collectAsState()
    val location by BridgeRuntime.hub.location.collectAsState()
    val battery by BridgeRuntime.hub.battery.collectAsState()
    val audio by BridgeRuntime.hub.audio.collectAsState()
    val sensors by BridgeRuntime.hub.sensors.collectAsState()
    val usb by BridgeRuntime.hub.usb.collectAsState()
    val port by BridgeRuntime.portState.collectAsState()

    val scope = rememberCoroutineScope()
    val permissionState = rememberMultiplePermissionsState(PermissionHelper.runtimePermissions)
    val micPermission = rememberPermissionState(android.Manifest.permission.RECORD_AUDIO)
    var micEnabled by remember { mutableStateOf(BridgePrefs.streamAudio(context)) }
    var showDisconnectConfirm by remember { mutableStateOf(false) }
    var diagHint by remember { mutableStateOf<String?>(null) }
    var batteryOk by remember {
        mutableStateOf(DiagnosticsBuilder.isIgnoringBatteryOptimizations(context))
    }

    LaunchedEffect(running) {
        while (true) {
            batteryOk = DiagnosticsBuilder.isIgnoringBatteryOptimizations(context)
            if (running) {
                runCatching {
                    val d = DiagnosticsBuilder.build(context)
                    diagHint = if (d.degraded) d.hints.firstOrNull() else "All collectors healthy"
                }
            }
            delay(4000)
        }
    }

    // Ticks every second so the privacy dots reflect the registry's rolling
    // "remotely active in the last 3s" window without waiting on collector updates.
    var remoteTick by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            remoteTick = System.currentTimeMillis()
            delay(1000)
        }
    }
    fun remotelyReading(signal: String): Boolean =
        BridgeRuntime.registry.isRemotelyActive(signal, remoteTick)

    val localUrl = "http://127.0.0.1:$port"
    val batteryPct = battery?.percent
    val gpsLocked = location != null && location?.stale != true

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ---- Hero: status + battery + master toggle -------------------
        GlassCard(strong = true, radius = 28.dp, contentPadding = 20.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LiveDot(active = running, size = 12.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    GlassText(
                        if (running) "Bridge active" else "Bridge stopped",
                        weight = FontWeight.Bold,
                        size = 20.sp,
                    )
                    GlassText(
                        if (running) "Streaming device signals" else "Tap start to begin streaming",
                        secondary = true,
                        size = 13.sp,
                    )
                }
                SignalBars(active = running)
            }

            // Battery as a gradient meter, not a number in a log.
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    SectionLabel("Battery")
                    GlassText(
                        battery?.let { "${it.percent}% · ${it.status}" } ?: "—",
                        secondary = true,
                        size = 12.sp,
                    )
                }
                GradientBar(progress = (batteryPct ?: 0) / 100f)
            }

            MasterToggle(
                running = running,
                onStart = {
                    if (!permissionState.allPermissionsGranted) {
                        permissionState.launchMultiplePermissionRequest()
                    }
                    BridgeForegroundService.start(context)
                },
                onStop = { BridgeForegroundService.stop(context) },
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .glass(g, radius = 14.dp)
                    .clickable {
                        copy(context, localUrl)
                        Toast.makeText(context, "Local URL copied", Toast.LENGTH_SHORT).show()
                    }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    SectionLabel("Endpoint")
                    MonoText(localUrl, color = g.textPrimary)
                }
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = "Copy local URL",
                    tint = g.textSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }

            GlassText(
                "Mode ${BridgePrefs.networkMode(context).name} · Auth ${if (BridgePrefs.authEnabled(context)) "on" else "off"}",
                secondary = true,
                size = 12.sp,
            )
            diagHint?.let {
                GlassText(it, secondary = true, size = 12.sp)
            }
        }

        // ---- Live vitals grid (glanceable status only) ----------------
        SectionLabel("Live signals", modifier = Modifier.padding(start = 4.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        ) {
            // Microphone: 3 states — no permission, granted-but-disabled, live.
            val micValue: String
            val micHint: String?
            val micAction: (() -> Unit)?
            when {
                !micPermission.status.isGranted -> {
                    micValue = "no access"
                    micHint = "Tap to grant"
                    micAction = { micPermission.launchPermissionRequest() }
                }
                !micEnabled -> {
                    micValue = "disabled"
                    micHint = "Tap to enable"
                    micAction = {
                        micEnabled = true
                        BridgePrefs.setStreamAudio(context, true)
                        scope.launch {
                            if (running) BridgeRuntime.restartServerIfRunning()
                        }
                    }
                }
                else -> {
                    micValue = audio?.let { "%.0f dB".format(it.rms_db) } ?: "waiting"
                    micHint = null
                    micAction = null
                }
            }
            VitalTile(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                icon = Icons.Default.Mic,
                label = "Microphone",
                value = micValue,
                live = remotelyReading("audio"),
                hint = micHint,
                onClick = micAction,
            )
            VitalTile(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                icon = Icons.Default.GpsFixed,
                label = "Location",
                value = if (gpsLocked) "locked" else if (location != null) "stale" else "no fix",
                live = remotelyReading("location"),
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        ) {
            VitalTile(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                icon = Icons.Default.Sensors,
                label = "Sensors",
                value = "${sensors.size} live",
                live = remotelyReading("sensors"),
            )
            VitalTile(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                icon = Icons.Default.Usb,
                label = "USB",
                value = usb?.let { "${it.device_count} dev" } ?: "—",
                live = remotelyReading("usb"),
            )
        }

        // ---- Setup & health ------------------------------------------
        GlassCard {
            SectionLabel("Setup & health")
            GlassText(
                if (permissionState.allPermissionsGranted) "Permissions granted"
                else "Some permissions missing",
                weight = FontWeight.SemiBold,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Only show Grant when there's something left to grant.
                if (!permissionState.allPermissionsGranted) {
                    PillButton("Grant", modifier = Modifier.weight(1f), primary = true) {
                        permissionState.launchMultiplePermissionRequest()
                    }
                }
                PillButton("App settings", modifier = Modifier.weight(1f)) {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        ),
                    )
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Spacer(Modifier.height(2.dp))
                GlassText(
                    if (batteryOk) "Battery unrestricted"
                    else "Battery optimized — may kill the bridge on Samsung",
                    secondary = true,
                    size = 12.sp,
                )
                if (!batteryOk) {
                    PillButton(
                        "Allow unrestricted battery",
                        modifier = Modifier.fillMaxWidth(),
                        primary = true,
                    ) {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:${context.packageName}"),
                                ),
                            )
                        }
                        batteryOk = DiagnosticsBuilder.isIgnoringBatteryOptimizations(context)
                    }
                }
            }
        }

        // ---- Connected clients + panic disconnect (only while running) ----
        if (running) {
            val clients by BridgeRuntime.registry.activeClients.collectAsState()
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel(
                        "Connected clients · ${clients.size}",
                        modifier = Modifier.weight(1f),
                    )
                    LiveDot(active = clients.isNotEmpty(), size = 8.dp)
                }
                if (clients.isEmpty()) {
                    GlassText("No clients connected right now.", secondary = true, size = 12.sp)
                } else {
                    clients.take(5).forEach { c ->
                        GlassText("${c.kind} · ${c.remoteIp}", secondary = true, size = 12.sp)
                    }
                }
                PillButton(
                    "Disconnect all & rotate token",
                    modifier = Modifier.fillMaxWidth(),
                ) { showDisconnectConfirm = true }
            }
        }
    }

    if (showDisconnectConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDisconnectConfirm = false },
            title = { androidx.compose.material3.Text("Disconnect all clients?") },
            text = {
                androidx.compose.material3.Text(
                    "This closes every open connection and rotates the access token, locking out " +
                        "all current clients immediately. The bridge keeps running, but you'll need to " +
                        "re-share the new token (or QR) to reconnect anything.",
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showDisconnectConfirm = false
                    scope.launch {
                        BridgeRuntime.disconnectAllClients()
                        Toast.makeText(context, "Disconnected all clients · token rotated", Toast.LENGTH_LONG).show()
                    }
                }) { androidx.compose.material3.Text("Disconnect all") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showDisconnectConfirm = false }) {
                    androidx.compose.material3.Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun MasterToggle(running: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    val g = rememberGlassTokens()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(16.dp))
            .then(
                if (running) Modifier.glass(g, radius = 16.dp, strong = true)
                else Modifier.background(g.accentBrush),
            )
            .clickable { if (running) onStop() else onStart() },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Text(
            if (running) "Stop bridge" else "Start bridge",
            color = if (running) g.danger else androidx.compose.ui.graphics.Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
        )
    }
}

@Composable
private fun VitalTile(
    icon: ImageVector,
    label: String,
    value: String,
    live: Boolean,
    modifier: Modifier = Modifier,
    hint: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val g = rememberGlassTokens()
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .glass(g, radius = 22.dp)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .glass(g, radius = 17.dp, strong = true),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = label, tint = g.accentEnd, modifier = Modifier.size(18.dp))
            }
            LiveDot(active = live, size = 8.dp)
        }
        Column {
            GlassText(value, weight = FontWeight.Bold, size = 18.sp)
            SectionLabel(label)
            if (hint != null) {
                Spacer(Modifier.height(4.dp))
                androidx.compose.material3.Text(
                    hint,
                    color = g.accentEnd,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun PillButton(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    val g = rememberGlassTokens()
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .then(
                if (primary) Modifier.background(g.accentBrush)
                else Modifier.glass(g, radius = 14.dp, strong = true),
            )
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Text(
            text,
            color = if (primary) androidx.compose.ui.graphics.Color.White else g.textPrimary,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
        )
    }
}

fun copy(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("device-bridge", text))
}
