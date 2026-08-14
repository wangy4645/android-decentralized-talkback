package com.talkback.core.signaling.peer

import com.talkback.core.model.SignalType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PeerControlSignalingAdmissionTest {

    @Test
    fun invSig018_hardGatesNewRequests_ignoresIceRestartVehicles() {
        assertTrue(PeerControlSignalingAdmission.isRequestAdmissionGated(SignalType.GROUP_INVITE))
        assertTrue(PeerControlSignalingAdmission.isRequestAdmissionGated(SignalType.CONFERENCE_REJOIN))
        assertFalse(PeerControlSignalingAdmission.isRequestAdmissionGated(SignalType.GROUP_ACCEPT))
        assertFalse(PeerControlSignalingAdmission.isRequestAdmissionGated(SignalType.GROUP_JOIN))
        assertFalse(PeerControlSignalingAdmission.isRequestAdmissionGated(SignalType.WEBRTC_OFFER))
        assertFalse(PeerControlSignalingAdmission.isRequestAdmissionGated(SignalType.WEBRTC_ANSWER))
        assertFalse(PeerControlSignalingAdmission.isRequestAdmissionGated(SignalType.WEBRTC_ICE))
        assertFalse(PeerControlSignalingAdmission.isRequestAdmissionGated(SignalType.HELLO))
        assertFalse(PeerControlSignalingAdmission.isRequestAdmissionGated(SignalType.HEARTBEAT))
        assertFalse(PeerControlSignalingAdmission.isRequestAdmissionGated(SignalType.FLOOR_REQUEST))
    }

    @Test
    fun requestResponseAdmissionMatrix_peerNotReady() {
        assertFalse(
            PeerControlSignalingAdmission.maySendNewControl(SignalType.GROUP_INVITE, peerEdgeReady = false)
        )
        assertTrue(
            PeerControlSignalingAdmission.maySendNewControl(SignalType.GROUP_INVITE, peerEdgeReady = true)
        )
        assertTrue(
            PeerControlSignalingAdmission.maySendNewControl(SignalType.GROUP_ACCEPT, peerEdgeReady = false)
        )
        assertTrue(
            PeerControlSignalingAdmission.maySendNewControl(SignalType.GROUP_JOIN, peerEdgeReady = false)
        )
        assertTrue(
            PeerControlSignalingAdmission.maySendNewControl(SignalType.WEBRTC_ICE, peerEdgeReady = false)
        )
    }

    @Test
    fun groupAccept_isClassifiedAsResponseControl() {
        assertTrue(PeerControlSignalingAdmission.isResponseControl(SignalType.GROUP_ACCEPT))
        assertFalse(PeerControlSignalingAdmission.isResponseControl(SignalType.GROUP_INVITE))
    }

    @Test
    fun adr0036_maySendMembershipRecoveryResync_blocksNeverObserved() {
        val snap = PeerEdgeSignalingSnapshot(
            remoteModuleId = "M01",
            ready = false,
            observedGeneration = null,
            lastPeerInboundObservedAtMs = null,
            reason = PeerEdgeSignalingNotReadyReason.NEVER_OBSERVED
        )
        assertFalse(
            PeerControlSignalingAdmission.maySendMembershipRecoveryResync(
                snap,
                localBidirectionalReady = true
            )
        )
    }

    @Test
    fun adr0036_maySendMembershipRecoveryResync_allowsFreshnessExpiredWhenBidirectionalReady() {
        val snap = PeerEdgeSignalingSnapshot(
            remoteModuleId = "M01",
            ready = false,
            observedGeneration = 2L,
            lastPeerInboundObservedAtMs = 1000L,
            reason = PeerEdgeSignalingNotReadyReason.FRESHNESS_EXPIRED
        )
        assertTrue(
            PeerControlSignalingAdmission.maySendMembershipRecoveryResync(
                snap,
                localBidirectionalReady = true
            )
        )
    }

    @Test
    fun adr0036_maySendMembershipRecoveryResync_requiresLocalBidirectionalReady() {
        val snap = PeerEdgeSignalingSnapshot(
            remoteModuleId = "M01",
            ready = true,
            observedGeneration = 2L,
            lastPeerInboundObservedAtMs = 1000L,
            reason = null
        )
        assertFalse(
            PeerControlSignalingAdmission.maySendMembershipRecoveryResync(
                snap,
                localBidirectionalReady = false
            )
        )
    }
}
