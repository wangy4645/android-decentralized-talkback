package com.talkback.governance.capability.adapter

import com.talkback.governance.capability.Capability
import com.talkback.governance.capability.CapabilityProbe
import com.talkback.governance.capability.CapabilityReadiness

/**
 * v1 adapter placeholder. Phase 1 wiring delegates to TalkbackCoordinator runtime state.
 */
class StubCapabilityProbe(
    override val capability: Capability,
    private val readinessSupplier: (String) -> CapabilityReadiness
) : CapabilityProbe {
    override fun readiness(channelId: String): CapabilityReadiness = readinessSupplier(channelId)
}
