package dev.asik.devicebridge.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object PermissionHelper {
    val runtimePermissions: List<String>
        get() = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.BODY_SENSORS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(Manifest.permission.ACTIVITY_RECOGNITION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.HIGH_SAMPLING_RATE_SENSORS)
            }
        }

    fun statusMap(context: Context): Map<String, String> {
        return runtimePermissions.associate { perm ->
            val short = perm.removePrefix("android.permission.")
            short to if (isGranted(context, perm)) "granted" else "denied"
        }
    }

    fun isGranted(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun hasLocation(context: Context): Boolean {
        return isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
            isGranted(context, Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    fun hasCamera(context: Context): Boolean {
        return isGranted(context, Manifest.permission.CAMERA)
    }

    fun hasMicrophone(context: Context): Boolean {
        return isGranted(context, Manifest.permission.RECORD_AUDIO)
    }
}
