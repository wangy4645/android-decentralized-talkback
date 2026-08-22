package com.talkback.core.session.failure

/**
 * L1/L2 classification terminals (Phase A). Distinct from B2-1 inputs and UI projection.
 */
sealed class ConferenceFailureTerminal {
    abstract val edgeKey: String
    abstract val runtimeDomainRef: String
    abstract val generationScope: ConferenceFailureGenerationScope

    data class EdgeFailed(
        override val edgeKey: String,
        override val runtimeDomainRef: String,
        override val generationScope: ConferenceFailureGenerationScope,
    ) : ConferenceFailureTerminal()

    data class DomainBlocked(
        val attribution: ConferenceFailureDomainBlockedAttribution,
    ) : ConferenceFailureTerminal() {
        override val edgeKey: String = attribution.impactEdgeKey
        override val runtimeDomainRef: String = attribution.runtimeDomainRef
        override val generationScope: ConferenceFailureGenerationScope = attribution.generationScope
    }
}
