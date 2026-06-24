package com.talkback.appprod.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address

object LocalNetworkHelper {
    fun hasLanLink(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            val onLan = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (!onLan) continue
            val linkProps = cm.getLinkProperties(network) ?: continue
            val hasIp = linkProps.linkAddresses.any { address ->
                address.address is Inet4Address &&
                    !address.address.isLoopbackAddress &&
                    !address.address.isLinkLocalAddress
            }
            if (hasIp) return true
        }
        return false
    }

    fun localIpv4(context: Context): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            val onLan = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (!onLan) continue
            val linkProps = cm.getLinkProperties(network) ?: return null
            val ip = linkProps.linkAddresses
                .map { it.address }
                .firstOrNull { address ->
                    address is Inet4Address && !address.isLoopbackAddress && !address.isLinkLocalAddress
                }
                ?.hostAddress
            if (ip != null) return ip
        }
        return null
    }
}
