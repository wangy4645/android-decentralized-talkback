package com.talkback.core.session.failure

/**
 * Phase A PR-A1: pure L1/L2 failure classifier.
 *
 * Runtime observation → classification → terminal representation.
 * No side effects · no projection · no topology/recovery authority.
 */
object ConferenceFailureClassifier {

    /**
     * Classify self-attributed L1 failure on [observation]'s edge.
     * Returns null when symptoms insufficient or edge is impact-only (peer-caused block).
     */
    fun classifyEdgeFailure(observation: ConferenceFailureObservation): ConferenceFailureTerminal.EdgeFailed? {
        if (isPeerCausedImpact(observation)) return null
        if (!hasSelfAttributionSymptoms(observation)) return null
        return ConferenceFailureTerminal.EdgeFailed(
            edgeKey = observation.edgeKey,
            runtimeDomainRef = observation.runtimeDomainRef,
            generationScope = observation.generationScope,
        )
    }

    /**
     * Classify L2 domain contention impact on [impactObservation]'s edge.
     *
     * [causeObservation] optional — refines causePhase when cause edge already L1 terminal.
     * LEASE_BUSY alone is insufficient; requires [ConferenceFailureCauseFact] derivation.
     */
    fun classifyDomainContention(
        impactObservation: ConferenceFailureObservation,
        causeObservation: ConferenceFailureObservation? = null,
        knownCausePhase: ConferenceFailureCausePhase? = null,
    ): ConferenceFailureTerminal.DomainBlocked? {
        val causeDerivation = deriveCause(impactObservation) ?: return null
        if (impactObservation.edgeKey == causeDerivation.causeEdgeKey) return null
        if (classifyEdgeFailure(impactObservation) != null && !isPeerCausedImpact(impactObservation)) {
            return null
        }
        val causePhase = knownCausePhase
            ?: resolveCausePhase(causeObservation, causeDerivation.causeEdgeKey)
        val attribution = ConferenceFailureDomainBlockedAttribution(
            impactEdgeKey = impactObservation.edgeKey,
            runtimeDomainRef = impactObservation.runtimeDomainRef,
            causeEdgeKey = causeDerivation.causeEdgeKey,
            causeFact = causeDerivation.causeFact,
            generationScope = impactObservation.generationScope,
            causePhase = causePhase,
        )
        return ConferenceFailureTerminal.DomainBlocked(attribution)
    }

    internal data class CauseDerivation(
        val causeEdgeKey: String,
        val causeFact: ConferenceFailureCauseFact,
    )

    internal fun deriveCause(observation: ConferenceFailureObservation): CauseDerivation? {
        val holder = observation.leaseWaitHolderEdgeKey?.takeIf { it.isNotBlank() }
        if (holder != null && holder != observation.edgeKey) {
            return CauseDerivation(holder, ConferenceFailureCauseFact.LEASE_HELD_BY_CAUSE)
        }
        if (observation.nativeDomainObstructed) {
            val cause = observation.activeLeaseHolderEdgeKey?.takeIf { it.isNotBlank() }
                ?: holder
            if (cause != null && cause != observation.edgeKey) {
                return CauseDerivation(
                    cause,
                    if (holder != null) {
                        ConferenceFailureCauseFact.LEASE_HELD_BY_CAUSE
                    } else {
                        ConferenceFailureCauseFact.NATIVE_DOMAIN_OBSTRUCTED
                    },
                )
            }
        }
        return null
    }

    private fun isPeerCausedImpact(observation: ConferenceFailureObservation): Boolean {
        val holder = observation.leaseWaitHolderEdgeKey?.takeIf { it.isNotBlank() } ?: return false
        return holder != observation.edgeKey
    }

    private fun hasSelfAttributionSymptoms(observation: ConferenceFailureObservation): Boolean {
        return observation.hangingObserved ||
            observation.srdTimeoutObserved ||
            observation.negotiationStuckObserved ||
            observation.edgeLocalFailureObserved
    }

    private fun resolveCausePhase(
        causeObservation: ConferenceFailureObservation?,
        causeEdgeKey: String,
    ): ConferenceFailureCausePhase {
        if (causeObservation != null &&
            causeObservation.edgeKey == causeEdgeKey &&
            classifyEdgeFailure(causeObservation) != null
        ) {
            return ConferenceFailureCausePhase.EDGE_FAILED
        }
        if (causeObservation?.hangingObserved == true) {
            return ConferenceFailureCausePhase.HANGING_OBSERVED
        }
        return ConferenceFailureCausePhase.HANGING_OBSERVED
    }
}
