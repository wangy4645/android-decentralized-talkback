package com.talkback.core.conference.capacity

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface

data class Gres3NetworkObservability(
    val senderLocalIp: String?,
    val wlanInterface: String?,
    val wifiSsid: String?,
    val wifiBssid: String?,
    val routeHints: List<Gres3TargetRouteHint>,
)

object Gres3NetworkObservabilityCapture {
    fun capture(
        context: Context,
        wlanInterface: String,
        routeHints: List<Gres3TargetRouteHint>,
    ): Gres3NetworkObservability {
        val localIp = resolveWlanIpv4(wlanInterface)
        val wifiSsid = readWifiSsid(context)
        val wifiBssid = readWifiBssid(context)
        return Gres3NetworkObservability(
            senderLocalIp = localIp,
            wlanInterface = wlanInterface,
            wifiSsid = wifiSsid,
            wifiBssid = wifiBssid,
            routeHints = routeHints,
        )
    }

    fun resolveWlanIpv4(interfaceName: String): String? {
        return try {
            val networkInterface = NetworkInterface.getByName(interfaceName) ?: return null
            networkInterface.inetAddresses.toList()
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress
        } catch (_: Exception) {
            null
        }
    }

    private fun readWifiSsid(context: Context): String? {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val info = wifiManager.connectionInfo ?: return null
            info.ssid?.trim('"')
        } catch (_: Exception) {
            null
        }
    }

    private fun readWifiBssid(context: Context): String? {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiManager.connectionInfo?.bssid
        } catch (_: Exception) {
            null
        }
    }

    fun hasValidatedWifi(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } catch (_: Exception) {
            false
        }
    }
}
