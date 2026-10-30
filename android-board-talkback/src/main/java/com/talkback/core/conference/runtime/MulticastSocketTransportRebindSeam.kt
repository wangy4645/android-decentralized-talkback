package com.talkback.core.conference.runtime

import com.talkback.core.conference.transport.ConferenceMulticastSocketBinder
import com.talkback.core.conference.transport.InterfaceOnlyMulticastSocketBinder
import com.talkback.core.conference.transport.MulticastSocketBindResult
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketAddress

/**
 * Product C-IG-01 transport rebind seam: real [MulticastSocket] rebuild + group re-join.
 *
 * Rebind MAY rebuild the socket and re-join [MediaGroupEndpointBinding]. It MUST NOT
 * admit Sources, clear HARD FENCE, or change conference generation / Membership (R14 / Q7).
 *
 * Join failure is a local transport result (socket still rebuilt when bind succeeds).
 */
class MulticastSocketTransportRebindSeam(
    private val socketBinder: ConferenceMulticastSocketBinder = InterfaceOnlyMulticastSocketBinder(),
) : TransportRebindSeam {
    override var rebindCount: Int = 0
        private set

    var currentSocket: MulticastSocket? = null
        private set

    var lastJoinAttempted: Boolean = false
        private set

    var lastJoinSucceeded: Boolean = false
        private set

    var lastJoinError: String? = null
        private set

    var lastJoinNetworkInterfaceName: String? = null
        private set

    var lastSocketBindResult: MulticastSocketBindResult? = null
        private set

    private var lastEndpoint: MediaGroupEndpointBinding? = null

    override fun isSocketOpen(): Boolean {
        val socket = currentSocket ?: return false
        return !socket.isClosed
    }

    override fun open(handle: TransportHandle): TransportHandle = replaceSocket(handle)

    override fun rebind(newHandle: TransportHandle): TransportHandle {
        rebindCount += 1
        return replaceSocket(newHandle)
    }

    override fun close() {
        closeCurrent()
        lastEndpoint = null
        lastJoinAttempted = false
        lastJoinSucceeded = false
        lastJoinError = null
        lastJoinNetworkInterfaceName = null
    }

    private fun replaceSocket(handle: TransportHandle): TransportHandle {
        closeCurrent()
        val endpoint = handle.endpoint ?: lastEndpoint
        lastEndpoint = endpoint
        lastJoinAttempted = false
        lastJoinSucceeded = false
        lastJoinError = null
        lastJoinNetworkInterfaceName = null
        lastSocketBindResult = null
        return try {
            val socket = createBoundSocket(endpoint, handle)
            currentSocket = socket
            if (endpoint != null) {
                joinGroup(socket, endpoint, handle.resolvedNetworkBinding()?.networkInterfaceName)
            }
            handle.copy(
                id = socketIdentity(socket),
                endpoint = endpoint,
            )
        } catch (_: Exception) {
            handle.copy(id = "mcast-failed-$rebindCount", endpoint = endpoint)
        }
    }

    private fun createBoundSocket(
        endpoint: MediaGroupEndpointBinding?,
        handle: TransportHandle,
    ): MulticastSocket {
        val preferred = endpoint?.mediaPort ?: 0
        val socket =
            try {
                bindOn(preferred)
            } catch (_: Exception) {
                bindOn(0)
            }
        val binding = handle.resolvedNetworkBinding()
        if (binding != null) {
            lastSocketBindResult = socketBinder.bindDatagramSocket(socket, binding)
            if (!lastSocketBindResult!!.bindSucceeded) {
                throw IllegalStateException(
                    lastSocketBindResult!!.error ?: "multicast socket bind failed",
                )
            }
            lastJoinNetworkInterfaceName = lastSocketBindResult!!.networkInterfaceName
        }
        return socket
    }

    private fun bindOn(port: Int): MulticastSocket {
        val socket = MulticastSocket(null as SocketAddress?)
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(port.coerceAtLeast(0)))
        return socket
    }

    private fun joinGroup(
        socket: MulticastSocket,
        endpoint: MediaGroupEndpointBinding,
        ifaceName: String?,
    ) {
        lastJoinAttempted = true
        try {
            val group = InetAddress.getByName(endpoint.multicastAddress)
            val iface = ifaceName?.let { name -> NetworkInterface.getByName(name) }
            lastJoinNetworkInterfaceName = iface?.name
            if (iface != null) {
                socket.joinGroup(InetSocketAddress(group, endpoint.mediaPort), iface)
            } else {
                @Suppress("DEPRECATION")
                socket.joinGroup(group)
            }
            lastJoinSucceeded = true
            lastJoinError = null
        } catch (e: Exception) {
            lastJoinSucceeded = false
            lastJoinError = "${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun closeCurrent() {
        val socket = currentSocket ?: return
        currentSocket = null
        val endpoint = lastEndpoint
        try {
            if (endpoint != null && !socket.isClosed) {
                try {
                    @Suppress("DEPRECATION")
                    socket.leaveGroup(InetAddress.getByName(endpoint.multicastAddress))
                } catch (_: Exception) {
                    // leave is best-effort before close
                }
            }
            socket.close()
        } catch (_: Exception) {
            // close is best-effort at transport-scope end / rebind
        }
    }

    private fun socketIdentity(socket: MulticastSocket): String =
        "mcast-${socket.localPort}-${System.identityHashCode(socket)}"
}
