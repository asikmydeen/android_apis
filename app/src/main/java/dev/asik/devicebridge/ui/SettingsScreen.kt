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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val running by BridgeRuntime.running.collectAsState()

    var startOnBoot by remember { mutableStateOf(BridgePrefs.startOnBoot(context)) }
    var streamLoc by remember { mutableStateOf(BridgePrefs.streamLocation(context)) }
    var streamSensors by remember { mutableStateOf(BridgePrefs.streamSensors(context)) }
    var streamUsb by remember { mutableStateOf(BridgePrefs.streamUsb(context)) }
    var portText by remember { mutableStateOf(BridgePrefs.port(context).toString()) }
    var authLocal by remember {
        mutableStateOf(BridgePrefs.authEnabled(context) && BridgePrefs.networkMode(context) == NetworkMode.LOCAL)
    }
    var logPreview by remember { mutableStateOf("") }

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
