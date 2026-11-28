package io.github.jonjonesbr.jangada.lan

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Descobre o endereço IPv4 local do aparelho na rede (preferindo Wi-Fi) para acesso via LAN.
 */
object LanNetwork {

    fun findLocalIpv4(context: Context): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        if (cm != null) {
            val active = cm.activeNetwork
            val caps = active?.let { cm.getNetworkCapabilities(it) }
            val preferLink = caps != null &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && (
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                    )
            if (preferLink && active != null) {
                val props = cm.getLinkProperties(active)
                val ip = props?.linkAddresses
                    ?.mapNotNull { it.address as? Inet4Address }
                    ?.firstOrNull { isUsableLocalIpv4(it) }
                if (ip != null) return ip.hostAddress
            }
        }

        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull() ?: return null
        val candidates = ArrayList<Pair<Int, String>>()
        for (iface in interfaces) {
            val up = runCatching { iface.isUp && !iface.isLoopback }.getOrDefault(false)
            if (!up) continue
            val name = iface.name.lowercase()
            if (name.startsWith("tun") || name.startsWith("tap") || name.startsWith("ppp") || name.startsWith("wg") || name.startsWith("tailscale")) {
                continue
            }
            val priority = when {
                name.startsWith("wlan") || name.startsWith("eth") -> 0
                name.startsWith("ap") || name.startsWith("swlan") -> 1
                else -> 2
            }
            for (addr in iface.inetAddresses) {
                if (addr is Inet4Address && isUsableLocalIpv4(addr)) {
                    addr.hostAddress?.let { candidates += priority to it }
                }
            }
        }
        return candidates.minByOrNull { it.first }?.second
    }

    private fun isUsableLocalIpv4(addr: Inet4Address): Boolean {
        if (addr.isLoopbackAddress || addr.isLinkLocalAddress) return false
        return addr.isSiteLocalAddress
    }
}
