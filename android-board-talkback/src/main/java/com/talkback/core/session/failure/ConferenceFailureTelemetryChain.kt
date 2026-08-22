package com.talkback.core.session.failure

/**
 * Phase A PR-A3: ordered audit chain with validation gates.
 */
data class ConferenceFailureTelemetryChain(
    val scenario: String,
    val events: List<ConferenceFailureTelemetryEvent>,
) {
    val causeEdgeKey: String?
        get() = events.firstOrNull { it.stage == ConferenceFailureTelemetryStage.CAUSE_INPUT_RECORDED }
            ?.holderEdgeKey
            ?: events.firstOrNull { it.stage == ConferenceFailureTelemetryStage.L1_CLASSIFIED }?.edgeKey
            ?: events.firstOrNull { it.stage == ConferenceFailureTelemetryStage.L2_CLASSIFIED }?.causeEdgeKey

    val impactEdgeKey: String?
        get() = events.firstOrNull { it.stage == ConferenceFailureTelemetryStage.L2_CLASSIFIED }?.edgeKey

    fun validate(): ConferenceFailureTelemetryValidation {
        val errors = mutableListOf<String>()

        val l2 = events.filter { it.stage == ConferenceFailureTelemetryStage.L2_CLASSIFIED }
        for (event in l2) {
            if (event.causeEdgeKey.isNullOrBlank()) {
                errors += "orphan DOMAIN_BLOCKED: missing causeEdgeKey on ${event.edgeKey}"
            }
            if (event.runtimeDomainRef.isBlank()) {
                errors += "missing runtimeDomainRef on ${event.edgeKey}"
            }
            if (event.causeFact == null) {
                errors += "DOMAIN_BLOCKED without causeFact on ${event.edgeKey}"
            }
            if (event.leaseBusyInputObserved && event.causeFact == null) {
                errors += "LEASE_BUSY input without derived causeFact on ${event.edgeKey}"
            }
        }

        val causeAt = events.firstOrNull { it.stage == ConferenceFailureTelemetryStage.CAUSE_INPUT_RECORDED }
            ?.observedAtMs
        val impactAt = l2.firstOrNull()?.observedAtMs
        if (causeAt != null && impactAt != null && impactAt < causeAt) {
            errors += "impact emitted before cause observation: causeAt=$causeAt impactAt=$impactAt"
        }

        val generations = events.map { it.generationScope }.distinct()
        if (generations.size > 1) {
            errors += "stale generation attribution: multiple generationScope in chain"
        }

        val l2WithoutProjection = l2.any { l2Event ->
            events.none { it.stage == ConferenceFailureTelemetryStage.PROJECTED && it.edgeKey == l2Event.edgeKey }
        }
        if (l2WithoutProjection) {
            errors += "L2_CLASSIFIED without matching PROJECTED event"
        }

        return if (errors.isEmpty()) {
            ConferenceFailureTelemetryValidation.Valid
        } else {
            ConferenceFailureTelemetryValidation.Invalid(errors)
        }
    }

    fun formatLines(): List<String> =
        events.map { ConferenceFailureTelemetryFormatter.formatEvent(it) } +
            listOf(
                ConferenceFailureTelemetryFormatter.formatChainValidated(
                    scenario = scenario,
                    causeEdgeKey = causeEdgeKey.orEmpty(),
                    impactEdgeKey = impactEdgeKey,
                ),
            )
}

sealed class ConferenceFailureTelemetryValidation {
    data object Valid : ConferenceFailureTelemetryValidation()
    data class Invalid(val errors: List<String>) : ConferenceFailureTelemetryValidation()
}
