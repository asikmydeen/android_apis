package dev.asik.devicebridge.ui

import android.hardware.Sensor
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.asik.devicebridge.BridgeRuntime
import dev.asik.devicebridge.collectors.CapabilityScanner
import dev.asik.devicebridge.model.SensorInfo
import dev.asik.devicebridge.model.SensorReading
import dev.asik.devicebridge.ui.components.GlassText
import dev.asik.devicebridge.ui.components.GradientBar
import dev.asik.devicebridge.ui.components.LiveDot
import dev.asik.devicebridge.ui.components.MonoText
import dev.asik.devicebridge.ui.components.SectionLabel
import dev.asik.devicebridge.ui.components.glass
import dev.asik.devicebridge.ui.theme.rememberGlassTokens
import dev.asik.devicebridge.util.ErrorLog
import dev.asik.devicebridge.util.TimeUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Diagnostics & reference data — the stuff users rarely act on. Rendered as
 * flat grouped sections (no scroll, no nested cards) so it can live inside a
 * single CollapsibleGlassCard on the Settings screen.
 */
@Composable
fun AdvancedDiagnostics() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val g = rememberGlassTokens()

    val location by BridgeRuntime.hub.location.collectAsState()
    val network by BridgeRuntime.hub.network.collectAsState()
    val usb by BridgeRuntime.hub.usb.collectAsState()
    val running by BridgeRuntime.running.collectAsState()
    val port by BridgeRuntime.portState.collectAsState()

    val capabilities = remember { CapabilityScanner(context).scan() }
    val allSensors = capabilities.sensors
    val allCameras = capabilities.cameras
    val sensorReadings by BridgeRuntime.hub.sensors.collectAsState()

    var logPreview by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // ---- Location & network -------------------------------------
        SectionLabel("Location & network")
        val loc = location
        if (loc != null) {
            MonoText("%.5f, %.5f".format(loc.lat, loc.lon), color = g.textPrimary)
            MonoText("±${loc.accuracy_m}m · ${loc.provider}" + if (loc.stale) " · stale ${loc.age_sec}s" else "")
        } else {
            GlassText("No fix yet", secondary = true, size = 13.sp)
        }
        MonoText(
            network?.let { "net: ${it.transport?.joinToString() ?: "—"} · ${if (it.connected) "connected" else "offline"}" } ?: "net: —",
        )

        Divider()

        // ---- Sensor inventory ---------------------------------------
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Sensor inventory · ${allSensors.size}", modifier = Modifier.weight(1f))
            LiveDot(active = running && sensorReadings.isNotEmpty(), size = 8.dp)
        }
        allSensors.forEach { sensor ->
            SensorRow(sensor, sensorReadings[sensor.type_name])
        }

        Divider()

        // ---- USB -----------------------------------------------------
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("USB", modifier = Modifier.weight(1f))
            LiveDot(active = (usb?.device_count ?: 0) > 0, size = 8.dp)
        }
        GlassText(
            "Host ${if (usb?.host_supported == true) "supported" else "unavailable"} · ${usb?.device_count ?: 0} device(s)",
            secondary = true,
            size = 13.sp,
        )
        usb?.devices?.forEach { d ->
            Column(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).glass(g, radius = 12.dp).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                GlassText("${d.product ?: d.device_name}", weight = FontWeight.SemiBold, size = 13.sp)
                MonoText("${d.vendor_id_hex}:${d.product_id_hex} · #${d.device_id}")
                MonoText("perm ${d.has_permission} · storage ${d.likely_mass_storage} · serial ${d.likely_serial}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!d.has_permission) {
                        SmallAction("Permission") {
                            runCatching {
                                BridgeRuntime.usbCollector.requestPermission(d.device_id)
                                Toast.makeText(context, "Accept USB dialog", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    if (d.likely_serial || !d.likely_mass_storage) {
                        SmallAction("Open serial") {
                            scope.launch {
                                val r = withContext(Dispatchers.IO) {
                                    BridgeRuntime.usbCollector.openSerial(d.device_id, 115200)
                                }
                                Toast.makeText(context, r.message, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            }
        }
        usb?.storage_volumes?.forEach { v ->
            Column(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).glass(g, radius = 12.dp).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MonoText("${v.path ?: v.label} · removable ${v.is_removable}", color = g.textPrimary)
                v.proot_hint?.let { MonoText(it) }
                v.path?.let { path ->
                    SmallAction("Copy proot bind") {
                        copy(context, "proot-distro login ubuntu --bind $path:/mnt/usb")
                        Toast.makeText(context, "proot bind command copied", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        SmallAction("Rescan USB", primary = true) {
            if (!running) {
                Toast.makeText(context, "Start bridge first", Toast.LENGTH_SHORT).show()
            } else {
                BridgeRuntime.usbCollector.rescan()
                Toast.makeText(context, "USB rescanned", Toast.LENGTH_SHORT).show()
            }
        }

        Divider()

        // ---- Cameras -------------------------------------------------
        SectionLabel("Cameras · ${allCameras.size}")
        allCameras.forEach { cam ->
            GlassText("ID ${cam.id} · ${cam.facing} · ${cam.hardware_level}", secondary = true, size = 13.sp)
        }
        SmallAction("Copy capture curl") {
            copy(context, "curl -s -X POST http://127.0.0.1:$port/v1/camera/0/capture")
            Toast.makeText(context, "Capture curl copied", Toast.LENGTH_SHORT).show()
        }

        Divider()

        // ---- Activity log: accepted API requests (who did what) -------
        val requests by BridgeRuntime.registry.recentRequests.collectAsState()
        SectionLabel("Activity log · ${requests.size}")
        GlassText("Recent API requests served (newest first).", secondary = true, size = 12.sp)
        if (requests.isEmpty()) {
            GlassText("No requests yet.", secondary = true, size = 12.sp)
        } else {
            requests.take(30).forEach { r ->
                val clock = TimeUtil.clockOf(r.tsMs)
                Text(
                    "$clock  ${r.status}  ${r.method} ${r.path}  · ${r.remoteIp}",
                    color = if (r.status in 200..299) g.textSecondary else g.warn,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
            }
        }

        Divider()

        // ---- Debug log -----------------------------------------------
        SectionLabel("Debug log")
        SmallAction("Refresh log") {
            logPreview = ErrorLog.recent(15).joinToString("\n") {
                "${it.level} ${it.code}: ${it.message}"
            }.ifBlank { "(empty)" }
        }
        if (logPreview.isNotBlank()) {
            Text(
                logPreview,
                color = g.textSecondary,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }
    }
}

@Composable
private fun Divider() {
    val g = rememberGlassTokens()
    HorizontalDivider(thickness = 0.5.dp, color = g.glassBorder)
}

@Composable
private fun SensorRow(info: SensorInfo, reading: SensorReading?) {
    val g = rememberGlassTokens()
    var expanded by rememberSaveable(info.type_name) { mutableStateOf(false) }
    val rotation = if (expanded) 180f else 0f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { expanded = !expanded }
            .padding(vertical = 8.dp, horizontal = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                GlassText(info.name, weight = FontWeight.SemiBold, size = 14.sp)
                Text(info.type_name, color = g.textFaint, fontSize = 11.sp)
            }
            if (reading != null) {
                LiveDot(active = true, size = 7.dp)
                Spacer(Modifier.width(10.dp))
            }
            Icon(
                Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = g.textFaint,
                modifier = Modifier.size(20.dp).rotate(rotation),
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp, start = 4.dp, end = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Vendor ${info.vendor}", color = g.textFaint, fontSize = 11.sp)
                    Text("Range ${info.max_range}", color = g.textFaint, fontSize = 11.sp)
                }
                if (reading != null) {
                    SensorValues(info, reading)
                } else {
                    Text("Waiting for reading…", color = g.textFaint, fontSize = 12.sp, fontStyle = FontStyle.Italic)
                }
            }
        }
    }
}

@Composable
private fun SensorValues(info: SensorInfo, reading: SensorReading) {
    val g = rememberGlassTokens()
    val labels = when (info.type) {
        Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_ACCELEROMETER_UNCALIBRATED,
        Sensor.TYPE_GYROSCOPE, Sensor.TYPE_GYROSCOPE_UNCALIBRATED,
        Sensor.TYPE_MAGNETIC_FIELD, Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED,
        Sensor.TYPE_GRAVITY, Sensor.TYPE_LINEAR_ACCELERATION,
        Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GAME_ROTATION_VECTOR -> listOf("X", "Y", "Z", "W")
        else -> emptyList()
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        reading.values.forEachIndexed { i, value ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    labels.getOrNull(i) ?: "V${i + 1}",
                    color = g.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(28.dp),
                )
                Text(
                    when (info.type) {
                        Sensor.TYPE_LIGHT -> "%.1f lx".format(value)
                        Sensor.TYPE_STEP_COUNTER -> "${value.toInt()} steps"
                        else -> "%.3f".format(value)
                    },
                    color = g.textPrimary, fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                    modifier = Modifier.width(96.dp),
                )
                if (info.max_range > 0 && info.type != Sensor.TYPE_STEP_COUNTER) {
                    val progress = (value.coerceIn(-info.max_range, info.max_range) + info.max_range) / (2 * info.max_range)
                    GradientBar(progress = progress, modifier = Modifier.weight(1f).padding(start = 8.dp), height = 6.dp)
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
        Text("accuracy ${reading.accuracy} · ts ${reading.timestamp_ns}", color = g.textFaint, fontSize = 10.sp)
    }
}

@Composable
private fun SmallAction(text: String, primary: Boolean = false, onClick: () -> Unit) {
    val g = rememberGlassTokens()
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .then(if (primary) Modifier.background(g.accentStart) else Modifier.glass(g, radius = 12.dp, strong = true))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(text, color = if (primary) Color.White else g.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
    }
}
