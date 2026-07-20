package dev.asik.devicebridge.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import dev.asik.devicebridge.BridgeRuntime
import dev.asik.devicebridge.service.BridgeForegroundService
import dev.asik.devicebridge.util.PermissionHelper

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun DeviceBridgeAppUi() {
    val context = LocalContext.current
    val running by BridgeRuntime.running.collectAsState()
    val location by BridgeRuntime.hub.location.collectAsState()
    val battery by BridgeRuntime.hub.battery.collectAsState()
    val sensors by BridgeRuntime.hub.sensors.collectAsState()
    val usb by BridgeRuntime.hub.usb.collectAsState()

    val permissionState = rememberMultiplePermissionsState(PermissionHelper.runtimePermissions)
    var capsSummary by remember { mutableStateOf("—") }

    LaunchedEffect(permissionState.allPermissionsGranted, running) {
        runCatching {
            val caps = BridgeRuntime.capabilityScanner.scan()
            capsSummary =
                "${caps.sensors.size} sensors · ${caps.cameras.size} cameras · " +
                    "loc providers: ${caps.location_providers.joinToString()}"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Device Bridge") })
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusCard(
                running = running,
                baseUrl = BridgeRuntime.baseUrl(),
                capsSummary = capsSummary,
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Permissions", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (permissionState.allPermissionsGranted) {
                            "All requested permissions granted"
                        } else {
                            "Some permissions missing — location/camera/sensors need grants"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { permissionState.launchMultiplePermissionRequest() }) {
                            Text("Grant permissions")
                        }
                        OutlinedButton(
                            onClick = {
                                val intent = Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", context.packageName, null),
                                )
                                context.startActivity(intent)
                            },
                        ) {
                            Text("App settings")
                        }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        OutlinedButton(
                            onClick = {
                                runCatching {
                                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                    context.startActivity(intent)
                                }
                            },
                        ) {
                            Text("Battery optimization settings")
                        }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Service", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (!permissionState.allPermissionsGranted) {
                                    permissionState.launchMultiplePermissionRequest()
                                }
                                BridgeForegroundService.start(context)
                            },
                            enabled = !running,
                        ) {
                            Text("Start bridge")
                        }
                        OutlinedButton(
                            onClick = { BridgeForegroundService.stop(context) },
                            enabled = running,
                        ) {
                            Text("Stop bridge")
                        }
                    }
                    Text(
                        "Keeps a foreground notification while the local API is up.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Live snapshot", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Location: " + (
                            location?.let { "${it.lat}, ${it.lon} (±${it.accuracy_m}m) via ${it.provider}" }
                                ?: "no fix yet"
                            ),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Battery: " + (
                            battery?.let { "${it.percent}% ${it.status} ${it.plugged}" }
                                ?: "—"
                            ),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Sensors with readings: ${sensors.size}",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "USB: " + (
                            usb?.let { o ->
                                "${o.device_count} device(s), ${o.storage_volumes.count { it.is_removable && !it.is_primary }} removable volume(s)"
                            } ?: "—"
                            ),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Termux / Ubuntu", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        """
                        curl -s ${BridgeRuntime.baseUrl()}/v1/health | jq .
                        curl -s ${BridgeRuntime.baseUrl()}/v1/usb | jq .
                        curl -s ${BridgeRuntime.baseUrl()}/v1/usb/storage | jq .
                        # serial gadget (after OTG plug + permission):
                        curl -s -X POST '${BridgeRuntime.baseUrl()}/v1/usb/devices/DEVICE_ID/permission'
                        curl -s -X POST '${BridgeRuntime.baseUrl()}/v1/usb/devices/DEVICE_ID/serial/open?baud=115200'
                        websocat 'ws://127.0.0.1:8765/v1/usb/serial/DEVICE_ID'
                        # flash drive files: see docs/USB_LINUX.md (bind /storage/XXXX into proot)
                        """.trimIndent(),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusCard(running: Boolean, baseUrl: String, capsSummary: String) {
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
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (running) "API running" else "API stopped",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(baseUrl, fontFamily = FontFamily.Monospace)
            Text(capsSummary, style = MaterialTheme.typography.bodySmall)
        }
    }
}
