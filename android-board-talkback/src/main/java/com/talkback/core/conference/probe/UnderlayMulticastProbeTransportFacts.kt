package com.talkback.core.conference.probe

import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface

/**
 * Read-only socket / interface facts for E3a.3a directional failure classification.
 * Does not alter bind, join, or send behavior.
 */
object UnderlayMulticastProbeTransportFacts {
    fun capture(
        config: UnderlayMulticastProbeConfig,
        socket: MulticastSocket,
        joinedNetworkInterfaceName: String?,
    ): Map<String, Any?> {
        return try {
            captureOrThrow(config, socket, joinedNetworkInterfaceName)
        } catch (e: Exception) {
            linkedMapOf<String, Any?>(
                "transportFactsError" to "${e.javaClass.simpleName}: ${e.message}",
            )
        }
    }

    private fun captureOrThrow(
        config: UnderlayMulticastProbeConfig,
        socket: MulticastSocket,
        joinedNetworkInterfaceName: String?,
    ): Map<String, Any?> {
        val localSocket = socket.localSocketAddress as? InetSocketAddress
        val localHost = localSocket?.address ?: socket.localAddress
        val socketIface = socket.networkInterface
        val resolvedIface =
            socketIface
                ?: config.networkInterfaceName?.let { runCatching { NetworkInterface.getByName(it) }.getOrNull() }
        val ifaceIpv4 = interfaceIpv4(resolvedIface)
        val facts = linkedMapOf<String, Any?>(
            "requestedNetworkInterfaceName" to config.networkInterfaceName,
            "socketNetworkInterfaceName" to socketIface?.name,
            "socketNetworkInterfaceIndex" to socketIface?.index,
            "socketLocalIpv4" to (ipv4Host(localHost) ?: ifaceIpv4),
            "networkInterfaceIpv4" to ifaceIpv4,
            "socketBindAddress" to localHost?.hostAddress,
            "socketLocalPort" to socket.localPort,
            "multicastGroupAddress" to config.multicastAddress,
            "multicastGroupPort" to config.mediaPort,
            "joinedNetworkInterfaceName" to joinedNetworkInterfaceName,
        )
        when (config.role) {
            UnderlayMulticastProbeRole.SENDER ->
                facts["destinationMulticastAddress"] = config.multicastAddress
            UnderlayMulticastProbeRole.RECEIVER ->
                facts["joinedMulticastAddress"] = config.multicastAddress
        }
        when (config.role) {
            UnderlayMulticastProbeRole.SENDER ->
                facts["destinationMediaPort"] = config.mediaPort
            UnderlayMulticastProbeRole.RECEIVER ->
                facts["joinedMediaPort"] = config.mediaPort
        }
        return facts
    }

    fun formatLogLine(facts: Map<String, Any?>): String =
        facts.entries.joinToString(" ") { (k, v) -> "$k=$v" }

    private fun ipv4Host(address: java.net.InetAddress?): String? {
        if (address == null) return null
        return when (address) {
            is Inet4Address -> address.hostAddress
            else -> null
        }
    }

    private fun interfaceIpv4(iface: NetworkInterface?): String? {
        if (iface == null) return null
        return iface.inetAddresses.asSequence()
            .mapNotNull { addr -> ipv4Host(addr) }
            .firstOrNull()
    }

    fun resolveInterfaceName(name: String?): String? {
        if (name.isNullOrBlank()) return null
        return try {
            NetworkInterface.getByName(name)?.name
        } catch (_: Exception) {
            null
        }
    }
}
