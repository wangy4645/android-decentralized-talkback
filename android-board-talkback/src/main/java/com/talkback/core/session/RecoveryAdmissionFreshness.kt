package com.talkback.core.session

/**
 * PR3-0: Admission evidence projection (observation only).
 * Consumes peer-edge facts; does not own peer state or dispatch gates.
 */
data class PeerEdgeSignalingEvidence(
    val localRebindGeneration: Long,
    val observedGeneration: Long?,
    val lastPeerInboundObservedAtMs: Long?
)

data class AdmissionFreshnessConfig(
    val tDispatchFreshMs: Long = 5_000L,
    val moduleStaleMs: Long = 15_000L
)

enum class PeerSignalingReachabilityConfidence {
    HIGH,
    MEDIUM,
    LOW
}

enum class AdmissionDecisionProjection {
    DISPATCH_NOW,
    WAITING_STALE,
    WAITING_LOW
}

enum class AdmissionConfidenceReason {
    CURRENT_GENERATION_FRESH_INBOUND,
    INBOUND_STALE_FOR_RECOVERY_DISPATCH,
    NO_CURRENT_EPOCH_INBOUND,
    GENERATION_MISMATCH,
    INBOUND_EXCEEDED_MODULE_STALE
}

data class PeerSignalingReachabilityProjection(
    val confidence: PeerSignalingReachabilityConfidence,
    val decision: AdmissionDecisionProjection,
    val reason: AdmissionConfidenceReason,
    val lastInboundAgeMs: Long?
)

fun projectPeerSignalingReachabilityConfidence(
    evidence: PeerEdgeSignalingEvidence,
    nowMs: Long,
    config: AdmissionFreshnessConfig = AdmissionFreshnessConfig()
): PeerSignalingReachabilityProjection {
    val lastAt = evidence.lastPeerInboundObservedAtMs
    val observedGen = evidence.observedGeneration
    val localGen = evidence.localRebindGeneration

    if (lastAt == null || observedGen == null) {
        return projection(
            confidence = PeerSignalingReachabilityConfidence.LOW,
            decision = AdmissionDecisionProjection.WAITING_LOW,
            reason = AdmissionConfidenceReason.NO_CURRENT_EPOCH_INBOUND,
            lastInboundAgeMs = null
        )
    }
    if (observedGen != localGen) {
        val age = nowMs - lastAt
        return projection(
            confidence = PeerSignalingReachabilityConfidence.LOW,
            decision = AdmissionDecisionProjection.WAITING_LOW,
            reason = AdmissionConfidenceReason.GENERATION_MISMATCH,
            lastInboundAgeMs = age
        )
    }
    val age = nowMs - lastAt
    if (age > config.moduleStaleMs) {
        return projection(
            confidence = PeerSignalingReachabilityConfidence.LOW,
            decision = AdmissionDecisionProjection.WAITING_LOW,
            reason = AdmissionConfidenceReason.INBOUND_EXCEEDED_MODULE_STALE,
            lastInboundAgeMs = age
        )
    }
    if (age > config.tDispatchFreshMs) {
        return projection(
            confidence = PeerSignalingReachabilityConfidence.MEDIUM,
            decision = AdmissionDecisionProjection.WAITING_STALE,
            reason = AdmissionConfidenceReason.INBOUND_STALE_FOR_RECOVERY_DISPATCH,
            lastInboundAgeMs = age
        )
    }
    return projection(
        confidence = PeerSignalingReachabilityConfidence.HIGH,
        decision = AdmissionDecisionProjection.DISPATCH_NOW,
        reason = AdmissionConfidenceReason.CURRENT_GENERATION_FRESH_INBOUND,
        lastInboundAgeMs = age
    )
}

private fun projection(
    confidence: PeerSignalingReachabilityConfidence,
    decision: AdmissionDecisionProjection,
    reason: AdmissionConfidenceReason,
    lastInboundAgeMs: Long?
): PeerSignalingReachabilityProjection =
    PeerSignalingReachabilityProjection(
        confidence = confidence,
        decision = decision,
        reason = reason,
        lastInboundAgeMs = lastInboundAgeMs
    )

/** PR3-1: admission gate decision shared by initial dispatch and delivery retry. */
data class RecoveryAdmissionDecision(
    val projection: PeerSignalingReachabilityProjection
) {
    val dispatchNow: Boolean =
        projection.decision == AdmissionDecisionProjection.DISPATCH_NOW
}

fun PeerSignalingReachabilityProjection.toRecoveryAdmissionDecision(): RecoveryAdmissionDecision =
    RecoveryAdmissionDecision(projection = this)

fun PeerSignalingReachabilityProjection.toRecoveryWaitingReason(): RecoveryWaitingReason? = when (decision) {
    AdmissionDecisionProjection.DISPATCH_NOW -> null
    AdmissionDecisionProjection.WAITING_STALE -> RecoveryWaitingReason.ADMISSION_CONFIDENCE_STALE
    AdmissionDecisionProjection.WAITING_LOW -> RecoveryWaitingReason.ADMISSION_CONFIDENCE_LOW
}

fun PeerSignalingReachabilityProjection.admissionRetryDeferReason(): String = when (decision) {
    AdmissionDecisionProjection.WAITING_STALE -> "admission_confidence_stale"
    AdmissionDecisionProjection.WAITING_LOW -> "admission_confidence_low"
    AdmissionDecisionProjection.DISPATCH_NOW -> "admission_confidence_high"
}

fun defaultRecoveryAdmissionProjection(): PeerSignalingReachabilityProjection =
    PeerSignalingReachabilityProjection(
        confidence = PeerSignalingReachabilityConfidence.HIGH,
        decision = AdmissionDecisionProjection.DISPATCH_NOW,
        reason = AdmissionConfidenceReason.CURRENT_GENERATION_FRESH_INBOUND,
        lastInboundAgeMs = 0L
    )