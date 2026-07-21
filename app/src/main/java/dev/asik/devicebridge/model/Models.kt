package dev.asik.devicebridge.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class HealthResponse(
    val ok: Boolean = true,
    val service: String = "device-bridge",
    val version: String,
    val uptime_sec: Long,
    val server_time: String,
    val running: Boolean = true,
    val degraded: Boolean = false,
    val diagnostics_url: String = "/v1/diagnostics",
)

@Serializable
data class LogEntry(
    val ts: String,
    val level: String,
    val code: String,
    val message: String,
    val detail: String? = null,
)

@Serializable
data class CollectorStatus(
    val name: String,
    val status: String,
    val detail: String? = null,
    val last_ok_age_sec: Long? = null,
)

@Serializable
data class DiagnosticsResponse(
    val ok: Boolean,
    val degraded: Boolean,
    val version: String,
    val uptime_sec: Long,
    val server_time: String,
    val base_url: String,
    val collectors: List<CollectorStatus>,
    val location: LocationDiag? = null,
    val usb: UsbDiag? = null,
    val permissions: Map<String, String> = emptyMap(),
    val battery_optimization_ignored: Boolean? = null,
    val hints: List<String> = emptyList(),
    val recent_errors: List<LogEntry> = emptyList(),
)

@Serializable
data class LocationDiag(
    val has_permission: Boolean,
    val has_fix: Boolean,
    val stale: Boolean = false,
    val age_sec: Long? = null,
    val provider: String? = null,
    val accuracy_m: Float? = null,
)

@Serializable
data class UsbDiag(
    val host_supported: Boolean,
    val device_count: Int,
    val storage_volume_count: Int,
    val serial_open: List<String> = emptyList(),
    val last_event: String? = null,
)

@Serializable
data class DeviceInfo(
    val manufacturer: String,
    val brand: String,
    val model: String,
    val product: String,
    val device: String,
    val sdk_int: Int,
    val release: String,
    val abis: List<String>,
)

@Serializable
data class SensorInfo(
    val type: Int,
    val type_name: String,
    val name: String,
    val vendor: String,
    val version: Int,
    val max_range: Float,
    val resolution: Float,
    val power_ma: Float,
    val min_delay_us: Int,
    val available: Boolean = true,
)

@Serializable
data class CameraInfo(
    val id: String,
    val facing: String,
    val has_flash: Boolean,
    val hardware_level: String? = null,
    val available: Boolean = true,
)

@Serializable
data class FeatureFlags(
    val location: Boolean,
    val camera: Boolean,
    val microphone: Boolean,
    val telephony: Boolean,
    val wifi: Boolean,
    val bluetooth: Boolean,
)

@Serializable
data class CapabilitiesResponse(
    val device: DeviceInfo,
    val permissions: Map<String, String>,
    val sensors: List<SensorInfo>,
    val cameras: List<CameraInfo>,
    val location_providers: List<String>,
    val features: FeatureFlags,
)

@Serializable
data class LocationReading(
    val lat: Double,
    val lon: Double,
    val alt_m: Double? = null,
    val accuracy_m: Float? = null,
    val speed_mps: Float? = null,
    val bearing_deg: Float? = null,
    val provider: String? = null,
    val time_ms: Long? = null,
    val elapsed_realtime_nanos: Long? = null,
    /** Wall clock when the bridge received this fix. */
    val received_at_ms: Long? = null,
    /** True when serving a cached fix older than the freshness threshold. */
    val stale: Boolean = false,
    val age_sec: Long? = null,
)

@Serializable
data class BatteryReading(
    val percent: Int?,
    val status: String,
    val plugged: String,
    val health: String? = null,
    val temp_c: Float? = null,
    val voltage_mv: Int? = null,
    val current_ua: Int? = null,
    val technology: String? = null,
)

@Serializable
data class WifiInfo(
    val ssid: String? = null,
    val bssid: String? = null,
    val rssi: Int? = null,
    val link_speed_mbps: Int? = null,
    val frequency_mhz: Int? = null,
)

@Serializable
data class NetworkReading(
    val connected: Boolean,
    val transport: List<String>,
    val wifi: WifiInfo? = null,
)

