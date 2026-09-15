package com.talkback.core.conference.transport

import java.net.MulticastSocket

data class MulticastSocketBindResult(
    val boundNetworkId: String?,
    val networkInterfaceName: String?,
    val bindSucceeded: Boolean,
    val error: String? = null,
)

/**
 * Binds multicast datagram sockets to an explicit underlay interface/network.
 * Phase 1: MUST NOT leave socket on default route.
 */
interface ConferenceMulticastSocketBinder {
    fun bindDatagramSocket(
        socket: MulticastSocket,
        binding: ConferenceMulticastNetworkBinding,
    ): MulticastSocketBindResult
}

/**
 * Harness / JVM fallback: sets outgoing multicast interface only (no Android Network).
 */
class InterfaceOnlyMulticastSocketBinder : ConferenceMulticastSocketBinder {
    override fun bindDatagramSocket(
        socket: MulticastSocket,
        binding: ConferenceMulticastNetworkBinding,
    ): MulticastSocketBindResult {
        val iface =
            java.net.NetworkInterface.getByName(binding.networkInterfaceName)
                ?: return MulticastSocketBindResult(
                    boundNetworkId = binding.boundNetworkId,
                    networkInterfaceName = null,
                    bindSucceeded = false,
                    error = "INTERFACE_NOT_FOUND:${binding.networkInterfaceName}",
                )
        return try {
            socket.networkInterface = iface
            MulticastSocketBindResult(
                boundNetworkId = binding.boundNetworkId,
                networkInterfaceName = iface.name,
                bindSucceeded = true,
            )
        } catch (e: Exception) {
            MulticastSocketBindResult(
                boundNetworkId = binding.boundNetworkId,
                networkInterfaceName = iface.name,
                bindSucceeded = false,
                error = "${e.javaClass.simpleName}:${e.message}",
            )
        }
    }
}
