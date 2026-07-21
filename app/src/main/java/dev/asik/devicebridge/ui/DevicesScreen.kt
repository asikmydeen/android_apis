package dev.asik.devicebridge.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.asik.devicebridge.BridgeRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DevicesScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val location by BridgeRuntime.hub.location.collectAsState()
    val battery by BridgeRuntime.hub.battery.collectAsState()
    val sensors by BridgeRuntime.hub.sensors.collectAsState()
    val usb by BridgeRuntime.hub.usb.collectAsState()
    val network by BridgeRuntime.hub.network.collectAsState()
    val running by BridgeRuntime.running.collectAsState()
    val port by BridgeRuntime.portState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Devices & data", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Location", style = MaterialTheme.typography.titleMedium)
                Text(
                    location?.let {
                        "lat=${it.lat} lon=${it.lon}\n±${it.accuracy_m}m · ${it.provider}" +
                            if (it.stale) " · STALE ${it.age_sec}s" else ""
                    } ?: "No fix yet",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Battery / network", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Battery: ${battery?.percent ?: "—"}% ${battery?.status ?: ""}",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Network: ${network?.transport?.joinToString() ?: "—"} connected=${network?.connected}",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Sensors (${sensors.size})", style = MaterialTheme.typography.titleMedium)
                sensors.entries.take(12).forEach { (name, reading) ->
                    Text(
                        "${name.substringAfterLast('.')}: ${reading.values.take(3)}",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (sensors.size > 12) {
                    Text("… +${sensors.size - 12} more via /v1/sensors", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("USB", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Host: ${usb?.host_supported} · Devices: ${usb?.device_count ?: 0}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                usb?.devices?.forEach { d ->
                    Text(
                        "#${d.device_id} ${d.product ?: d.device_name} ${d.vendor_id_hex}:${d.product_id_hex}\n" +
                            "perm=${d.has_permission} storage=${d.likely_mass_storage} serial=${d.likely_serial}",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!d.has_permission) {
                            OutlinedButton(
                                onClick = {
                                    runCatching {
                                        BridgeRuntime.usbCollector.requestPermission(d.device_id)
                                        Toast.makeText(context, "Accept USB dialog", Toast.LENGTH_SHORT).show()
                                    }
                                },
                            ) { Text("Permission") }
                        }
                        if (d.likely_serial || !d.likely_mass_storage) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        val r = withContext(Dispatchers.IO) {
                                            BridgeRuntime.usbCollector.openSerial(d.device_id, 115200)
                                        }
                                        Toast.makeText(context, r.message, Toast.LENGTH_LONG).show()
                                    }
                                },
                            ) { Text("Open serial") }
                        }
                    }
                }
                Text("Storage volumes", style = MaterialTheme.typography.titleSmall)
                usb?.storage_volumes?.forEach { v ->
                    Text(
                        "${v.path ?: v.label} removable=${v.is_removable}\n${v.proot_hint ?: ""}",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    v.path?.let { path ->
                        OutlinedButton(
                            onClick = {
                                copy(context, "proot-distro login ubuntu --bind $path:/mnt/usb")
                                Toast.makeText(context, "proot bind command copied", Toast.LENGTH_SHORT).show()
                            },
                        ) { Text("Copy proot bind") }
                    }
                }
                Button(
                    onClick = {
                        if (!running) {
                            Toast.makeText(context, "Start bridge first", Toast.LENGTH_SHORT).show()
                        } else {
                            BridgeRuntime.usbCollector.rescan()
                            Toast.makeText(context, "USB rescanned", Toast.LENGTH_SHORT).show()
                        }
                    },
                ) { Text("Rescan USB") }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Camera", style = MaterialTheme.typography.titleMedium)
                Text("Still capture via API.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(
                    onClick = {
                        copy(
                            context,
                            "curl -s -X POST http://127.0.0.1:$port/v1/camera/0/capture",
                        )
                        Toast.makeText(context, "Capture curl copied", Toast.LENGTH_SHORT).show()
                    },
                ) { Text("Copy capture curl") }
            }
        }
    }
}
