package com.talkback.governance.capability

data class CapabilitySnapshot(
    val channelId: String,
    val byCapability: Map<Capability, CapabilityReadiness>
) {
    fun readiness(capability: Capability): CapabilityReadiness =
        byCapability[capability] ?: CapabilityReadiness.NOT_READY
}

fun capabilitySnapshot(channelId: String, probes: Iterable<CapabilityProbe>): CapabilitySnapshot {
    val merged = LinkedHashMap<Capability, CapabilityReadiness>()
    probes.forEach { probe ->
        merged[probe.capability] = probe.readiness(channelId)
    }
    return CapabilitySnapshot(channelId, merged)
}
