package com.talkback.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.talkback.core.network.SelectedOperationalNetworkRegistry
import com.talkback.core.signaling.SignalingTransportManager

/** C6: network capability facts -> transport manager. Does not mutate recovery state. */
class NetworkCapabilityObserver(
    context: Context,
    private val transportManager: SignalingTransportManager,
    private val operationalNetworkRegistry: SelectedOperationalNetworkRegistry,
) {
    private val connectivity =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun start() {
        if (callback != null) return
        val cb =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    publishAvailable(network)
                }

                override fun onLost(network: Network) {
                    publishLost(network)
                }

                override fun onLinkPropertiesChanged(
                    network: Network,
                    linkProperties: LinkProperties,
                ) {
                    operationalNetworkRegistry.onNetworkPropertiesChanged(network)
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) {
                    operationalNetworkRegistry.onNetworkPropertiesChanged(network)
                }
            }
        callback = cb
        connectivity.registerNetworkCallback(NetworkRequest.Builder().build(), cb)
        connectivity.activeNetwork?.let(::publishAvailable)
    }

    fun stop() {
        callback?.let { connectivity.unregisterNetworkCallback(it) }
        callback = null
    }

    private fun publishAvailable(network: Network) {
        val networkId = network.toString()
        val caps = connectivity.getNetworkCapabilities(network)
        val iface = caps?.let { describeInterface(it) } ?: "unknown"
        operationalNetworkRegistry.onNetworkAvailable(network)
        operationalNetworkRegistry.onNetworkPropertiesChanged(network)
        transportManager.onNetworkAvailable(networkId, iface)
    }

    private fun publishLost(network: Network) {
        val networkId = network.toString()
        val caps = runCatching { connectivity.getNetworkCapabilities(network) }.getOrNull()
        val iface = caps?.let { describeInterface(it) } ?: "unknown"
        operationalNetworkRegistry.onNetworkLost(network)
        transportManager.onNetworkLost(networkId, iface)
    }

    private fun describeInterface(caps: NetworkCapabilities): String =
        when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
}
