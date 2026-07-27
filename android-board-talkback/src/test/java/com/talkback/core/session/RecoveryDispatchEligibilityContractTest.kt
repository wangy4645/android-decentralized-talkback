package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Recovery Dispatch Eligibility Contract.
 *
 * Recovery action eligibility MUST NOT depend on the media state that the action
 * is intended to restore. [EdgeReachabilitySnapshot.mediaRouteConnected] is sourced from
 * mesh ICE connectedness (media plane), so it MUST NOT gate dispatch or initiation.
 *
 * Completion is evidence of success rather than an action, so it MAY keep the media
 * dependency — see [completion_stillRequiresMediaRoute].
 */
class RecoveryDispatchEligibilityContractTest {

    /** Media-plane fact false; transport and discovery facts true. */
    private fun mediaDown() = EdgeReachabilitySnapshot(
        linkReady = true,
        peerDiscovered = true,
        peerSignalingReachable = true,
        mediaRouteConnected = false,
        authorityReachable = true
    )

    @Test
    fun dispatch_mustNotRequireMediaRoute() {
        assertTrue(mediaDown().canDispatchRecoverySignal())
    }

    @Test
    fun dispatch_waitingReason_mustNotBeRouteWhenOnlyMediaIsDown() {
        assertEquals(null, mediaDown().dispatchWaitingReason())
    }

    @Test
    fun capability_participant_mediaDown_allowsReattachDispatch() {
        val signature = projectRecoveryCapabilitySignature(
            mediaDown(),
            initiatesReattach = true,
            controlPlaneStarted = false
        )
        assertTrue(RecoveryAction.DISPATCH_REATTACH in signature.permittedActions)
    }

    /**
     * Host ICE restart is unlocked by inbound reattach, not by media route.
     * Without control plane the host still waits for inbound — but the reason
     * MUST be WAITING_FOR_INBOUND, never WAITING_FOR_ROUTE.
     */
    @Test
    fun capability_host_mediaDown_noControlPlane_waitsForInboundNotRoute() {
        val signature = projectRecoveryCapabilitySignature(
            mediaDown(),
            initiatesReattach = false,
            controlPlaneStarted = false
        )
        assertEquals(RecoveryWaitingReason.WAITING_FOR_INBOUND, signature.waitingReason)
    }

    @Test
    fun capability_host_mediaDown_controlPlaneStarted_allowsIceRestart() {
        val signature = projectRecoveryCapabilitySignature(
            mediaDown(),
            initiatesReattach = false,
            controlPlaneStarted = true
        )
        assertTrue(RecoveryAction.ICE_RESTART in signature.permittedActions)
    }

    /** Guard against over-correction: completion is not an action. */
    @Test
    fun completion_stillRequiresMediaRoute() {
        assertFalse(mediaDown().canCompleteRecovery())
        assertTrue(
            EdgeReachabilitySnapshot(
                linkReady = true,
                peerDiscovered = true,
                peerSignalingReachable = true,
                mediaRouteConnected = true,
                authorityReachable = true
            ).canCompleteRecovery()
        )
    }

    /** Initiation gate stays media-independent; transport/discovery still block. */
    @Test
    fun attempt_stillBlockedByTransportAndDiscovery() {
        val linkDown = mediaDown().copy(linkReady = false)
        assertFalse(linkDown.canAttemptRecovery())
        assertEquals(RecoveryWaitingReason.WAITING_FOR_LINK, linkDown.attemptWaitingReason())

        val peerMissing = mediaDown().copy(peerDiscovered = false)
        assertFalse(peerMissing.canAttemptRecovery())
        assertEquals(RecoveryWaitingReason.WAITING_FOR_DISCOVERY, peerMissing.attemptWaitingReason())
    }
}
