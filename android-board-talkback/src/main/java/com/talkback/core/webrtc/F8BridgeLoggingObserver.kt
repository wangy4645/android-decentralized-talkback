package com.talkback.core.webrtc

import org.webrtc.NetworkChangeDetector

internal class F8BridgeLoggingObserver(
    private val delegate: NetworkChangeDetector.Observer,
) : NetworkChangeDetector.Observer() {

    override fun onConnectionTypeChanged(newConnectionType: NetworkChangeDetector.ConnectionType) {
        delegate.onConnectionTypeChanged(newConnectionType)
    }

    override fun onNetworkConnect(networkInfo: NetworkChangeDetector.NetworkInformation) {
        F8BridgeDiagnostic.observerNetworkConnect(networkInfo)
        delegate.onNetworkConnect(networkInfo)
    }

    override fun onNetworkDisconnect(networkHandle: Long) {
        F8BridgeDiagnostic.observerNetworkDisconnect(networkHandle)
        delegate.onNetworkDisconnect(networkHandle)
    }

    override fun onNetworkPreference(
        types: MutableList<NetworkChangeDetector.ConnectionType>,
        preference: Int,
    ) {
        delegate.onNetworkPreference(types, preference)
    }
}
