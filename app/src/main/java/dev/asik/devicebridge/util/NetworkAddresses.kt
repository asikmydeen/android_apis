package dev.asik.devicebridge.util

import java.net.Inet4Address
import java.net.NetworkInterface

data class HostAddress(
    val interfaceName: String,
    val ip: String,
    val isLoopback: Boolean,
    val isTailscale: Boolean,
    val isPrivateLan: Boolean,
)

object NetworkAddresses {
    /** Classic Tailscale CGNAT range 100.64.0.0/10 */
    fun isTailscaleIp(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false
        val a = parts[0].toIntOrNull() ?: return false
        val b = parts[1].toIntOrNull() ?: return false
        return a == 100 && b in 64..127
    }

    fun isPrivateLan(ip: String): Boolean {
        if (ip.startsWith("10.")) return true
        if (ip.startsWith("192.168.")) return true
        val parts = ip.split(".")
        if (parts.size == 4) {
            val a = parts[0].toIntOrNull() ?: return false
            val b = parts[1].toIntOrNull() ?: return false
            if (a == 172 && b in 16..31) return true
        }
        return false
    }

    fun allIpv4(): List<HostAddress> {
        val out = mutableListOf<HostAddress>()
        runCatching {
            val en = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            while (en.hasMoreElements()) {
                val nif = en.nextElement()
                if (!nif.isUp) continue
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr !is Inet4Address) continue
                    val ip = addr.hostAddress ?: continue
                    out += HostAddress(
                        interfaceName = nif.name ?: "?",
                        ip = ip,
                        isLoopback = addr.isLoopbackAddress,
                        isTailscale = isTailscaleIp(ip) ||
                            (nif.name?.contains("tailscale", ignoreCase = true) == true),
                        isPrivateLan = isPrivateLan(ip) && !addr.isLoopbackAddress,
                    )
                }
            }
        }
        return out.distinctBy { it.ip }
    }

    fun tailscaleIps(): List<String> =
        allIpv4().filter { it.isTailscale }.map { it.ip }

    fun lanIps(): List<String> =
        allIpv4().filter { it.isPrivateLan && !it.isTailscale && !it.isLoopback }.map { it.ip }

    fun urlsForPort(port: Int): List<String> {
        return buildList {
            add("http://127.0.0.1:$port")
            lanIps().forEach { add("http://$it:$port") }
            tailscaleIps().forEach { add("http://$it:$port") }
        }.distinct()
    }
}
