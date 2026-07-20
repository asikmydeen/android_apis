package dev.asik.devicebridge.collectors

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dev.asik.devicebridge.hub.StreamHub
import dev.asik.devicebridge.model.LocationReading
import dev.asik.devicebridge.util.PermissionHelper

class LocationCollector(
    private val context: Context,
    private val hub: StreamHub,
) {
    private val fused = LocationServices.getFusedLocationProviderClient(context)
    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var usingFused = false
    private var running = false

    private val fusedCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { publish(it, "fused") }
        }
    }

    private val legacyListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            publish(location, location.provider ?: "location_manager")
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    @SuppressLint("MissingPermission")
    fun start(intervalMs: Long = 2000L) {
        if (running) return
        if (!PermissionHelper.hasLocation(context)) return
        running = true

        // Last known quickly
        runCatching {
            fused.lastLocation.addOnSuccessListener { loc ->
                loc?.let { publish(it, "fused_last") }
            }
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .setWaitForAccurateLocation(false)
            .build()

        try {
            fused.requestLocationUpdates(request, fusedCallback, Looper.getMainLooper())
            usingFused = true
        } catch (_: Exception) {
            usingFused = false
            startLegacy(intervalMs)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLegacy(intervalMs: Long) {
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )
        for (p in providers) {
            if (locationManager.isProviderEnabled(p)) {
                runCatching {
                    locationManager.requestLocationUpdates(
                        p,
                        intervalMs,
                        0f,
                        legacyListener,
                        Looper.getMainLooper(),
                    )
                }
                runCatching {
                    locationManager.getLastKnownLocation(p)?.let { publish(it, p) }
                }
            }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        if (usingFused) {
            runCatching { fused.removeLocationUpdates(fusedCallback) }
        }
        runCatching { locationManager.removeUpdates(legacyListener) }
        usingFused = false
    }

    private fun publish(location: Location, provider: String) {
        hub.publishLocation(
            LocationReading(
                lat = location.latitude,
                lon = location.longitude,
                alt_m = if (location.hasAltitude()) location.altitude else null,
                accuracy_m = if (location.hasAccuracy()) location.accuracy else null,
                speed_mps = if (location.hasSpeed()) location.speed else null,
                bearing_deg = if (location.hasBearing()) location.bearing else null,
                provider = provider,
                time_ms = location.time,
                elapsed_realtime_nanos = location.elapsedRealtimeNanos,
            ),
        )
    }
}
