package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeReachabilitySnapshotTest {

    @Test
    fun gate_s13bSoak_routeNotConverged_blocksDispatch() {
        val snap = EdgeReachabilitySnapshot(
            linkReady = true,
            peerDiscovered = true,
            routeConverged = false,
            authorityReachable = true
        )
        assertFalse(snap.canDispatchRecoverySignal())
        assertEquals(RecoveryWaitingReason.WAITING_FOR_ROUTE, snap.dispatchWaitingReason())
    }

    @Test
    fun gate_allFactsReady_allowsDispatch() {
        val snap = EdgeReachabilitySnapshot(
            linkReady = true,
            peerDiscovered = true,
            routeConverged = true,
            authorityReachable = false
        )
        assertTrue(snap.canDispatchRecoverySignal())
        assertNull(snap.dispatchWaitingReason())
        assertFalse(snap.canCompleteRecovery())
    }

    @Test
    fun gate_completionRequiresAuthority() {
        val snap = EdgeReachabilitySnapshot(
            linkReady = true,
            peerDiscovered = true,
            routeConverged = true,
            authorityReachable = true
        )
        assertTrue(snap.canCompleteRecovery())
    }

    @Test
    fun capability_participant_routeBlocked_thenConverged_isMaterial() {
        val blocked = projectRecoveryCapabilitySignature(
            EdgeReachabilitySnapshot(
                linkReady = true,
                peerDiscovered = true,
                routeConverged = false,
                authorityReachable = true
            ),
            initiatesReattach = true,
            controlPlaneStarted = false
        )
        val converged = projectRecoveryCapabilitySignature(
            EdgeReachabilitySnapshot(
                linkReady = true,
                peerDiscovered = true,
                routeConverged = true,
                authorityReachable = false
            ),
            initiatesReattach = true,
            controlPlaneStarted = false
        )
        assertTrue(converged.isMaterialChangeFrom(blocked))
        assertEquals("WAITING_FOR_ROUTE", blocked.formatCapabilityLabel())
        assertEquals("DISPATCH_REATTACH", converged.formatCapabilityLabel())
    }

    @Test
    fun capability_host_sameSnapshot_notMaterial() {
        val snapshot = EdgeReachabilitySnapshot(
            linkReady = true,
            peerDiscovered = true,
            routeConverged = true,
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
    fun wakeupBinding_routeConvergedEdge_matchesRemoteRecoveredAndPeerDiscovered() {
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
}
