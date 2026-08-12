package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConferenceRecoveryAdmissionGateTest {

    private lateinit var tracker: ConferenceAdmissionTracker
    private val key = ConferenceAdmissionKey(sessionId = "sess-1", peerId = "M01")

    @Before
    fun setUp() {
        tracker = ConferenceAdmissionTracker(logSink = {})
    }

    @Test
    fun allowsRecovery_onlyWhenReady() {
        assertFalse(tracker.allowsRecovery(key))

        tracker.transition(key, ConferenceAdmissionPhase.NEGOTIATING, ConferenceAdmissionTransitionReason.APPLY_REMOTE_OFFER)
        assertFalse(tracker.allowsRecovery(key))

        tracker.transition(key, ConferenceAdmissionPhase.READY, ConferenceAdmissionTransitionReason.ANSWER_COMMITTED)
        assertTrue(tracker.allowsRecovery(key))
    }

    @Test
    fun evaluateAdmissionProjection_blocksUntilReady() {
        val blocked = evaluateConferenceEdgeRecoveryAdmission(tracker, key.sessionId, key.peerId)
        assertEquals(AdmissionDecisionProjection.WAITING_LOW, blocked.decision)

        tracker.transition(key, ConferenceAdmissionPhase.READY, ConferenceAdmissionTransitionReason.ANSWER_COMMITTED)
        val allowed = evaluateConferenceEdgeRecoveryAdmission(tracker, key.sessionId, key.peerId)
        assertEquals(AdmissionDecisionProjection.DISPATCH_NOW, allowed.decision)
    }
}

/** Test seam mirroring TalkbackCoordinator.evaluateConferenceEdgeRecoveryAdmission. */
private fun evaluateConferenceEdgeRecoveryAdmission(
    tracker: ConferenceAdmissionTracker,
    sessionId: String,
    remoteModuleId: String
): PeerSignalingReachabilityProjection {
    val admissionKey = ConferenceAdmissionKey(sessionId, remoteModuleId)
    if (!tracker.allowsRecovery(admissionKey)) {
        return PeerSignalingReachabilityProjection(
            confidence = PeerSignalingReachabilityConfidence.LOW,
            decision = AdmissionDecisionProjection.WAITING_LOW,
            reason = AdmissionConfidenceReason.NO_CURRENT_EPOCH_INBOUND,
            lastInboundAgeMs = null
        )
    }
    return defaultRecoveryAdmissionProjection()
}
