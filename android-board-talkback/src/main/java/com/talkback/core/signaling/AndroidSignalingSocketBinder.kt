package com.talkback.core.signaling

import android.net.Network
import com.talkback.core.network.SelectedOperationalNetworkRegistry
import java.net.DatagramSocket

/** Binds UDP sockets to the active Android [Network]. Trace-only side effect via manager. */
class AndroidSignalingSocketBinder : SignalingSocketBinder {
    @Volatile
    private var activeNetwork: Network? = null

    @Volatile
    private var activeNetworkId: String = SignalingTransportManager.BOUND_NETWORK_UNBOUND

    fun attachRegistry(registry: SelectedOperationalNetworkRegistry) {
        registry.addListener { event ->
            when (event) {
                is SelectedOperationalNetworkRegistry.Event.Selected,
                is SelectedOperationalNetworkRegistry.Event.Updated ->
                    onNetworkAvailable(event.snapshot.network, event.snapshot.networkId)
                is SelectedOperationalNetworkRegistry.Event.Lost ->
                    onNetworkLost(event.snapshot.networkId)
            }
        }
    }

    fun onNetworkAvailable(network: Network, networkId: String) {
        activeNetwork = network
        activeNetworkId = networkId
    }

    fun onNetworkLost(networkId: String = SignalingTransportManager.BOUND_NETWORK_UNBOUND) {
        if (activeNetworkId != networkId) return
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