package dev.asik.devicebridge.util

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

/**
 * Advertises the running bridge on the local network via mDNS / DNS-SD (NsdManager,
 * built into Android — no dependency) so clients can resolve the phone by a stable
 * service name instead of chasing a DHCP-assigned IP.
 *
 * Only meaningful when the server is reachable on the LAN (LAN / Tailscale modes);
 * LOCAL and CLOUDFLARE bind loopback and have nothing to discover.
 */
class MdnsAdvertiser(private val context: Context) {

    private var nsdManager: NsdManager? = null
    private var listener: NsdManager.RegistrationListener? = null

    fun register(port: Int, version: String, authEnabled: Boolean) {
        if (listener != null) return // already registered
        val mgr = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return

        val info = NsdServiceInfo().apply {
            serviceName = "Device Bridge"
            serviceType = SERVICE_TYPE
            setPort(port)
            // TXT records let a client learn version + whether a token is required
            // before connecting. NsdManager encodes these as DNS-SD TXT key/values.
            setAttribute("version", version)
            setAttribute("auth", if (authEnabled) "required" else "none")
            setAttribute("path", "/v1/health")
        }

        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                ErrorLog.info("mdns", "advertised ${info.serviceName} $SERVICE_TYPE:$port")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                ErrorLog.warn("mdns", "registration failed code=$errorCode")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }

        runCatching {
            mgr.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
            nsdManager = mgr
            listener = l
        }.onFailure {
            ErrorLog.warn("mdns", "register threw: ${it.message}")
        }
    }

    fun unregister() {
        val mgr = nsdManager
        val l = listener
        if (mgr != null && l != null) {
            runCatching { mgr.unregisterService(l) }
        }
        nsdManager = null
        listener = null
    }

    companion object {
        const val SERVICE_TYPE = "_devicebridge._tcp."
    }
}
