package dev.asik.devicebridge.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
import dev.asik.devicebridge.collectors.CapabilityScanner
import android.hardware.Sensor
import dev.asik.devicebridge.model.SensorInfo
import dev.asik.devicebridge.model.SensorReading
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.asik.devicebridge.BridgeRuntime
import dev.asik.devicebridge.util.BridgePrefs
import dev.asik.devicebridge.util.ErrorLog
import dev.asik.devicebridge.util.NetworkMode
import kotlinx.coroutines.launch

import dev.asik.devicebridge.util.ThemeMode

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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
    var logPreview by remember { mutableStateOf("") }

    val capabilities = remember { CapabilityScanner(context).scan() }
    val allSensors = capabilities.sensors
    val allCameras = capabilities.cameras
    val sensorReadings by BridgeRuntime.hub.sensors.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Appearance", style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ThemeMode.entries.forEach { mode ->
                        val label = when (mode) {
                            ThemeMode.SYSTEM -> "System"
                            ThemeMode.LIGHT -> "Light"
                            ThemeMode.DARK -> "Dark"
                        }
                        val selected = currentTheme == mode
                        if (selected) {
                            Button(
                                onClick = {
                                    currentTheme = mode
                                    BridgePrefs.setThemeMode(context, mode)
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text(label) }
                        } else {
                            androidx.compose.material3.OutlinedButton(
                                onClick = {
                                    currentTheme = mode
                                    BridgePrefs.setThemeMode(context, mode)
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text(label) }
                        }
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Service Protection & Keep-Alive", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Prevents system from killing the bridge or sleeping CPU/Wi-Fi when screen turns off.",
                    style = MaterialTheme.typography.bodySmall
                )
                PrefSwitch("Keep CPU & Wi-Fi Awake", keepAwake) {
                    keepAwake = it
                    BridgePrefs.setKeepAwake(context, it)
                }
                PrefSwitch("Start on boot", startOnBoot) {
                    startOnBoot = it
                    BridgePrefs.setStartOnBoot(context, it)
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Server", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it.filter { ch -> ch.isDigit() }.take(5) },
                    label = { Text("Port") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        val p = portText.toIntOrNull() ?: 8765
                        BridgePrefs.setPort(context, p)
                        scope.launch {
                            if (running) BridgeRuntime.restartServerIfRunning()
                            else BridgeRuntime.refreshNetworkConfigFromPrefs()
                        }
                        Toast.makeText(context, "Port set to $p", Toast.LENGTH_SHORT).show()
                    },
                ) { Text("Apply port") }

                PrefSwitch("Start on boot", startOnBoot) {
                    startOnBoot = it
                    BridgePrefs.setStartOnBoot(context, it)
                }
                if (BridgePrefs.networkMode(context) == NetworkMode.LOCAL) {
                    PrefSwitch("Require token (even on localhost)", authLocal) {
                        authLocal = it
                        BridgePrefs.setAuthEnabled(context, it)
                        scope.launch { if (running) BridgeRuntime.restartServerIfRunning() }
                    }
                } else {
                    Text(
                        "Token auth is always on in ${BridgePrefs.networkMode(context).name} mode.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Collectors", style = MaterialTheme.typography.titleMedium)
                Text("Disable to save battery. Restart bridge to apply.", style = MaterialTheme.typography.bodySmall)
                PrefSwitch("Location", streamLoc) {
                    streamLoc = it
                    BridgePrefs.setStreamLocation(context, it)
                }
                PrefSwitch("Sensors", streamSensors) {
                    streamSensors = it
                    BridgePrefs.setStreamSensors(context, it)
                }
                PrefSwitch("Microphone", streamAudio) {
                    streamAudio = it
                    BridgePrefs.setStreamAudio(context, it)
                }
                PrefSwitch("Touchscreen", streamTouch) {
                    streamTouch = it
                    BridgePrefs.setStreamTouch(context, it)
                }
                Button(
                    onClick = {
                        try {
                            val intent = android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            Toast.makeText(context, "Could not open Accessibility Settings", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Enable Global Touch Tracking (Accessibility)")
                }
                PrefSwitch("USB", streamUsb) {
                    streamUsb = it
                    BridgePrefs.setStreamUsb(context, it)
                }
                Button(
                    onClick = {
                        scope.launch {
                            if (running) BridgeRuntime.restartServerIfRunning()
                            Toast.makeText(context, "Collectors reapplied", Toast.LENGTH_SHORT).show()
                        }
                    },
                ) { Text("Restart bridge to apply") }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Debug log", style = MaterialTheme.typography.titleMedium)
                Button(
                    onClick = {
                        logPreview = ErrorLog.recent(15).joinToString("\n") {
                            "${it.level} ${it.code}: ${it.message}"
                        }.ifBlank { "(empty)" }
                    },
                ) { Text("Refresh log") }
                Text(logPreview, style = MaterialTheme.typography.labelSmall)
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Hardware Inventory", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Identified sensors on this device. Values shown if collector is running.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(4.dp))

                allSensors.forEachIndexed { index, sensor ->
                    SensorItem(sensor, sensorReadings[sensor.type_name])
                    if (index < allSensors.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 4.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Other Hardware", style = MaterialTheme.typography.titleMedium)
                
                Text("Microphone", style = MaterialTheme.typography.titleSmall)
                val audioReading by BridgeRuntime.hub.audio.collectAsState()
                Text(
                    if (audioReading != null) "Active: %.1f dB (RMS)".format(audioReading?.rms_db)
                    else "Inactive or permission denied",
                    style = MaterialTheme.typography.bodySmall
                )

                Spacer(Modifier.height(4.dp))
                Text("Cameras", style = MaterialTheme.typography.titleSmall)
                allCameras.forEach { cam ->
                    Text(
                        "ID ${cam.id}: ${cam.facing} (${cam.hardware_level})",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Spacer(Modifier.height(4.dp))
                Text("Location Providers", style = MaterialTheme.typography.titleSmall)
                Text(
                    capabilities.location_providers.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun SensorItem(info: SensorInfo, reading: SensorReading?) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(info.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Text(info.type_name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
            Text(
                if (expanded) "▲" else "▼",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, start = 8.dp, end = 8.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Vendor: ${info.vendor}", style = MaterialTheme.typography.labelSmall)
                    Text("Range: ${info.max_range}", style = MaterialTheme.typography.labelSmall)
                }

                if (reading != null) {
                    SensorDataDisplay(info, reading)
                } else {
                    Text(
                        "Waiting for reading...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                    )
                }
            }
        }
    }
}

@Composable
private fun SensorDataDisplay(info: SensorInfo, reading: SensorReading?) {
    if (reading == null) return

    val labels = when (info.type) {
        Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_ACCELEROMETER_UNCALIBRATED,
        Sensor.TYPE_GYROSCOPE, Sensor.TYPE_GYROSCOPE_UNCALIBRATED,
        Sensor.TYPE_MAGNETIC_FIELD, Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED,
        Sensor.TYPE_GRAVITY, Sensor.TYPE_LINEAR_ACCELERATION,
        Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GAME_ROTATION_VECTOR -> listOf("X", "Y", "Z", "W")
        else -> emptyList()
    }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        reading.values.forEachIndexed { i, value ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val label = labels.getOrNull(i) ?: "Val ${i + 1}"
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(32.dp)
                )

                Text(
                    text = when (info.type) {
                        Sensor.TYPE_LIGHT -> "%.1f lx".format(value)
                        Sensor.TYPE_STEP_COUNTER -> "${value.toInt()} steps (since boot)"
                        else -> "%.4f".format(value)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary
                )

                // Simple visual bar for common sensors
                if (info.max_range > 0 && info.type != Sensor.TYPE_STEP_COUNTER) {
                    val progress = (value.coerceIn(-info.max_range, info.max_range) + info.max_range) / (2 * info.max_range)
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp)
                            .height(4.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
        
        Text(
            "Accuracy: ${reading.accuracy} · TS: ${reading.timestamp_ns}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun PrefSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
