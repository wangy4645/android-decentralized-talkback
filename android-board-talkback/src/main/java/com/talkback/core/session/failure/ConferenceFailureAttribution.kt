package com.talkback.core.session.failure

/**
 * L2 DOMAIN_BLOCKED minimum attribution tuple (Phase A AUTH-PA-3).
 */
data class ConferenceFailureDomainBlockedAttribution(
    val impactEdgeKey: String,
    val runtimeDomainRef: String,
    val causeEdgeKey: String,
    val causeFact: ConferenceFailureCauseFact,
    val contentionKind: String = CONTENTION_KIND,
    val blockReason: String = BLOCK_REASON,
    val generationScope: ConferenceFailureGenerationScope,
    val causePhase: ConferenceFailureCausePhase,
) {
    init {
        require(impactEdgeKey.isNotBlank()) { "impactEdgeKey required" }
        require(runtimeDomainRef.isNotBlank()) { "runtimeDomainRef required" }
        require(causeEdgeKey.isNotBlank()) { "causeEdgeKey required" }
        require(contentionKind == CONTENTION_KIND) { "contentionKind frozen" }
        require(blockReason == BLOCK_REASON) { "blockReason frozen" }
    }

    companion object {
        const val CONTENTION_KIND = "RUNTIME_DOMAIN_CONTENTION"
        const val BLOCK_REASON = "RUNTIME_DOMAIN_UNAVAILABLE"
    }
}
