package dev.asik.devicebridge.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.asik.devicebridge.BridgeRuntime
import dev.asik.devicebridge.service.BridgeForegroundService
import dev.asik.devicebridge.util.BridgePrefs
import dev.asik.devicebridge.util.NetworkAddresses
import dev.asik.devicebridge.util.NetworkMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val running by BridgeRuntime.running.collectAsState()
    val port by BridgeRuntime.portState.collectAsState()

    var mode by remember { mutableStateOf(BridgePrefs.networkMode(context)) }
    var token by remember { mutableStateOf(BridgePrefs.authToken(context)) }
    var publicUrl by remember { mutableStateOf(BridgePrefs.publicUrl(context)) }
    var lanIps by remember { mutableStateOf(NetworkAddresses.lanIps()) }
    var tsIps by remember { mutableStateOf(NetworkAddresses.tailscaleIps()) }

    LaunchedEffect(Unit) {
        while (true) {
            lanIps = NetworkAddresses.lanIps()
            tsIps = NetworkAddresses.tailscaleIps()
            delay(5000)
        }
    }

    fun applyMode(m: NetworkMode) {
        mode = m
        BridgePrefs.setNetworkMode(context, m)
        token = BridgePrefs.authToken(context)
        scope.launch {
            if (running) {
                BridgeRuntime.restartServerIfRunning()
                Toast.makeText(context, "Server restarted ($m)", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val modes = listOf(NetworkMode.LOCAL, NetworkMode.LAN, NetworkMode.TAILSCALE, NetworkMode.CLOUDFLARE)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Remote Access", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Pick how external or local clients reach this bridge API.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Network Mode", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    modes.forEachIndexed { index, m ->
                        SegmentedButton(
                            selected = mode == m,
                            onClick = { applyMode(m) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                            label = {
                                Text(
                                    when (m) {
                                        NetworkMode.LOCAL -> "Local"
                                        NetworkMode.LAN -> "LAN"
                                        NetworkMode.TAILSCALE -> "Tailscale"
                                        NetworkMode.CLOUDFLARE -> "Cloudflare"
                                    },
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        )
                    }
                }

                Text(
                    when (mode) {
                        NetworkMode.LOCAL -> "Binds 127.0.0.1 — on-device only (Termux/Ubuntu). Safest mode."
                        NetworkMode.LAN -> "Binds 0.0.0.0 — accessible on local Wi-Fi. Bearer token required."
                        NetworkMode.TAILSCALE -> "Binds 0.0.0.0 — accessible via Tailscale IP across your tailnet. Bearer token required."
                        NetworkMode.CLOUDFLARE -> "Binds 127.0.0.1 — forward with cloudflared in Termux for public HTTPS access. Bearer token required."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Access Token", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    if (BridgePrefs.authEnabled(context)) "Auth ENABLED — send Bearer token in header or ?token="
                    else "Auth OFF (allowed in Local mode only)",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    token,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            copy(context, token)
                            Toast.makeText(context, "Token copied", Toast.LENGTH_SHORT).show()
                        },
                    ) { Text("Copy Token") }
                    Button(
                        onClick = {
                            token = BridgePrefs.rotateToken(context)
                            scope.launch {
                                if (running) BridgeRuntime.restartServerIfRunning()
                            }
                            Toast.makeText(context, "Token rotated", Toast.LENGTH_SHORT).show()
                        },
                    ) { Text("Rotate") }
                }
            }
        }

        // Tailscale
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Tailscale", style = MaterialTheme.typography.titleMedium)
                if (tsIps.isEmpty()) {
                    Text(
                        "No Tailscale IP found. Install the Tailscale app, sign in, then reopen this screen.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse("https://tailscale.com/download/android")),
                                )
                            }
                        },
                    ) { Text("Get Tailscale") }
                } else {
                    tsIps.forEach { ip ->
                        val url = "http://$ip:$port"
                        Text(url, fontFamily = FontFamily.Monospace)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                copy(context, url)
                                Toast.makeText(context, "Copied $url", Toast.LENGTH_SHORT).show()
                            }) { Text("Copy URL") }
                            OutlinedButton(onClick = {
                                copy(context, "curl -s -H \"Authorization: Bearer $token\" $url/v1/snapshot")
                            }) { Text("Copy curl") }
                        }
                    }
                    Text(
                        "Tip: set mode to Tailscale, Start bridge, then open the URL from a PC on your tailnet.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        // Cloudflare
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Cloudflare Tunnel (cloudflared)", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Runs in Termux (not inside the APK). Quick tunnel gives a random trycloudflare.com HTTPS URL.",
                    style = MaterialTheme.typography.bodySmall,
                )
                val cmd = BridgeRuntime.cloudflaredCommand()
                Text(cmd, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        copy(context, cmd)
                        Toast.makeText(context, "cloudflared command copied", Toast.LENGTH_SHORT).show()
                    }) { Text("Copy command") }
                    OutlinedButton(onClick = {
                        copy(
                            context,
                            """
                            # Termux
                            pkg install cloudflared
                            # Start Device Bridge first, mode=Cloudflare
                            $cmd
                            """.trimIndent(),
                        )
                    }) { Text("Copy setup") }
                }
                OutlinedTextField(
                    value = publicUrl,
                    onValueChange = { publicUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Public URL (paste from cloudflared)") },
                    placeholder = { Text("https://xxxx.trycloudflare.com") },
                    singleLine = true,
                )
                Button(
                    onClick = {
                        BridgePrefs.setPublicUrl(context, publicUrl)
                        Toast.makeText(context, "Public URL saved", Toast.LENGTH_SHORT).show()
                    },
                ) { Text("Save public URL") }
                if (publicUrl.isNotBlank()) {
                    Text(
                        "curl -s -H \"Authorization: Bearer $token\" ${publicUrl.trimEnd('/')}/v1/health",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    OutlinedButton(onClick = {
                        copy(
                            context,
                            "curl -s -H \"Authorization: Bearer $token\" ${publicUrl.trimEnd('/')}/v1/snapshot",
                        )
                    }) { Text("Copy public curl") }
                }
            }
        }

        // LAN
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("LAN (same Wi‑Fi)", style = MaterialTheme.typography.titleMedium)
                if (lanIps.isEmpty()) {
                    Text("No private LAN IPs right now.", style = MaterialTheme.typography.bodySmall)
                } else {
                    lanIps.forEach { ip ->
                        val url = "http://$ip:$port"
                        Text(url, fontFamily = FontFamily.Monospace)
                        OutlinedButton(onClick = { copy(context, url) }) { Text("Copy $ip") }
                    }
                }
            }
        }

        if (!running) {
            Button(
                onClick = { BridgeForegroundService.start(context) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Start bridge to apply remote access") }
        }
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
    )
}
