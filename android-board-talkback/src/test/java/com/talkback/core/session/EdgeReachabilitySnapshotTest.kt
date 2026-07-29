package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeReachabilitySnapshotTest {

    @Test
    fun gate_allFactsReady_allowsDispatch() {
        val snap = EdgeReachabilitySnapshot(
            linkReady = true,
            peerDiscovered = true,
            peerSignalingReachable = true,
            mediaRouteConnected = true,
            authorityReachable = false
        )
        assertTrue(snap.canAttemptRecovery())
        assertTrue(snap.canDispatchRecoverySignal())
        assertNull(snap.attemptWaitingReason())
        assertNull(snap.dispatchWaitingReason())
        assertFalse(snap.canCompleteRecovery())
    }

    @Test
    fun gate_completionRequiresAuthority() {
        val snap = EdgeReachabilitySnapshot(
            linkReady = true,
            peerDiscovered = true,
            peerSignalingReachable = true,
            mediaRouteConnected = true,
            authorityReachable = true
        )
        assertTrue(snap.canCompleteRecovery())
    }

    @Test
    fun capability_participant_discoveryBlocked_thenResolved_isMaterial() {
        val blocked = projectRecoveryCapabilitySignature(
            EdgeReachabilitySnapshot(
                linkReady = true,
                peerDiscovered = false,
                peerSignalingReachable = true,
                mediaRouteConnected = true,
                authorityReachable = true
            ),
            initiatesReattach = true,
            controlPlaneStarted = false
        )
        assertEquals(RecoveryWaitingReason.WAITING_FOR_DISCOVERY, blocked.waitingReason)
        val resolved = projectRecoveryCapabilitySignature(
            EdgeReachabilitySnapshot(
                linkReady = true,
                peerDiscovered = true,
                peerSignalingReachable = true,
                mediaRouteConnected = true,
                authorityReachable = false
            ),
            initiatesReattach = true,
            controlPlaneStarted = false
        )
        assertTrue(resolved.isMaterialChangeFrom(blocked))
        assertEquals("WAITING_FOR_DISCOVERY", blocked.formatCapabilityLabel())
        assertEquals("DISPATCH_REATTACH", resolved.formatCapabilityLabel())
    }

    @Test
    fun capability_host_routeReady_waitsForInbound() {
        val snapshot = EdgeReachabilitySnapshot(
            linkReady = true,
            peerDiscovered = true,
            peerSignalingReachable = true,
            mediaRouteConnected = true,
            authorityReachable = true
        )
        val signature = projectRecoveryCapabilitySignature(
            snapshot,
            initiatesReattach = false,
            controlPlaneStarted = false
        )
        assertFalse(signature.isMaterialChangeFrom(signature))
        assertEquals(RecoveryWaitingReason.WAITING_FOR_INBOUND, signature.waitingReason)
    }

    @Test
    fun wakeupBinding_mediaRouteConnectedEdge_matchesRemoteRecoveredAndPeerDiscovered() {
        val binding = WakeupBinding(
            sourceType = WakeupSourceType.ROUTE_CONVERGED,
            sourceKey = edgeWakeupKey("sess-1", "M02")
        )
        assertTrue(binding.matchesTrigger(RecoveryReevaluateTrigger.ROUTE_CONVERGED, "sess-1", "M02"))
        assertTrue(binding.matchesTrigger(RecoveryReevaluateTrigger.REMOTE_MODULE_RECOVERED, "sess-1", "M02"))
        assertTrue(binding.matchesTrigger(RecoveryReevaluateTrigger.PEER_DISCOVERED, "sess-1", "M02"))
        assertFalse(binding.matchesTrigger(RecoveryReevaluateTrigger.REMOTE_MODULE_RECOVERED, "sess-1", "M01"))
        assertFalse(binding.matchesTrigger(RecoveryReevaluateTrigger.AUTHORITY_REACHABLE, "sess-1", "M02"))
    }

    @Test
    fun wakeupBinding_routeConverged_matchesPeerReachabilityRestored() {
        val binding = WakeupBinding(
            sourceType = WakeupSourceType.ROUTE_CONVERGED,
            sourceKey = edgeWakeupKey("sess-1", "M02")
        )
        assertTrue(
            binding.matchesTrigger(RecoveryReevaluateTrigger.PEER_REACHABILITY_RESTORED, "sess-1", "M02")
        )
        assertTrue(binding.matchesTrigger(RecoveryReevaluateTrigger.REMOTE_MODULE_RECOVERED, "sess-1", "M02"))
        assertTrue(binding.matchesTrigger(RecoveryReevaluateTrigger.PEER_DISCOVERED, "sess-1", "M02"))
        assertFalse(binding.matchesTrigger(RecoveryReevaluateTrigger.REMOTE_MODULE_RECOVERED, "sess-1", "M01"))
    }
}
