package com.talkback.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.talkback.core.signaling.AndroidSignalingSocketBinder
import com.talkback.core.signaling.SignalingTransportManager

/** C6: network capability facts -> transport manager. Does not mutate recovery state. */
class NetworkCapabilityObserver(
    context: Context,
    private val transportManager: SignalingTransportManager,
    private val socketBinder: AndroidSignalingSocketBinder
) {
    private val connectivity =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun start() {
        if (callback != null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val networkId = network.toString()
                val caps = connectivity.getNetworkCapabilities(network)
                val iface = caps?.let { describeInterface(it) } ?: "unknown"
                socketBinder.onNetworkAvailable(network, networkId)
                transportManager.onNetworkAvailable(networkId, iface)
            }

            override fun onLost(network: Network) {
                val networkId = network.toString()
                val caps = runCatching { connectivity.getNetworkCapabilities(network) }.getOrNull()
                val iface = caps?.let { describeInterface(it) } ?: "unknown"
                socketBinder.onNetworkLost()
                transportManager.onNetworkLost(networkId, iface)
            }
        }
        callback = cb
        connectivity.registerNetworkCallback(NetworkRequest.Builder().build(), cb)
        connectivity.activeNetwork?.let { network ->
            val networkId = network.toString()
            val caps = connectivity.getNetworkCapabilities(network)
            val iface = caps?.let { describeInterface(it) } ?: "unknown"
            socketBinder.onNetworkAvailable(network, networkId)
            transportManager.onNetworkAvailable(networkId, iface)
        }
    }

    fun stop() {
        callback?.let { connectivity.unregisterNetworkCallback(it) }
        callback = null
    }

    private fun describeInterface(caps: NetworkCapabilities): String = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        else -> "other"
    }
}