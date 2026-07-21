package dev.asik.devicebridge.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.OutlinedCard
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import android.hardware.Sensor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import dev.asik.devicebridge.BridgeRuntime
import dev.asik.devicebridge.service.BridgeForegroundService
import dev.asik.devicebridge.util.BridgePrefs
import dev.asik.devicebridge.util.DiagnosticsBuilder
import dev.asik.devicebridge.util.PermissionHelper
import kotlinx.coroutines.delay

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun HomeScreen(onOpenRemote: () -> Unit) {
    val context = LocalContext.current
    val running by BridgeRuntime.running.collectAsState()
    val location by BridgeRuntime.hub.location.collectAsState()
    val battery by BridgeRuntime.hub.battery.collectAsState()
    val audio by BridgeRuntime.hub.audio.collectAsState()
    val touch by BridgeRuntime.hub.touch.collectAsState()
    val sensors by BridgeRuntime.hub.sensors.collectAsState()
    val usb by BridgeRuntime.hub.usb.collectAsState()
    val port by BridgeRuntime.portState.collectAsState()

    val permissionState = rememberMultiplePermissionsState(PermissionHelper.runtimePermissions)
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

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = if (running) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(
                                    if (running) Color(0xFF10B981).copy(alpha = pulseAlpha)
                                    else Color(0xFF9CA3AF)
                                )
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (running) "SensIO Active" else "SensIO Stopped",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(
                        "v${BridgeRuntime.VERSION}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    "http://127.0.0.1:$port",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    "Mode: ${BridgePrefs.networkMode(context).name} · Auth: ${if (BridgePrefs.authEnabled(context)) "on" else "off"}",
                    style = MaterialTheme.typography.bodySmall,
                )

                diagHint?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }

                // One-click prominent master toggle button
                Button(
                    onClick = {
                        if (running) {
                            BridgeForegroundService.stop(context)
                        } else {
                            if (!permissionState.allPermissionsGranted) {
                                permissionState.launchMultiplePermissionRequest()
                            }
                            BridgeForegroundService.start(context)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = if (running) {
                        androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    } else {
                        androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        )
                    },
                ) {
                    Text(
                        if (running) "Stop Bridge Service" else "Start Bridge Service",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    OutlinedButton(
                        onClick = {
                            copy(context, "http://127.0.0.1:$port")
                            Toast.makeText(context, "Local URL copied", Toast.LENGTH_SHORT).show()
                        },
                    ) { Text("Copy Local URL") }
                }
            }
        }

        // Live status
        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Live Telemetry", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                
                StatusLine(
                    "Location",
                    location?.let {
                        val s = if (it.stale) " (stale ${it.age_sec}s)" else ""
                        "%.5f, %.5f$s".format(it.lat, it.lon)
                    } ?: "no fix",
                )
                StatusLine("Battery", battery?.let { "${it.percent}% ${it.status}" } ?: "—")
                StatusLine("Microphone", audio?.let { "%.1f dB".format(it.rms_db) } ?: "off")
                StatusLine(
                    "Touchscreen",
                    touch?.let { t ->
                        val p = t.pointers.firstOrNull()
                        if (p != null) {
                            "${t.action} x:%.0f y:%.0f (%.2f, %.2f)".format(p.x, p.y, p.x_norm, p.y_norm)
                        } else "idle"
                    } ?: "no touches",
                )
                
                val stepCounterReading = sensors.values.firstOrNull { it.type == Sensor.TYPE_STEP_COUNTER }
                val stepStr = if (stepCounterReading != null && stepCounterReading.values.size >= 2) {
                    "+${stepCounterReading.values[1].toInt()} steps (session) · ${stepCounterReading.values[0].toInt()} (boot)"
                } else if (stepCounterReading != null) {
                    "${stepCounterReading.values.firstOrNull()?.toInt()} steps (boot)"
                } else {
                    "${sensors.size} sensors reporting"
                }
                StatusLine("Sensors", stepStr)
                
                StatusLine(
                    "USB",
                    usb?.let { "${it.device_count} devices · ${it.storage_volumes.size} volumes" } ?: "—",
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Permissions & power", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (permissionState.allPermissionsGranted) "Permissions OK"
                    else "Some permissions missing",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { permissionState.launchMultiplePermissionRequest() }) {
                        Text("Grant")
                    }
                    OutlinedButton(
                        onClick = {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", context.packageName, null),
                                ),
                            )
                        },
                    ) { Text("App settings") }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Text(
                        if (batteryOk) "Battery unrestricted ✓"
                        else "Battery optimized — may kill bridge on Samsung",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (!batteryOk) {
                        Button(
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(
                                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                            Uri.parse("package:${context.packageName}"),
                                        ),
                                    )
                                }
                                batteryOk = DiagnosticsBuilder.isIgnoringBatteryOptimizations(context)
                            },
                        ) { Text("Allow unrestricted battery") }
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Internet / remote", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Expose via Tailscale or Cloudflare from the Remote tab. Token auth is forced when not local-only.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = onOpenRemote) { Text("Open Remote setup") }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Quick curl", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    """
                    curl -s http://127.0.0.1:$port/v1/health
                    curl -s http://127.0.0.1:$port/v1/diagnostics
                    curl -s http://127.0.0.1:$port/v1/snapshot
                    """.trimIndent(),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Text(
        "$label: $value",
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall,
    )
}

fun copy(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("device-bridge", text))
}
