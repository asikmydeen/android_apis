package dev.asik.devicebridge.util

import android.content.Context
import android.os.PowerManager
import dev.asik.devicebridge.BridgeRuntime
import dev.asik.devicebridge.model.CollectorStatus
import dev.asik.devicebridge.model.DiagnosticsResponse
import dev.asik.devicebridge.model.LocationDiag
import dev.asik.devicebridge.model.UsbDiag

object DiagnosticsBuilder {
    /** Location fresher than this is "ok"; older is stale but still returned. */
    const val LOCATION_STALE_SEC = 30L
    const val LOCATION_DEAD_SEC = 120L

    fun build(context: Context): DiagnosticsResponse {
        val hub = BridgeRuntime.hub
        val started = BridgeRuntime.startedAtMs.value
        val uptime = if (started > 0) (System.currentTimeMillis() - started) / 1000 else 0L
        val loc = hub.location.value
        val age = loc?.let { ageSec(it.received_at_ms ?: it.time_ms) }
        val hasPerm = PermissionHelper.hasLocation(context)
        val locStatus = when {
            !hasPerm -> CollectorStatus("location", "permission_denied", "Grant fine/coarse location")
            loc == null -> CollectorStatus("location", "no_fix", "No fix yet — outdoors / enable Location")
            age != null && age > LOCATION_DEAD_SEC ->
                CollectorStatus("location", "stale", "Last fix ${age}s ago", age)
            age != null && age > LOCATION_STALE_SEC ->
                CollectorStatus("location", "degraded", "Fix aging (${age}s)", age)
            else -> CollectorStatus("location", "ok", last_ok_age_sec = age)
        }

        val battery = hub.battery.value
        val batteryStatus = if (battery == null) {
            CollectorStatus("battery", "no_data")
        } else {
            CollectorStatus("battery", "ok", "${battery.percent}% ${battery.status}")
        }

        val network = hub.network.value
        val networkStatus = if (network == null) {
            CollectorStatus("network", "no_data")
        } else {
            CollectorStatus(
                "network",
                if (network.connected) "ok" else "degraded",
                network.transport.joinToString(",").ifEmpty { "disconnected" },
            )
        }

        val sensors = hub.sensorSnapshot()
        val sensorStatus = when {
            sensors.isEmpty() -> CollectorStatus("sensors", "no_data", "No readings yet")
            else -> CollectorStatus("sensors", "ok", "${sensors.size} types")
        }

        val usb = hub.usb.value
        val openIds = runCatching { BridgeRuntime.usbCollector.openSerialDeviceIds() }.getOrDefault(emptyList())
        val usbStatus = when {
            usb == null -> CollectorStatus("usb", "no_data")
            !usb.host_supported -> CollectorStatus("usb", "unsupported", "USB host feature not advertised")
            else -> CollectorStatus(
                "usb",
                "ok",
                "${usb.device_count} device(s), ${usb.storage_volumes.size} volume(s), serial_open=${openIds.size}",
            )
        }

        val collectors = listOf(locStatus, batteryStatus, networkStatus, sensorStatus, usbStatus)
        val degraded = collectors.any { it.status != "ok" }
        val ok = BridgeRuntime.running.value && collectors.none { it.status == "error" }

        val hints = mutableListOf<String>()
        if (!BridgeRuntime.running.value) {
            hints += "Bridge not running — open Device Bridge and tap Start bridge"
        }
        if (!hasPerm) hints += "Grant location permission"
        if (locStatus.status == "no_fix" || locStatus.status == "stale") {
            hints += "Enable system Location; wait outdoors for GPS; keep notification active"
        }
        if (!isIgnoringBatteryOptimizations(context)) {
            hints += "Set battery usage to Unrestricted (Samsung kills background services otherwise)"
        }
        if (usb != null && usb.device_count > 0 && openIds.isEmpty()) {
            val needsPerm = usb.devices.any { !it.has_permission }
            if (needsPerm) hints += "USB device needs permission: POST /v1/usb/devices/{id}/permission"
        }
        if (usb != null && usb.storage_volumes.none { it.is_removable && !it.is_primary }) {
            hints += "For flash drives: plug OTG, unlock phone, open Files once, then GET /v1/usb/storage"
        }

        return DiagnosticsResponse(
            ok = ok && !degraded,
            degraded = degraded,
            version = BridgeRuntime.VERSION,
            uptime_sec = uptime,
            server_time = TimeUtil.nowIso(),
            base_url = BridgeRuntime.baseUrl(),
            collectors = collectors,
            location = LocationDiag(
                has_permission = hasPerm,
                has_fix = loc != null,
                stale = age != null && age > LOCATION_STALE_SEC,
                age_sec = age,
                provider = loc?.provider,
                accuracy_m = loc?.accuracy_m,
            ),
            usb = UsbDiag(
                host_supported = usb?.host_supported ?: false,
                device_count = usb?.device_count ?: 0,
                storage_volume_count = usb?.storage_volumes?.size ?: 0,
                serial_open = openIds,
                last_event = null,
            ),
            permissions = PermissionHelper.statusMap(context),
            battery_optimization_ignored = isIgnoringBatteryOptimizations(context),
            hints = hints,
            recent_errors = ErrorLog.recent(40),
        )
    }

    fun enrichLocation(reading: dev.asik.devicebridge.model.LocationReading?): dev.asik.devicebridge.model.LocationReading? {
        if (reading == null) return null
        val age = ageSec(reading.received_at_ms ?: reading.time_ms) ?: return reading
        return reading.copy(
            age_sec = age,
            stale = age > LOCATION_STALE_SEC,
        )
    }

    private fun ageSec(epochMs: Long?): Long? {
        if (epochMs == null || epochMs <= 0) return null
        return ((System.currentTimeMillis() - epochMs) / 1000).coerceAtLeast(0)
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } catch (_: Exception) {
            null
        } == true
    }
}
