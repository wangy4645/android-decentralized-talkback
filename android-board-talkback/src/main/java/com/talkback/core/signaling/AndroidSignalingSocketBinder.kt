package com.talkback.core.signaling

import android.net.Network
import java.net.DatagramSocket

/** Binds UDP sockets to the active Android [Network]. Trace-only side effect via manager. */
class AndroidSignalingSocketBinder : SignalingSocketBinder {
    @Volatile
    private var activeNetwork: Network? = null

    @Volatile
    private var activeNetworkId: String = SignalingTransportManager.BOUND_NETWORK_UNBOUND

    fun onNetworkAvailable(network: Network, networkId: String) {
        activeNetwork = network
        activeNetworkId = networkId
    }

    fun onNetworkLost() {
        activeNetwork = null
        activeNetworkId = SignalingTransportManager.BOUND_NETWORK_UNBOUND
    }

    override fun bindSocket(socket: DatagramSocket): String {
        val network = activeNetwork ?: return SignalingTransportManager.BOUND_NETWORK_UNBOUND
        return runCatching {
            network.bindSocket(socket)
            activeNetworkId
        }.getOrElse { SignalingTransportManager.BOUND_NETWORK_UNBOUND }
    }
}