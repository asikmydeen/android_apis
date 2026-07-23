package dev.asik.devicebridge.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.asik.devicebridge.BridgeRuntime
import dev.asik.devicebridge.service.BridgeForegroundService
import dev.asik.devicebridge.ui.components.CollapsibleGlassCard
import dev.asik.devicebridge.ui.components.GlassCard
import dev.asik.devicebridge.ui.components.GlassText
import dev.asik.devicebridge.ui.components.LiveDot
import dev.asik.devicebridge.ui.components.MonoText
import dev.asik.devicebridge.ui.components.QrCode
import dev.asik.devicebridge.ui.components.SectionLabel
import dev.asik.devicebridge.ui.components.glass
import dev.asik.devicebridge.ui.theme.rememberGlassTokens
import dev.asik.devicebridge.util.BridgePrefs
import dev.asik.devicebridge.util.NetworkAddresses
import dev.asik.devicebridge.util.NetworkMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val g = rememberGlassTokens()
    val running by BridgeRuntime.running.collectAsState()
    val port by BridgeRuntime.portState.collectAsState()

    var mode by remember { mutableStateOf(BridgePrefs.networkMode(context)) }
    var token by remember { mutableStateOf(BridgePrefs.authToken(context)) }
    var publicUrl by remember { mutableStateOf(BridgePrefs.publicUrl(context)) }
    var lanIps by remember { mutableStateOf(NetworkAddresses.lanIps()) }
    var tsIps by remember { mutableStateOf(NetworkAddresses.tailscaleIps()) }
    var showRotateConfirm by remember { mutableStateOf(false) }
    var testState by remember { mutableStateOf(TestState.IDLE) }
    var testDetail by remember { mutableStateOf("") }
    var showQr by remember { mutableStateOf(false) }
    var showAdvancedMode by remember { mutableStateOf(false) }

    // The address a client should actually use, given the current mode.
    fun bestBaseUrl(): String = when (mode) {
        NetworkMode.LOCAL -> "http://127.0.0.1:$port"
        NetworkMode.LAN -> lanIps.firstOrNull()?.let { "http://$it:$port" } ?: "http://127.0.0.1:$port"
        NetworkMode.TAILSCALE -> tsIps.firstOrNull()?.let { "http://$it:$port" } ?: "http://127.0.0.1:$port"
        NetworkMode.CLOUDFLARE -> publicUrl.ifBlank { "http://127.0.0.1:$port" }
    }

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

    // Cloudflare is an advanced/niche path (manual Termux). Keep the primary chips
    // to the common modes and reveal Cloudflare only on demand (or if it's active).
    val primaryModes = listOf(NetworkMode.LOCAL, NetworkMode.LAN, NetworkMode.TAILSCALE)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column {
            GlassText("Remote access", weight = FontWeight.Bold, size = 24.sp)
            GlassText("Choose how clients reach this bridge.", secondary = true, size = 13.sp)
        }

        // ---- Network mode selector -----------------------------------
        GlassCard(strong = true) {
            SectionLabel("Network mode")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                primaryModes.forEach { m ->
                    ModeChip(
                        label = when (m) {
                            NetworkMode.LOCAL -> "Local"
                            NetworkMode.LAN -> "LAN"
                            NetworkMode.TAILSCALE -> "Tailscale"
                            else -> m.name
                        },
                        selected = mode == m,
                        modifier = Modifier.weight(1f),
                        onClick = { applyMode(m) },
                    )
                }
            }
            // Cloudflare chip appears only when advanced is expanded or already active.
            if (showAdvancedMode || mode == NetworkMode.CLOUDFLARE) {
                ModeChip(
                    label = "Cloudflare · advanced",
                    selected = mode == NetworkMode.CLOUDFLARE,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { applyMode(NetworkMode.CLOUDFLARE) },
                )
            } else {
                ActionButton("Show advanced modes", fullWidth = true) { showAdvancedMode = true }
            }
            GlassText(
                when (mode) {
                    NetworkMode.LOCAL -> "Binds 127.0.0.1 — on-device only (Termux/Ubuntu). Safest mode."
                    NetworkMode.LAN -> "Binds 0.0.0.0 — reachable on local Wi-Fi. Bearer token required."
                    NetworkMode.TAILSCALE -> "Binds only the Tailscale IP — reachable across your tailnet, WireGuard-encrypted. Bearer token required."
                    NetworkMode.CLOUDFLARE -> "Binds 127.0.0.1 — forward with cloudflared for public HTTPS. Bearer token required."
                },
                secondary = true,
                size = 12.sp,
            )
        }

        // ---- Access token --------------------------------------------
        GlassCard {
            SectionLabel("Access token")
            GlassText(
                if (BridgePrefs.authEnabled(context)) "Auth enabled — send Bearer token or ?token="
                else "Auth off (Local mode only)",
                secondary = true,
                size = 12.sp,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .glass(g, radius = 12.dp)
                    .padding(12.dp),
            ) {
                MonoText(token, color = g.accentEnd)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton("Copy token") {
                    copy(context, token)
                    Toast.makeText(context, "Token copied", Toast.LENGTH_SHORT).show()
                }
                ActionButton("Rotate") { showRotateConfirm = true }
            }
            // One-tap client config: base URL + token, ready to paste into an agent
            // config / .env instead of copying two values into two places.
            ActionButton("Copy connection config", primary = true, fullWidth = true) {
                val cfg = buildString {
                    appendLine("DEVICE_BRIDGE_URL=${bestBaseUrl()}")
                    appendLine("DEVICE_BRIDGE_TOKEN=$token")
                }.trimEnd()
                copy(context, cfg)
                Toast.makeText(context, "Connection config copied", Toast.LENGTH_SHORT).show()
            }

            // Confirm the endpoint actually answers with this token, in-app.
            ActionButton(
                text = when (testState) {
                    TestState.TESTING -> "Testing…"
                    else -> "Test connection"
                },
                fullWidth = true,
            ) {
                if (testState == TestState.TESTING) return@ActionButton
                testState = TestState.TESTING
                testDetail = ""
                scope.launch {
                    val result = probeHealth(bestBaseUrl(), token)
                    testState = if (result.ok) TestState.OK else TestState.FAIL
                    testDetail = result.detail
                }
            }
            if (testState != TestState.IDLE && testState != TestState.TESTING) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LiveDot(active = testState == TestState.OK, size = 8.dp)
                    Spacer(Modifier.width(8.dp))
                    GlassText(
                        testDetail,
                        secondary = true,
                        size = 12.sp,
                    )
                }
            }

            // QR pairing: scan URL+token onto another device instead of typing them.
            ActionButton(
                text = if (showQr) "Hide pairing QR" else "Show pairing QR",
                fullWidth = true,
            ) { showQr = !showQr }
            if (showQr) {
                val payload = "devicebridge://connect?url=${java.net.URLEncoder.encode(bestBaseUrl(), "UTF-8")}" +
                    "&token=${java.net.URLEncoder.encode(token, "UTF-8")}"
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White)
                            .padding(12.dp),
                    ) {
                        QrCode(content = payload, size = 200.dp)
                    }
                }
                GlassText(
                    "Scan to pair a client with this endpoint and token. Anyone who scans gets full access — treat it like the token.",
                    secondary = true,
                    size = 12.sp,
                )
            }
        }

        // ---- API docs (discoverability) ------------------------------
        GlassCard {
            SectionLabel("API for developers")
            GlassText(
                "Interactive Swagger docs describe every endpoint so clients and AI agents can be built against the bridge. Open it in a browser, or point tools at the OpenAPI spec.",
                secondary = true,
                size = 12.sp,
            )
            ActionButton("View API docs", primary = true, fullWidth = true) {
                val base = bestBaseUrl().trimEnd('/')
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$base/docs")))
                }.onFailure {
                    copy(context, "$base/docs")
                    Toast.makeText(context, "Docs URL copied: $base/docs", Toast.LENGTH_LONG).show()
                }
            }
            ActionButton("Copy OpenAPI spec URL", fullWidth = true) {
                val url = "${bestBaseUrl().trimEnd('/')}/v1/openapi.json"
                copy(context, url)
                Toast.makeText(context, "OpenAPI URL copied", Toast.LENGTH_SHORT).show()
            }
        }

        // ---- LAN ------------------------------------------------------
        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("LAN · same Wi-Fi", modifier = Modifier.weight(1f))
                if (mode != NetworkMode.LAN) InactiveTag()
            }
            when {
                mode != NetworkMode.LAN -> GlassText(
                    "Switch to LAN mode to serve these addresses. The bridge currently binds a different interface.",
                    secondary = true,
                    size = 12.sp,
                )
                lanIps.isEmpty() -> GlassText("No private LAN IPs right now.", secondary = true, size = 13.sp)
                else -> lanIps.forEach { ip ->
                    val url = "http://$ip:$port"
                    UrlRow(url) {
                        copy(context, url)
                        Toast.makeText(context, "Copied $url", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // ---- Tailscale ------------------------------------------------
        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Tailscale", modifier = Modifier.weight(1f))
                if (mode != NetworkMode.TAILSCALE && tsIps.isNotEmpty()) InactiveTag()
            }
            if (tsIps.isEmpty()) {
                GlassText(
                    "No Tailscale IP found. Install the app, sign in, then reopen this screen.",
                    secondary = true,
                    size = 13.sp,
                )
                ActionButton("Get Tailscale") {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://tailscale.com/download/android")),
                        )
                    }
                }
            } else {
                if (mode != NetworkMode.TAILSCALE) {
                    GlassText(
                        "Switch to Tailscale mode to bind these addresses.",
                        secondary = true,
                        size = 12.sp,
                    )
                }
                tsIps.forEach { ip ->
                    val url = "http://$ip:$port"
                    UrlRow(url) {
                        copy(context, url)
                        Toast.makeText(context, "Copied $url", Toast.LENGTH_SHORT).show()
                    }
                }
                GlassText(
                    "Open the URL from any device on your tailnet — traffic is WireGuard-encrypted end to end.",
                    secondary = true,
                    size = 12.sp,
                )

                // HV12: Tailscale Funnel — public HTTPS with Tailscale's own certs,
                // no Termux/Cloudflare. One command from a device already on the tailnet.
                Spacer(Modifier.height(4.dp))
                SectionLabel("Funnel · public HTTPS")
                GlassText(
                    "Expose the bridge publicly over HTTPS using Tailscale's cert — run this where the tailscale CLI is available.",
                    secondary = true,
                    size = 12.sp,
                )
                val funnelCmd = "tailscale funnel $port"
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .glass(g, radius = 12.dp)
                        .padding(12.dp),
                ) {
                    MonoText(funnelCmd)
                }
                ActionButton("Copy funnel command") {
                    copy(context, funnelCmd)
                    Toast.makeText(context, "Funnel command copied", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // ---- Cloudflare (advanced, collapsed by default) -------------
        // Manual Termux paste-dance — tucked away since most users are served by
        // LAN / Tailscale / Funnel. Kept for those who specifically want a
        // trycloudflare public URL without Tailscale.
        CollapsibleGlassCard(
            title = "Cloudflare tunnel · advanced",
            subtitle = "Public HTTPS via Termux cloudflared",
        ) {
            GlassText(
                "Runs in Termux, not in the app. A quick tunnel gives a random trycloudflare.com HTTPS URL. Prefer Tailscale Funnel above if you use Tailscale.",
                secondary = true,
                size = 12.sp,
            )
            val cmd = BridgeRuntime.cloudflaredCommand()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .glass(g, radius = 12.dp)
                    .padding(12.dp),
            ) {
                MonoText(cmd)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton("Copy command") {
                    copy(context, cmd)
                    Toast.makeText(context, "cloudflared command copied", Toast.LENGTH_SHORT).show()
                }
                ActionButton("Copy setup") {
                    copy(
                        context,
                        """
                        # Termux
                        pkg install cloudflared
                        # Start Device Bridge first, mode=Cloudflare
                        $cmd
                        """.trimIndent(),
                    )
                }
            }
            OutlinedTextField(
                value = publicUrl,
                onValueChange = { publicUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Public URL from cloudflared") },
                placeholder = { Text("https://xxxx.trycloudflare.com") },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedTextColor = g.textPrimary,
                    unfocusedTextColor = g.textPrimary,
                    focusedLabelColor = g.accentEnd,
                    unfocusedLabelColor = g.textFaint,
                    cursorColor = g.accentEnd,
                    focusedIndicatorColor = g.accentEnd,
                    unfocusedIndicatorColor = g.glassBorder,
                ),
            )
            ActionButton("Save public URL", primary = true) {
                BridgePrefs.setPublicUrl(context, publicUrl)
                Toast.makeText(context, "Public URL saved", Toast.LENGTH_SHORT).show()
            }
            if (publicUrl.isNotBlank()) {
                ActionButton("Copy public curl") {
                    copy(context, "curl -s -H \"Authorization: Bearer $token\" ${publicUrl.trimEnd('/')}/v1/snapshot")
                }
            }
        }

        if (!running) {
            ActionButton("Start bridge to apply", primary = true, fullWidth = true) {
                BridgeForegroundService.start(context)
            }
        }
    }

    if (showRotateConfirm) {
        AlertDialog(
            onDismissRequest = { showRotateConfirm = false },
            title = { Text("Rotate access token?") },
            text = {
                Text(
                    "This generates a new token and immediately disconnects every client " +
                        "using the current one. You'll need to re-share the new token (or " +
                        "connection config) with each client.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRotateConfirm = false
                    token = BridgePrefs.rotateToken(context)
                    scope.launch { if (running) BridgeRuntime.restartServerIfRunning() }
                    Toast.makeText(context, "Token rotated", Toast.LENGTH_SHORT).show()
                }) { Text("Rotate") }
            },
            dismissButton = {
                TextButton(onClick = { showRotateConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val g = rememberGlassTokens()
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(14.dp))
            .then(
                if (selected) Modifier.background(g.accentBrush)
                else Modifier.glass(g, radius = 14.dp),
            )
            .clickable(
                onClickLabel = "Select $label mode",
                role = Role.RadioButton,
            ) { onClick() }
            .padding(vertical = 12.dp),
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
private fun UrlRow(url: String, onCopy: () -> Unit) {
    val g = rememberGlassTokens()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .glass(g, radius = 12.dp)
            .clickable(onClickLabel = "Copy $url", role = Role.Button) { onCopy() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MonoText(url, modifier = Modifier.weight(1f), color = g.textPrimary)
        Text("Copy", color = g.accentEnd, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
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
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(14.dp))
            .then(
                if (primary) Modifier.background(g.accentBrush)
                else Modifier.glass(g, radius = 14.dp, strong = true),
            )
            .clickable(onClickLabel = text, role = Role.Button) { onClick() }
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

private enum class TestState { IDLE, TESTING, OK, FAIL }

private data class ProbeResult(val ok: Boolean, val detail: String)

/**
 * GET {baseUrl}/v1/health with the bearer token. Distinguishes reachable+authorized
 * (200), reachable-but-wrong-token (401), and unreachable (connect failure) — three
 * actionable outcomes. Uses HttpURLConnection to avoid pulling in a Ktor client dep.
 */
private suspend fun probeHealth(baseUrl: String, token: String): ProbeResult =
    withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        runCatching {
            val url = java.net.URL("${baseUrl.trimEnd('/')}/v1/health")
            val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4000
                readTimeout = 4000
                setRequestProperty("Authorization", "Bearer $token")
            }
            val code = conn.responseCode
            conn.disconnect()
            val ms = System.currentTimeMillis() - started
            when (code) {
                200 -> ProbeResult(true, "Reachable · ${ms}ms")
                401, 403 -> ProbeResult(false, "Reachable but token rejected (HTTP $code)")
                else -> ProbeResult(false, "Unexpected HTTP $code")
            }
        }.getOrElse { e ->
            ProbeResult(false, "Unreachable: ${e.message ?: e::class.java.simpleName}")
        }
    }

@Composable
private fun InactiveTag() {
    val g = rememberGlassTokens()
    Text(
        "INACTIVE",
        color = g.textFaint,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
    )
}
