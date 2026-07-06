package com.talkback.governance.gate

import com.talkback.governance.capability.Capability

internal object CapabilityBlockReasons {
    fun readinessReason(capability: Capability): ReadinessReason = when (capability) {
        Capability.Membership -> ReadinessReason.MembershipNotReady
        Capability.Routing -> ReadinessReason.RoutingNotReady
        Capability.Authority -> ReadinessReason.AuthorityNotReady
        Capability.Conference -> ReadinessReason.ConferenceNotReady
        Capability.Media -> ReadinessReason.MediaNotReady
        Capability.Directory -> ReadinessReason.DirectoryNotReady
    }
}
