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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Device Bridge", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("v${BridgeRuntime.VERSION}", style = MaterialTheme.typography.labelMedium)

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (running) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ),
        ) {
            Column(Modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (running) "● API running" else "○ API stopped",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text("http://127.0.0.1:$port", fontFamily = FontFamily.Monospace)
                Text(
                    "Mode: ${BridgePrefs.networkMode(context).name} · Auth: ${if (BridgePrefs.authEnabled(context)) "on" else "off"}",
                    style = MaterialTheme.typography.bodySmall,
                )
                diagHint?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            if (!permissionState.allPermissionsGranted) {
                                permissionState.launchMultiplePermissionRequest()
                            }
                            BridgeForegroundService.start(context)
                        },
                        enabled = !running,
                    ) { Text("Start") }
                    OutlinedButton(
                        onClick = { BridgeForegroundService.stop(context) },
                        enabled = running,
                    ) { Text("Stop") }
                    OutlinedButton(
                        onClick = {
                            copy(context, "http://127.0.0.1:$port")
                            Toast.makeText(context, "Local URL copied", Toast.LENGTH_SHORT).show()
                        },
                    ) { Text("Copy URL") }
                }
            }
        }

        // Quick status chips
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Live", style = MaterialTheme.typography.titleMedium)
                StatusLine(
                    "Location",
                    location?.let {
                        val s = if (it.stale) " (stale ${it.age_sec}s)" else ""
                        "%.5f, %.5f$s".format(it.lat, it.lon)
                    } ?: "no fix",
                )
                StatusLine("Battery", battery?.let { "${it.percent}% ${it.status}" } ?: "—")
                StatusLine("Sensors", "${sensors.size} readings")
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
            Column(Modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Internet / remote", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Expose via Tailscale or Cloudflare from the Remote tab. Token auth is forced when not local-only.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = onOpenRemote) { Text("Open Remote setup") }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier = Modifier.padding(16.dp)) {
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
