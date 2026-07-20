package dev.asik.devicebridge.collectors

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import dev.asik.devicebridge.hub.StreamHub
import dev.asik.devicebridge.model.NetworkReading
import dev.asik.devicebridge.model.WifiInfo
import dev.asik.devicebridge.util.PermissionHelper

class NetworkCollector(
    private val context: Context,
    private val hub: StreamHub,
) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun start() {
        if (callback != null) return
        publishCurrent()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = publishCurrent()
            override fun onLost(network: Network) = publishCurrent()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                publishCurrent()
        }
        callback = cb
        val request = NetworkRequest.Builder().build()
        runCatching { cm.registerNetworkCallback(request, cb) }
    }

    fun stop() {
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
    }

    private fun publishCurrent() {
        val active = cm.activeNetwork
        val caps = active?.let { cm.getNetworkCapabilities(it) }
        val transports = mutableListOf<String>()
        if (caps != null) {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) transports += "wifi"
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) transports += "cellular"
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) transports += "ethernet"
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) transports += "bluetooth"
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) transports += "vpn"
        }
        val wifi = if (transports.contains("wifi")) readWifi() else null
        hub.publishNetwork(
            NetworkReading(
                connected = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
                transport = transports,
                wifi = wifi,
            ),
        )
    }

    @Suppress("DEPRECATION")
    private fun readWifi(): WifiInfo? {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val info = wm.connectionInfo ?: return null
            val ssid = info.ssid
                ?.trim('"')
                ?.takeIf { it.isNotBlank() && it != "<unknown ssid>" && PermissionHelper.hasLocation(context) }
            WifiInfo(
                ssid = ssid,
                bssid = info.bssid,
                rssi = info.rssi,
                link_speed_mbps = info.linkSpeed,
                frequency_mhz = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) info.frequency else null,
            )
        } catch (_: Exception) {
            null
        }
    }
}
