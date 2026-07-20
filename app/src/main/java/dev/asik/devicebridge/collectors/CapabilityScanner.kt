package dev.asik.devicebridge.collectors

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.os.Build
import dev.asik.devicebridge.model.CameraInfo
import dev.asik.devicebridge.model.CapabilitiesResponse
import dev.asik.devicebridge.model.DeviceInfo
import dev.asik.devicebridge.model.FeatureFlags
import dev.asik.devicebridge.model.SensorInfo
import dev.asik.devicebridge.util.PermissionHelper

class CapabilityScanner(private val context: Context) {

    fun scan(): CapabilitiesResponse {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensors = sensorManager.getSensorList(Sensor.TYPE_ALL).map { s ->
            SensorInfo(
                type = s.type,
                type_name = sensorTypeName(s.type, s.stringType),
                name = s.name,
                vendor = s.vendor,
                version = s.version,
                max_range = s.maximumRange,
                resolution = s.resolution,
                power_ma = s.power,
                min_delay_us = s.minDelay,
                available = true,
            )
        }

        val cameras = listCameras()
        val locationProviders = listLocationProviders()
        val pm = context.packageManager

        return CapabilitiesResponse(
            device = DeviceInfo(
                manufacturer = Build.MANUFACTURER,
                brand = Build.BRAND,
                model = Build.MODEL,
                product = Build.PRODUCT,
                device = Build.DEVICE,
                sdk_int = Build.VERSION.SDK_INT,
                release = Build.VERSION.RELEASE,
                abis = Build.SUPPORTED_ABIS.toList(),
            ),
            permissions = PermissionHelper.statusMap(context),
            sensors = sensors,
            cameras = cameras,
            location_providers = locationProviders,
            features = FeatureFlags(
                location = pm.hasSystemFeature(PackageManager.FEATURE_LOCATION) ||
                    pm.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS),
                camera = pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY),
                microphone = pm.hasSystemFeature(PackageManager.FEATURE_MICROPHONE),
                telephony = pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY),
                wifi = pm.hasSystemFeature(PackageManager.FEATURE_WIFI),
                bluetooth = pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH),
            ),
        )
    }

    private fun listCameras(): List<CameraInfo> {
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cm.cameraIdList.map { id ->
                val chars = cm.getCameraCharacteristics(id)
                val facing = when (chars.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "front"
                    CameraCharacteristics.LENS_FACING_BACK -> "back"
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
                    else -> "unknown"
                }
                val flash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                val level = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                val levelName = when (level) {
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
                    else -> level?.toString()
                }
                CameraInfo(
                    id = id,
                    facing = facing,
                    has_flash = flash,
                    hardware_level = levelName,
                    available = true,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun listLocationProviders(): List<String> {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = lm.getProviders(true).toMutableList()
        if (!providers.contains("fused")) {
            providers.add("fused")
        }
        return providers
    }

    companion object {
        fun sensorTypeName(type: Int, stringType: String?): String {
            if (!stringType.isNullOrBlank()) return stringType
            return when (type) {
                Sensor.TYPE_ACCELEROMETER -> "android.sensor.accelerometer"
                Sensor.TYPE_GYROSCOPE -> "android.sensor.gyroscope"
                Sensor.TYPE_MAGNETIC_FIELD -> "android.sensor.magnetic_field"
                Sensor.TYPE_LIGHT -> "android.sensor.light"
                Sensor.TYPE_PROXIMITY -> "android.sensor.proximity"
                Sensor.TYPE_PRESSURE -> "android.sensor.pressure"
                Sensor.TYPE_ROTATION_VECTOR -> "android.sensor.rotation_vector"
                Sensor.TYPE_GRAVITY -> "android.sensor.gravity"
                Sensor.TYPE_LINEAR_ACCELERATION -> "android.sensor.linear_acceleration"
                Sensor.TYPE_AMBIENT_TEMPERATURE -> "android.sensor.ambient_temperature"
                Sensor.TYPE_RELATIVE_HUMIDITY -> "android.sensor.relative_humidity"
                Sensor.TYPE_STEP_COUNTER -> "android.sensor.step_counter"
                Sensor.TYPE_STEP_DETECTOR -> "android.sensor.step_detector"
                else -> "android.sensor.type_$type"
            }
        }
    }
}
