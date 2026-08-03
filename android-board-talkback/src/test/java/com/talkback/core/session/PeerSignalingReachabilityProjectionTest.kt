package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerSignalingReachabilityProjectionTest {

    private val config = AdmissionFreshnessConfig(
        tDispatchFreshMs = 5_000L,
        moduleStaleMs = 15_000L
    )

    private fun evidence(
        localGen: Long = 5L,
        observedGen: Long? = 5L,
        lastInboundAtMs: Long? = 1_000_000L
    ) = PeerEdgeSignalingEvidence(
        localRebindGeneration = localGen,
        observedGeneration = observedGen,
        lastPeerInboundObservedAtMs = lastInboundAtMs
    )

    @Test
    fun caseA_freshInbound_currentGeneration_high() {
        val now = 1_001_000L
        val result = projectPeerSignalingReachabilityConfidence(
            evidence(lastInboundAtMs = now - 1_000L),
            nowMs = now,
            config = config
        )
        assertEquals(PeerSignalingReachabilityConfidence.HIGH, result.confidence)
        assertEquals(AdmissionDecisionProjection.DISPATCH_NOW, result.decision)
        assertEquals(AdmissionConfidenceReason.CURRENT_GENERATION_FRESH_INBOUND, result.reason)
    }

    @Test
    fun caseB_staleButKnown_medium() {
        val now = 1_010_000L
        val result = projectPeerSignalingReachabilityConfidence(
            evidence(lastInboundAtMs = now - 10_000L),
            nowMs = now,
            config = config
        )
        assertEquals(PeerSignalingReachabilityConfidence.MEDIUM, result.confidence)
        assertEquals(AdmissionDecisionProjection.WAITING_STALE, result.decision)
        assertEquals(AdmissionConfidenceReason.INBOUND_STALE_FOR_RECOVERY_DISPATCH, result.reason)
    }

    @Test
    fun caseC_exceededModuleStale_low() {
        val now = 1_020_000L
        val result = projectPeerSignalingReachabilityConfidence(
            evidence(lastInboundAtMs = now - 20_000L),
            nowMs = now,
            config = config
        )
        assertEquals(PeerSignalingReachabilityConfidence.LOW, result.confidence)
        assertEquals(AdmissionDecisionProjection.WAITING_LOW, result.decision)
        assertEquals(AdmissionConfidenceReason.INBOUND_EXCEEDED_MODULE_STALE, result.reason)
    }

    @Test
    fun caseD_generationMismatch_lowEvenWhenFresh() {
        val now = 1_001_000L
        val result = projectPeerSignalingReachabilityConfidence(
            evidence(localGen = 6L, observedGen = 5L, lastInboundAtMs = now - 1_000L),
            nowMs = now,
            config = config
        )
        assertEquals(PeerSignalingReachabilityConfidence.LOW, result.confidence)
        assertEquals(AdmissionDecisionProjection.WAITING_LOW, result.decision)
        assertEquals(AdmissionConfidenceReason.GENERATION_MISMATCH, result.reason)
    }

    @Test
    fun caseE_soakDispatchCandidate_medium() {
        val lastInboundAt = 1_000_000L
        val dispatchCandidateAt = lastInboundAt + 14_000L
        val atDispatch = projectPeerSignalingReachabilityConfidence(
            evidence(lastInboundAtMs = lastInboundAt),
            nowMs = dispatchCandidateAt,
            config = config
        )
        assertEquals(PeerSignalingReachabilityConfidence.MEDIUM, atDispatch.confidence)
        assertEquals(AdmissionDecisionProjection.WAITING_STALE, atDispatch.decision)

        val inboundRestoreAt = dispatchCandidateAt + 21_000L
        val afterInbound = projectPeerSignalingReachabilityConfidence(
            evidence(lastInboundAtMs = inboundRestoreAt - 1_000L),
            nowMs = inboundRestoreAt,
            config = config
        )
        assertEquals(PeerSignalingReachabilityConfidence.HIGH, afterInbound.confidence)
        assertEquals(AdmissionDecisionProjection.DISPATCH_NOW, afterInbound.decision)
    }

    @Test
    fun noInboundObservation_low() {
        val result = projectPeerSignalingReachabilityConfidence(
            evidence(observedGen = null, lastInboundAtMs = null),
            nowMs = 1_000_000L,
            config = config
        )
        assertEquals(PeerSignalingReachabilityConfidence.LOW, result.confidence)
        assertEquals(AdmissionConfidenceReason.NO_CURRENT_EPOCH_INBOUND, result.reason)
        assertEquals(RecoveryWaitingReason.ADMISSION_CONFIDENCE_LOW, result.toRecoveryWaitingReason())
    }

    @Test
    fun recoveryAdmissionDecision_dispatchNow() {
        val projection = projectPeerSignalingReachabilityConfidence(
            evidence(lastInboundAtMs = 1_001_000L),
            nowMs = 1_001_000L,
            config = config
        )
        val decision = projection.toRecoveryAdmissionDecision()
        assertTrue(decision.dispatchNow)
        assertEquals(null, decision.projection.toRecoveryWaitingReason())
    }
}