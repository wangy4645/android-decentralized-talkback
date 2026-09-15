package com.talkback.core.conference.transport

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import java.net.MulticastSocket
import java.net.NetworkInterface

/**
 * Product binder: [Network.bindSocket] when [ConferenceMulticastNetworkBinding.boundNetworkId]
 * resolves, plus explicit multicast egress interface ([MulticastSocket.networkInterface]).
 */
class AndroidConferenceMulticastSocketBinder(
    private val resolveNetwork: (String) -> Network?,
) : ConferenceMulticastSocketBinder {
    override fun bindDatagramSocket(
        socket: MulticastSocket,
        binding: ConferenceMulticastNetworkBinding,
    ): MulticastSocketBindResult {
        val iface =
            NetworkInterface.getByName(binding.networkInterfaceName)
                ?: return MulticastSocketBindResult(
                    boundNetworkId = binding.boundNetworkId,
                    networkInterfaceName = null,
                    bindSucceeded = false,
                    error = "INTERFACE_NOT_FOUND:${binding.networkInterfaceName}",
                )

        val networkId = binding.boundNetworkId
        if (networkId != null) {
            val network = resolveNetwork(networkId)
            if (network == null) {
                return MulticastSocketBindResult(
                    boundNetworkId = networkId,
                    networkInterfaceName = iface.name,
                    bindSucceeded = false,
                    error = "NETWORK_NOT_FOUND:$networkId",
                )
            }
            try {
                network.bindSocket(socket)
            } catch (e: Exception) {
                return MulticastSocketBindResult(
                    boundNetworkId = networkId,
                    networkInterfaceName = iface.name,
                    bindSucceeded = false,
                    error = "BIND_SOCKET_FAILED:${e.javaClass.simpleName}:${e.message}",
                )
            }
        }

        return try {
            socket.networkInterface = iface
            MulticastSocketBindResult(
                boundNetworkId = networkId,
                networkInterfaceName = iface.name,
                bindSucceeded = true,
            )
        } catch (e: Exception) {
            MulticastSocketBindResult(
                boundNetworkId = networkId,
                networkInterfaceName = iface.name,
                bindSucceeded = false,
                error = "SET_NETWORK_INTERFACE_FAILED:${e.javaClass.simpleName}:${e.message}",
            )
        }
    }

    companion object {
        fun from(context: Context): AndroidConferenceMulticastSocketBinder {
            val cm =
                context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
                    as ConnectivityManager
            return AndroidConferenceMulticastSocketBinder { networkId ->
                resolveNetworkById(cm, networkId)
            }
        }

        private fun resolveNetworkById(
            connectivityManager: ConnectivityManager,
            networkId: String,
        ): Network? {
            for (network in connectivityManager.allNetworks) {
                val id = network.toString()
                if (id == networkId || id.contains(networkId)) {
                    return network
                }
            }
            return null
        }
    }
}