@Serializable
data class TelephonyReading(
    val network_operator_name: String? = null,
    val sim_operator_name: String? = null,
    val data_network_type: String? = null,
    val phone_type: String? = null,
    val sim_state: String? = null,
    val restricted: Boolean = false,
    val note: String? = null,
)

@Serializable
data class SensorReading(
    val values: List<Float>,
    val accuracy: Int,
    val timestamp_ns: Long,
    val type: Int? = null,
    val type_name: String? = null,
)

@Serializable
data class CameraMeta(
    val active_camera_id: String? = null,
    val last_capture: CaptureMeta? = null,
)

@Serializable
data class CaptureMeta(
    val camera_id: String,
    val path: String,
    val bytes: Int,
    val captured_at: String,
    val content_url: String = "/v1/camera/last.jpg",
)

@Serializable
data class CaptureResponse(
    val ok: Boolean,
    val capture: CaptureMeta,
    val base64_jpeg: String? = null,
)

@Serializable
data class UsbInterfaceInfo(
    val id: Int,
    val iface_class: Int,
    val iface_subclass: Int,
    val iface_protocol: Int,
    val endpoint_count: Int,
    val name: String? = null,
)

@Serializable
data class UsbDeviceInfo(
    val device_id: String,
    val device_name: String,
    val vendor_id: Int,
    val product_id: Int,
    val vendor_id_hex: String,
    val product_id_hex: String,
    val device_class: Int,
    val device_subclass: Int,
    val device_protocol: Int,
    val manufacturer: String? = null,
    val product: String? = null,
    val serial: String? = null,
    val has_permission: Boolean,
    val interface_count: Int,
    val interfaces: List<UsbInterfaceInfo> = emptyList(),
    val likely_mass_storage: Boolean = false,
    val likely_serial: Boolean = false,
)

@Serializable
data class UsbStorageVolume(
    val id: String,
    val label: String? = null,
    val path: String? = null,
    val state: String? = null,
    val is_removable: Boolean = false,
    val is_primary: Boolean = false,
    val description: String? = null,
    val proot_hint: String? = null,
)

@Serializable
data class UsbOverview(
    val host_supported: Boolean,
    val device_count: Int,
    val devices: List<UsbDeviceInfo>,
    val storage_volumes: List<UsbStorageVolume>,
    val notes: List<String> = emptyList(),
)

@Serializable
data class UsbEvent(
    val action: String,
    val device: UsbDeviceInfo? = null,
    val message: String? = null,
)

@Serializable
data class UsbSerialOpenResponse(
    val ok: Boolean,
    val device_id: String,
    val baud_rate: Int,
    val message: String,
    val stream_ws: String,
)

@Serializable
data class DeviceSnapshot(
    val timestamp: String,
    val location: LocationReading? = null,
    val battery: BatteryReading? = null,
    val network: NetworkReading? = null,
    val telephony: TelephonyReading? = null,
    val sensors: Map<String, SensorReading> = emptyMap(),
    val camera_meta: CameraMeta? = null,
    val usb: UsbOverview? = null,
    val errors: List<String> = emptyList(),
)

@Serializable
data class ApiErrorBody(
    val error: ApiError,
)

@Serializable
data class ApiError(
    val code: String,
    val message: String,
    val permission: String? = null,
)

@Serializable
data class ConfigResponse(
    val bind: String,
    val port: Int,
    val base_url: String,
    val auth_enabled: Boolean,
    val auth_required_hint: String? = null,
    val version: String,
    val network_mode: String = "LOCAL",
    val public_url: String? = null,
    val lan_urls: List<String> = emptyList(),
    val tailscale_urls: List<String> = emptyList(),
    val all_urls: List<String> = emptyList(),
    val cloudflared_command: String? = null,
)

@Serializable
data class StreamEnvelope(
    val topic: String,
    val ts: String,
    val data: JsonElement = JsonObject(emptyMap()),
)

@Serializable
data class ClientControlMessage(
    val op: String,
    val topics: List<String>? = null,
    val topic: String? = null,
    val hz: Int? = null,
)

@Serializable
data class SimpleStatus(
    val ok: Boolean,
    val message: String,
)
