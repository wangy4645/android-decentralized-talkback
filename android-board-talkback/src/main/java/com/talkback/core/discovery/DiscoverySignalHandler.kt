package com.talkback.core.discovery

import com.talkback.core.model.SignalEnvelope
import com.talkback.core.signaling.PeerTarget

interface DiscoverySignalHandler {
    fun onDiscoveryProbe(signal: SignalEnvelope, fromPeer: PeerTarget)
    fun onDiscoveryAnnounce(signal: SignalEnvelope, fromPeer: PeerTarget)
}

interface GossipDiscoveryControl {
    fun resetAndSweep()
}
