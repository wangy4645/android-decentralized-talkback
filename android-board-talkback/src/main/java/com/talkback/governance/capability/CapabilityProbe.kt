package com.talkback.governance.capability

/**
 * Read-only capability readiness contract. v1 uses adapter probes that delegate to Runtime state.
 */
interface CapabilityProbe {
    val capability: Capability
    fun readiness(channelId: String): CapabilityReadiness
}
